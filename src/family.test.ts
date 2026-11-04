import { describe, expect, it } from 'vitest'
import { generateSecretKey, finalizeEvent } from 'nostr-tools/pure'
import { localSigner, dependantSigner } from './signer.js'
import {
  approvalUnsigned,
  dependantOfUnsigned,
  emergencyUnsigned,
  guardianOfUnsigned,
  readApproval,
  readEmergency,
  readLinkStatement,
  readTier,
  sanitiseReason,
  tierDigest,
  tierUnsigned,
  unlinkUnsigned,
  TIER_GENESIS,
  type Tier,
} from './statements.js'
import { ApprovalBook, FamilyGraph, TierLedger } from './family.js'

function person() {
  const sk = generateSecretKey()
  return { sk, signer: localSigner(sk), pk: localSigner(sk).pubkey }
}
function child() {
  const sk = generateSecretKey()
  const signer = dependantSigner(sk)
  return { sk, signer, pk: signer.pubkey }
}

const NOW = 1_800_000_000

async function link(g: ReturnType<typeof person>, c: ReturnType<typeof child>, at = NOW) {
  return [
    await g.signer.signEvent(guardianOfUnsigned(c.pk, at)),
    await c.signer.signEvent(dependantOfUnsigned(g.pk, at)),
  ]
}

describe('guardian links', () => {
  it('reads both halves and refuses a link to oneself', async () => {
    const mum = person()
    const sam = child()
    const [down, up] = await link(mum, sam)
    expect(readLinkStatement(down)?.type).toBe('guardian-of')
    expect(readLinkStatement(up)?.type).toBe('dependant-of')
    const self = await mum.signer.signEvent(guardianOfUnsigned(mum.pk, NOW))
    expect(readLinkStatement(self)).toBeNull()
  })

  it('needs both halves', async () => {
    const mum = person()
    const sam = child()
    const [down, up] = await link(mum, sam)
    const g = new FamilyGraph()
    g.add(down)
    expect(g.isGuardian(mum.pk, sam.pk)).toBe(false)
    g.add(up)
    expect(g.isGuardian(mum.pk, sam.pk)).toBe(true)
    expect(g.guardiansOf(sam.pk)).toEqual([mum.pk])
    expect(g.childrenOf(mum.pk)).toEqual([sam.pk])
  })

  it('refuses a forged half', async () => {
    const mum = person()
    const sam = child()
    const [down] = await link(mum, sam)
    const forged = { ...down!, content: 'tampered' }
    const g = new FamilyGraph()
    expect(g.add(forged)).toBe(false)
  })

  it('an unlink from either side ends the link until a newer pair', async () => {
    const mum = person()
    const sam = child()
    const g = new FamilyGraph()
    g.addAll(await link(mum, sam, NOW))
    g.add(await sam.signer.signEvent(unlinkUnsigned(mum.pk, NOW + 10)))
    expect(g.isGuardian(mum.pk, sam.pk)).toBe(false)
    // Replaying the old pair changes nothing.
    g.addAll(await link(mum, sam, NOW))
    expect(g.isGuardian(mum.pk, sam.pk)).toBe(false)
    g.addAll(await link(mum, sam, NOW + 20))
    expect(g.isGuardian(mum.pk, sam.pk)).toBe(true)
  })

  it('a stranger cannot claim a child', async () => {
    const mum = person()
    const stranger = person()
    const sam = child()
    const g = new FamilyGraph()
    g.addAll(await link(mum, sam))
    g.add(await stranger.signer.signEvent(guardianOfUnsigned(sam.pk, NOW)))
    expect(g.guardiansOf(sam.pk)).toEqual([mum.pk])
  })
})

