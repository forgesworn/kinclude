import { describe, expect, it } from 'vitest'
import { generateSecretKey, getPublicKey } from 'nostr-tools/pure'
import { banners, checkNewcomer, checkTransition, computeReaders, mayObserve, mayRemove } from './policy.js'
import { readState, signState, type RoomState, type StateMember } from './state.js'
import { adult, approvalPair, kid, linkEvents, NOW } from '../test/fixtures.js'

const authoritySk = generateSecretKey()
const authority = getPublicKey(authoritySk)
const room = getPublicKey(generateSecretKey())

function genesis(creator: string): RoomState {
  return {
    v: 1, room, seq: 0, prev: null, name: 'Family', epoch: 1,
    members: [{ pk: creator, role: 'adult', by: creator, at: NOW, approvals: [], links: [] }],
    readers: [creator], removed: [],
  }
}

function withMembers(prev: RoomState, members: StateMember[], prevId = 'a'.repeat(64)): RoomState {
  return { ...prev, seq: prev.seq + 1, prev: prevId, members: [...prev.members, ...members] }
}

describe('room state', () => {
  it('signs and reads, and only from the authority', () => {
    const mum = adult('Mum')
    const s = genesis(mum.pk)
    const ev = signState(authoritySk, s, NOW)
    expect(readState(ev, { room, authority })?.state).toEqual(s)
    expect(readState(ev, { room, authority: mum.pk })).toBeNull()
    expect(readState({ ...ev, content: ev.content.replace('Family', 'Evil') }, { room, authority })).toBeNull()
  })

  it('refuses a reader who is not listed', () => {
    const mum = adult('Mum')
    expect(() => signState(authoritySk, { ...genesis(mum.pk), readers: [adult('x').pk] }, NOW)).toThrow()
  })
})

describe('admission', () => {
  it('a creator adds their own child with the child and themself agreeing', async () => {
    const mum = adult('Mum')
    const sam = kid('Sam')
    const s0 = genesis(mum.pk)
    const entry: StateMember = {
      pk: sam.pk, role: 'child', by: mum.pk, at: NOW,
      links: await linkEvents(mum, sam),
      approvals: await approvalPair({ room, invitee: sam, child: sam, guardian: mum, role: 'child', guardians: [mum.pk] }),
    }
    expect(checkNewcomer(s0, entry, NOW).ok).toBe(true)
    expect(checkTransition(s0, withMembers(s0, [entry]), NOW, mum.pk)).toEqual({ ok: true })
  })

  it('an adult needs a pair for every child already in the room', async () => {
    const mum = adult('Mum')
    const dad = adult('Dad')
    const nan = adult('Nan')
    const sam = kid('Sam')
    const jo = kid('Jo')
    const s0 = genesis(mum.pk)
    const samEntry: StateMember = {
      pk: sam.pk, role: 'child', by: mum.pk, at: NOW, links: await linkEvents(mum, sam),
      approvals: await approvalPair({ room, invitee: sam, child: sam, guardian: mum, role: 'child', guardians: [mum.pk] }),
    }
    const joEntry: StateMember = {
      pk: jo.pk, role: 'child', by: mum.pk, at: NOW, links: [...(await linkEvents(mum, jo)), ...(await linkEvents(dad, jo))],
      approvals: [
        ...(await approvalPair({ room, invitee: jo, child: jo, guardian: dad, role: 'child', guardians: [mum.pk, dad.pk] })),
        ...(await approvalPair({ room, invitee: jo, child: sam, guardian: mum, role: 'child', guardians: [mum.pk, dad.pk] })),
      ],
    }
    const s1 = withMembers(s0, [samEntry])
    expect(checkNewcomer(s1, joEntry, NOW).ok).toBe(true)
    const s2 = withMembers(s1, [joEntry])

    const nanOnlySam: StateMember = {
      pk: nan.pk, role: 'adult', by: mum.pk, at: NOW, links: [],
      approvals: await approvalPair({ room, invitee: nan, child: sam, guardian: mum }),
    }
    const verdict = checkNewcomer(s2, nanOnlySam, NOW)
    expect(verdict).toMatchObject({ ok: false, missing: [jo.pk] })
    expect(checkTransition(s2, withMembers(s2, [nanOnlySam]), NOW, mum.pk).ok).toBe(false)

    nanOnlySam.approvals.push(...(await approvalPair({ room, invitee: nan, child: jo, guardian: dad })))
    expect(checkNewcomer(s2, nanOnlySam, NOW).ok).toBe(true)
    // ...but not once the approvals have expired.
    expect(checkNewcomer(s2, nanOnlySam, NOW + 8 * 86400).ok).toBe(false)
  })

  it('a child approval must name exactly the guardians their links show', async () => {
    const mum = adult('Mum')
    const dad = adult('Dad')
    const sam = kid('Sam')
    const s0 = genesis(mum.pk)
    const entry: StateMember = {
      pk: sam.pk, role: 'child', by: mum.pk, at: NOW,
      links: [...(await linkEvents(mum, sam)), ...(await linkEvents(dad, sam))],
      approvals: await approvalPair({ room, invitee: sam, child: sam, guardian: mum, role: 'child', guardians: [mum.pk] }),
    }
    expect(checkNewcomer(s0, entry, NOW).ok).toBe(false)
  })

  it('a child cannot be slipped in as an adult', async () => {
    const mum = adult('Mum')
    const sam = kid('Sam')
    const s0 = genesis(mum.pk)
    const entry: StateMember = { pk: sam.pk, role: 'adult', by: mum.pk, at: NOW, links: await linkEvents(mum, sam), approvals: [] }
    expect(checkNewcomer(s0, entry, NOW)).toMatchObject({ ok: false })
  })

  it('a stranger cannot be listed as a guardian', async () => {
    const mum = adult('Mum')
    const dad = adult('Dad')
    const stranger = adult('Stranger')
    const sam = kid('Sam')
    const s0 = genesis(dad.pk)
    const entry: StateMember = {
      pk: sam.pk, role: 'child', by: dad.pk, at: NOW,
      links: [...(await linkEvents(mum, sam)), ...(await linkEvents(dad, sam))],
      approvals: await approvalPair({ room, invitee: sam, child: sam, guardian: dad, role: 'child', guardians: [dad.pk, mum.pk].sort() }),
    }
    const s1 = withMembers(s0, [entry])
    expect(mayObserve(s1, mum.pk)).toBe(true)
    expect(mayObserve(s1, stranger.pk)).toBe(false)
    const asGuardian = (pk: string): StateMember => ({ pk, role: 'guardian', by: dad.pk, at: NOW, approvals: [], links: [] })
    expect(checkTransition(s0, withMembers(s0, [entry, asGuardian(mum.pk)]), NOW, dad.pk)).toEqual({ ok: true })
    expect(checkTransition(s0, withMembers(s0, [entry, asGuardian(stranger.pk)]), NOW, dad.pk).ok).toBe(false)
  })
})

