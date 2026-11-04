import { finalizeEvent, type Event } from 'nostr-tools/pure'
import { nip44 } from 'nostr-tools'
import { bytesToHex, randomBytes } from '@noble/hashes/utils'
import { verifyDeviceCredential, verifyEventUncached, sanitiseDisplayName, KINDS, type DeviceCredential } from '@forgesworn/fold-kit'
import { isPubkey } from './statements.js'

/**
 * Room messages.
 *
 * On the wire these are KithMoot chat messages, envelope 1: kind 1460, a
 * `d` tag naming the stream, NIP-44 v2 content under the stream key, signed
 * by the device key, with the device credential carried inside. Kinclude's
 * own payloads ride in one extra field, `kinclude`, beside a `text` that
 * reads sensibly on its own, the same discipline KithMoot's message layer
 * keeps. Relays see nothing a KithMoot room does not show them.
 *
 * Only the fields Kinclude uses are read. A KithMoot field this codec does
 * not know (reactions, threads, attachments) is dropped on decode rather
 * than passed through unchecked.
 */
export const MAX_TEXT_LENGTH = 2_000
export const MAX_CLOCK_SKEW_SECONDS = 300
export const MAX_EVENTS_PER_PAYLOAD = 64

export interface Stream {
  /** The `d` tag. */
  id: string
  /** The NIP-44 conversation key. */
  key: Uint8Array
}

export type KincludePayload =
  /** A member's family statements: guardian links and their tier chain. */
  | { t: 'statements'; events: Event[] }
  /** Somebody would like `invitee` in the room. Approvals follow. */
  | { t: 'propose'; invitee: string; role: 'adult' | 'child'; guardians: string[]; name?: string; expires: number }
  /** The authority's signed room state. */
  | { t: 'state'; event: Event }
  /** A guardian read the room up to a message, before seeing it. */
  | { t: 'read'; child: string; upTo: string; at: number }
  /** A tier-3 child's device: this epoch's secret, sealed to each guardian. */
  | { t: 'escrow'; epoch: number; copies: Array<{ guardian: string; ct: string }> }
  /** A guardian is opening a tier-3 child's room. */
  | { t: 'emergency'; event: Event }
  /** A member asks the authority to remove somebody, or themself. */
  | { t: 'remove'; target: string }

export interface RoomMessage {
  id: string
  participant: string
  device: string
  credential: DeviceCredential
  name?: string
  text: string
  sentAt: number
  sentAtMs?: number
  /** A message this one withdraws. KithMoot's field, kept for chat. */
  retracts?: string
  kinclude?: KincludePayload
}

export function newMessageId(): string {
  return bytesToHex(randomBytes(16))
}

export function encodeMessage(msg: RoomMessage, opts: { stream: Stream; deviceSk: Uint8Array }): Event {
  const body: RoomMessage = {
    id: msg.id,
    participant: msg.participant,
    device: msg.device,
    credential: msg.credential,
    text: msg.text,
    sentAt: msg.sentAt,
  }
  const name = sanitiseDisplayName(msg.name)
  if (name !== undefined) body.name = name
  if (msg.sentAtMs !== undefined) body.sentAtMs = msg.sentAtMs
  if (msg.retracts !== undefined) body.retracts = msg.retracts
  if (msg.kinclude !== undefined) {
    const k = readPayload(msg.kinclude)
    if (!k) throw new Error('malformed kinclude payload')
    body.kinclude = k
  }
  if (body.text.length === 0 || body.text.length > MAX_TEXT_LENGTH) throw new Error('a message has readable text')
  return finalizeEvent({
    kind: KINDS.CHAT,
    created_at: msg.sentAt,
    tags: [['d', opts.stream.id]],
    content: nip44.v2.encrypt(JSON.stringify(body), opts.stream.key),
  }, opts.deviceSk)
}

