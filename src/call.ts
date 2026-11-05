import type { Event } from 'nostr-tools/pure'
import { bytesToHex, randomBytes } from '@noble/hashes/utils'
import {
  decodePresence,
  decodeSignal,
  encodePresence,
  encodeSignal,
  presenceKeys,
  PRESENCE_KIND,
  PRESENCE_STALE_SECONDS,
  SIGNAL_WRAP_KIND,
  type Presence,
  type SignalBody,
} from './call-wire.js'
import type { CallContext, KincludeRoom } from './room.js'

/**
 * Voice and video calls in a room: a mesh, one peer connection per pair of
 * devices, which suits a family's handful of people.
 *
 * Who is on the call is each device's presence (`call-wire.ts`), encrypted
 * under the room's current epoch, so only somebody who can read the room
 * can be on its call. Three rules on top:
 *
 * 1. **Joining is said first.** A device publishes a durable "joined the
 *    call" message into the room, and waits for the relay to acknowledge
 *    it, before it sends presence, signals or media. The room's history
 *    says who was on every call.
 * 2. **Your phone only connects to people it shows.** A device negotiates
 *    only with devices whose presence it holds, for this call, whose person
 *    the room's state lists as a reader. Media goes to nobody else, so
 *    nobody can listen in without being on the list everyone sees: not a
 *    guardian at the third tier, who holds no key, and not a guardian at
 *    the first two, who shows as on the call like anybody else (design §7.2).
 * 3. **The room's key set is the call's.** When the room rekeys and
 *    somebody stops being a reader, every phone drops its connection to
 *    them; a phone that can no longer read leaves the call.
 */

export interface TrackLike {
  kind: string
  enabled: boolean
  stop(): void
}

export interface StreamLike {
  getTracks(): TrackLike[]
}

export interface PeerConnectionLike {
  addTrack(track: TrackLike, stream: StreamLike): unknown
  createOffer(): Promise<{ type: string; sdp?: string }>
  createAnswer(): Promise<{ type: string; sdp?: string }>
  setLocalDescription(d: { type: string; sdp?: string }): Promise<void>
  setRemoteDescription(d: { type: string; sdp?: string }): Promise<void>
  addIceCandidate(c: unknown): Promise<void>
  close(): void
  readonly localDescription: { type: string; sdp: string } | null
  readonly remoteDescription: unknown
  readonly connectionState: string
  onicecandidate: ((e: { candidate: { toJSON(): unknown } | null }) => void) | null
  ontrack: ((e: { track: TrackLike; streams: readonly StreamLike[] }) => void) | null
  onconnectionstatechange: (() => void) | null
}

export interface CallEnvironment {
  createPeerConnection(config: { iceServers: Array<{ urls: string | string[]; username?: string; credential?: string }> }): PeerConnectionLike
  getUserMedia(constraints: { audio: boolean; video: boolean }): Promise<StreamLike>
}

export interface CallPeer {
  device: string
  participant: string
  video: boolean
  connection: string
  stream: StreamLike | undefined
}

export interface CallOptions {
  iceServers?: Array<{ urls: string | string[]; username?: string; credential?: string }>
  /** How often a device on the call says so. */
  heartbeatMs?: number
  onChange?: () => void
}

interface Peer {
  device: string
  participant: string
  pc: PeerConnectionLike
  stream: StreamLike | undefined
  pendingIce: unknown[]
  offered: boolean
}

export class RoomCall {
  status: 'idle' | 'joining' | 'on' = 'idle'
  id: string | undefined
  video = false
  muted = false
  cameraOff = false
  local: StreamLike | undefined
  /** Why the call ended without being asked to, if it did. */
  ended: string | undefined

  private readonly room: KincludeRoom
  private readonly env: CallEnvironment
  private readonly opts: CallOptions
  private readonly present = new Map<string, Presence>()
  private readonly peerMap = new Map<string, Peer>()
  private readonly pendingOffers = new Map<string, SignalBody>()
  private readonly seenSignals = new Set<string>()
  private watchingEpoch: number | undefined
  private unwatchPresence: (() => void) | undefined
  private unwatchSignals: (() => void) | undefined
  private unwatchRoom: (() => void) | undefined
  private timer: ReturnType<typeof setInterval> | undefined

