import { getPublicKey, type Event } from 'nostr-tools/pure'
import { nip44 } from 'nostr-tools'
import { bytesToHex, hexToBytes, randomBytes } from '@noble/hashes/utils'
import {
  createDeviceCredential,
  createRoomInvitation,
  decodeEpochGrant,
  decodeEpochRequest,
  decodeRekeyEvent,
  deriveChannel,
  deriveEpoch,
  deriveRoom,
  encodeEpochGrant,
  encodeEpochRequest,
  encodeInvitationRetirement,
  encodePersistentInvitation,
  encodeRekeyEvent,
  generateEpochSecret,
  generateRoomSecret,
  KINDS,
  peekRekeyEvent,
  roomInvitation,
  type DeviceCredential,
  type RelayTransport,
  type RekeyRecipient,
} from '@forgesworn/fold-kit'
import type { Filter } from 'nostr-tools/filter'
import { ApprovalBook, FamilyGraph, TierLedger } from './family.js'
import { roomDeviceKey, wrapInvitation, type InvitationEnvelope } from './invite.js'
import { decodeMessage, encodeMessage, newMessageId, MAX_CLOCK_SKEW_SECONDS, type KincludePayload, type RoomMessage, type Stream } from './messages.js'
import { banners, checkNewcomer, checkTransition, computeReaders, mayObserve, mayRemove, requiredChildren, type ChildBanner } from './policy.js'
import type { KincludeSigner } from './signer.js'
import { member, readState, signState, type RoomState, type StateMember } from './state.js'
import {
  approvalUnsigned,
  emergencyUnsigned,
  readApproval,
  readEmergency,
  APPROVAL_TTL_SECONDS,
  type SignedEmergency,
  type Tier,
} from './statements.js'

/** The epoch-0 channel every admitted device can read: where a newcomer
 *  introduces itself, the authority posts the room's state, a tier-3 child
 *  escrows each epoch's key and a guardian announces an emergency opening.
 *  Everything else rides in the current epoch. */
export const LOBBY_CHANNEL = 'kinclude-lobby'

/** A room credential lasts a year; a device renews it by joining again. */
export const CREDENTIAL_SECONDS = 365 * 24 * 60 * 60

export interface RoomSecrets {
  /** The room secret: epoch 0. */
  secret: string
  /** The room's authority: the root inviter of its group invitations. */
  authority: string
  /** Held only by the device that created the room. */
  authoritySk?: string
  /** Every epoch this device holds, by number. */
  epochs: Record<string, string>
  /** Invitations the authority issued, by invitee. */
  issued?: Record<string, string>
}

export interface ShownMessage {
  id: string
  from: string
  name?: string
  text: string
  sentAt: number
  epoch: number
  /** Read only because a guardian opened the room in an emergency. */
  viaEmergency?: true
  retracted?: true
}

export interface Proposal {
  invitee: string
  name?: string
  role: 'adult' | 'child'
  guardians: string[]
  by: string
  at: number
  expires: number
  /** Per required child: who has said yes so far. */
  approvals: Array<{ child: string; byChild: boolean; byGuardian: string | undefined }>
  /** True once the room's authority has sent the invitation. */
  invited: boolean
}

export interface Receipt {
  guardian: string
  child: string
  upTo: string
  at: number
}

export interface RoomView {
  roomId: string
  name: string
  state: RoomState | undefined
  /** Why the newest state seen was not accepted, if it was not. */
  stateProblem?: string
  creator: string | undefined
  me: string
  role: StateMember['role'] | undefined
  epoch: number
  /** True when this device can read the room's current epoch. */
  reading: boolean
  /** Removed from the room's key set. */
  removed: boolean
  banners: ChildBanner[]
  tierOf: Record<string, Tier | undefined>
  messages: ShownMessage[]
  /** Messages a guardian at tier 2 has not yet published a receipt for. */
  unrevealed: number
  /** Children whose tier makes this viewer publish a receipt before reading. */
  gatedFor: string[]
  proposals: Proposal[]
  receipts: Receipt[]
  emergencies: Array<SignedEmergency & { device: string }>
  /** Children in the room this viewer could open in an emergency now. */
  openable: string[]
}

export interface RoomOptions {
  transport: RelayTransport
  identity: KincludeSigner
  name?: string
  secrets: RoomSecrets
  phoneSecret: Uint8Array
  credential?: DeviceCredential
  /** This person's own family statements (links, tier chain), introduced to
   *  the room when the device joins. */
  family?: Event[]
  now?: () => number
  onChange?: () => void
  /** How often a device that should be reading asks for the epoch again. */
  requestEveryMs?: number
  /** For the authority: how long an invitation's approvals stand. */
  approvalSeconds?: number
  /** Called with family statements as they arrive, so the person's own
   *  record of their family (and a tier change waiting on them) stays
   *  current across rooms. */
  onStatements?: (events: Event[]) => void
}

type Settled = () => void

/**
 * One device's view of, and part in, one Kinclude room.
 *
 * It is a KithMoot group underneath: fold-kit's group invitation, epochs and
 * rekeys, and KithMoot's chat envelope. What it adds is the family: who may
 * join, who may read, and what a guardian must publish before reading.
 *
 * Every device runs the rules (`policy.ts`) on everything it is shown. The
 * device holding the room's authority key also acts on them: it admits
 * approved newcomers, rekeys whenever who may read changes, and answers
 * epoch requests only from participants the room's state says may read.
 */
