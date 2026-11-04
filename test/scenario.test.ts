import { describe, expect, it } from 'vitest'
import { generateSecretKey, type Event } from 'nostr-tools/pure'
import { hexToBytes } from '@noble/hashes/utils'
import { deriveRoom, requestPersistentRoomAdmission, roomInvitation } from '@forgesworn/fold-kit'
import { MemoryRelay } from '../src/memory-relay.js'
import { KincludeRoom } from '../src/room.js'
import { unwrapInvitation, type InvitationEnvelope } from '../src/invite.js'
import { approvalUnsigned, APPROVAL_TTL_SECONDS, type Tier } from '../src/statements.js'
import { adult, kid, linkEvents, tierEvents, type Person } from './fixtures.js'

const clock = { t: 1_800_000_000 }
const now = () => clock.t

async function quiet(relay: MemoryRelay, rooms: KincludeRoom[], rounds = 40): Promise<void> {
  let last = -1
  let still = 0
  for (let i = 0; i < rounds * 10 && still < 4; i++) {
    await new Promise((r) => setTimeout(r, 2))
    await Promise.all(rooms.map((r) => r.idle()))
    if (relay.log.length === last) still++
    else { still = 0; last = relay.log.length }
  }
}

async function inbox(relay: MemoryRelay, who: Person): Promise<InvitationEnvelope[]> {
  const out: InvitationEnvelope[] = []
  for (const e of relay.stored.filter((e) => e.kind === 1059 && e.tags.some((t) => t[0] === 'p' && t[1] === who.pk))) {
    const opened = await unwrapInvitation(e, who.signer)
    if (opened) out.push(opened.envelope)
  }
  return out
}

async function join(relay: MemoryRelay, who: Person, envelope: InvitationEnvelope, family: Event[] = []): Promise<KincludeRoom> {
  const admission = await requestPersistentRoomAdmission({
    transport: relay.connect(),
    invitation: roomInvitation(hexToBytes(envelope.bearer), envelope.authority, true),
  })
  expect(deriveRoom(admission.secret).roomId).toBe(envelope.room)
  const room = new KincludeRoom({
    transport: relay.connect(), identity: who.signer, name: who.name, now, family, requestEveryMs: 0,
    phoneSecret: generateSecretKey(),
    secrets: { secret: Buffer.from(admission.secret).toString('hex'), authority: envelope.authority, epochs: {} },
  })
  await room.start()
  return room
}

/** The pair a child newcomer arrives with: their own yes and a guardian's. */
async function ownPair(envelope: InvitationEnvelope, child: Person, guardian: Person): Promise<Event[]> {
  const a = { room: envelope.room, invitee: child.pk, child: child.pk, role: 'child' as const, guardians: envelope.guardians, expires: now() + APPROVAL_TTL_SECONDS }
  return [await child.signer.signEvent(approvalUnsigned(a, now())), await guardian.signer.signEvent(approvalUnsigned(a, now()))]
}

async function familyOf(child: Person, guardians: Person[], tier: Tier) {
  const events: Event[] = []
  for (const g of guardians) events.push(...(await linkEvents(g, child, now())))
  const t = await tierEvents(child, guardians[0]!, tier, 0, undefined, now())
  events.push(...t.events)
  return { events, digest: t.digest }
}

