import { finalizeEvent, type Event } from 'nostr-tools/pure'
import { verifyEventUncached, sanitiseDisplayName } from '@forgesworn/fold-kit'
import { isPubkey } from './statements.js'
import { isEventShape } from './messages.js'

/**
 * The room's state: who is in it, in what role, and who can read it.
 *
 * Signed by the room's authority key, the same key that signs its rekeys
 * (the root inviter of the room's KithMoot group link), and chained by
 * `prev`, the id of the state it replaces. Every member keeps the newest
 * state that checks out and checks each change itself (`policy.ts`): a
 * newcomer has to arrive with the approvals the room needed, so a member's
 * device refuses to treat somebody as in the room just because the
 * authority says so.
 *
 * The state carries each member's approvals and, for a child, their
 * guardian links, so a member can check it alone, without having been
 * there when the approvals were made.
 *
 * `readers` is who the authority sealed the current epoch to, by
 * participant. It is what every banner is drawn from: the room says who can
 * read it, and the authority is the one party that knows.
 */
export const STATE_KIND = 30078
export const STATE_PREFIX = 'kinclude/state/'
export const MAX_MEMBERS = 64

/** `adult` and `child` are members in their own right. `guardian` is in the
 *  room only because a child in it is theirs: they read it at the first two
 *  tiers and are listed, not reading, at the third. */
export type Role = 'adult' | 'child' | 'guardian'

export interface StateMember {
  pk: string
  role: Role
  name?: string
  /** Who proposed them; the room's creator for the creator. */
  by: string
  /** When they joined, by the authority's clock. */
  at: number
  /** The approval halves that let them in. */
  approvals: Event[]
  /** A child's guardian links, both halves. */
  links: Event[]
}

export interface RoomState {
  v: 1
  room: string
  seq: number
  /** The id of the state event this one replaces; null at seq 0. */
  prev: string | null
  name: string
  /** The epoch `readers` holds. */
  epoch: number
  members: StateMember[]
  readers: string[]
  /** Everyone ever removed from the key set and not let back in. */
  removed: string[]
}

export function signState(authoritySk: Uint8Array, state: RoomState, now: number): Event {
  const checked = parseState(JSON.parse(JSON.stringify(state)))
  if (!checked) throw new Error('malformed room state')
  return finalizeEvent({
    kind: STATE_KIND,
    created_at: now,
    tags: [['d', STATE_PREFIX + state.room], ['seq', String(state.seq)]],
    content: JSON.stringify(checked),
  }, authoritySk)
}

export interface SignedState {
  state: RoomState
  event: Event
}

export function readState(event: unknown, opts: { room: string; authority: string }): SignedState | null {
  try {
    if (!isEventShape(event) || event.kind !== STATE_KIND || event.pubkey !== opts.authority) return null
    if (!verifyEventUncached(event)) return null
    const d = event.tags.find((t) => t[0] === 'd')?.[1]
    if (d !== STATE_PREFIX + opts.room) return null
    const state = parseState(JSON.parse(event.content))
    if (!state || state.room !== opts.room) return null
    if (event.tags.find((t) => t[0] === 'seq')?.[1] !== String(state.seq)) return null
    return { state, event }
  } catch {
    return null
  }
}

function parseState(raw: unknown): RoomState | null {
  if (typeof raw !== 'object' || raw === null) return null
  const s = raw as Record<string, unknown>
  if (s.v !== 1 || !isPubkey(s.room)) return null
  if (!Number.isSafeInteger(s.seq) || (s.seq as number) < 0) return null
  if (s.seq === 0 ? s.prev !== null : !isPubkey(s.prev)) return null
  const name = sanitiseDisplayName(s.name) ?? 'Family room'
  if (!Number.isSafeInteger(s.epoch) || (s.epoch as number) < 0) return null
  if (!Array.isArray(s.members) || s.members.length === 0 || s.members.length > MAX_MEMBERS) return null
  const members: StateMember[] = []
  const seen = new Set<string>()
  for (const m of s.members as unknown[]) {
    const mm = m as Record<string, unknown>
    if (typeof mm !== 'object' || mm === null || !isPubkey(mm.pk) || seen.has(mm.pk)) return null
    if (mm.role !== 'adult' && mm.role !== 'child' && mm.role !== 'guardian') return null
    if (!isPubkey(mm.by) || !Number.isSafeInteger(mm.at)) return null
    if (!Array.isArray(mm.approvals) || mm.approvals.length > 32 || !mm.approvals.every(isEventShape)) return null
    if (!Array.isArray(mm.links) || mm.links.length > 32 || !mm.links.every(isEventShape)) return null
    seen.add(mm.pk)
    const memberName = sanitiseDisplayName(mm.name)
    members.push({
      pk: mm.pk, role: mm.role, by: mm.by, at: mm.at as number,
      approvals: mm.approvals as Event[], links: mm.links as Event[],
      ...(memberName !== undefined ? { name: memberName } : {}),
    })
  }
  const list = (v: unknown): string[] | null =>
    Array.isArray(v) && v.length <= MAX_MEMBERS * 2 && v.every(isPubkey) ? [...new Set(v as string[])].sort() : null
  const readers = list(s.readers)
  const removed = list(s.removed)
  if (!readers || !removed) return null
  if (readers.some((r) => !seen.has(r))) return null
  return { v: 1, room: s.room, seq: s.seq as number, prev: (s.prev as string | null), name, epoch: s.epoch as number, members, readers, removed }
}

export function member(state: RoomState, pk: string): StateMember | undefined {
  return state.members.find((m) => m.pk === pk)
}

export function children(state: RoomState): StateMember[] {
  return state.members.filter((m) => m.role === 'child')
}