export class KincludeRoom {
  readonly roomId: string
  readonly roomKey: Uint8Array
  readonly authority: string
  readonly device: string
  readonly lobby: Stream
  readonly family = new FamilyGraph()
  readonly tiers = new TierLedger(this.family)
  readonly book = new ApprovalBook(this.family)

  private readonly opts: RoomOptions
  private readonly deviceSk: Uint8Array
  private readonly secret: Uint8Array
  private readonly authoritySk: Uint8Array | undefined
  private credential: DeviceCredential | undefined
  private readonly epochs = new Map<number, Uint8Array>()
  private current = 0
  private readonly issued = new Map<string, string>()

  private readonly seen = new Set<string>()
  private readonly rekeys = new Map<number, Event[]>()
  private readonly states: Event[] = []
  private accepted: { state: RoomState; event: Event }[] = []
  private stateProblem: string | undefined
  private creator: string | undefined
  private readonly messages = new Map<string, { msg: RoomMessage; epoch: number; viaEmergency?: true }>()
  private readonly proposals = new Map<string, { p: Extract<KincludePayload, { t: 'propose' }>; by: string; at: number }>()
  private readonly receipts: Array<Receipt & { epoch: number; participant: string; sentAt: number }> = []
  private readonly escrows = new Map<string, { device: string; copies: Array<{ guardian: string; ct: string }> }>()
  private readonly emergencies: Array<SignedEmergency & { device: string }> = []
  private readonly hellos = new Map<string, { device: string; at: number; name?: string }>()
  private readonly removals: Array<{ actor: string; target: string }> = []
  private readonly devices = new Map<string, Map<string, DeviceCredential | undefined>>()
  private readonly requests = new Set<string>()
  private lastRequest = 0
  private readonly escrowed = new Set<number>()
  private readonly rekeyAfterEmergency = new Set<string>()
  private readonly opened = new Map<number, Uint8Array>()
  private readonly revealed = new Set<string>()
  private removedMe = false
  private readonly subs: Array<() => void> = []
  private readonly mainSubs = new Map<number, () => void>()
  private work: Promise<void> = Promise.resolve()
  private settleQueued = false
  private lobbyReady!: Settled
  readonly ready: Promise<void>
  private closed = false

  constructor(opts: RoomOptions) {
    this.opts = opts
    this.secret = hexToBytes(opts.secrets.secret)
    const { roomId, roomKey } = deriveRoom(this.secret)
    this.roomId = roomId
    this.roomKey = roomKey
    this.authority = opts.secrets.authority
    this.authoritySk = opts.secrets.authoritySk ? hexToBytes(opts.secrets.authoritySk) : undefined
    if (this.authoritySk && getPublicKey(this.authoritySk) !== this.authority) throw new Error('authority key does not match')
    this.deviceSk = roomDeviceKey(opts.phoneSecret, roomId)
    this.device = getPublicKey(this.deviceSk)
    this.credential = opts.credential
    this.lobby = deriveChannel(roomId, roomKey, LOBBY_CHANNEL)
    this.epochs.set(0, this.secret)
    for (const [n, s] of Object.entries(opts.secrets.epochs)) {
      const epoch = Number(n)
      if (Number.isSafeInteger(epoch) && epoch > 0) this.epochs.set(epoch, hexToBytes(s))
    }
    this.current = Math.max(...this.epochs.keys())
    for (const [invitee, bearer] of Object.entries(opts.secrets.issued ?? {})) this.issued.set(invitee, bearer)
    this.family.addAll(opts.family ?? [])
    this.tiers.addAll(opts.family ?? [])
    this.ready = new Promise((resolve) => { this.lobbyReady = resolve })
  }

  /** Make a new room: this device holds its authority, and its creator is
   *  its only member until somebody is approved in. */
  static async create(opts: Omit<RoomOptions, 'secrets'> & { roomName: string }): Promise<KincludeRoom> {
    const { inviterSk } = createRoomInvitation(true)
    const secret = generateRoomSecret()
    const room = new KincludeRoom({
      ...opts,
      secrets: { secret: bytesToHex(secret), authority: getPublicKey(inviterSk), authoritySk: bytesToHex(inviterSk), epochs: {} },
    })
    await room.start()
    await room.found(opts.roomName)
    return room
  }

  private now(): number {
    return (this.opts.now ?? (() => Math.floor(Date.now() / 1000)))()
  }

  get me(): string {
    return this.opts.identity.pubkey
  }

  /** What a host persists to come back to this room. */
  secrets(): RoomSecrets {
    const epochs: Record<string, string> = {}
    for (const [n, s] of this.epochs) if (n > 0) epochs[String(n)] = bytesToHex(s)
    return {
      secret: bytesToHex(this.secret),
      authority: this.authority,
      ...(this.authoritySk ? { authoritySk: bytesToHex(this.authoritySk) } : {}),
      epochs,
      ...(this.issued.size ? { issued: Object.fromEntries(this.issued) } : {}),
    }
  }

  async start(): Promise<void> {
    if (!this.credential) {
      this.credential = await createDeviceCredential({
        identity: this.opts.identity,
        devicePubkey: this.device,
        roomId: this.roomId,
        expiresAt: this.now() + CREDENTIAL_SECONDS,
        now: () => this.now(),
      })
    }
    this.noteDevice(this.me, this.device, this.credential)
    const t = this.opts.transport
    this.subs.push(t.subscribe([{ kinds: [KINDS.CHAT], '#d': [this.lobby.id] }], (e) => this.onEvent(e), () => this.lobbyReady()))
    this.subs.push(t.subscribe([{ kinds: [KINDS.ROOM_REKEY], '#d': [this.roomId] }], (e) => this.onEvent(e)))
    this.subs.push(t.subscribe([{ kinds: [KINDS.EPOCH_GRANT], '#d': [this.roomId], '#p': [this.device] }], (e) => this.onEvent(e)))
    if (this.authoritySk) {
      this.subs.push(t.subscribe([{ kinds: [KINDS.EPOCH_REQUEST], '#d': [this.roomId], '#p': [this.authority] }], (e) => this.onEvent(e)))
    }
    for (const n of this.epochs.keys()) if (n > 0) this.watchEpoch(n)
    await this.ready
    const head = this.head()
    if (!this.authoritySk && (!head || !member(head.state, this.me))) await this.hello()
    this.queueSettle()
  }

