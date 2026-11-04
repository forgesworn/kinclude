import type { Event } from 'nostr-tools/pure'
import {
  readApproval,
  readLinkStatement,
  readTier,
  approvalKey,
  TIER_GENESIS,
  type Approval,
  type LinkStatement,
  type SignedApproval,
  type SignedTier,
  type Tier,
} from './statements.js'

/**
 * Who is whose guardian, from the link statements this device has seen.
 *
 * A link holds when both halves exist (guardian-of signed by the guardian,
 * dependant-of signed by the child) and both are newer than any unlink
 * either of them signed naming the other. An unlink is permanent until a
 * new pair is made after it, which in practice means in person again.
 *
 * Nothing here is trusted because of where it came from: every statement
 * is checked by signature, and a statement that does not verify is not
 * kept.
 */
export class FamilyGraph {
  /** Newest statement of each type, per ordered (signer, other) pair. */
  private readonly newest = new Map<string, LinkStatement>()

  add(event: unknown): boolean {
    const s = readLinkStatement(event)
    if (!s) return false
    const key = `${s.type}:${s.signer}:${s.other}`
    const held = this.newest.get(key)
    if (held && (held.at > s.at || (held.at === s.at && held.event.id <= s.event.id))) return true
    this.newest.set(key, s)
    return true
  }

  addAll(events: Iterable<unknown>): void {
    for (const e of events) this.add(e)
  }

  private get(type: string, signer: string, other: string): LinkStatement | undefined {
    return this.newest.get(`${type}:${signer}:${other}`)
  }

  /** True when `guardian` holds a current link to `child`. */
  isGuardian(guardian: string, child: string): boolean {
    const down = this.get('guardian-of', guardian, child)
    const up = this.get('dependant-of', child, guardian)
    if (!down || !up) return false
    const cuts = [this.get('unlink', guardian, child), this.get('unlink', child, guardian)]
    const lastCut = Math.max(-1, ...cuts.map((c) => c?.at ?? -1))
    return down.at > lastCut && up.at > lastCut
  }

  guardiansOf(child: string): string[] {
    const out: string[] = []
    for (const s of this.newest.values()) {
      if (s.type === 'guardian-of' && s.other === child && this.isGuardian(s.signer, child)) out.push(s.signer)
    }
    return out.sort()
  }

  childrenOf(guardian: string): string[] {
    const out: string[] = []
    for (const s of this.newest.values()) {
      if (s.type === 'guardian-of' && s.signer === guardian && this.isGuardian(guardian, s.other)) out.push(s.other)
    }
    return out.sort()
  }

  /** True when `pk` is somebody's child in this graph. */
  isChild(pk: string): boolean {
    return this.guardiansOf(pk).length > 0
  }

  /** Every statement that bears on `child`, to carry into a room so the
   *  other members can check the links for themselves. */
  statementsFor(child: string): Event[] {
    const out: Event[] = []
    for (const s of this.newest.values()) {
      if (s.signer === child || s.other === child) out.push(s.event)
    }
    return out.sort((a, b) => a.created_at - b.created_at || (a.id < b.id ? -1 : 1))
  }
}

export interface TierPosition {
  tier: Tier
  seq: number
  digest: string
  /** The guardian who co-signed it. */
  agreedWith: string
  /** The later of the two signatures. */
  at: number
}

/**
 * A child's tier, as the chain of co-signed statements makes it.
 *
 * A statement counts once both halves are held: one signed by the child,
 * one by a guardian `family` links to the child at the time of reading.
 * The chain starts at a complete `genesis` statement and follows complete
 * statements whose `prev` is the one before. Two complete statements on the
 * same `prev` (two guardians agreeing different things at once) resolve to
 * the lower digest, so every device reaches the same answer.
 */
export class TierLedger {
  private readonly byDigest = new Map<string, { child?: SignedTier; guardians: Map<string, SignedTier> }>()

  constructor(private readonly family: FamilyGraph) {}

  add(event: unknown): boolean {
    const t = readTier(event)
    if (!t) return false
    let slot = this.byDigest.get(t.digest)
    if (!slot) {
      slot = { guardians: new Map() }
      this.byDigest.set(t.digest, slot)
    }
    if (t.signer === t.statement.child) slot.child ??= t
    else if (!slot.guardians.has(t.signer)) slot.guardians.set(t.signer, t)
    return true
  }

  addAll(events: Iterable<unknown>): void {
    for (const e of events) this.add(e)
  }

