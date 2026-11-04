import { describe, expect, it } from 'vitest'
import { MemoryRelay } from '../src/memory-relay.js'
import { Household, MemoryStore } from '../src/household.js'
import { wrapInvitation } from '../src/invite.js'
import type { KincludeRoom } from '../src/room.js'
import { adult, kid, type Person } from './fixtures.js'

const clock = { t: 1_800_000_000 }
const now = () => clock.t

async function quiet(relay: MemoryRelay, households: Household[]): Promise<void> {
  let last = -1
  let still = 0
  for (let i = 0; i < 400 && still < 5; i++) {
    await new Promise((r) => setTimeout(r, 3))
    for (const h of households) for (const r of h.rooms.values()) await r.idle()
    if (relay.log.length === last) still++
    else { still = 0; last = relay.log.length }
  }
}

async function open(relay: MemoryRelay, p: Person, kind: 'adult' | 'child'): Promise<Household> {
  const h = await Household.open({ transport: relay.connect(), identity: p.signer, store: new MemoryStore(), name: p.name, kind, now })
  await h.start()
  return h
}

function roomOf(h: Household, roomId: string): KincludeRoom {
  const r = h.rooms.get(roomId)
  if (!r) throw new Error(`${h.data.name} is not in the room`)
  return r
}

describe('a household', () => {
  it('pairs in person, brings the child in through their guardians, and agrees a tier together', async () => {
    const relay = new MemoryRelay()
    const mum = await open(relay, adult('Mum'), 'adult')
    const dad = await open(relay, adult('Dad'), 'adult')
    const sam = await open(relay, kid('Sam'), 'child')
    const nanP = adult('Nan')
    const nan = await open(relay, nanP, 'adult')

    // Mum pairs with Sam's phone, starting at Growing up.
    const offer = mum.offerPairing({ childName: 'Sam', tier: 2 })
    await sam.acceptPairing(offer.code)
    expect(await offer.paired).toBe(sam.me)
    expect(mum.tierOf(sam.me)).toBe(2)
    expect(sam.tierOf(sam.me)).toBe(2)
    // Dad pairs too; Sam's phone sends the chain it already has.
    const offer2 = dad.offerPairing({ tier: 1 })
    await sam.acceptPairing(offer2.code)
    await offer2.paired
    expect(dad.tierOf(sam.me)).toBe(2)
    expect(sam.family.guardiansOf(sam.me)).toEqual([dad.me, mum.me].sort())
    // Sam's phone told both guardians about each other's link.
    await quiet(relay, [mum, dad, sam])
    expect(mum.family.guardiansOf(sam.me)).toEqual([dad.me, mum.me].sort())

    // Mum and Dad know each other; Nan knows Mum.
    mum.setContacts([{ pk: dad.me, tier: 'kin' }, { pk: nan.me, tier: 'kin' }])
    dad.setContacts([{ pk: mum.me, tier: 'kin' }])
    nan.setContacts([{ pk: mum.me, tier: 'kin' }])

    const room = await mum.createRoom('Home')
    await mum.addChild(room, sam.me)
    await quiet(relay, [mum, dad, sam, nan])
    // Both guardians were sent Sam's invitation.
    const forMum = mum.data.invites.find((i) => i.forChild === sam.me)
    const forDad = dad.data.invites.find((i) => i.forChild === sam.me)
    expect(forMum && forDad).toBeTruthy()
    await mum.acceptInvite(forMum!.id)
    await dad.acceptInvite(forDad!.id)
    await quiet(relay, [mum, dad, sam, nan])
    const samInvite = sam.data.invites[0]
    expect(samInvite?.envelope.name).toBe('Home')
    await sam.acceptInvite(samInvite!.id)
    await quiet(relay, [mum, dad, sam, nan])

    const samRoom = roomOf(sam, room.roomId)
    const dadRoom = roomOf(dad, room.roomId)
    expect(samRoom.view()).toMatchObject({ role: 'child', reading: true })
    expect(dadRoom.view()).toMatchObject({ role: 'guardian', reading: true, gatedFor: [sam.me] })
    expect(samRoom.view().banners[0]).toMatchObject({ tier: 2, reading: [dad.me], inOwnRight: [mum.me] })

    // Nan, with Sam and a guardian agreeing.
    await room.propose({ invitee: nan.me, name: 'Nan', role: 'adult' })
    await quiet(relay, [mum, dad, sam, nan])
    expect(nan.data.invites).toHaveLength(0)
    await samRoom.approve(nan.me)
    await quiet(relay, [mum, dad, sam, nan])
    expect(nan.data.invites).toHaveLength(1)
    await nan.acceptInvite(nan.data.invites[0]!.id)
    await quiet(relay, [mum, dad, sam, nan])
    expect(roomOf(nan, room.roomId).view()).toMatchObject({ role: 'adult', reading: true })

    // Sam asks to move to Nearly grown; Dad agrees on his phone.
    await sam.proposeTier(sam.me, 3)
    await quiet(relay, [mum, dad, sam, nan])
    const waiting = dad.pendingTiers()
    expect(waiting).toEqual([expect.objectContaining({ child: sam.me, tier: 3, signedBy: sam.me })])
    await dad.agreeTier(waiting[0]!)
    await quiet(relay, [mum, dad, sam, nan])
    for (const h of [mum, dad, sam, nan]) expect(roomOf(h, room.roomId).view().tierOf[sam.me]).toBe(3)
    expect(dadRoom.view().reading).toBe(false)
    expect(mum.tierOf(sam.me)).toBe(3)
    // Mum is in the room in her own right, and the banner says so.
    expect(samRoom.view().banners[0]).toMatchObject({ tier: 3, reading: [], notReading: [dad.me], inOwnRight: [mum.me] })

    for (const h of [mum, dad, sam, nan]) h.close()
  })

  it('an invitation from somebody who is not kin or kith is dropped without a word', async () => {
    const relay = new MemoryRelay()
    const strangerP = adult('Stranger')
    const nan = await open(relay, adult('Nan'), 'adult')
    await relay.connect().publish(await wrapInvitation({
      sender: strangerP.signer, recipient: nan.me, now: now(),
      envelope: { v: 1, room: 'a'.repeat(64), name: 'Free stuff', bearer: 'b'.repeat(64), authority: strangerP.pk, invitee: nan.me, role: 'adult', guardians: [] },
    }))
    await quiet(relay, [nan])
    expect(nan.data.invites).toHaveLength(0)
    nan.close()
  })

  it('a child phone takes forwarded invitations only from its own guardians', async () => {
    const relay = new MemoryRelay()
    const mum = await open(relay, adult('Mum'), 'adult')
    const sam = await open(relay, kid('Sam'), 'child')
    const offer = mum.offerPairing({ tier: 1 })
    await sam.acceptPairing(offer.code)
    await offer.paired
    expect(sam.data.kind).toBe('child')
    // A child has no identity inbox: an invitation sealed to Sam's identity
    // is never even looked at.
    const strangerP = adult('Stranger')
    await relay.connect().publish(await wrapInvitation({
      sender: strangerP.signer, recipient: sam.me, now: now(),
      envelope: { v: 1, room: 'a'.repeat(64), name: 'Hi', bearer: 'b'.repeat(64), authority: strangerP.pk, invitee: sam.me, role: 'adult', guardians: [] },
    }))
    await quiet(relay, [mum, sam])
    expect(sam.data.invites).toHaveLength(0)
    mum.close()
    sam.close()
  })
})