  /** Introduce this device in the lobby, with this person's family
   *  statements and any extra (a child's own approval pair). */
  async hello(extra: Event[] = []): Promise<void> {
    const events = [...(this.opts.family ?? []), ...extra]
    this.family.addAll(events)
    this.tiers.addAll(events)
    this.book.addAll(events)
    await this.post({ t: 'hello', events }, 'lobby', `${this.opts.name ?? 'Someone'} arrived.`)
  }

  close(): void {
    this.closed = true
    for (const u of this.subs) u()
    for (const u of this.mainSubs.values()) u()
    this.mainSubs.clear()
  }

  private async found(roomName: string): Promise<void> {
    if (!this.authoritySk || !this.credential) throw new Error('only the authority founds a room')
    if (this.accepted.length > 0) return
    const next = generateEpochSecret()
    await this.opts.transport.publish(encodeRekeyEvent({
      roomId: this.roomId,
      authoritySk: this.authoritySk,
      current: deriveEpoch({ epoch: 0, secret: this.secret }),
      next: { epoch: 1, secret: next },
      recipients: [{ device: this.device, credential: this.credential }],
      removed: [],
      members: [this.me],
      commit: true,
      now: this.now(),
    }))
    this.adoptEpoch(1, next)
    const genesis: RoomState = {
      v: 1, room: this.roomId, seq: 0, prev: null, name: roomName, epoch: 1,
      members: [{ pk: this.me, role: 'adult', by: this.me, at: this.now(), approvals: [], links: [], ...(this.opts.name ? { name: this.opts.name } : {}) }],
      readers: [this.me], removed: [],
    }
    await this.publishState(genesis)
  }

  // ------------------------------------------------------------ intake

  private onEvent(event: Event): void {
    if (this.closed || this.seen.has(event.id)) return
    this.seen.add(event.id)
    switch (event.kind) {
      case KINDS.CHAT:
        this.onChat(event)
        break
      case KINDS.ROOM_REKEY: {
        const epoch = peekRekeyEvent(event, { roomId: this.roomId, authority: this.authority })
        if (epoch === null) return
        const list = this.rekeys.get(epoch) ?? []
        list.push(event)
        this.rekeys.set(epoch, list)
        this.advance()
        break
      }
      case KINDS.EPOCH_GRANT:
        this.onGrant(event)
        break
      case KINDS.EPOCH_REQUEST:
        this.onRequest(event)
        break
    }
    this.queueSettle()
  }

  private readonly raw = new Map<string, Event[]>()

  private onChat(event: Event): void {
    const d = event.tags.find((t) => t[0] === 'd')?.[1]
    if (d === this.lobby.id) {
      const msg = decodeMessage(event, { roomId: this.roomId, stream: this.lobby, now: this.now() })
      if (msg) this.onMessage(msg, 0, 'lobby')
      return
    }
    for (const [n, s] of [...this.epochs, ...this.opened]) {
      const keys = deriveEpoch({ epoch: n, secret: s })
      if (keys.id !== d) continue
      const msg = decodeMessage(event, { roomId: this.roomId, stream: { id: keys.id, key: keys.key }, now: this.now() })
      if (msg) this.onMessage(msg, n, 'main', this.opened.has(n) && !this.epochs.has(n))
      return
    }
    const list = this.raw.get(d ?? '') ?? []
    list.push(event)
    this.raw.set(d ?? '', list)
  }

  private onMessage(msg: RoomMessage, epoch: number, where: 'lobby' | 'main', viaEmergency = false): void {
    this.noteDevice(msg.participant, msg.device, msg.credential)
    const k = msg.kinclude
    if (!k) {
      if (where === 'main') this.messages.set(msg.id, { msg, epoch, ...(viaEmergency ? { viaEmergency: true as const } : {}) })
      return
    }
    switch (k.t) {
      case 'statements':
      case 'hello':
        this.family.addAll(k.events)
        this.tiers.addAll(k.events)
        this.book.addAll(k.events)
        if (k.events.length) this.opts.onStatements?.(k.events)
        if (k.t === 'hello' && where === 'lobby') {
          const held = this.hellos.get(msg.participant)
          if (!held || held.at <= msg.sentAt) this.hellos.set(msg.participant, { device: msg.device, at: msg.sentAt, ...(msg.name ? { name: msg.name } : {}) })
        }
        break
      case 'propose':
        if (where === 'main') {
          const held = this.proposals.get(k.invitee)
          if (!held || held.at <= msg.sentAt) this.proposals.set(k.invitee, { p: k, by: msg.participant, at: msg.sentAt })
        }
        break
      case 'state':
        if (where === 'lobby') this.states.push(k.event)
        this.acceptStates()
        break
      case 'read':
        if (where === 'main') this.receipts.push({ guardian: msg.participant, child: k.child, upTo: k.upTo, at: k.at, epoch, participant: msg.participant, sentAt: msg.sentAt })
        break
      case 'escrow':
        if (where === 'lobby') this.escrows.set(`${msg.participant}:${k.epoch}`, { device: msg.device, copies: k.copies })
        break
      case 'emergency': {
        if (where !== 'lobby') break
        const e = readEmergency(k.event)
        if (!e || e.signer !== msg.participant || e.notice.room !== this.roomId) break
        if (!this.family.isGuardian(e.signer, e.notice.child)) break
        if (this.emergencies.some((x) => x.event.id === e.event.id)) break
        this.emergencies.push({ ...e, device: msg.device })
        // Only an opening of the epoch the room is at calls for a rekey; one
        // replayed from before the last rekey has been answered already.
        if (e.notice.epoch === this.head()?.state.epoch) this.rekeyAfterEmergency.add(e.event.id)
        break
      }
      case 'remove':
        if (where === 'main') this.removals.push({ actor: msg.participant, target: k.target })
        break
    }
    if (where === 'main' && msg.retracts) {
      const target = this.messages.get(msg.retracts)
      if (target && target.msg.participant === msg.participant) target.msg = { ...target.msg, text: 'Message withdrawn' }
    }
  }

