import { finalizeEvent, generateSecretKey, getEventHash, getPublicKey, verifyEvent, type Event } from 'nostr-tools/pure'
import { nip44 } from 'nostr-tools'
import { hkdf } from '@noble/hashes/hkdf'
import { sha256 } from '@noble/hashes/sha2'
import { bytesToHex, hexToBytes } from '@noble/hashes/utils'
import { sanitiseDisplayName } from '@forgesworn/fold-kit'
import { isPubkey } from './statements.js'
import type { KincludeSigner } from './signer.js'

/**
 * Keys a phone holds, all derived from one secret it keeps.
 *
 * A device key per room, so relays cannot link one person's rooms by the
 * key that signs their messages; and an inbox key, which a child's phone
 * gives its guardians at pairing so they can forward the child's
 * invitations to it. A child's identity cannot decrypt anything (My Signet
 * refuses NIP-44 to a dependant), so this is the only way anything sealed
 * reaches a child's phone.
 */
export const DEVICE_KEY_INFO = 'kinclude/v1/room-device:'
export const INBOX_KEY_INFO = 'kinclude/v1/inbox'

export function roomDeviceKey(phoneSecret: Uint8Array, roomId: string): Uint8Array {
  if (!isPubkey(roomId)) throw new Error('room id is lower-case hex')
  return hkdf(sha256, phoneSecret, undefined, DEVICE_KEY_INFO + roomId, 32)
}

export function inboxKey(phoneSecret: Uint8Array): Uint8Array {
  return hkdf(sha256, phoneSecret, undefined, INBOX_KEY_INFO, 32)
}

/**
 * Invitations travel as NIP-59 gift wraps: a kind 1059 from a throwaway
 * key, around a kind 13 seal signed by the sender's identity, around an
 * unsigned rumor. The seal is what makes the sender known to the recipient
 * and to nobody else; relays see a throwaway key writing to a pubkey.
 *
 * The rumor kind is Kinclude's and is never published bare.
 */
export const GIFT_WRAP_KIND = 1059
export const SEAL_KIND = 13
export const INVITATION_RUMOR_KIND = 1480
/** NIP-59 backdates the wrap and seal by up to two days, so their
 *  timestamps say nothing about when the invitation was made. */
export const WRAP_JITTER_SECONDS = 2 * 24 * 60 * 60

export interface Rumor {
  kind: number
  created_at: number
  tags: string[][]
  content: string
  pubkey: string
  id: string
}

export async function wrapGift(opts: {
  sender: KincludeSigner
  recipient: string
  kind: number
  content: string
  now: number
  random?: () => number
}): Promise<Event> {
  if (!opts.sender.nip44Encrypt) throw new Error('this identity cannot seal: it needs NIP-44')
  const random = opts.random ?? Math.random
  const jitter = () => opts.now - Math.floor(random() * WRAP_JITTER_SECONDS)
  const unsignedRumor = { kind: opts.kind, created_at: opts.now, tags: [], content: opts.content, pubkey: opts.sender.pubkey }
  const rumor: Rumor = { ...unsignedRumor, id: getEventHash(unsignedRumor) }
  const seal = await opts.sender.signEvent({
    kind: SEAL_KIND,
    created_at: jitter(),
    tags: [],
    content: await opts.sender.nip44Encrypt(opts.recipient, JSON.stringify(rumor)),
  })
  const throwaway = generateSecretKey()
  try {
    return finalizeEvent({
      kind: GIFT_WRAP_KIND,
      created_at: jitter(),
      tags: [['p', opts.recipient]],
      content: nip44.v2.encrypt(JSON.stringify(seal), nip44.v2.utils.getConversationKey(throwaway, opts.recipient)),
    }, throwaway)
  } finally {
    throwaway.fill(0)
  }
}

