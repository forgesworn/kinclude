import { finalizeEvent, generateSecretKey, getPublicKey, verifyEvent, type Event } from 'nostr-tools/pure'
import { nip44 } from 'nostr-tools'
import { hkdf } from '@noble/hashes/hkdf'
import { sha256 } from '@noble/hashes/sha2'
import { bytesToHex } from '@noble/hashes/utils'
import { verifyDeviceCredential, type DeviceCredential } from '@forgesworn/fold-kit'
import { isPubkey } from './statements.js'

/**
 * Call signalling: KithMoot's call signalling profile 1, unchanged.
 *
 * An offer, answer or ICE candidate is a kind 20462 event signed by the
 * room device key, tagged `p` = recipient device, `call-id` = room id,
 * `alt` and `kithmoot` = `1`, and NIP-44 encrypted, whole, from a key made
 * for that one wrap to the recipient device, inside a kind 21059 tagged
 * only `p` and a NIP-40 `expiration` 60 seconds on. A reader accepts it
 * within 20 seconds of its inner time.
 *
 * Kinclude adds one field to the signal body, `call`, the id of the call it
 * belongs to; a KithMoot reader ignores it.
 */
export const SIGNAL_KIND = 20462
export const SIGNAL_WRAP_KIND = 21059
export const SIGNAL_EXPIRY_SECONDS = 60
export const SIGNAL_STALE_SECONDS = 20
export const MAX_SIGNAL_CONTENT = 131_072

export type SignalType = 'offer' | 'answer' | 'ice'

export interface SignalBody {
  type: SignalType
  roomId: string
  call: string
  sdp?: string
  /** An RTCIceCandidateInit, as a JSON string. */
  candidate?: string
}

const CALL_ID = /^[0-9a-f]{32}$/

export function isCallId(v: unknown): v is string {
  return typeof v === 'string' && CALL_ID.test(v)
}

export function encodeSignal(opts: { deviceSk: Uint8Array; recipient: string; body: SignalBody; now: number }): Event {
  const inner = finalizeEvent({
    kind: SIGNAL_KIND,
    created_at: opts.now,
    tags: [['p', opts.recipient], ['call-id', opts.body.roomId], ['alt', 'KithMoot call signalling'], ['kithmoot', '1']],
    content: JSON.stringify(opts.body),
  }, opts.deviceSk)
  const throwaway = generateSecretKey()
  try {
    return finalizeEvent({
      kind: SIGNAL_WRAP_KIND,
      created_at: opts.now,
      tags: [['p', opts.recipient], ['expiration', String(opts.now + SIGNAL_EXPIRY_SECONDS)]],
      content: nip44.v2.encrypt(JSON.stringify(inner), nip44.v2.utils.getConversationKey(throwaway, opts.recipient)),
    }, throwaway)
  } finally {
    throwaway.fill(0)
  }
}

export interface ReceivedSignal {
  /** The device that signed it. */
  from: string
  /** The inner event's id, for deduplication across rewraps. */
  id: string
  body: SignalBody
}