describe('readers and banners', () => {
  async function familyRoom() {
    const dad = adult('Dad')
    const mum = adult('Mum')
    const sam = kid('Sam')
    const s0 = genesis(dad.pk)
    const entry: StateMember = {
      pk: sam.pk, role: 'child', by: dad.pk, at: NOW,
      links: [...(await linkEvents(mum, sam)), ...(await linkEvents(dad, sam))],
      approvals: await approvalPair({ room, invitee: sam, child: sam, guardian: dad, role: 'child', guardians: [dad.pk, mum.pk].sort() }),
    }
    const s1 = withMembers(s0, [entry, { pk: mum.pk, role: 'guardian', by: dad.pk, at: NOW, approvals: [], links: [] }])
    return { dad, mum, sam, s1 }
  }

  it('a listed guardian reads at tiers 1 and 2 and not at 3', async () => {
    const { dad, mum, sam, s1 } = await familyRoom()
    expect(computeReaders(s1, () => 1)).toEqual([dad.pk, mum.pk, sam.pk].sort())
    expect(computeReaders(s1, () => 2)).toContain(mum.pk)
    expect(computeReaders(s1, () => 3)).toEqual([dad.pk, sam.pk].sort())
    // A child with no tier yet is read as the first tier.
    expect(computeReaders(s1, () => undefined)).toContain(mum.pk)
  })

  it('banners come from readers, and say when the room is catching up', async () => {
    const { dad, mum, sam, s1 } = await familyRoom()
    const atTier2 = { ...s1, readers: computeReaders(s1, () => 2) }
    expect(banners(atTier2, () => 2)).toEqual([{ child: sam.pk, tier: 2, reading: [mum.pk], notReading: [], inOwnRight: [dad.pk], catchingUp: false }])
    // Sam moved to tier 3, the authority has not rekeyed yet.
    expect(banners(atTier2, () => 3)[0]).toMatchObject({ reading: [mum.pk], catchingUp: true })
    const atTier3 = { ...s1, readers: computeReaders(s1, () => 3) }
    expect(banners(atTier3, () => 3)[0]).toMatchObject({ reading: [], notReading: [mum.pk], catchingUp: false })
  })

  it('removal rights', async () => {
    const { dad, mum, sam, s1 } = await familyRoom()
    expect(mayRemove(s1, sam.pk, sam.pk, dad.pk)).toBe(true)
    expect(mayRemove(s1, mum.pk, sam.pk, dad.pk)).toBe(true)
    expect(mayRemove(s1, sam.pk, mum.pk, dad.pk)).toBe(false)
    expect(mayRemove(s1, mum.pk, dad.pk, dad.pk)).toBe(false)
    expect(mayRemove(s1, adult('x').pk, sam.pk, dad.pk)).toBe(false)
  })
})