export async function unwrapGift(wrap: Event, recipient: KincludeSigner): Promise<{ rumor: Rumor; sender: string } | null> {
  try {
    if (!recipient.nip44Decrypt) return null
    if (wrap.kind !== GIFT_WRAP_KIND || !verifyEvent(wrap)) return null
    if (wrap.tags.find((t) => t[0] === 'p')?.[1] !== recipient.pubkey) return null
    const seal = JSON.parse(await recipient.nip44Decrypt(wrap.pubkey, wrap.content)) as Event
    if (seal.kind !== SEAL_KIND || !verifyEvent(seal)) return null
    const rumor = JSON.parse(await recipient.nip44Decrypt(seal.pubkey, seal.content)) as Rumor
    if (typeof rumor !== 'object' || rumor === null || rumor.pubkey !== seal.pubkey) return null
    const { id, ...unsigned } = rumor
    if (getEventHash(unsigned) !== id) return null
    return { rumor, sender: seal.pubkey }
  } catch {
    return null
  }
}

/**
 * What an invitation says. `room` is the room id the bearer must lead to: a
 * joiner refuses a link that opens some other room. `role` and `guardians`
 * say how the newcomer was proposed; the room checks them again.
 */
export interface InvitationEnvelope {
  v: 1
  room: string
  name: string
  bearer: string
  authority: string
  invitee: string
  role: 'adult' | 'child'
  guardians: string[]
  /** What the person who proposed them is called, as the room knows them. */
  fromName?: string
  /** The room's relays, when it names its own. */
  relays?: string[]
}

export function readEnvelope(raw: unknown): InvitationEnvelope | null {
  if (typeof raw !== 'object' || raw === null) return null
  const e = raw as Record<string, unknown>
  if (e.v !== 1 || !isPubkey(e.room) || !isPubkey(e.authority) || !isPubkey(e.invitee)) return null
  if (typeof e.bearer !== 'string' || !/^[0-9a-f]{64}$/.test(e.bearer)) return null
  if (e.role !== 'adult' && e.role !== 'child') return null
  if (!Array.isArray(e.guardians) || e.guardians.length > 8 || !e.guardians.every(isPubkey)) return null
  if ((e.role === 'adult') !== (e.guardians.length === 0)) return null
  const relays = Array.isArray(e.relays) && e.relays.length <= 8 && e.relays.every((r) => typeof r === 'string' && /^wss?:\/\//.test(r) && r.length < 256)
    ? (e.relays as string[]) : undefined
  const fromName = sanitiseDisplayName(e.fromName)
  return {
    v: 1, room: e.room, name: sanitiseDisplayName(e.name) ?? 'A Kinclude room', bearer: e.bearer, authority: e.authority,
    invitee: e.invitee, role: e.role, guardians: [...(e.guardians as string[])].sort(),
    ...(fromName !== undefined ? { fromName } : {}),
    ...(relays !== undefined ? { relays } : {}),
  }
}

export async function wrapInvitation(opts: { sender: KincludeSigner; recipient: string; envelope: InvitationEnvelope; now: number }): Promise<Event> {
  if (!readEnvelope(opts.envelope)) throw new Error('malformed invitation')
  return wrapGift({ sender: opts.sender, recipient: opts.recipient, kind: INVITATION_RUMOR_KIND, content: JSON.stringify(opts.envelope), now: opts.now })
}

export async function unwrapInvitation(wrap: Event, recipient: KincludeSigner): Promise<{ envelope: InvitationEnvelope; sender: string } | null> {
  const opened = await unwrapGift(wrap, recipient)
  if (!opened || opened.rumor.kind !== INVITATION_RUMOR_KIND) return null
  try {
    const envelope = readEnvelope(JSON.parse(opened.rumor.content))
    return envelope ? { envelope, sender: opened.sender } : null
  } catch {
    return null
  }
}

export const hex = { toBytes: hexToBytes, fromBytes: bytesToHex, pubkey: (sk: Uint8Array) => getPublicKey(sk) }