export function decodeMessage(event: Event, opts: { roomId: string; stream: Stream; now: number }): RoomMessage | null {
  try {
    if (event.kind !== KINDS.CHAT) return null
    const d = event.tags.find((t) => t[0] === 'd')?.[1]
    if (d !== opts.stream.id) return null
    if (!verifyEventUncached(event)) return null
    const raw = JSON.parse(nip44.v2.decrypt(event.content, opts.stream.key)) as Record<string, unknown>
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null
    const { id, participant, device, credential, text, sentAt } = raw
    if (typeof id !== 'string' || id.length === 0 || id.length > 128) return null
    if (typeof participant !== 'string' || typeof device !== 'string') return null
    if (typeof text !== 'string' || text.length === 0 || text.length > MAX_TEXT_LENGTH) return null
    if (typeof sentAt !== 'number' || !Number.isSafeInteger(sentAt)) return null
    if (typeof credential !== 'object' || credential === null) return null
    const dev = device.toLowerCase()
    const who = participant.toLowerCase()
    if (dev !== event.pubkey) return null
    if (sentAt !== event.created_at) return null
    if (sentAt > opts.now + MAX_CLOCK_SKEW_SECONDS) return null
    const verdict = verifyDeviceCredential(credential as DeviceCredential, { roomId: opts.roomId, now: sentAt })
    if (!verdict.ok || verdict.device !== dev || verdict.participant !== who) return null
    const msg: RoomMessage = { id, participant: who, device: dev, credential: credential as DeviceCredential, text, sentAt }
    const name = sanitiseDisplayName(raw.name)
    if (name !== undefined) msg.name = name
    if (Number.isSafeInteger(raw.sentAtMs) && Math.floor((raw.sentAtMs as number) / 1000) === sentAt) msg.sentAtMs = raw.sentAtMs as number
    if (typeof raw.retracts === 'string' && raw.retracts.length > 0 && raw.retracts.length <= 128) msg.retracts = raw.retracts
    if (raw.kinclude !== undefined) {
      const k = readPayload(raw.kinclude)
      // A Kinclude payload that does not check out is not a message to make
      // the best of: its text was written to stand for it.
      if (!k) return null
      msg.kinclude = k
    }
    return msg
  } catch {
    return null
  }
}

function eventList(raw: unknown): Event[] | null {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > MAX_EVENTS_PER_PAYLOAD) return null
  for (const e of raw) if (!isEventShape(e)) return null
  return raw as Event[]
}

export function isEventShape(e: unknown): e is Event {
  if (typeof e !== 'object' || e === null) return false
  const x = e as Record<string, unknown>
  return typeof x.id === 'string' && typeof x.pubkey === 'string' && typeof x.sig === 'string' &&
    typeof x.kind === 'number' && typeof x.created_at === 'number' && typeof x.content === 'string' &&
    Array.isArray(x.tags) && x.tags.every((t) => Array.isArray(t) && t.every((s) => typeof s === 'string'))
}

/** Check a payload's shape. Signatures inside it are checked by whoever
 *  uses them, against the statement or state each claims to be. */
export function readPayload(raw: unknown): KincludePayload | null {
  if (typeof raw !== 'object' || raw === null) return null
  const p = raw as Record<string, unknown>
  switch (p.t) {
    case 'statements': {
      const events = eventList(p.events)
      return events ? { t: 'statements', events } : null
    }
    case 'propose': {
      if (!isPubkey(p.invitee) || (p.role !== 'adult' && p.role !== 'child')) return null
      if (!Array.isArray(p.guardians) || p.guardians.length > 8 || !p.guardians.every(isPubkey)) return null
      if ((p.role === 'adult') !== (p.guardians.length === 0)) return null
      if (!Number.isSafeInteger(p.expires)) return null
      const name = sanitiseDisplayName(p.name)
      return {
        t: 'propose', invitee: p.invitee, role: p.role, guardians: [...(p.guardians as string[])].sort(), expires: p.expires as number,
        ...(name !== undefined ? { name } : {}),
      }
    }
    case 'state':
    case 'emergency':
      return isEventShape(p.event) ? { t: p.t, event: p.event } : null
    case 'read':
      if (!isPubkey(p.child) || typeof p.upTo !== 'string' || p.upTo.length === 0 || p.upTo.length > 128) return null
      if (!Number.isSafeInteger(p.at)) return null
      return { t: 'read', child: p.child, upTo: p.upTo, at: p.at as number }
    case 'escrow': {
      if (!Number.isSafeInteger(p.epoch) || (p.epoch as number) < 1) return null
      if (!Array.isArray(p.copies) || p.copies.length === 0 || p.copies.length > 8) return null
      const copies: Array<{ guardian: string; ct: string }> = []
      for (const c of p.copies as unknown[]) {
        const cc = c as Record<string, unknown>
        if (typeof cc !== 'object' || cc === null || !isPubkey(cc.guardian) || typeof cc.ct !== 'string' || cc.ct.length > 1024) return null
        copies.push({ guardian: cc.guardian, ct: cc.ct })
      }
      return { t: 'escrow', epoch: p.epoch as number, copies }
    }
    case 'remove':
      return isPubkey(p.target) ? { t: 'remove', target: p.target } : null
    default:
      return null
  }
}