describe('tier chain', () => {
  async function both(c: ReturnType<typeof child>, g: ReturnType<typeof person>, tier: Tier, seq: number, prev: string, at = NOW) {
    const s = { child: c.pk, tier, seq, prev }
    return {
      digest: tierDigest(s),
      child: await c.signer.signEvent(tierUnsigned(s, at, 'Sam')),
      guardian: await g.signer.signEvent(tierUnsigned(s, at)),
    }
  }

  it('starts at a co-signed genesis and moves only with both signatures', async () => {
    const mum = person()
    const sam = child()
    const fam = new FamilyGraph()
    fam.addAll(await link(mum, sam))
    const ledger = new TierLedger(fam)
    const g0 = await both(sam, mum, 1, 0, TIER_GENESIS)
    ledger.add(g0.child)
    expect(ledger.current(sam.pk)).toBeUndefined()
    expect(ledger.pending(sam.pk)).toHaveLength(1)
    ledger.add(g0.guardian)
    expect(ledger.current(sam.pk)?.tier).toBe(1)
    expect(ledger.pending(sam.pk)).toHaveLength(0)

    const up = await both(sam, mum, 2, 1, g0.digest, NOW + 5)
    ledger.add(up.guardian)
    expect(ledger.current(sam.pk)?.tier).toBe(1)
    ledger.add(up.child)
    expect(ledger.current(sam.pk)).toMatchObject({ tier: 2, seq: 1, agreedWith: mum.pk })
    expect(ledger.chainEvents(sam.pk)).toHaveLength(4)
  })

  it('refuses a statement co-signed by somebody who is not a guardian', async () => {
    const mum = person()
    const stranger = person()
    const sam = child()
    const fam = new FamilyGraph()
    fam.addAll(await link(mum, sam))
    const ledger = new TierLedger(fam)
    const g0 = await both(sam, stranger, 3, 0, TIER_GENESIS)
    ledger.addAll([g0.child, g0.guardian])
    expect(ledger.current(sam.pk)).toBeUndefined()
  })

  it('an old statement replayed after the chain moved on changes nothing', async () => {
    const mum = person()
    const sam = child()
    const fam = new FamilyGraph()
    fam.addAll(await link(mum, sam))
    const ledger = new TierLedger(fam)
    const g0 = await both(sam, mum, 3, 0, TIER_GENESIS)
    const down = await both(sam, mum, 1, 1, g0.digest)
    ledger.addAll([g0.child, g0.guardian, down.child, down.guardian])
    expect(ledger.current(sam.pk)?.tier).toBe(1)
    ledger.addAll([g0.child, g0.guardian])
    expect(ledger.current(sam.pk)?.tier).toBe(1)
  })

  it('two complete forks on one prev resolve to the lower digest everywhere', async () => {
    const mum = person()
    const dad = person()
    const sam = child()
    const fam = new FamilyGraph()
    fam.addAll([...(await link(mum, sam)), ...(await link(dad, sam))])
    const g0 = await both(sam, mum, 1, 0, TIER_GENESIS)
    const a = await both(sam, mum, 2, 1, g0.digest)
    const b = await both(sam, dad, 3, 1, g0.digest)
    const l1 = new TierLedger(fam)
    const l2 = new TierLedger(fam)
    l1.addAll([g0.child, g0.guardian, a.child, a.guardian, b.child, b.guardian])
    l2.addAll([b.guardian, b.child, g0.guardian, a.guardian, g0.child, a.child])
    expect(l1.current(sam.pk)?.digest).toBe([a.digest, b.digest].sort()[0])
    expect(l2.current(sam.pk)?.digest).toBe(l1.current(sam.pk)?.digest)
  })

  it('refuses malformed statements', async () => {
    const mum = person()
    const sam = child()
    expect(() => tierUnsigned({ child: sam.pk, tier: 2, seq: 1, prev: TIER_GENESIS }, NOW)).toThrow()
    expect(() => tierUnsigned({ child: sam.pk, tier: 4 as Tier, seq: 0, prev: TIER_GENESIS }, NOW)).toThrow()
    const ev = finalizeEvent({ kind: 30078, created_at: NOW, tags: [['d', `kinclude/tier/${sam.pk}`], ['p', sam.pk], ['tier', '02'], ['seq', '0'], ['prev', 'genesis']], content: '' }, mum.sk)
    expect(readTier(ev)).toBeNull()
  })
})

describe('approvals', () => {
  it('needs the child and a guardian of that child on the same terms', async () => {
    const mum = person()
    const nan = person()
    const sam = child()
    const stranger = person()
    const room = person().pk
    const fam = new FamilyGraph()
    fam.addAll(await link(mum, sam))
    const book = new ApprovalBook(fam)
    const a = { room, invitee: nan.pk, child: sam.pk, role: 'adult' as const, guardians: [], expires: NOW + 100 }
    book.add(await sam.signer.signEvent(approvalUnsigned(a, NOW, 'Nan')))
    book.add(await stranger.signer.signEvent(approvalUnsigned(a, NOW)))
    expect(book.pair(room, nan.pk, sam.pk, NOW)).toBeUndefined()
    // A guardian's yes on different terms is not the same approval.
    book.add(await mum.signer.signEvent(approvalUnsigned({ ...a, expires: NOW + 99 }, NOW)))
    expect(book.pair(room, nan.pk, sam.pk, NOW)).toBeUndefined()
    book.add(await mum.signer.signEvent(approvalUnsigned(a, NOW)))
    expect(book.pair(room, nan.pk, sam.pk, NOW)?.guardian.signer).toBe(mum.pk)
    expect(book.pair(room, nan.pk, sam.pk, NOW + 100)).toBeUndefined()
  })

  it('a child newcomer is approved with their guardians named', async () => {
    const aunt = person()
    const jo = child()
    const room = person().pk
    expect(() => approvalUnsigned({ room, invitee: jo.pk, child: jo.pk, role: 'child', guardians: [], expires: NOW + 1 }, NOW)).toThrow()
    const ev = await jo.signer.signEvent(approvalUnsigned({ room, invitee: jo.pk, child: jo.pk, role: 'child', guardians: [aunt.pk], expires: NOW + 1 }, NOW))
    expect(readApproval(ev)?.approval.guardians).toEqual([aunt.pk])
  })

  it('refuses unknown tags', async () => {
    const mum = person()
    const room = person().pk
    const nan = person().pk
    const sam = child().pk
    const u = approvalUnsigned({ room, invitee: nan, child: sam, role: 'adult', guardians: [], expires: NOW + 10 }, NOW)
    u.tags.push(['extra', 'x'])
    expect(readApproval(await mum.signer.signEvent(u))).toBeNull()
  })
})

describe('emergency notice', () => {
  it('round-trips with a sanitised reason', async () => {
    const dad = person()
    const sam = child().pk
    const room = person().pk
    const ev = await dad.signer.signEvent(emergencyUnsigned({ room, child: sam, epoch: 3, reason: 'You did not come home‮ and your phone is off' }, NOW))
    const read = readEmergency(ev)
    expect(read?.notice).toMatchObject({ room, child: sam, epoch: 3 })
    expect(read?.notice.reason).toBe('You did not come home and your phone is off')
    expect(read?.signer).toBe(dad.pk)
  })

  it('sanitiseReason caps and trims', () => {
    expect(sanitiseReason('  a\u0000b  ')).toBe('a b')
    expect(sanitiseReason('x'.repeat(500))).toHaveLength(280)
    expect(sanitiseReason('   ')).toBeUndefined()
  })
})