  constructor(room: KincludeRoom, env: CallEnvironment, opts: CallOptions = {}) {
    this.room = room
    this.env = env
    this.opts = opts
  }

  private ctx(): CallContext {
    return this.room.callContext()
  }

  private changed(): void {
    this.opts.onChange?.()
  }

  /** Start listening for who is on the room's call, without joining. */
  watch(): void {
    if (!this.unwatchRoom) this.unwatchRoom = this.room.subscribe(() => this.onRoomChange())
    this.watchPresence()
  }

  private watchPresence(): void {
    const ctx = this.ctx()
    if (!ctx.epoch) {
      this.unwatchPresence?.()
      this.unwatchPresence = undefined
      this.watchingEpoch = undefined
      this.present.clear()
      return
    }
    if (this.watchingEpoch === ctx.epoch.epoch) return
    this.unwatchPresence?.()
    this.present.clear()
    this.watchingEpoch = ctx.epoch.epoch
    const { tag } = presenceKeys(ctx.epoch.key)
    this.unwatchPresence = ctx.transport.subscribe([{ kinds: [PRESENCE_KIND], '#d': [tag] }], (e) => this.onPresence(e))
  }

  private onPresence(event: Event): void {
    const ctx = this.ctx()
    if (!ctx.epoch) return
    const p = decodePresence(event, { roomId: ctx.roomId, epochKey: ctx.epoch.key, now: ctx.now() })
    if (!p || p.device === ctx.device) return
    const held = this.present.get(p.device)
    if (held && held.at > p.at) return
    const arriving = !held || held.state !== 'on' || held.call !== p.call
    this.present.set(p.device, p)
    if (this.status === 'on' && this.id) {
      // Two calls started at once: the lower id is the call.
      if (p.state === 'on' && p.call < this.id && ctx.readers.includes(p.participant)) {
        void this.switchTo(p.call)
        return
      }
      // Somebody new, on this call or on another one started at the same
      // moment: say where we are at once, rather than at the next beat, so
      // the two calls merge into the lower id without waiting.
      if (arriving && p.state === 'on') void this.announce()
    }
    this.reconcile()
    this.changed()
  }

  /** Everyone the room's call shows, by presence, except this device. */
  onCall(): Array<{ participant: string; device: string; video: boolean; call: string }> {
    const ctx = this.ctx()
    const now = ctx.now()
    return [...this.present.values()]
      .filter((p) => p.state === 'on' && now - p.at <= PRESENCE_STALE_SECONDS && ctx.readers.includes(p.participant))
      .map(({ participant, device, video, call }) => ({ participant, device, video, call }))
  }

  peers(): CallPeer[] {
    return [...this.peerMap.values()].map((p) => ({
      device: p.device, participant: p.participant, connection: p.pc.connectionState, stream: p.stream,
      video: this.present.get(p.device)?.video ?? false,
    }))
  }

  /**
   * Join the room's call, or start one.
   *
   * Refused when this device cannot read the room. The join is recorded in
   * the room, and acknowledged, before anything else: before presence,
   * before signalling, before any media leaves this phone.
   */
  async join(opts: { video: boolean }): Promise<void> {
    if (this.status !== 'idle') return
    const ctx = this.ctx()
    if (!ctx.epoch) throw new Error('You cannot join this call: you cannot read this room at the moment.')
    this.watch()
    this.status = 'joining'
    this.ended = undefined
    this.changed()
    try {
      this.local = await this.env.getUserMedia({ audio: true, video: opts.video })
      this.video = opts.video
      const live = this.onCall().map((p) => p.call).sort()[0]
      this.id = live ?? this.room.view().call?.id ?? bytesToHex(randomBytes(16))
      await this.room.postCall(this.id, 'join', opts.video)
    } catch (err) {
      this.stopLocal()
      this.status = 'idle'
      this.id = undefined
      this.changed()
      throw err
    }
    this.status = 'on'
    this.unwatchSignals = ctx.transport.subscribe([{ kinds: [SIGNAL_WRAP_KIND], '#p': [ctx.device] }], (e) => void this.onSignal(e))
    await this.announce()
    this.timer = setInterval(() => {
      void this.announce()
      this.reconcile()
      this.changed()
    }, this.opts.heartbeatMs ?? 10_000)
    this.reconcile()
    this.changed()
  }

