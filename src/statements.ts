import type { Event } from 'nostr-tools/pure'
import { verifyEventUncached, type UnsignedEvent } from '@forgesworn/fold-kit'
import { sha256 } from '@noble/hashes/sha2'
import { bytesToHex } from '@noble/hashes/utils'

/**
 * Statements: what a person signs with their identity, through My Signet.
 *
 * Every statement is a kind 30078 event (NIP-78 application data), the kind
 * My Signet already labels "app state" and asks a dependant about at every
 * signature below full autonomy. None is ever published to a relay on its
 * own: each travels inside a room's ciphertext, so a relay never learns who
 * is whose guardian, who approved whom or what tier a child is at.
 *
 * The data is in tags, not content, so the content can be a sentence a
 * person reads in My Signet's raw-event view before tapping approve.
 *
 * Guardian links use Kindependence's `d` tags byte for byte, so a family
 * that uses both products links each child once (design §3).
 */
export const STATEMENT_KIND = 30078

export const LINK_PREFIX = {
  guardianOf: 'kindependence/dependant/',
  dependantOf: 'kindependence/guardian/',
  unlink: 'kindependence/unlink/',
} as const

export const KINCLUDE_PREFIX = {
  approval: 'kinclude/approval/',
  tier: 'kinclude/tier/',
  emergency: 'kinclude/emergency/',
} as const

/** How long an approval stands before it is used: a week. An approval that
 *  waited longer is asked for again rather than trusted to still mean yes. */
export const APPROVAL_TTL_SECONDS = 7 * 24 * 60 * 60

const HEX64 = /^[0-9a-f]{64}$/

export function isPubkey(value: unknown): value is string {
  return typeof value === 'string' && HEX64.test(value)
}

function single(event: Event, name: string): string | undefined {
  const found = event.tags.filter((t) => t[0] === name)
  if (found.length !== 1 || found[0]!.length !== 2) return undefined
  return found[0]![1]
}

function baseCheck(event: unknown): event is Event {
  if (typeof event !== 'object' || event === null) return false
  const e = event as Event
  return e.kind === STATEMENT_KIND && Array.isArray(e.tags) && typeof e.content === 'string' &&
    Number.isSafeInteger(e.created_at) && verifyEventUncached(e)
}

/** Strip what does not belong in a short human sentence: control
 *  characters, bidirectional overrides and zero-width joiners, then cap. */
export function sanitiseReason(raw: unknown, max = 280): string | undefined {
  if (typeof raw !== 'string') return undefined
  // eslint-disable-next-line no-control-regex
  const cleaned = raw.replace(/[\u0000-\u001f\u007f-\u009f​-‏‪-‮⁦-⁩﻿]/g, ' ')
    .replace(/\s+/g, ' ').trim()
  if (cleaned.length === 0) return undefined
  return Array.from(cleaned).slice(0, max).join('')
}

// ---------------------------------------------------------------- links

export type LinkStatementType = 'guardian-of' | 'dependant-of' | 'unlink'

export interface LinkStatement {
  type: LinkStatementType
  /** Who signed it. */
  signer: string
  /** The other person it names. */
  other: string
  at: number
  event: Event
}

function linkUnsigned(prefix: string, other: string, content: string, now: number): UnsignedEvent {
  if (!isPubkey(other)) throw new Error('a link names a lower-case hex pubkey')
  return { kind: STATEMENT_KIND, created_at: now, tags: [['d', prefix + other], ['p', other]], content }
}

/** Signed by the guardian: "this person is my dependant". */
export function guardianOfUnsigned(dependant: string, now: number): UnsignedEvent {
  return linkUnsigned(LINK_PREFIX.guardianOf, dependant, 'I am a guardian of this person.', now)
}

/** Signed by the dependant (through the guardian's My Signet): "this person
 *  is my guardian". One half alone proves nothing: anyone could claim a
 *  dependant, or a guardian. */
export function dependantOfUnsigned(guardian: string, now: number): UnsignedEvent {
  return linkUnsigned(LINK_PREFIX.dependantOf, guardian, 'This person is my guardian.', now)
}

/** Signed by either side: the link is over, until a new pair is made in
 *  person after this moment. */
export function unlinkUnsigned(other: string, now: number): UnsignedEvent {
  return linkUnsigned(LINK_PREFIX.unlink, other, 'This person is no longer linked to me as guardian or dependant.', now)
}

export function readLinkStatement(event: unknown): LinkStatement | null {
  if (!baseCheck(event)) return null
  const d = single(event, 'd')
  const p = single(event, 'p')
  if (d === undefined || !isPubkey(p)) return null
  const types: Array<[string, LinkStatementType]> = [
    [LINK_PREFIX.guardianOf, 'guardian-of'],
    [LINK_PREFIX.dependantOf, 'dependant-of'],
    [LINK_PREFIX.unlink, 'unlink'],
  ]
  for (const [prefix, type] of types) {
    if (d === prefix + p) {
      if (p === event.pubkey) return null
      return { type, signer: event.pubkey, other: p, at: event.created_at, event }
    }
  }
  return null
}