  private noteDevice(participant: string, device: string, credential?: DeviceCredential): void {
    let map = this.devices.get(participant)
    if (!map) {
      map = new Map()
      this.devices.set(participant, map)
    }
    if (credential || !map.has(device)) map.set(device, credential ?? map.get(device))
  }

  // ------------------------------------------------------------ state

  private acceptStates(): void {
    let moved = true
    while (moved) {
      moved = false
      const head = this.accepted.at(-1)
      for (const ev of this.states) {
        const signed = readState(ev, { room: this.roomId, authority: this.authority })
        if (!signed) continue
        const s = signed.state
        if (!head) {
          if (s.seq !== 0) continue
          const creator = s.members[0]?.pk
          if (!creator) continue
          const check = checkTransition(null, s, ev.created_at, creator)
          if (!check.ok) { this.stateProblem = check.reason; continue }
          this.creator = creator
        } else {
          if (s.seq !== head.state.seq + 1 || s.prev !== head.event.id) continue
          const check = checkTransition(head.state, s, ev.created_at, this.creator!)
          if (!check.ok) { this.stateProblem = check.reason; continue }
        }
        this.accepted.push({ state: s, event: ev })
        this.stateProblem = undefined
        for (const m of s.members) {
          this.family.addAll(m.links)
          this.book.addAll(m.approvals)
        }
        moved = true
        break
      }
    }
  }

  private head(): { state: RoomState; event: Event } | undefined {
    return this.accepted.at(-1)
  }

  // ------------------------------------------------------------ epochs

  private adoptEpoch(n: number, secret: Uint8Array): void {
    if (this.epochs.has(n)) return
    this.epochs.set(n, secret)
    if (n > this.current) this.current = n
    this.watchEpoch(n)
    this.advance()
  }

  private watchEpoch(n: number): void {
    if (this.mainSubs.has(n)) return
    const secret = this.epochs.get(n) ?? this.opened.get(n)
    if (!secret) return
    const { id } = deriveEpoch({ epoch: n, secret })
    // Anything that arrived before the key did.
    for (const e of this.raw.get(id) ?? []) {
      this.seen.delete(e.id)
      this.onEvent(e)
    }
    this.raw.delete(id)
    this.mainSubs.set(n, this.opts.transport.subscribe([{ kinds: [KINDS.CHAT], '#d': [id] }], (e) => this.onEvent(e)))
  }

  /** Follow rekeys forward from where this device is, as far as it holds a
   *  copy. */
  private advance(): void {
    for (;;) {
      const next = this.rekeys.get(this.current + 1)
      if (!next) return
      const keys = deriveEpoch({ epoch: this.current, secret: this.epochs.get(this.current)! })
      let moved = false
      for (const e of next) {
        const notice = decodeRekeyEvent(e, { roomId: this.roomId, authority: this.authority, current: keys, deviceSk: this.deviceSk })
        if (notice?.removed.includes(this.me)) this.removedMe = true
        if (notice?.secret) {
          this.adoptEpoch(notice.epoch, notice.secret)
          moved = true
          break
        }
      }
      if (!moved) return
    }
  }

  private onGrant(event: Event): void {
    for (const request of this.requests) {
      const grant = decodeEpochGrant(event, { roomId: this.roomId, authority: this.authority, deviceSk: this.deviceSk, request, now: this.now() })
      if (!grant) continue
      this.requests.delete(request)
      if (grant.refused) {
        if (grant.refused === 'removed') this.removedMe = true
        return
      }
      if ('secret' in grant.epoch) {
        this.removedMe = false
        this.adoptEpoch(grant.epoch.epoch, grant.epoch.secret)
      }
      return
    }
  }

  private async requestEpoch(): Promise<void> {
    if (!this.credential) return
    const every = this.opts.requestEveryMs ?? 5_000
    if (Date.now() - this.lastRequest < every) return
    this.lastRequest = Date.now()
    const event = encodeEpochRequest({ roomId: this.roomId, authority: this.authority, deviceSk: this.deviceSk, roomKey: this.roomKey, credential: this.credential, now: this.now() })
    this.requests.add(event.id)
    await this.opts.transport.publish(event)
  }

  // ------------------------------------------------------------ the desk