  private complete(child: string): Array<{ s: SignedTier; guardian: SignedTier }> {
    const out: Array<{ s: SignedTier; guardian: SignedTier }> = []
    for (const slot of this.byDigest.values()) {
      if (!slot.child || slot.child.statement.child !== child) continue
      const guardian = [...slot.guardians.values()]
        .filter((g) => this.family.isGuardian(g.signer, child))
        .sort((a, b) => (a.signer < b.signer ? -1 : 1))[0]
      if (guardian) out.push({ s: slot.child, guardian })
    }
    return out
  }

  current(child: string): TierPosition | undefined {
    const complete = this.complete(child)
    let at: TierPosition | undefined
    let prev = TIER_GENESIS
    for (let seq = 0; ; seq++) {
      const next = complete
        .filter((c) => c.s.statement.seq === seq && c.s.statement.prev === prev)
        .sort((a, b) => (a.s.digest < b.s.digest ? -1 : 1))[0]
      if (!next) return at
      at = {
        tier: next.s.statement.tier,
        seq,
        digest: next.s.digest,
        agreedWith: next.guardian.signer,
        at: Math.max(next.s.at, next.guardian.at),
      }
      prev = next.s.digest
    }
  }

  /** Halves waiting for the other signature, on top of where the child is
   *  now: what the "waiting for Sam" queue shows. */
  pending(child: string): SignedTier[] {
    const pos = this.current(child)
    const prev = pos?.digest ?? TIER_GENESIS
    const seq = pos ? pos.seq + 1 : 0
    const out: SignedTier[] = []
    for (const slot of this.byDigest.values()) {
      const any = slot.child ?? [...slot.guardians.values()][0]
      if (!any || any.statement.child !== child || any.statement.seq !== seq || any.statement.prev !== prev) continue
      const guardianOk = [...slot.guardians.values()].some((g) => this.family.isGuardian(g.signer, child))
      if (slot.child && guardianOk) continue
      out.push(any)
    }
    return out
  }

  /** The halves of the chain as it stands, to carry into a room. */
  chainEvents(child: string): Event[] {
    const out: Event[] = []
    let prev = TIER_GENESIS
    const complete = this.complete(child)
    for (let seq = 0; ; seq++) {
      const next = complete
        .filter((c) => c.s.statement.seq === seq && c.s.statement.prev === prev)
        .sort((a, b) => (a.s.digest < b.s.digest ? -1 : 1))[0]
      if (!next) return out
      out.push(next.s.event, next.guardian.event)
      prev = next.s.digest
    }
  }
}

export interface ApprovalPair {
  approval: Approval
  child: SignedApproval
  guardian: SignedApproval
}

/**
 * Approvals seen so far, half by half.
 *
 * A pair is the same approval (`approvalKey`) signed by its child and by a
 * guardian `family` links to that child. An approval is good for admission
 * until it expires; a pair records which guardian it was.
 */
export class ApprovalBook {
  private readonly halves = new Map<string, Map<string, SignedApproval>>()

  constructor(private readonly family: FamilyGraph) {}

  add(event: unknown): SignedApproval | null {
    const a = readApproval(event)
    if (!a) return null
    const key = approvalKey(a.approval)
    let slot = this.halves.get(key)
    if (!slot) {
      slot = new Map()
      this.halves.set(key, slot)
    }
    if (!slot.has(a.signer)) slot.set(a.signer, a)
    return a
  }

  addAll(events: Iterable<unknown>): void {
    for (const e of events) this.add(e)
  }

  /** A complete pair for (room, invitee, child), still in date at `now`. */
  pair(room: string, invitee: string, child: string, now: number): ApprovalPair | undefined {
    const found: ApprovalPair[] = []
    for (const slot of this.halves.values()) {
      const any = slot.values().next().value as SignedApproval | undefined
      if (!any) continue
      const a = any.approval
      if (a.room !== room || a.invitee !== invitee || a.child !== child || a.expires <= now) continue
      const c = slot.get(child)
      const g = [...slot.values()].filter((h) => h.signer !== child && this.family.isGuardian(h.signer, child))
        .sort((x, y) => (x.signer < y.signer ? -1 : 1))[0]
      if (c && g) found.push({ approval: a, child: c, guardian: g })
    }
    // The newest pair wins: a later approval may name a different set of
    // guardians for a child newcomer.
    return found.sort((x, y) => Math.max(y.child.at, y.guardian.at) - Math.max(x.child.at, x.guardian.at))[0]
  }

  /** Every half held for `invitee` in `room`, signed by anybody. */
  halvesFor(room: string, invitee: string): SignedApproval[] {
    const out: SignedApproval[] = []
    for (const slot of this.halves.values()) {
      for (const h of slot.values()) {
        if (h.approval.room === room && h.approval.invitee === invitee) out.push(h)
      }
    }
    return out
  }
}