describe('a family room', () => {
  it('a guardian makes a room, brings their child in, and invites Nan with everyone agreeing', async () => {
    const relay = new MemoryRelay()
    const mum = adult('Mum')
    const sam = kid('Sam')
    const nan = adult('Nan')
    const fam = await familyOf(sam, [mum], 1)

    const mumRoom = await KincludeRoom.create({
      transport: relay.connect(), identity: mum.signer, name: 'Mum', roomName: 'Home', now,
      phoneSecret: generateSecretKey(), family: fam.events, requestEveryMs: 0,
    })
    await quiet(relay, [mumRoom])
    expect(mumRoom.view()).toMatchObject({ name: 'Home', reading: true, role: 'adult', epoch: 1 })

    // Mum proposes Sam; there are no children yet, so Mum's word is enough
    // for the invitation, and Sam arrives with Sam's own pair.
    await mumRoom.propose({ invitee: sam.pk, name: 'Sam', role: 'child', guardians: [mum.pk] })
    await quiet(relay, [mumRoom])
    const [samInvite] = await inbox(relay, mum)
    expect(samInvite).toMatchObject({ invitee: sam.pk, role: 'child', guardians: [mum.pk] })
    const samRoom = await join(relay, sam, samInvite!, [...fam.events, ...(await ownPair(samInvite!, sam, mum))])
    await quiet(relay, [mumRoom, samRoom])
    expect(samRoom.view()).toMatchObject({ role: 'child', reading: true })
    expect(samRoom.view().tierOf[sam.pk]).toBe(1)

    await samRoom.send('hello mum')
    await quiet(relay, [mumRoom, samRoom])
    expect(mumRoom.view().messages.map((m) => m.text)).toEqual(['hello mum'])

    // Nan needs Sam and Mum, on Sam's behalf.
    await mumRoom.propose({ invitee: nan.pk, name: 'Nan', role: 'adult' })
    await quiet(relay, [mumRoom, samRoom])
    expect(await inbox(relay, nan)).toHaveLength(0)
    const pending = samRoom.view().proposals.find((p) => p.invitee === nan.pk)
    expect(pending?.approvals).toEqual([{ child: sam.pk, byChild: false, byGuardian: mum.pk }])
    expect(samRoom.approvalsToSign(nan.pk)).toEqual([sam.pk])
    await samRoom.approve(nan.pk)
    await quiet(relay, [mumRoom, samRoom])
    const [nanInvite] = await inbox(relay, nan)
    expect(nanInvite).toMatchObject({ invitee: nan.pk, role: 'adult', name: 'Home' })

    const nanRoom = await join(relay, nan, nanInvite!)
    await quiet(relay, [mumRoom, samRoom, nanRoom])
    expect(nanRoom.view()).toMatchObject({ role: 'adult', reading: true })
    await nanRoom.send('Did you see the heron this morning?')
    await quiet(relay, [mumRoom, samRoom, nanRoom])
    expect(samRoom.view().messages.map((m) => m.text)).toContain('Did you see the heron this morning?')
    // Nan joined after Sam's first message, in a later epoch: she cannot
    // read what was said before she was let in.
    expect(nanRoom.view().messages.map((m) => m.text)).not.toContain('hello mum')
    // Every member checked the state for themself.
    for (const r of [mumRoom, samRoom, nanRoom]) expect(r.view().stateProblem).toBeUndefined()
    for (const r of [mumRoom, samRoom, nanRoom]) r.close()
  })

  it('a stranger who learns the room id and authority is never let in', async () => {
    const relay = new MemoryRelay()
    const mum = adult('Mum')
    const mumRoom = await KincludeRoom.create({
      transport: relay.connect(), identity: mum.signer, name: 'Mum', roomName: 'Home', now,
      phoneSecret: generateSecretKey(), requestEveryMs: 0,
    })
    await quiet(relay, [mumRoom])
    // Somebody holding the room secret but never proposed or approved: as if
    // a bearer leaked.
    const stranger = adult('Stranger')
    const leaked = new KincludeRoom({
      transport: relay.connect(), identity: stranger.signer, name: 'Stranger', now, requestEveryMs: 0,
      phoneSecret: generateSecretKey(), secrets: { ...mumRoom.secrets(), authoritySk: undefined, epochs: {} },
    })
    await leaked.start()
    await quiet(relay, [mumRoom, leaked])
    await mumRoom.send('private')
    await quiet(relay, [mumRoom, leaked])
    expect(leaked.view()).toMatchObject({ reading: false, role: undefined })
    expect(leaked.view().messages).toHaveLength(0)
    expect(mumRoom.view().state?.members.map((m) => m.pk)).toEqual([mum.pk])
    mumRoom.close()
    leaked.close()
  })
})