  private onRequest(event: Event): void {
    if (!this.authoritySk) return
    const req = decodeEpochRequest(event, { roomId: this.roomId, authoritySk: this.authoritySk, roomKey: this.roomKey, now: this.now() })
    if (!req) return
    this.noteDevice(req.participant, req.device)
    const head = this.head()
    if (!head) return
    const secret = this.epochs.get(head.state.epoch)
    let grant: Event | undefined
    if (head.state.readers.includes(req.participant) && secret) {
      grant = encodeEpochGrant({
        roomId: this.roomId, authoritySk: this.authoritySk, device: req.device, request: req.request, now: this.now(),
        epoch: { epoch: head.state.epoch, secret }, removed: head.state.removed,
        credential: this.devices.get(req.participant)?.get(req.device),
      })
    } else if (head.state.removed.includes(req.participant)) {
      grant = encodeEpochGrant({ roomId: this.roomId, authoritySk: this.authoritySk, device: req.device, request: req.request, now: this.now(), refused: 'removed' })
    }
    // Anybody else may be waiting to be admitted: say nothing yet.
    if (grant) void this.opts.transport.publish(grant)
  }

  // ------------------------------------------------------------ settling

  private queueSettle(): void {
    if (this.settleQueued || this.closed) return
    this.settleQueued = true
    this.work = this.work.then(async () => {
      this.settleQueued = false
      try {
        await this.settle()
      } catch (err) {
        console.error('kinclude: settle failed', err)
      }
      this.opts.onChange?.()
    })
  }

  /** Resolves once everything already received has been acted on. */
  async idle(): Promise<void> {
    let before: Promise<void>
    do {
      before = this.work
      await before
      await new Promise((r) => setTimeout(r, 0))
    } while (before !== this.work || this.settleQueued)
  }

  private async settle(): Promise<void> {
    const head = this.head()
    if (!head) return
    const s = head.state
    // Should be reading and is not: ask.
    if (s.readers.includes(this.me) && this.current < s.epoch) await this.requestEpoch()
    await this.escrowDuty(s)
    if (this.authoritySk) await this.authorityDuty()
  }

  private tierOf = (child: string): Tier | undefined => this.tiers.current(child)?.tier

  /** A tier-3 child's device seals each epoch it holds to each guardian
   *  listed for it who cannot read, so a guardian can open the room in an
   *  emergency, and only by announcing it first. */
  private async escrowDuty(s: RoomState): Promise<void> {
    const mine = member(s, this.me)
    if (mine?.role !== 'child' || this.tierOf(this.me) !== 3) return
    if (this.current !== s.epoch || this.escrowed.has(s.epoch)) return
    const secret = this.epochs.get(s.epoch)
    if (!secret) return
    const guardians = this.family.guardiansOf(this.me).filter((g) => member(s, g)?.role === 'guardian' && !s.readers.includes(g))
    if (guardians.length === 0) return
    this.escrowed.add(s.epoch)
    const copies = guardians.map((g) => ({
      guardian: g,
      ct: nip44.v2.encrypt(bytesToHex(secret), nip44.v2.utils.getConversationKey(this.deviceSk, g)),
    }))
    await this.post({ t: 'escrow', epoch: s.epoch, copies }, 'lobby', 'Kept a sealed key for an emergency.')
  }

  private async authorityDuty(): Promise<void> {
    const head = this.head()!
    const s = head.state
    const now = this.now()
    let members = [...s.members]
    let changed = false

    // Removals the room's rules allow.
    for (const r of this.removals.splice(0)) {
      if (!members.some((m) => m.pk === r.target) || r.target === this.creator) continue
      if (!mayRemove({ ...s, members }, r.actor, r.target, this.creator!)) continue
      members = members.filter((m) => m.pk !== r.target)
      changed = true
      const bearer = this.issued.get(r.target)
      if (bearer) {
        await this.opts.transport.publish(encodeInvitationRetirement({ invitation: roomInvitation(hexToBytes(bearer), this.authority, true), inviterSk: this.authoritySk!, now }))
        this.issued.delete(r.target)
      }
    }
    // A guardian listed for a child who has left goes with them.
    for (;;) {
      const draft = { ...s, members }
      const orphan = members.find((m) => m.role === 'guardian' && !mayObserve(draft, m.pk))
      if (!orphan) break
      members = members.filter((m) => m !== orphan)
      changed = true
    }

    // Newcomers who have arrived with what the room needs.
    for (const [pk, hello] of this.hellos) {
      if (members.some((m) => m.pk === pk)) continue
      const proposal = this.proposals.get(pk)
      if (!proposal || !this.issued.has(pk) || !this.proposalStands(pk)) continue
      const draft: RoomState = { ...s, members }
      const entry: StateMember = {
        pk, role: proposal.p.role, by: proposal.by, at: now,
        approvals: this.book.halvesFor(this.roomId, pk).map((h) => h.event),
        links: proposal.p.role === 'child' ? this.family.statementsFor(pk) : [],
        ...(proposal.p.name ?? hello.name ? { name: (proposal.p.name ?? hello.name)! } : {}),
      }
      const verdict = checkNewcomer(draft, entry, now)
      if (!verdict.ok) continue
      // Keep only the halves that made the pairs.
      entry.approvals = verdict.pairs.flatMap((p) => [p.child.event, p.guardian.event])
      members.push(entry)
      if (entry.role === 'child') {
        for (const g of proposal.p.guardians) {
          if (!members.some((m) => m.pk === g)) members.push({ pk: g, role: 'guardian', by: proposal.by, at: now, approvals: [], links: [] })
        }
      }
      changed = true
    }

    // Invitations whose approvals are complete.
    for (const [invitee, { p, by }] of this.proposals) {
      if (this.issued.has(invitee) || members.some((m) => m.pk === invitee) || !this.proposalStands(invitee)) continue
      // Somebody being removed in this same pass is not invited back on the
      // proposal that first brought them.
      if (s.members.some((m) => m.pk === invitee)) continue
      if (!members.some((m) => m.pk === by && m.role !== 'guardian')) continue
      if (p.expires <= now) continue
      const needed = requiredChildren({ ...s, members }, invitee, p.role).filter((c) => c !== invitee)
      const complete = needed.every((c) => {
        const pair = this.book.pair(this.roomId, invitee, c, now)
        return pair && pair.approval.role === p.role && pair.approval.guardians.join(',') === p.guardians.join(',')
      })
      if (!complete) continue
      await this.invite(invitee, p, by)
    }

    // Who may read, as the tiers stand now.
    const draft: RoomState = { ...s, members }
    const readers = computeReaders(draft, this.tierOf)
    const readersChanged = readers.join(',') !== s.readers.join(',')
    const emergency = this.rekeyAfterEmergency.size > 0
    this.rekeyAfterEmergency.clear()
    if (!changed && !readersChanged && !emergency) return

    const dropped = s.readers.filter((r) => !readers.includes(r))
    const removed = [...new Set([...s.removed.filter((r) => !readers.includes(r)), ...dropped])]
      .filter((r) => !members.some((m) => m.pk === r && (m.role === 'adult' || m.role === 'child')))
    const nextEpoch = s.epoch + 1
    const secret = generateEpochSecret()
    const recipients: RekeyRecipient[] = []
    for (const r of readers) {
      for (const [device, credential] of this.devices.get(r) ?? []) recipients.push(credential ? { device, credential } : { device })
    }
    const current = this.epochs.get(s.epoch)
    if (!current) return
    await this.opts.transport.publish(encodeRekeyEvent({
      roomId: this.roomId, authoritySk: this.authoritySk!,
      current: deriveEpoch({ epoch: s.epoch, secret: current }),
      next: { epoch: nextEpoch, secret },
      recipients, removed: dropped, members: readers, commit: true, now,
      ...(dropped.length === 0 && emergency && !changed ? { scheduled: true } : {}),
    }))
    this.adoptEpoch(nextEpoch, secret)
    await this.publishState({ ...s, seq: s.seq + 1, prev: head.event.id, epoch: nextEpoch, members, readers, removed })
  }