export function decodeSignal(wrap: Event, opts: { deviceSk: Uint8Array; roomId: string; now: number }): ReceivedSignal | null {
  try {
    if (wrap.kind !== SIGNAL_WRAP_KIND || wrap.content.length > MAX_SIGNAL_CONTENT || wrap.tags.length > 16) return null
    const me = getPublicKey(opts.deviceSk)
    if (wrap.tags.find((t) => t[0] === 'p')?.[1] !== me) return null
    if (!verifyEvent(wrap)) return null
    const inner = JSON.parse(nip44.v2.decrypt(wrap.content, nip44.v2.utils.getConversationKey(opts.deviceSk, wrap.pubkey))) as Event
    if (inner.kind !== SIGNAL_KIND || !verifyEvent(inner)) return null
    if (Math.abs(inner.created_at - opts.now) > SIGNAL_STALE_SECONDS) return null
    if (inner.tags.find((t) => t[0] === 'p')?.[1] !== me) return null
    const callIds = inner.tags.filter((t) => t[0] === 'call-id')
    if (callIds.length > 1 || (callIds.length === 1 && callIds[0]![1] !== opts.roomId)) return null
    const body = JSON.parse(inner.content) as Record<string, unknown>
    if (body.roomId !== opts.roomId || !isCallId(body.call)) return null
    if (body.type !== 'offer' && body.type !== 'answer' && body.type !== 'ice') return null
    if (body.sdp !== undefined && (typeof body.sdp !== 'string' || body.sdp.length > 65_536)) return null
    if (body.candidate !== undefined && (typeof body.candidate !== 'string' || body.candidate.length > 4_096)) return null
    if ((body.type === 'offer' || body.type === 'answer') && typeof body.sdp !== 'string') return null
    if (body.type === 'ice' && typeof body.candidate !== 'string') return null
    return {
      from: inner.pubkey,
      id: inner.id,
      body: {
        type: body.type, roomId: opts.roomId, call: body.call,
        ...(typeof body.sdp === 'string' ? { sdp: body.sdp } : {}),
        ...(typeof body.candidate === 'string' ? { candidate: body.candidate } : {}),
      },
    }
  } catch {
    return null
  }
}

/**
 * Who is on a call: a heartbeat each device on it sends, kind 20481
 * (ephemeral), signed by the room device key, tagged `d` with a value
 * derived from the current epoch key, and NIP-44 encrypted under another.
 * Only somebody who can read the room can write or read one, so somebody
 * who cannot read the room cannot be on its call, or see who is.
 */
export const PRESENCE_KIND = 20481
export const PRESENCE_TAG_INFO = 'kinclude/v1/call-presence-tag'
export const PRESENCE_KEY_INFO = 'kinclude/v1/call-presence-key'
/** A heartbeat this old says nothing any more. */
export const PRESENCE_STALE_SECONDS = 30

export interface Presence {
  call: string
  state: 'on' | 'off'
  participant: string
  device: string
  credential: DeviceCredential
  video: boolean
  at: number
}

export function presenceKeys(epochKey: Uint8Array): { tag: string; key: Uint8Array } {
  return {
    tag: bytesToHex(hkdf(sha256, epochKey, undefined, PRESENCE_TAG_INFO, 32)),
    key: hkdf(sha256, epochKey, undefined, PRESENCE_KEY_INFO, 32),
  }
}

export function encodePresence(p: Presence, opts: { deviceSk: Uint8Array; epochKey: Uint8Array }): Event {
  const k = presenceKeys(opts.epochKey)
  return finalizeEvent({
    kind: PRESENCE_KIND,
    created_at: p.at,
    tags: [['d', k.tag]],
    content: nip44.v2.encrypt(JSON.stringify(p), k.key),
  }, opts.deviceSk)
}

export function decodePresence(event: Event, opts: { roomId: string; epochKey: Uint8Array; now: number }): Presence | null {
  try {
    const k = presenceKeys(opts.epochKey)
    if (event.kind !== PRESENCE_KIND || event.tags.find((t) => t[0] === 'd')?.[1] !== k.tag) return null
    if (!verifyEvent(event)) return null
    const p = JSON.parse(nip44.v2.decrypt(event.content, k.key)) as Record<string, unknown>
    if (!isCallId(p.call) || (p.state !== 'on' && p.state !== 'off')) return null
    if (!isPubkey(p.participant) || p.device !== event.pubkey || typeof p.video !== 'boolean') return null
    if (p.at !== event.created_at || Math.abs((p.at as number) - opts.now) > PRESENCE_STALE_SECONDS) return null
    const verdict = verifyDeviceCredential(p.credential as DeviceCredential, { roomId: opts.roomId, now: event.created_at })
    if (!verdict.ok || verdict.device !== event.pubkey || verdict.participant !== p.participant) return null
    return {
      call: p.call, state: p.state, participant: p.participant, device: event.pubkey,
      credential: p.credential as DeviceCredential, video: p.video, at: event.created_at,
    }
  } catch {
    return null
  }
}
