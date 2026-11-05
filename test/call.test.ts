import { describe, expect, it } from 'vitest'
import { RoomCall, type CallEnvironment, type PeerConnectionLike, type StreamLike, type TrackLike } from '../src/call.js'
import { decodePresence, decodeSignal, encodeSignal, PRESENCE_KIND, SIGNAL_WRAP_KIND } from '../src/call-wire.js'
import { KINDS } from '@forgesworn/fold-kit'
import type { KincludeRoom } from '../src/room.js'
import { household, moveTier, now, quiet } from './rooms.js'
import type { MemoryRelay } from '../src/memory-relay.js'

/**
 * A stand-in for WebRTC: an offer names its connection, the answer links
 * the pair, and once linked each side gets the other's local stream as its
 * remote track. Enough to check who connects to whom, and when.
 */
class FakeStream implements StreamLike {
  constructor(readonly owner: string, readonly tracks: TrackLike[]) {}
  getTracks() { return this.tracks }
}

const registry = new Map<string, FakePC>()
let nextId = 0

class FakePC implements PeerConnectionLike {
  readonly id = `pc${nextId++}`
  localDescription: { type: string; sdp: string } | null = null
  remoteDescription: unknown = null
  connectionState = 'new'
  onicecandidate: PeerConnectionLike['onicecandidate'] = null
  ontrack: PeerConnectionLike['ontrack'] = null
  onconnectionstatechange: (() => void) | null = null
  local: StreamLike | undefined
  other: FakePC | undefined
  closed = false
  constructor() { registry.set(this.id, this) }
  addTrack(_t: TrackLike, s: StreamLike) { this.local = s }
  async createOffer() { return { type: 'offer', sdp: `offer:${this.id}` } }
  async createAnswer() { return { type: 'answer', sdp: `answer:${this.id}` } }
  async setLocalDescription(d: { type: string; sdp?: string }) {
    this.localDescription = { type: d.type, sdp: d.sdp! }
    queueMicrotask(() => this.onicecandidate?.({ candidate: { toJSON: () => ({ candidate: `cand:${this.id}` }) } }))
  }
  async setRemoteDescription(d: { type: string; sdp?: string }) {
    this.remoteDescription = d
    const other = registry.get(d.sdp!.split(':')[1]!)
    if (!other) throw new Error('unknown sdp')
    this.other = other
    if (d.type === 'answer') { this.link(); other.link() }
  }
  async addIceCandidate() {}
  link() {
    if (this.connectionState === 'connected' || !this.other) return
    this.connectionState = 'connected'
    this.ontrack?.({ track: this.other.local!.getTracks()[0]!, streams: [this.other.local!] })
    this.onconnectionstatechange?.()
  }
  close() { this.closed = true; this.connectionState = 'closed' }
}

function env(owner: string): CallEnvironment & { asked: Array<{ audio: boolean; video: boolean }> } {
  const asked: Array<{ audio: boolean; video: boolean }> = []
  return {
    asked,
    createPeerConnection: () => new FakePC(),
    async getUserMedia(c) {
      asked.push(c)
      const tracks: TrackLike[] = [{ kind: 'audio', enabled: true, stop() {} }]
      if (c.video) tracks.push({ kind: 'video', enabled: true, stop() {} })
      return new FakeStream(owner, tracks)
    },
  }
}

async function settle(relay: MemoryRelay, rooms: KincludeRoom[]) {
  await quiet(relay, rooms)
  for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 2))
  await quiet(relay, rooms)
}

function remoteOwners(call: RoomCall): string[] {
  return call.peers().filter((p) => p.connection === 'connected').map((p) => (p.stream as FakeStream).owner).sort()
}