  /** When `pk` last left the room, by the state that first left them out;
   *  undefined for somebody who never left. A participant comes back only
   *  through a proposal made after that. */
  private departedAt(pk: string): number | undefined {
    let inRoom = false
    let left: number | undefined
    for (const a of this.accepted) {
      const present = a.state.members.some((m) => m.pk === pk)
      if (inRoom && !present) left = a.event.created_at
      inRoom = present
    }
    return inRoom ? undefined : left
  }

  private proposalStands(pk: string): boolean {
    const p = this.proposals.get(pk)
    if (!p) return false
    const left = this.departedAt(pk)
    return left === undefined || p.at > left
  }

  private async invite(invitee: string, p: Extract<KincludePayload, { t: 'propose' }>, by: string): Promise<void> {
    const bearer = randomBytes(32)
    const invitation = roomInvitation(bearer, this.authority, true)
    await this.opts.transport.publish(encodePersistentInvitation({ invitation, inviterSk: this.authoritySk!, roomSecret: this.secret, now: this.now() }))
    this.issued.set(invitee, bytesToHex(bearer))
    const head = this.head()!
    const envelope: InvitationEnvelope = {
      v: 1, room: this.roomId, name: head.state.name, bearer: bytesToHex(bearer), authority: this.authority,
      invitee, role: p.role, guardians: p.guardians,
      ...(member(head.state, by)?.name ? { fromName: member(head.state, by)!.name! } : {}),
    }
    const recipients = p.role === 'child' ? p.guardians : [invitee]
    for (const r of recipients) {
      await this.opts.transport.publish(await wrapInvitation({ sender: this.opts.identity, recipient: r, envelope, now: this.now() }))
    }
  }

  private async publishState(state: RoomState): Promise<void> {
    if (!this.authoritySk) throw new Error('only the authority publishes state')
    const event = signState(this.authoritySk, state, this.now())
    await this.post({ t: 'state', event }, 'lobby', 'The room changed.')
  }

  // ------------------------------------------------------------ acting

  private async post(payload: KincludePayload | undefined, where: 'lobby' | 'main', text: string, epoch = this.current): Promise<string> {
    if (!this.credential) throw new Error('the device has no credential yet')
    let stream: Stream = this.lobby
    if (where === 'main') {
      const secret = this.epochs.get(epoch)
      if (!secret || epoch === 0) throw new Error('this device cannot write to the room yet')
      const keys = deriveEpoch({ epoch, secret })
      stream = { id: keys.id, key: keys.key }
    }
    const now = this.now()
    const msg: RoomMessage = {
      id: newMessageId(), participant: this.me, device: this.device, credential: this.credential,
      text, sentAt: now, sentAtMs: now * 1000 + (Date.now() % 1000),
      ...(this.opts.name ? { name: this.opts.name } : {}),
      ...(payload ? { kinclude: payload } : {}),
    }
    const event = encodeMessage(msg, { stream, deviceSk: this.deviceSk })
    await this.opts.transport.publish(event)
    this.onEvent(event)
    return msg.id
  }

  /** Say something in the room. */
  async send(text: string): Promise<string> {
    return this.post(undefined, 'main', text)
  }