// ---------------------------------------------------------------- approvals

/**
 * One person's yes to one newcomer joining one room, on behalf of one child
 * in it.
 *
 * Valid only as a pair: the same approval signed by the child and by one of
 * the child's guardians (design §6.2). A room needs a pair for every child
 * in it, and when the newcomer is a child, a pair from the newcomer and
 * their own guardian as well (`child` = `invitee`).
 *
 * `guardians` names the newcomer's guardians when the newcomer is a child.
 * They are part of what is approved: at the first two tiers those guardians
 * can read the room, and the people saying yes are told so before they do.
 */
export interface Approval {
  room: string
  invitee: string
  /** The child this half is on behalf of. */
  child: string
  role: 'adult' | 'child'
  /** The newcomer's guardians, sorted; empty for an adult. */
  guardians: string[]
  /** Unix seconds after which it no longer admits anybody. */
  expires: number
}

/** The part of an approval both halves must agree on, as one string. */
export function approvalKey(a: Approval): string {
  return [a.room, a.invitee, a.child, a.role, [...a.guardians].sort().join(','), a.expires].join('|')
}

export function approvalUnsigned(a: Approval, now: number, name?: string): UnsignedEvent {
  for (const pk of [a.room, a.invitee, a.child, ...a.guardians]) {
    if (!isPubkey(pk)) throw new Error('an approval names lower-case hex ids')
  }
  if (a.role === 'adult' && a.guardians.length > 0) throw new Error('an adult newcomer has no guardians to name')
  if (a.role === 'child' && a.guardians.length === 0) throw new Error('a child newcomer comes with their guardians')
  if (!Number.isSafeInteger(a.expires) || a.expires <= now) throw new Error('an approval expires after it is made')
  const who = sanitiseReason(name, 64) ?? 'this person'
  return {
    kind: STATEMENT_KIND,
    created_at: now,
    tags: [
      ['d', `${KINCLUDE_PREFIX.approval}${a.room}/${a.invitee}/${a.child}`],
      ['p', a.invitee],
      ['room', a.room],
      ['child', a.child],
      ['role', a.role],
      ...[...a.guardians].sort().map((g) => ['guardian', g]),
      ['expires', String(a.expires)],
    ],
    content: a.role === 'child'
      ? `I agree to ${who} joining this Kinclude room, with their guardians able to see it as their tier allows.`
      : `I agree to ${who} joining this Kinclude room.`,
  }
}

export interface SignedApproval {
  approval: Approval
  signer: string
  at: number
  event: Event
}

export function readApproval(event: unknown): SignedApproval | null {
  if (!baseCheck(event)) return null
  const d = single(event, 'd')
  const invitee = single(event, 'p')
  const room = single(event, 'room')
  const child = single(event, 'child')
  const role = single(event, 'role')
  const expiresRaw = single(event, 'expires')
  if (!isPubkey(invitee) || !isPubkey(room) || !isPubkey(child)) return null
  if (d !== `${KINCLUDE_PREFIX.approval}${room}/${invitee}/${child}`) return null
  if (role !== 'adult' && role !== 'child') return null
  if (expiresRaw === undefined || !/^[0-9]{1,15}$/.test(expiresRaw)) return null
  const guardianTags = event.tags.filter((t) => t[0] === 'guardian')
  if (guardianTags.some((t) => t.length !== 2 || !isPubkey(t[1]))) return null
  const guardians = guardianTags.map((t) => t[1]!).sort()
  if (new Set(guardians).size !== guardians.length) return null
  if ((role === 'adult') !== (guardians.length === 0)) return null
  if (guardians.includes(invitee)) return null
  const known = new Set(['d', 'p', 'room', 'child', 'role', 'guardian', 'expires'])
  if (event.tags.some((t) => !known.has(t[0]!))) return null
  return {
    approval: { room, invitee, child, role, guardians, expires: Number(expiresRaw) },
    signer: event.pubkey,
    at: event.created_at,
    event,
  }
}

// ---------------------------------------------------------------- tiers

/** 1 First messages, 2 Growing up, 3 Nearly grown. */
export type Tier = 1 | 2 | 3

export const TIER_NAMES: Record<Tier, string> = {
  1: 'First messages',
  2: 'Growing up',
  3: 'Nearly grown',
}

export function isTier(value: unknown): value is Tier {
  return value === 1 || value === 2 || value === 3
}

export const TIER_GENESIS = 'genesis'

/**
 * Where a child's oversight stands. Signed twice, by the child and by one of
 * their guardians, and chained: `prev` is the digest of the statement this
 * one replaces, or `genesis` for the tier agreed at pairing. A client takes
 * a statement only on top of the one it holds, so neither side can replay
 * an older, more permissive one (design §7.4).
 */