describe('calls', () => {
  it('two people in a room call each other, and each join is recorded before anything else goes out', async () => {
    const h = await household(1)
    const samCall = new RoomCall(h.samRoom, env('Sam'))
    const dadCall = new RoomCall(h.dadRoom, env('Dad'))
    const before = h.relay.log.length
    await samCall.join({ video: true })
    await settle(h.relay, h.rooms)
    // Sam's first event after deciding to join is the durable record.
    const samsFirst = h.relay.log.slice(before).find((e) => e.pubkey === h.samRoom.device || e.kind === SIGNAL_WRAP_KIND || e.kind === PRESENCE_KIND)
    expect(samsFirst?.kind).toBe(KINDS.CHAT)
    expect(h.dadRoom.view().call).toMatchObject({ on: [{ participant: h.sam.pk, video: true }] })
    expect(h.dadRoom.view().messages.map((m) => m.text)).toEqual([])
    expect(h.dadRoom.view().callLog.map((c) => c.state)).toEqual(['join'])

    dadCall.watch()
    await dadCall.join({ video: true })
    await settle(h.relay, h.rooms)
    expect(dadCall.id).toBe(samCall.id)
    expect(remoteOwners(samCall)).toEqual(['Dad'])
    expect(remoteOwners(dadCall)).toEqual(['Sam'])

    await dadCall.leave()
    await settle(h.relay, h.rooms)
    expect(h.samRoom.view().callLog.map((c) => c.state)).toEqual(['join', 'join', 'leave'])
    await samCall.close()
  })

  it('a guardian at Nearly grown cannot join, and no phone connects to them', async () => {
    const h = await household(3)
    const samCall = new RoomCall(h.samRoom, env('Sam'))
    const mumCall = new RoomCall(h.mumRoom, env('Mum'))
    await samCall.join({ video: false })
    await settle(h.relay, h.rooms)
    const mumEnv = (mumCall as unknown as { env: ReturnType<typeof env> }).env
    await expect(mumCall.join({ video: false })).rejects.toThrow('cannot read this room')
    // Refused before the camera or microphone was even asked for.
    expect(mumEnv.asked).toEqual([])
    expect(samCall.onCall()).toEqual([])
    await samCall.close()
  })

  it('a guardian at First messages joins in sight of everyone, and steps off when the child moves to Nearly grown', async () => {
    const h = await household(1)
    const samCall = new RoomCall(h.samRoom, env('Sam'))
    const mumCall = new RoomCall(h.mumRoom, env('Mum'))
    await samCall.join({ video: true })
    await settle(h.relay, h.rooms)
    await mumCall.join({ video: false })
    await settle(h.relay, h.rooms)
    expect(samCall.onCall().map((p) => p.participant)).toEqual([h.mum.pk])
    expect(remoteOwners(samCall)).toEqual(['Mum'])
    expect(h.samRoom.view().call?.on.map((o) => o.participant).sort()).toEqual([h.mum.pk, h.sam.pk].sort())

    await moveTier(h, 3, 1, h.fam.digest)
    await settle(h.relay, h.rooms)
    expect(h.mumRoom.view().reading).toBe(false)
    expect(mumCall.status).toBe('idle')
    expect(mumCall.ended).toContain('can no longer read this room')
    expect(samCall.peers()).toEqual([])
    await samCall.close()
  })

  it('two people starting at once end up on one call', async () => {
    const h = await household(1)
    const samCall = new RoomCall(h.samRoom, env('Sam'))
    const dadCall = new RoomCall(h.dadRoom, env('Dad'))
    await Promise.all([samCall.join({ video: false }), dadCall.join({ video: false })])
    await settle(h.relay, h.rooms)
    await settle(h.relay, h.rooms)
    expect(samCall.id).toBe(dadCall.id)
    expect(remoteOwners(samCall)).toEqual(['Dad'])
    expect(remoteOwners(dadCall)).toEqual(['Sam'])
    await samCall.close()
    await dadCall.close()
  })
})

describe('call wire', () => {
  it('a signal opens only for its recipient, in its room, while fresh', async () => {
    const h = await household(1)
    const sam = h.samRoom.callContext()
    const dad = h.dadRoom.callContext()
    const call = 'ab'.repeat(16)
    const wrap = encodeSignal({ deviceSk: sam.deviceSk, recipient: dad.device, now: now(), body: { type: 'offer', roomId: sam.roomId, call, sdp: 'v=0' } })
    expect(wrap.kind).toBe(21059)
    expect(wrap.tags.map((t) => t[0])).toEqual(['p', 'expiration'])
    expect(decodeSignal(wrap, { deviceSk: dad.deviceSk, roomId: dad.roomId, now: now() })).toMatchObject({ from: sam.device, body: { type: 'offer', call, sdp: 'v=0' } })
    expect(decodeSignal(wrap, { deviceSk: sam.deviceSk, roomId: sam.roomId, now: now() })).toBeNull()
    expect(decodeSignal(wrap, { deviceSk: dad.deviceSk, roomId: 'cd'.repeat(32), now: now() })).toBeNull()
    expect(decodeSignal(wrap, { deviceSk: dad.deviceSk, roomId: dad.roomId, now: now() + 21 })).toBeNull()
  })

  it('presence from a device whose credential is for another participant is refused', async () => {
    const h = await household(1)
    const sam = h.samRoom.callContext()
    const dad = h.dadRoom.callContext()
    const { encodePresence } = await import('../src/call-wire.js')
    const forged = encodePresence({ call: 'ab'.repeat(16), state: 'on', participant: h.dad.pk, device: sam.device, credential: sam.credential, video: false, at: now() },
      { deviceSk: sam.deviceSk, epochKey: sam.epoch!.key })
    expect(decodePresence(forged, { roomId: sam.roomId, epochKey: dad.epoch!.key, now: now() })).toBeNull()
  })
})
