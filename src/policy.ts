import { ApprovalBook, FamilyGraph, type ApprovalPair } from './family.js'
import { member, type RoomState, type StateMember } from './state.js'
import type { Tier } from './statements.js'

/**
 * The rules, as pure functions of a room's state.
 *
 * The authority's device applies them before it changes the room, and every
 * other member's device applies them again to each change it is shown. A
 * state that breaks them is not taken: the device keeps the last good one
 * and says the room is in a state it cannot check.
 */

/** Everyone's guardian links, as the room's members carry them. */
export function familyOf(state: RoomState, extra: readonly StateMember[] = []): FamilyGraph {
  const family = new FamilyGraph()
  for (const m of [...state.members, ...extra]) family.addAll(m.links)
  return family
}

/** The children whose approval pairs a newcomer needs: every child in the
 *  room, and the newcomer too when they are a child. */
export function requiredChildren(state: RoomState, invitee: string, role: 'adult' | 'child'): string[] {
  const out = state.members.filter((m) => m.role === 'child' && m.pk !== invitee).map((m) => m.pk)
  if (role === 'child') out.push(invitee)
  return [...new Set(out)].sort()
}

export type Verdict = { ok: true; pairs: ApprovalPair[] } | { ok: false; reason: string; missing?: string[] }

/**
 * Whether `entry` may join the room as it stood in `state`, at `now`.
 *
 * An adult or child needs, for each required child, the same approval
 * signed by that child and by one of their guardians, naming this room,
 * this newcomer and their role. A child newcomer's approvals also name
 * their guardians, and must name exactly the guardians their links show.
 */
export function checkNewcomer(state: RoomState, entry: StateMember, now: number): Verdict {
  if (entry.role === 'guardian') return { ok: false, reason: 'a guardian joins through their child, not by approval' }
  const family = familyOf(state, [entry])
  if (entry.role === 'child' && family.guardiansOf(entry.pk).length === 0) {
    return { ok: false, reason: 'a child joins with their guardian links' }
  }
  if (entry.role === 'adult' && family.isChild(entry.pk)) {
    return { ok: false, reason: 'somebody with a guardian joins as a child' }
  }
  const book = new ApprovalBook(family)
  book.addAll(entry.approvals)
  const pairs: ApprovalPair[] = []
  const missing: string[] = []
  const guardians = entry.role === 'child' ? family.guardiansOf(entry.pk) : []
  for (const child of requiredChildren(state, entry.pk, entry.role)) {
    const pair = book.pair(state.room, entry.pk, child, now)
    if (!pair || pair.approval.role !== entry.role || pair.approval.guardians.join(',') !== guardians.join(',')) {
      missing.push(child)
    } else {
      pairs.push(pair)
    }
  }
  if (missing.length > 0) return { ok: false, reason: 'approvals missing', missing }
  return { ok: true, pairs }
}

/**
 * Whether `pk` may be listed as a guardian (role `guardian`): a guardian,
 * by the room's own links, of a child in the room, and named among that
 * child's guardians when the child was approved in.
 */
export function mayObserve(state: RoomState, pk: string): boolean {
  const family = familyOf(state)
  for (const c of state.members) {
    if (c.role !== 'child' || !family.isGuardian(pk, c.pk)) continue
    const book = new ApprovalBook(family)
    book.addAll(c.approvals)
    // The child's own pair, at the time it was used: expiry is judged at
    // the child's arrival, not now.
    const own = book.pair(state.room, c.pk, c.pk, c.at)
    if (own?.approval.guardians.includes(pk)) return true
  }
  return false
}

/** The guardians a child's approval named, who are not members in their
 *  own right: who the room lists in role `guardian` for that child. */
export function observersFor(state: RoomState, child: StateMember): string[] {
  const family = familyOf(state)
  const book = new ApprovalBook(family)
  book.addAll(child.approvals)
  const own = book.pair(state.room, child.pk, child.pk, child.at)
  if (!own) return []
  return own.approval.guardians.filter((g) => family.isGuardian(g, child.pk) && member(state, g)?.role !== 'adult')
}

export type TierOf = (child: string) => Tier | undefined

/**
 * Who should hold the room's key: every member in their own right, and
 * every guardian listed for a child in the room at the first or second
 * tier. A child whose tier is not known is read as the first tier: it is
 * the tier every family starts at when it has not said otherwise, and the
 * banner says so.
 */