  private async switchTo(call: string): Promise<void> {
    if (this.status !== 'on' || !this.id || call >= this.id) return
    for (const p of this.peerMap.values()) p.pc.close()
    this.peerMap.clear()
    this.id = call
    await this.room.postCall(call, 'join', this.video)
    await this.announce()
    this.reconcile()
    this.changed()
  }

  private async announce(state: 'on' | 'off' = 'on'): Promise<void> {
    const ctx = this.ctx()
    if (!ctx.epoch || !this.id) return
    const p: Presence = {
      call: this.id, state, participant: ctx.me, device: ctx.device, credential: ctx.credential,
      video: this.video && !this.cameraOff, at: ctx.now(),
    }
    await ctx.transport.publish(encodePresence(p, { deviceSk: ctx.deviceSk, epochKey: ctx.epoch.key }))
  }

  /** Who this device may be connected to now. */
  private allowed(): Map<string, Presence> {
    const ctx = this.ctx()
    const now = ctx.now()
    const out = new Map<string, Presence>()
    if (this.status !== 'on' || !this.id || !ctx.epoch) return out
    for (const p of this.present.values()) {
      if (p.state !== 'on' || p.call !== this.id || now - p.at > PRESENCE_STALE_SECONDS) continue
      if (!ctx.readers.includes(p.participant)) continue
      out.set(p.device, p)
    }
    return out
  }

  private reconcile(): void {
    const allowed = this.allowed()
    for (const [device, peer] of this.peerMap) {
      if (!allowed.has(device)) {
        peer.pc.close()
        this.peerMap.delete(device)
      }
    }
    if (this.status !== 'on') return
    const me = this.ctx().device
    for (const [device, p] of allowed) {
      if (this.peerMap.has(device)) continue
      const peer = this.makePeer(device, p.participant)
      if (me < device) void this.offer(peer)
      const pending = this.pendingOffers.get(device)
      if (pending) {
        this.pendingOffers.delete(device)
        void this.answer(peer, pending)
      }
    }
  }

  private makePeer(device: string, participant: string): Peer {
    const pc = this.env.createPeerConnection({ iceServers: this.opts.iceServers ?? [] })
    const peer: Peer = { device, participant, pc, stream: undefined, pendingIce: [], offered: false }
    for (const track of this.local?.getTracks() ?? []) pc.addTrack(track, this.local!)
    pc.onicecandidate = (e) => {
      if (e.candidate) void this.send(device, { type: 'ice', candidate: JSON.stringify(e.candidate.toJSON()) })
    }
    pc.ontrack = (e) => {
      peer.stream = e.streams[0] ?? peer.stream
      this.changed()
    }
    pc.onconnectionstatechange = () => this.changed()
    this.peerMap.set(device, peer)
    return peer
  }

  private async send(device: string, body: Omit<SignalBody, 'roomId' | 'call'>): Promise<void> {
    const ctx = this.ctx()
    if (!this.id) return
    await ctx.transport.publish(encodeSignal({
      deviceSk: ctx.deviceSk, recipient: device, now: ctx.now(),
      body: { ...body, roomId: ctx.roomId, call: this.id },
    }))
  }

  private async offer(peer: Peer): Promise<void> {
    if (peer.offered) return
    peer.offered = true
    const offer = await peer.pc.createOffer()
    await peer.pc.setLocalDescription(offer)
    await this.send(peer.device, { type: 'offer', sdp: peer.pc.localDescription?.sdp ?? offer.sdp ?? '' })
  }

