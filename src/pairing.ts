import { finalizeEvent, getPublicKey, type Event } from 'nostr-tools/pure'
import { nip44 } from 'nostr-tools'
import { hkdf } from '@noble/hashes/hkdf'
import { sha256 } from '@noble/hashes/sha2'
import { bytesToHex, hexToBytes, randomBytes } from '@noble/hashes/utils'
import { sanitiseDisplayName, type RelayTransport } from '@forgesworn/fold-kit'
import { FamilyGraph, TierLedger } from './family.js'
import type { KincludeSigner } from './signer.js'
import {
  dependantOfUnsigned,
  guardianOfUnsigned,
  isPubkey,
  isTier,
  readLinkStatement,
  readTier,
  tierUnsigned,
  TIER_GENESIS,
  type Tier,
} from './statements.js'
import { isEventShape } from './messages.js'

/**
 * Pairing a child's phone with a guardian, in person.
 *
 * The guardian's phone shows a code (a QR in the app) carrying the
 * guardian's pubkey, a one-time secret and the tier they are proposing to
 * start at. The child's phone reads it, signs its half of the guardian link
 * and of the starting tier, and sends them under a key derived from the
 * secret; the guardian's phone checks them, signs its own halves and sends
 * them back. Both phones then hold the whole set.
 *
 * The exchange rides kind 20480, an ephemeral kind: both phones are in the
 * same room as each other, so neither needs a relay to keep anything. A
 * second guardian pairs the same way; the child's phone then sends the tier
 * chain it already has instead of a new starting tier.
 */
export const PAIRING_KIND = 20480
export const PAIRING_KEY_INFO = 'kinclude/v1/pairing-key'
export const PAIRING_TAG_INFO = 'kinclude/v1/pairing-tag'
export const PAIRING_PREFIX = 'kinclude-pair:'

export interface PairingCode {
  guardian: string
  secret: string
  tier: Tier
  childName?: string
  guardianName?: string
}

function toBase64url(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64url(text: string): string {
  const bin = atob(text.replace(/-/g, '+').replace(/_/g, '/'))
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)))
}

export function encodePairingCode(c: PairingCode): string {
  const body = JSON.stringify({ g: c.guardian, s: c.secret, t: c.tier, n: c.childName, m: c.guardianName })
  return PAIRING_PREFIX + toBase64url(body)
}

export function decodePairingCode(raw: string): PairingCode | null {
  try {
    const trimmed = raw.trim()
    if (!trimmed.startsWith(PAIRING_PREFIX)) return null
    const b = JSON.parse(fromBase64url(trimmed.slice(PAIRING_PREFIX.length))) as Record<string, unknown>
    if (!isPubkey(b.g) || typeof b.s !== 'string' || !/^[0-9a-f]{32}$/.test(b.s) || !isTier(b.t)) return null
    const childName = sanitiseDisplayName(b.n)
    const guardianName = sanitiseDisplayName(b.m)
    return {
      guardian: b.g, secret: b.s, tier: b.t,
      ...(childName !== undefined ? { childName } : {}),
      ...(guardianName !== undefined ? { guardianName } : {}),
    }
  } catch {
    return null
  }
}

function keys(secret: string) {
  const s = hexToBytes(secret)
  return {
    key: hkdf(sha256, s, undefined, PAIRING_KEY_INFO, 32),
    tag: bytesToHex(hkdf(sha256, s, undefined, PAIRING_TAG_INFO, 32)),
  }
}

interface PairingMessage {
  from: 'child' | 'guardian'
  pk: string
  name?: string
  /** The child's inbox key, where their guardians forward invitations. */
  inbox?: string
  events: Event[]
}

function seal(secret: string, msg: PairingMessage, signerSk: Uint8Array, now: number): Event {
  const k = keys(secret)
  return finalizeEvent({ kind: PAIRING_KIND, created_at: now, tags: [['d', k.tag]], content: nip44.v2.encrypt(JSON.stringify(msg), k.key) }, signerSk)
}

function open(secret: string, event: Event): PairingMessage | null {
  try {
    const k = keys(secret)
    if (event.kind !== PAIRING_KIND || event.tags.find((t) => t[0] === 'd')?.[1] !== k.tag) return null
    const raw = JSON.parse(nip44.v2.decrypt(event.content, k.key)) as Record<string, unknown>
    if ((raw.from !== 'child' && raw.from !== 'guardian') || !isPubkey(raw.pk)) return null
    if (!Array.isArray(raw.events) || raw.events.length > 32 || !raw.events.every(isEventShape)) return null
    if (raw.inbox !== undefined && !isPubkey(raw.inbox)) return null
    const name = sanitiseDisplayName(raw.name)
    return {
      from: raw.from, pk: raw.pk, events: raw.events as Event[],
      ...(name !== undefined ? { name } : {}),
      ...(raw.inbox !== undefined ? { inbox: raw.inbox as string } : {}),
    }
  } catch {
    return null
  }
}

export interface PairedChild {
  pk: string
  name?: string
  inbox: string
  events: Event[]
}