export function computeReaders(state: RoomState, tierOf: TierOf): string[] {
  const family = familyOf(state)
  const out = new Set<string>()
  for (const m of state.members) {
    if (m.role === 'adult' || m.role === 'child') out.add(m.pk)
  }
  for (const m of state.members) {
    if (m.role !== 'guardian') continue
    const reading = state.members.some((c) =>
      c.role === 'child' && family.isGuardian(m.pk, c.pk) && (tierOf(c.pk) ?? 1) < 3)
    if (reading) out.add(m.pk)
  }
  return [...out].sort()
}

export interface ChildBanner {
  child: string
  tier: Tier | undefined
  /** Guardians who can read the room because of this child. */
  reading: string[]
  /** Guardians listed for this child who cannot. */
  notReading: string[]
  /** Guardians of this child who are in the room in their own right. */
  inOwnRight: string[]
  /** The room's key set does not yet match the child's tier: it still
   *  includes a guardian who should have stepped back, or leaves out one
   *  who should read. Shown, not hidden, until the authority catches up. */
  catchingUp: boolean
}

/** What every member is shown about every child in the room. Drawn from
 *  the authority's `readers`, never from a local setting. */
export function banners(state: RoomState, tierOf: TierOf): ChildBanner[] {
  const family = familyOf(state)
  const readers = new Set(state.readers)
  const expected = new Set(computeReaders(state, tierOf))
  const out: ChildBanner[] = []
  for (const c of state.members) {
    if (c.role !== 'child') continue
    const guardians = family.guardiansOf(c.pk)
    const inOwnRight = guardians.filter((g) => member(state, g)?.role === 'adult')
    const listed = guardians.filter((g) => member(state, g)?.role === 'guardian')
    const reading = listed.filter((g) => readers.has(g))
    const notReading = listed.filter((g) => !readers.has(g))
    const catchingUp = listed.some((g) => readers.has(g) !== expected.has(g))
    out.push({ child: c.pk, tier: tierOf(c.pk), reading, notReading, inOwnRight, catchingUp })
  }
  return out
}

/**
 * Who may ask for somebody's removal: the person themself, whoever
 * proposed them, the room's creator, and any guardian of a child in the
 * room. A guardian listed only for their child leaves with the child, not
 * on anybody's say-so but the child's guardians'.
 */
export function mayRemove(state: RoomState, actor: string, target: string, creator: string): boolean {
  const t = member(state, target)
  if (!t || !member(state, actor)) return false
  if (target === creator && actor !== creator) return false
  if (actor === target || actor === creator || t.by === actor) return true
  const family = familyOf(state)
  return state.members.some((c) => c.role === 'child' && family.isGuardian(actor, c.pk))
}

export type TransitionCheck = { ok: true } | { ok: false; reason: string }

/**
 * Whether `next` is a change a member should accept from `prev`. `at` is
 * the new state's signing time, against which approvals are judged.
 */
export function checkTransition(prev: RoomState | null, next: RoomState, at: number, creator: string): TransitionCheck {
  if (prev === null) {
    if (next.seq !== 0 || next.prev !== null) return { ok: false, reason: 'a room starts at seq 0' }
    const [only, ...rest] = next.members
    if (!only || rest.length > 0 || only.pk !== creator || only.role !== 'adult') {
      return { ok: false, reason: 'a room starts with its creator alone' }
    }
    return { ok: true }
  }
  if (next.room !== prev.room || next.seq !== prev.seq + 1) return { ok: false, reason: 'out of sequence' }
  if (next.epoch < prev.epoch) return { ok: false, reason: 'the epoch went backwards' }
  if (!member(next, creator)) return { ok: false, reason: 'the creator is always in their room' }
  // Newcomers are judged one at a time against the room as it was, plus
  // the newcomers already let in ahead of them in this change.
  let running: RoomState = { ...prev, members: prev.members.filter((m) => member(next, m.pk)) }
  const guardiansAdded: StateMember[] = []
  for (const m of next.members) {
    const before = member(prev, m.pk)
    if (before && before.role === m.role) continue
    if (m.role === 'guardian') {
      guardiansAdded.push(m)
      continue
    }
    const verdict = checkNewcomer(running, m, at)
    if (!verdict.ok) return { ok: false, reason: `${m.pk.slice(0, 8)}: ${verdict.reason}` }
    running = { ...running, members: [...running.members.filter((x) => x.pk !== m.pk), m] }
  }
  for (const g of guardiansAdded) {
    if (!mayObserve(running, g.pk)) return { ok: false, reason: `${g.pk.slice(0, 8)} is not a guardian the room approved` }
  }
  // A guardian whose child has left is not kept on as a guardian.
  for (const m of next.members) {
    if (m.role === 'guardian' && !mayObserve(next, m.pk)) return { ok: false, reason: `${m.pk.slice(0, 8)} has no child here` }
  }
  return { ok: true }
}