  private async answer(peer: Peer, body: SignalBody): Promise<void> {
    await peer.pc.setRemoteDescription({ type: 'offer', sdp: body.sdp! })
    await this.flushIce(peer)
    const answer = await peer.pc.createAnswer()
    await peer.pc.setLocalDescription(answer)
    await this.send(peer.device, { type: 'answer', sdp: peer.pc.localDescription?.sdp ?? answer.sdp ?? '' })
  }

  private async flushIce(peer: Peer): Promise<void> {
    for (const c of peer.pendingIce.splice(0)) await peer.pc.addIceCandidate(c).catch(() => {})
  }

  private async onSignal(event: Event): Promise<void> {
    const ctx = this.ctx()
    const s = decodeSignal(event, { deviceSk: ctx.deviceSk, roomId: ctx.roomId, now: ctx.now() })
    if (!s || this.seenSignals.has(s.id) || s.body.call !== this.id) return
    this.seenSignals.add(s.id)
    const peer = this.peerMap.get(s.from)
    if (!peer) {
      // An offer can beat its sender's presence here. Keep it until the
      // presence says who it is from; never answer a device this phone does
      // not show on the call.
      if (s.body.type === 'offer') this.pendingOffers.set(s.from, s.body)
      return
    }
    try {
      if (s.body.type === 'offer') await this.answer(peer, s.body)
      else if (s.body.type === 'answer') {
        await peer.pc.setRemoteDescription({ type: 'answer', sdp: s.body.sdp! })
        await this.flushIce(peer)
      } else if (s.body.type === 'ice') {
        const candidate = JSON.parse(s.body.candidate!) as unknown
        if (peer.pc.remoteDescription) await peer.pc.addIceCandidate(candidate).catch(() => {})
        else peer.pendingIce.push(candidate)
      }
    } catch (err) {
      console.error('kinclude: call signal failed', err)
    }
    this.changed()
  }

  private onRoomChange(): void {
    const ctx = this.ctx()
    if (this.status !== 'idle' && !ctx.epoch) {
      void this.leave('You can no longer read this room, so you left its call.')
      return
    }
    const before = this.watchingEpoch
    this.watchPresence()
    if (this.status === 'on' && before !== this.watchingEpoch) void this.announce()
    this.reconcile()
    this.changed()
  }

  setMuted(muted: boolean): void {
    this.muted = muted
    for (const t of this.local?.getTracks() ?? []) if (t.kind === 'audio') t.enabled = !muted
    this.changed()
  }

  setCameraOff(off: boolean): void {
    this.cameraOff = off
    for (const t of this.local?.getTracks() ?? []) if (t.kind === 'video') t.enabled = !off
    void this.announce()
    this.changed()
  }

  private stopLocal(): void {
    for (const t of this.local?.getTracks() ?? []) t.stop()
    this.local = undefined
  }

  async leave(reason?: string): Promise<void> {
    if (this.status === 'idle') return
    const call = this.id
    if (this.timer) clearInterval(this.timer)
    this.timer = undefined
    this.unwatchSignals?.()
    this.unwatchSignals = undefined
    for (const p of this.peerMap.values()) p.pc.close()
    this.peerMap.clear()
    this.pendingOffers.clear()
    this.stopLocal()
    const wasOn = this.status === 'on'
    this.status = 'idle'
    this.ended = reason
    this.changed()
    if (wasOn && call) {
      await this.announce('off').catch(() => {})
      if (this.ctx().epoch) await this.room.postCall(call, 'leave', this.video).catch(() => {})
    }
    this.id = undefined
  }

  /** Stop listening altogether: leave the call if on it. */
  async close(): Promise<void> {
    await this.leave()
    this.unwatchPresence?.()
    this.unwatchRoom?.()
    this.unwatchPresence = undefined
    this.unwatchRoom = undefined
    this.watchingEpoch = undefined
  }
}