export interface TierStatement {
  child: string
  tier: Tier
  seq: number
  prev: string
}

export const TIER_DIGEST_LABEL = 'kinclude/v1/tier'

export function tierDigest(t: TierStatement): string {
  return bytesToHex(sha256(new TextEncoder().encode(`${TIER_DIGEST_LABEL}:${t.child}:${t.seq}:${t.tier}:${t.prev}`)))
}

export function tierUnsigned(t: TierStatement, now: number, name?: string): UnsignedEvent {
  if (!isPubkey(t.child) || !isTier(t.tier) || !Number.isSafeInteger(t.seq) || t.seq < 0) throw new Error('malformed tier statement')
  if ((t.seq === 0) !== (t.prev === TIER_GENESIS)) throw new Error('only the first tier statement starts the chain')
  if (t.prev !== TIER_GENESIS && !isPubkey(t.prev)) throw new Error('prev is a digest')
  const who = sanitiseReason(name, 64) ?? 'This child'
  return {
    kind: STATEMENT_KIND,
    created_at: now,
    tags: [
      ['d', `${KINCLUDE_PREFIX.tier}${t.child}`],
      ['p', t.child],
      ['tier', String(t.tier)],
      ['seq', String(t.seq)],
      ['prev', t.prev],
    ],
    content: `${who}'s oversight is now: ${TIER_NAMES[t.tier]}. We agreed this together.`,
  }
}

export interface SignedTier {
  statement: TierStatement
  digest: string
  signer: string
  at: number
  event: Event
}

export function readTier(event: unknown): SignedTier | null {
  if (!baseCheck(event)) return null
  const child = single(event, 'p')
  const d = single(event, 'd')
  const tierRaw = single(event, 'tier')
  const seqRaw = single(event, 'seq')
  const prev = single(event, 'prev')
  if (!isPubkey(child) || d !== `${KINCLUDE_PREFIX.tier}${child}`) return null
  const tier = Number(tierRaw)
  if (!isTier(tier) || tierRaw !== String(tier)) return null
  if (seqRaw === undefined || !/^(0|[1-9][0-9]{0,8})$/.test(seqRaw)) return null
  const seq = Number(seqRaw)
  if (prev === undefined) return null
  if ((seq === 0) !== (prev === TIER_GENESIS)) return null
  if (prev !== TIER_GENESIS && !isPubkey(prev)) return null
  if (event.tags.length !== 5) return null
  const statement: TierStatement = { child, tier, seq, prev }
  return { statement, digest: tierDigest(statement), signer: event.pubkey, at: event.created_at, event }
}

// ---------------------------------------------------------------- emergency

/**
 * A guardian opening a tier-3 child's room. Signed by the guardian's
 * identity and published into the room before any escrowed key is opened,
 * so that by the time the guardian can read anything, the child and
 * everybody else in the room have been told (design §7.3).
 */
export interface EmergencyNotice {
  room: string
  child: string
  /** The one epoch being opened. */
  epoch: number
  /** What the guardian wants the child to know, if anything. */
  reason?: string
}

export function emergencyUnsigned(n: EmergencyNotice, now: number): UnsignedEvent {
  if (!isPubkey(n.room) || !isPubkey(n.child) || !Number.isSafeInteger(n.epoch) || n.epoch < 1) {
    throw new Error('malformed emergency notice')
  }
  const reason = sanitiseReason(n.reason)
  return {
    kind: STATEMENT_KIND,
    created_at: now,
    tags: [
      ['d', `${KINCLUDE_PREFIX.emergency}${n.room}/${n.child}/${n.epoch}`],
      ['p', n.child],
      ['room', n.room],
      ['epoch', String(n.epoch)],
    ],
    content: reason ?? 'I opened these messages in an emergency.',
  }
}

export interface SignedEmergency {
  notice: EmergencyNotice
  signer: string
  at: number
  event: Event
}

export function readEmergency(event: unknown): SignedEmergency | null {
  if (!baseCheck(event)) return null
  const child = single(event, 'p')
  const room = single(event, 'room')
  const epochRaw = single(event, 'epoch')
  if (!isPubkey(child) || !isPubkey(room) || epochRaw === undefined || !/^[1-9][0-9]{0,8}$/.test(epochRaw)) return null
  const epoch = Number(epochRaw)
  if (single(event, 'd') !== `${KINCLUDE_PREFIX.emergency}${room}/${child}/${epoch}`) return null
  if (event.tags.length !== 4) return null
  const reason = sanitiseReason(event.content)
  return {
    notice: { room, child, epoch, ...(reason !== undefined ? { reason } : {}) },
    signer: event.pubkey,
    at: event.created_at,
    event,
  }
}