/** The guardian's side: make a code, wait for the child's phone, answer. */
export function offerPairing(opts: {
  transport: RelayTransport
  guardian: KincludeSigner
  guardianName?: string
  childName?: string
  tier: Tier
  now: () => number
}): { code: string; paired: Promise<PairedChild>; cancel(): void } {
  const secret = bytesToHex(randomBytes(16))
  const code = encodePairingCode({
    guardian: opts.guardian.pubkey, secret, tier: opts.tier,
    ...(opts.childName ? { childName: opts.childName } : {}),
    ...(opts.guardianName ? { guardianName: opts.guardianName } : {}),
  })
  const throwaway = randomBytes(32)
  let stop = () => {}
  const paired = new Promise<PairedChild>((resolve) => {
    stop = opts.transport.subscribe([{ kinds: [PAIRING_KIND], '#d': [keys(secret).tag] }], (event) => {
      void (async () => {
        const msg = open(secret, event)
        if (!msg || msg.from !== 'child' || !msg.inbox) return
        const child = msg.pk
        // What the child sent: their half of the link to this guardian, and
        // either a new starting tier at the offered tier, or the chain they
        // already have with somebody else.
        const family = new FamilyGraph()
        family.addAll(msg.events)
        const up = msg.events.map(readLinkStatement).find((s) => s?.type === 'dependant-of' && s.signer === child && s.other === opts.guardian.pubkey)
        if (!up) return
        const tierHalves = msg.events.map(readTier).filter((t) => t && t.statement.child === child)
        const genesis = tierHalves.find((t) => t!.statement.seq === 0 && t!.signer === child)
        if (!genesis) return
        const mine: Event[] = [await opts.guardian.signEvent(guardianOfUnsigned(child, opts.now()))]
        family.addAll(mine)
        family.add(up.event)
        const ledger = new TierLedger(family)
        ledger.addAll(msg.events)
        if (!ledger.current(child)) {
          // A new child: the genesis must be what was offered.
          if (genesis.statement.tier !== opts.tier || genesis.statement.prev !== TIER_GENESIS) return
          mine.push(await opts.guardian.signEvent(tierUnsigned(genesis.statement, opts.now(), msg.name)))
        }
        await opts.transport.publish(seal(secret, { from: 'guardian', pk: opts.guardian.pubkey, events: mine, ...(opts.guardianName ? { name: opts.guardianName } : {}) }, throwaway, opts.now()))
        stop()
        resolve({ pk: child, inbox: msg.inbox, events: [...msg.events, ...mine], ...(msg.name ? { name: msg.name } : {}) })
      })()
    })
  })
  return { code, paired, cancel: () => stop() }
}

/** The child's side: read the code, send this phone's halves, wait for the
 *  guardian's. `existing` is the family statements this phone already
 *  holds; when they carry a tier chain the child is already on, it is sent
 *  instead of a new starting tier. */
export async function acceptPairing(opts: {
  transport: RelayTransport
  child: KincludeSigner
  childName?: string
  inboxSk: Uint8Array
  code: string
  existing?: Event[]
  now: () => number
  timeoutMs?: number
}): Promise<{ guardian: string; guardianName?: string; events: Event[] }> {
  const code = decodePairingCode(opts.code)
  if (!code) throw new Error('That is not a Kinclude pairing code.')
  if (code.guardian === opts.child.pubkey) throw new Error('A phone cannot pair with its own person.')
  const family = new FamilyGraph()
  family.addAll(opts.existing ?? [])
  const ledger = new TierLedger(family)
  ledger.addAll(opts.existing ?? [])
  const events: Event[] = [await opts.child.signEvent(dependantOfUnsigned(code.guardian, opts.now()))]
  if (ledger.current(opts.child.pubkey)) {
    events.push(...family.statementsFor(opts.child.pubkey), ...ledger.chainEvents(opts.child.pubkey))
  } else {
    events.push(await opts.child.signEvent(tierUnsigned({ child: opts.child.pubkey, tier: code.tier, seq: 0, prev: TIER_GENESIS }, opts.now(), opts.childName)))
  }
  const answer = new Promise<{ guardian: string; guardianName?: string; events: Event[] }>((resolve, reject) => {
    const timer = setTimeout(() => { stop(); reject(new Error('The other phone did not answer.')) }, opts.timeoutMs ?? 120_000)
    const stop = opts.transport.subscribe([{ kinds: [PAIRING_KIND], '#d': [keys(code.secret).tag] }], (event) => {
      const msg = open(code.secret, event)
      if (!msg || msg.from !== 'guardian' || msg.pk !== code.guardian) return
      const down = msg.events.map(readLinkStatement).find((s) => s?.type === 'guardian-of' && s.signer === code.guardian && s.other === opts.child.pubkey)
      if (!down) return
      clearTimeout(timer)
      stop()
      resolve({ guardian: code.guardian, events: [...events, ...msg.events], ...(msg.name ? { guardianName: msg.name } : {}) })
    })
  })
  // Give the subscription a moment to be in place before speaking.
  await new Promise((r) => setTimeout(r, 0))
  await opts.transport.publish(seal(code.secret, {
    from: 'child', pk: opts.child.pubkey, inbox: getPublicKey(opts.inboxSk), events,
    ...(opts.childName ? { name: opts.childName } : {}),
  }, opts.inboxSk, opts.now()))
  return answer
}