  async retract(id: string): Promise<void> {
    const m = this.messages.get(id)
    if (!m || m.msg.participant !== this.me) return
    if (!this.credential) return
    const secret = this.epochs.get(m.epoch)
    if (!secret) return
    const keys = deriveEpoch({ epoch: m.epoch, secret })
    const now = this.now()
    const msg: RoomMessage = {
      id: newMessageId(), participant: this.me, device: this.device, credential: this.credential,
      text: 'Message withdrawn', sentAt: now, retracts: id,
    }
    const event = encodeMessage(msg, { stream: { id: keys.id, key: keys.key }, deviceSk: this.deviceSk })
    await this.opts.transport.publish(event)
    this.onEvent(event)
  }

  /** Carry statements into the room: new tier halves, new links. */
  async postStatements(events: Event[], text = 'Updated family details.'): Promise<void> {
    if (events.length === 0) return
    this.family.addAll(events)
    this.tiers.addAll(events)
    this.book.addAll(events)
    // Approvals ride in the current epoch, among the people who can read the
    // room. Links and tier statements ride in the lobby, because a guardian
    // at tier 3 holds no current key and still has to see the child's tier
    // change, or their device would keep showing the old one.
    const approvals = events.some((e) => readApproval(e))
    const canWrite = this.epochs.has(this.current) && this.current > 0 && !!this.head()?.state.readers.includes(this.me)
    await this.post({ t: 'statements', events }, approvals && canWrite ? 'main' : 'lobby', text)
  }

  /** Ask for somebody to join. The room's children and their guardians are
   *  asked to approve; the invitation goes out once they all have. */
  async propose(opts: { invitee: string; name?: string; role: 'adult' | 'child'; guardians?: string[] }): Promise<void> {
    const guardians = [...(opts.guardians ?? [])].sort()
    const expires = this.now() + (this.opts.approvalSeconds ?? APPROVAL_TTL_SECONDS)
    const who = opts.name ?? 'someone'
    await this.post(
      { t: 'propose', invitee: opts.invitee, role: opts.role, guardians, expires, ...(opts.name ? { name: opts.name } : {}) },
      'main', `I'd like to invite ${who}. Everyone's approval is needed.`,
    )
    // Proposing is saying yes on one's own part.
    await this.approve(opts.invitee)
  }

  /** The approval halves this identity can sign for a proposal: as a child
   *  in the room, or as a guardian of children in it. */
  approvalsToSign(invitee: string): string[] {
    const head = this.head()
    const prop = this.proposals.get(invitee)
    if (!head || !prop) return []
    const needed = requiredChildren(head.state, invitee, prop.p.role).filter((c) => c !== invitee)
    return needed.filter((c) => {
      if (c !== this.me && !this.family.isGuardian(this.me, c)) return false
      return !this.book.halvesFor(this.roomId, invitee).some((h) => h.signer === this.me && h.approval.child === c &&
        h.approval.expires === prop.p.expires)
    })
  }

  async approve(invitee: string): Promise<number> {
    const prop = this.proposals.get(invitee)
    if (!prop) throw new Error('no such proposal')
    const events: Event[] = []
    for (const child of this.approvalsToSign(invitee)) {
      events.push(await this.opts.identity.signEvent(approvalUnsigned({
        room: this.roomId, invitee, child, role: prop.p.role, guardians: prop.p.guardians, expires: prop.p.expires,
      }, this.now(), prop.p.name)))
    }
    if (events.length) await this.postStatements(events, `I approve ${prop.p.name ?? 'the invitation'}.`)
    return events.length
  }

  /** Ask the room's authority to take somebody out, or to let oneself go. */
  async remove(target: string): Promise<void> {
    await this.post({ t: 'remove', target }, 'main', target === this.me ? 'I am leaving this room.' : 'I asked for someone to be removed.')
  }

  // ------------------------------------------------------------ guardians

  /** Children whose tier means this viewer publishes a receipt before
   *  reading: the viewer is listed only as their guardian, and the child is
   *  past the first tier. Tier 3 is included: a guardian there holds no
   *  current key, but may still hold messages from when the child was at
   *  tier 2, and those are not shown without a receipt either. */
  private gatedFor(s: RoomState | undefined): string[] {
    if (!s) return []
    const mine = member(s, this.me)
    if (mine?.role !== 'guardian') return []
    return s.members
      .filter((c) => c.role === 'child' && this.family.isGuardian(this.me, c.pk) && (this.tierOf(c.pk) ?? 1) > 1)
      .map((c) => c.pk)
  }

  /**
   * Whether something written in `epoch` by `participant` at `sentAt` is the
   * room's: the room's state listed them as a reader of that epoch, and it
   * is not dated after the room left the epoch. A member who was removed
   * still holds the keys to the epochs they were in; this is what keeps
   * them from writing into the room's history afterwards.
   */
  private belongs(epoch: number, participant: string, sentAt: number): boolean {
    let listed = false
    let leftAt: number | undefined
    for (const a of this.accepted) {
      if (a.state.epoch === epoch && a.state.readers.includes(participant)) listed = true
      if (a.state.epoch > epoch && leftAt === undefined) leftAt = a.event.created_at
    }
    return listed && (leftAt === undefined || sentAt <= leftAt + MAX_CLOCK_SKEW_SECONDS)
  }

  private ordered(): Array<{ msg: RoomMessage; epoch: number; viaEmergency?: true }> {
    return [...this.messages.values()].filter((m) => this.belongs(m.epoch, m.msg.participant, m.msg.sentAt)).sort((a, b) =>
      (a.msg.sentAtMs ?? a.msg.sentAt * 1000) - (b.msg.sentAtMs ?? b.msg.sentAt * 1000) || (a.msg.id < b.msg.id ? -1 : 1))
  }