describe('oversight tiers', () => {
  /** Dad runs the room; Sam is in it; Mum is listed as Sam's guardian. */
  async function household(tier: Tier) {
    const relay = new MemoryRelay()
    const dad = adult('Dad')
    const mum = adult('Mum')
    const sam = kid('Sam')
    const fam = await familyOf(sam, [dad, mum], tier)
    const dadRoom = await KincludeRoom.create({
      transport: relay.connect(), identity: dad.signer, name: 'Dad', roomName: 'Home', now,
      phoneSecret: generateSecretKey(), family: fam.events, requestEveryMs: 0,
    })
    await dadRoom.propose({ invitee: sam.pk, name: 'Sam', role: 'child', guardians: [dad.pk, mum.pk] })
    await quiet(relay, [dadRoom])
    const [invite] = await inbox(relay, mum)
    expect((await inbox(relay, dad)).length).toBe(1)
    const samRoom = await join(relay, sam, invite!, [...fam.events, ...(await ownPair(invite!, sam, mum))])
    await quiet(relay, [dadRoom, samRoom])
    const mumRoom = await join(relay, mum, invite!, fam.events)
    await quiet(relay, [dadRoom, samRoom, mumRoom])
    return { relay, dad, mum, sam, fam, dadRoom, samRoom, mumRoom, rooms: [dadRoom, samRoom, mumRoom] }
  }

  async function moveTier(h: Awaited<ReturnType<typeof household>>, tier: Tier, seq: number, prev: string) {
    const t = await tierEvents(h.sam, h.mum, tier, seq, prev, now())
    await h.samRoom.postStatements([t.events[0]!])
    await h.mumRoom.postStatements([t.events[1]!])
    await quiet(h.relay, h.rooms)
    return t.digest
  }

  it('tier 1: Mum reads everything and the room says so', async () => {
    const h = await household(1)
    expect(h.mumRoom.view()).toMatchObject({ role: 'guardian', reading: true, gatedFor: [] })
    await h.samRoom.send('Yes!! It was huge.')
    await quiet(h.relay, h.rooms)
    expect(h.mumRoom.view().messages.map((m) => m.text)).toEqual(['Yes!! It was huge.'])
    for (const r of h.rooms) {
      expect(r.view().banners).toEqual([{ child: h.sam.pk, tier: 1, reading: [h.mum.pk], notReading: [], inOwnRight: [h.dad.pk], catchingUp: false }])
    }
  })

  it('tier 2: Mum publishes a receipt before anything is shown to her', async () => {
    const h = await household(2)
    await h.samRoom.send('Are you coming on Saturday?')
    await quiet(h.relay, h.rooms)
    expect(h.mumRoom.view()).toMatchObject({ reading: true, gatedFor: [h.sam.pk], unrevealed: 1, messages: [] })
    const before = h.relay.log.length
    await h.mumRoom.reveal()
    // The receipt went out before the view changed.
    expect(h.relay.log.length).toBeGreaterThan(before)
    expect(h.mumRoom.view().messages.map((m) => m.text)).toEqual(['Are you coming on Saturday?'])
    await quiet(h.relay, h.rooms)
    const receipts = h.samRoom.view().receipts
    expect(receipts).toHaveLength(1)
    expect(receipts[0]).toMatchObject({ guardian: h.mum.pk, child: h.sam.pk })
    // A new message is hidden again until the next receipt.
    await h.samRoom.send('Yes, Mum\'s driving.')
    await quiet(h.relay, h.rooms)
    expect(h.mumRoom.view()).toMatchObject({ unrevealed: 1 })
  })

  it('tier 2: a receipt that cannot be published reveals nothing', async () => {
    const h = await household(2)
    await h.samRoom.send('secret plans')
    await quiet(h.relay, h.rooms)
    const transport = (h.mumRoom as unknown as { opts: { transport: { publish: (e: Event) => Promise<void> } } }).opts.transport
    const real = transport.publish
    transport.publish = async () => { throw new Error('offline') }
    await expect(h.mumRoom.reveal()).rejects.toThrow('offline')
    expect(h.mumRoom.view().messages).toHaveLength(0)
    transport.publish = real
  })

  it('tier 3: moving up rekeys Mum out; she can open only by announcing it', async () => {
    const h = await household(2)
    await h.samRoom.send('before')
    await quiet(h.relay, h.rooms)
    const epochBefore = h.dadRoom.view().epoch
    await moveTier(h, 3, 1, h.fam.digest)
    // Every device agrees Sam is at tier 3, and the room rekeyed without Mum.
    for (const r of h.rooms) expect(r.view().tierOf[h.sam.pk]).toBe(3)
    expect(h.dadRoom.view().epoch).toBe(epochBefore + 1)
    expect(h.mumRoom.view()).toMatchObject({ role: 'guardian', reading: false })
    expect(h.samRoom.view().banners[0]).toMatchObject({ tier: 3, reading: [], notReading: [h.mum.pk], catchingUp: false })

    await h.samRoom.send('Text me when you\'re home.')
    await quiet(h.relay, h.rooms)
    expect(h.mumRoom.view().messages.map((m) => m.text)).not.toContain('Text me when you\'re home.')
    // Sam's device escrowed this epoch for Mum.
    expect(h.mumRoom.view().openable).toEqual([h.sam.pk])

    // Watch for the moment Mum's My Signet is asked to open the escrow: the
    // notice must already be on the relay, signed by her device.
    let publishedBeforeDecrypt: Event | undefined
    const decrypt = h.mum.signer.nip44Decrypt!
    h.mum.signer.nip44Decrypt = async (peer, ct) => {
      publishedBeforeDecrypt = h.relay.log.at(-1)
      return decrypt(peer, ct)
    }
    const epoch = await h.mumRoom.openInEmergency(h.sam.pk, 'Your phone has been off since 9')
    expect(publishedBeforeDecrypt?.kind).toBe(1460)
    expect(publishedBeforeDecrypt?.pubkey).toBe(h.mumRoom.device)
    await quiet(h.relay, h.rooms)
    expect(epoch).toBe(epochBefore + 1)
    expect(h.samRoom.view().emergencies).toHaveLength(1)
    expect(h.samRoom.view().emergencies[0]!.notice).toMatchObject({ child: h.sam.pk, reason: 'Your phone has been off since 9' })
    const opened = h.mumRoom.view().messages
    expect(opened.map((m) => m.text)).toContain('Text me when you\'re home.')
    expect(opened.every((m) => m.viaEmergency)).toBe(true)
    // The authority rekeyed on the notice, so the opening does not last.
    expect(h.dadRoom.view().epoch).toBe(epoch + 1)
    await h.samRoom.send('after the opening')
    await quiet(h.relay, h.rooms)
    expect(h.mumRoom.view().messages.map((m) => m.text)).not.toContain('after the opening')
  })

  it('a guardian cannot open without an escrow, or for somebody else\'s child', async () => {
    const h = await household(1)
    await expect(h.mumRoom.openInEmergency(h.sam.pk)).rejects.toThrow()
    await expect(h.dadRoom.openInEmergency(adult('x').pk)).rejects.toThrow()
    // No notice went out for either attempt.
    expect(h.samRoom.view().emergencies).toHaveLength(0)
  })

  it('moving back down needs both signatures and does not hand over tier-3 history', async () => {
    const h = await household(3)
    expect(h.mumRoom.view().reading).toBe(false)
    await h.samRoom.send('mine alone')
    await quiet(h.relay, h.rooms)
    // Mum alone cannot move Sam down.
    const half = await tierEvents(h.sam, h.mum, 1, 1, h.fam.digest, now())
    await h.mumRoom.postStatements([half.events[1]!])
    await quiet(h.relay, h.rooms)
    expect(h.dadRoom.view().tierOf[h.sam.pk]).toBe(3)
    expect(h.mumRoom.view().reading).toBe(false)
    // With Sam's half too, Mum is let back in, from a new epoch.
    await h.samRoom.postStatements([half.events[0]!])
    await quiet(h.relay, h.rooms)
    expect(h.dadRoom.view().tierOf[h.sam.pk]).toBe(1)
    expect(h.mumRoom.view().reading).toBe(true)
    expect(h.mumRoom.view().messages.map((m) => m.text)).not.toContain('mine alone')
    await h.samRoom.send('Mum can see this')
    await quiet(h.relay, h.rooms)
    expect(h.mumRoom.view().messages.map((m) => m.text)).toEqual(['Mum can see this'])
  })

  it('removal: Sam leaves, and Mum, listed only for Sam, goes with them', async () => {
    const h = await household(1)
    await h.samRoom.remove(h.sam.pk)
    await quiet(h.relay, h.rooms)
    const s = h.dadRoom.view().state!
    expect(s.members.map((m) => m.pk)).toEqual([h.dad.pk])
    expect(h.samRoom.view().reading).toBe(false)
    expect(h.mumRoom.view().reading).toBe(false)
    await h.dadRoom.send('just me now')
    await quiet(h.relay, h.rooms)
    expect(h.samRoom.view().messages.map((m) => m.text)).not.toContain('just me now')
    // Sam still holds the keys to the epoch Sam was in. Writing into it
    // later does not put words into the room's history.
    clock.t += 3600
    await h.samRoom.send('written after I left')
    await quiet(h.relay, h.rooms)
    expect(h.dadRoom.view().messages.map((m) => m.text)).not.toContain('written after I left')
    clock.t -= 3600
  })
})
