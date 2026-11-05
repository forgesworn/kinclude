import { expect } from 'vitest'
import { generateSecretKey, type Event } from 'nostr-tools/pure'
import { hexToBytes } from '@noble/hashes/utils'
import { deriveRoom, requestPersistentRoomAdmission, roomInvitation } from '@forgesworn/fold-kit'
import { MemoryRelay } from '../src/memory-relay.js'
import { KincludeRoom } from '../src/room.js'
import { unwrapInvitation, type InvitationEnvelope } from '../src/invite.js'
import { approvalUnsigned, APPROVAL_TTL_SECONDS, type Tier } from '../src/statements.js'
import { adult, kid, linkEvents, tierEvents, type Person } from './fixtures.js'

// Helpers shared by the room and call scenario tests.

export const clock = { t: 1_800_000_000 }
export const now = () => clock.t

export async function quiet(relay: MemoryRelay, rooms: KincludeRoom[], rounds = 40): Promise<void> {
  let last = -1
  let still = 0
  for (let i = 0; i < rounds * 10 && still < 4; i++) {
    await new Promise((r) => setTimeout(r, 2))
    await Promise.all(rooms.map((r) => r.idle()))
    if (relay.log.length === last) still++
    else { still = 0; last = relay.log.length }
  }
}

export async function inbox(relay: MemoryRelay, who: Person): Promise<InvitationEnvelope[]> {
  const out: InvitationEnvelope[] = []
  for (const e of relay.stored.filter((e) => e.kind === 1059 && e.tags.some((t) => t[0] === 'p' && t[1] === who.pk))) {
    const opened = await unwrapInvitation(e, who.signer)
    if (opened) out.push(opened.envelope)
  }
  return out
}

export async function join(relay: MemoryRelay, who: Person, envelope: InvitationEnvelope, family: Event[] = []): Promise<KincludeRoom> {
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
export async function ownPair(envelope: InvitationEnvelope, child: Person, guardian: Person): Promise<Event[]> {
  const a = { room: envelope.room, invitee: child.pk, child: child.pk, role: 'child' as const, guardians: envelope.guardians, expires: now() + APPROVAL_TTL_SECONDS }
  return [await child.signer.signEvent(approvalUnsigned(a, now())), await guardian.signer.signEvent(approvalUnsigned(a, now()))]
}

export async function familyOf(child: Person, guardians: Person[], tier: Tier) {
  const events: Event[] = []
  for (const g of guardians) events.push(...(await linkEvents(g, child, now())))
  const t = await tierEvents(child, guardians[0]!, tier, 0, undefined, now())
  events.push(...t.events)
  return { events, digest: t.digest }
}

export async function household(tier: Tier) {
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

export async function moveTier(h: Awaited<ReturnType<typeof household>>, tier: Tier, seq: number, prev: string) {
  const t = await tierEvents(h.sam, h.mum, tier, seq, prev, now())
  await h.samRoom.postStatements([t.events[0]!])
  await h.mumRoom.postStatements([t.events[1]!])
  await quiet(h.relay, h.rooms)
  return t.digest
}