  /**
   * For a guardian at tier 2: publish that you are reading, then read.
   *
   * The receipt names the newest message and is published, and the relay
   * has acknowledged it, before anything is revealed. If publishing fails,
   * nothing is revealed. This is what "they can see exactly what you've
   * read" rests on (design §7.2).
   */
  async reveal(): Promise<void> {
    const gated = this.gatedFor(this.head()?.state)
    const all = this.ordered().filter((m) => !m.viaEmergency)
    const ids = all.map((m) => m.msg.id)
    if (gated.length === 0 || all.length === 0) {
      for (const id of ids) this.revealed.add(id)
      return
    }
    if (ids.every((id) => this.revealed.has(id))) return
    const upTo = ids.at(-1)!
    for (const child of gated) {
      await this.post({ t: 'read', child, upTo, at: this.now() }, 'main', `${this.opts.name ?? 'A guardian'} read this conversation.`)
    }
    for (const id of ids) this.revealed.add(id)
  }

  /**
   * For a guardian of a tier-3 child: announce, then open.
   *
   * The notice is signed by the guardian's identity and published into the
   * room before the escrowed key is asked for; the child's device shows it
   * the moment it arrives. Only the epoch the room is at is opened, and the
   * room's authority rekeys on seeing the notice, so an opening does not
   * become standing access (design §7.3).
   */
  async openInEmergency(child: string, reason?: string): Promise<number> {
    const head = this.head()
    if (!head) throw new Error('the room is not loaded yet')
    if (!this.family.isGuardian(this.me, child)) throw new Error('only a guardian of this child can open their messages')
    if (!this.opts.identity.nip44Decrypt) throw new Error('this identity cannot open an escrowed key')
    const epoch = head.state.epoch
    const escrow = this.escrows.get(`${child}:${epoch}`)
    const copy = escrow?.copies.find((c) => c.guardian === this.me)
    if (!escrow || !copy) throw new Error('there is nothing sealed for you for this epoch')
    const notice = await this.opts.identity.signEvent(emergencyUnsigned({ room: this.roomId, child, epoch, ...(reason ? { reason } : {}) }, this.now()))
    await this.post({ t: 'emergency', event: notice }, 'lobby', `${this.opts.name ?? 'A guardian'} opened messages in an emergency.`)
    const secretHex = await this.opts.identity.nip44Decrypt(escrow.device, copy.ct)
    const secret = hexToBytes(secretHex)
    if (secret.length !== 32) throw new Error('escrowed key is malformed')
    this.opened.set(epoch, secret)
    this.watchEpoch(epoch)
    return epoch
  }

  // ------------------------------------------------------------ views

  view(): RoomView {
    const head = this.head()
    const s = head?.state
    const tierOf: Record<string, Tier | undefined> = {}
    for (const m of s?.members ?? []) if (m.role === 'child') tierOf[m.pk] = this.tierOf(m.pk)
    const gated = this.gatedFor(s)
    const all = this.ordered()
    const visible = gated.length > 0
      ? all.filter((m) => this.revealed.has(m.msg.id) || m.viaEmergency)
      : all
    const proposals: Proposal[] = []
    if (s) {
      for (const [invitee, { p, by, at }] of this.proposals) {
        if (member(s, invitee) && member(s, invitee)!.role !== 'guardian') continue
        if (p.expires <= this.now()) continue
        const needed = requiredChildren(s, invitee, p.role).filter((c) => c !== invitee)
        const halves = this.book.halvesFor(this.roomId, invitee).filter((h) => h.approval.expires === p.expires)
        proposals.push({
          invitee, role: p.role, guardians: p.guardians, by, at, expires: p.expires, invited: this.issued.has(invitee) || false,
          ...(p.name ? { name: p.name } : {}),
          approvals: needed.map((c) => ({
            child: c,
            byChild: halves.some((h) => h.signer === c && h.approval.child === c),
            byGuardian: halves.find((h) => h.approval.child === c && h.signer !== c && this.family.isGuardian(h.signer, c))?.signer,
          })),
        })
      }
    }
    const openable = s
      ? s.members.filter((c) => c.role === 'child' && this.family.isGuardian(this.me, c.pk) &&
        this.escrows.get(`${c.pk}:${s.epoch}`)?.copies.some((x) => x.guardian === this.me)).map((c) => c.pk)
      : []
    return {
      roomId: this.roomId,
      name: s?.name ?? 'Loading…',
      state: s,
      ...(this.stateProblem ? { stateProblem: this.stateProblem } : {}),
      creator: this.creator,
      me: this.me,
      role: s ? member(s, this.me)?.role : undefined,
      epoch: this.current,
      reading: !!s && this.current >= s.epoch && s.readers.includes(this.me),
      removed: this.removedMe || (!!s && s.removed.includes(this.me)),
      banners: s ? banners(s, this.tierOf) : [],
      tierOf,
      messages: visible.map(({ msg, epoch, viaEmergency }) => ({
        id: msg.id, from: msg.participant, text: msg.text, sentAt: msg.sentAt, epoch,
        ...(msg.name ? { name: msg.name } : {}),
        ...(viaEmergency ? { viaEmergency } : {}),
      })),
      unrevealed: all.length - visible.length,
      gatedFor: gated,
      proposals,
      receipts: this.receipts.filter((r) => this.belongs(r.epoch, r.participant, r.sentAt))
        .map(({ guardian, child, upTo, at }) => ({ guardian, child, upTo, at })),
      emergencies: [...this.emergencies],
      openable,
    }
  }
}
