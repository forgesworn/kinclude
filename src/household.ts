import { generateSecretKey, getPublicKey, type Event } from 'nostr-tools/pure'
import { bytesToHex, hexToBytes } from '@noble/hashes/utils'
import { deriveRoom, requestPersistentRoomAdmission, roomInvitation, sanitiseDisplayName, type RelayTransport } from '@forgesworn/fold-kit'
import { FamilyGraph, TierLedger } from './family.js'
import {
  inboxKey,
  readEnvelope,
  unwrapGift,
  unwrapInvitation,
  wrapGift,
  GIFT_WRAP_KIND,
  type InvitationEnvelope,
} from './invite.js'
import { acceptPairing, offerPairing } from './pairing.js'
import { KincludeRoom, type RoomSecrets } from './room.js'
import { localSigner, type KincludeSigner } from './signer.js'
import { member } from './state.js'
import {
  approvalUnsigned,
  readApproval,
  readLinkStatement,
  readTier,
  tierUnsigned,
  APPROVAL_TTL_SECONDS,
  TIER_GENESIS,
  type Tier,
} from './statements.js'
import { isEventShape } from './messages.js'

/** The rumor kind a guardian forwards a child's invitation in, to the
 *  child's phone inbox, with the guardian's half of the child's own
 *  approval. */
export const FORWARD_RUMOR_KIND = 1481

/** The rumor kind a child's phone tells its guardians about the family in,
 *  sealed from its inbox key: after a new guardian pairs, so every
 *  guardian knows every other one before proposing the child anywhere. */
export const FAMILY_RUMOR_KIND = 1482

/**
 * Who an adult will take an invitation from: their kin and kith in My
 * Signet. In the app this is filled from the signet-contacts grant; the
 * kens a person has only met online are not here, and an invitation from
 * anybody not here is dropped without a word (design §6.3).
 */
export interface Contact {
  pk: string
  name?: string
  tier: 'kin' | 'kith'
}

export interface PendingInvite {
  /** The gift wrap it came in. */
  id: string
  envelope: InvitationEnvelope
  sender: string
  /** A child's phone: the guardian's half of the child's own approval. */
  approvals?: Event[]
  /** A guardian's phone: the invitation is for this child of theirs. */
  forChild?: string
}

export interface HouseholdData {
  v: 1
  name: string
  kind: 'adult' | 'child'
  phoneSecret: string
  family: Event[]
  children: Array<{ pk: string; name?: string; inbox: string }>
  guardians: Array<{ pk: string; name?: string }>
  contacts: Contact[]
  rooms: RoomSecrets[]
  handled: string[]
  invites: PendingInvite[]
}

export interface HouseholdStore {
  load(): Promise<HouseholdData | undefined>
  save(data: HouseholdData): Promise<void>
}

export class MemoryStore implements HouseholdStore {
  data: HouseholdData | undefined
  async load() { return this.data && structuredClone(this.data) }
  async save(d: HouseholdData) { this.data = structuredClone(d) }
}

export interface PendingTier {
  child: string
  tier: Tier
  seq: number
  prev: string
  /** Who has signed so far. */
  signedBy: string
}

/**
 * One person on one phone: their family, their contacts, their rooms and
 * their invitations. What the app drives.
 */
export class Household {
  readonly identity: KincludeSigner
  data: HouseholdData
  readonly rooms = new Map<string, KincludeRoom>()
  readonly family = new FamilyGraph()
  readonly tiers = new TierLedger(this.family)

  private readonly transport: RelayTransport
  private readonly store: HouseholdStore
  private readonly nowFn: () => number
  private readonly onChange: (() => void) | undefined
  private readonly subs: Array<() => void> = []
  private saving: Promise<void> = Promise.resolve()

  private constructor(opts: { transport: RelayTransport; identity: KincludeSigner; store: HouseholdStore; data: HouseholdData; now?: () => number; onChange?: () => void }) {
    this.transport = opts.transport
    this.identity = opts.identity
    this.store = opts.store
    this.data = opts.data
    this.nowFn = opts.now ?? (() => Math.floor(Date.now() / 1000))
    this.onChange = opts.onChange
    this.family.addAll(this.data.family)
    this.tiers.addAll(this.data.family)
  }

  static async open(opts: {
    transport: RelayTransport
    identity: KincludeSigner
    store: HouseholdStore
    name?: string
    kind?: 'adult' | 'child'
    now?: () => number
    onChange?: () => void
  }): Promise<Household> {
    const data = (await opts.store.load()) ?? {
      v: 1, name: sanitiseDisplayName(opts.name) ?? 'Me', kind: opts.kind ?? 'adult',
      phoneSecret: bytesToHex(generateSecretKey()), family: [], children: [], guardians: [], contacts: [],
      rooms: [], handled: [], invites: [],
    }
    const h = new Household({ ...opts, data })
    await h.save()
    return h
  }

  get me(): string {
    return this.identity.pubkey
  }

  now(): number {
    return this.nowFn()
  }

  private get phoneSecret(): Uint8Array {
    return hexToBytes(this.data.phoneSecret)
  }

  /** The key a child's phone receives forwarded invitations on. */
  get inbox(): string {
    return getPublicKey(inboxKey(this.phoneSecret))
  }

  async start(): Promise<void> {
    if (this.data.kind === 'adult') {
      this.subs.push(this.transport.subscribe([{ kinds: [GIFT_WRAP_KIND], '#p': [this.me] }], (e) => void this.onWrap(e, 'identity')))
    }
    this.subs.push(this.transport.subscribe([{ kinds: [GIFT_WRAP_KIND], '#p': [this.inbox] }], (e) => void this.onWrap(e, 'inbox')))
    for (const secrets of this.data.rooms) await this.openRoom(secrets)
  }

  close(): void {
    for (const u of this.subs) u()
    for (const r of this.rooms.values()) r.close()
  }

  private changed(): void {
    void this.save()
    this.onChange?.()
  }

  async save(): Promise<void> {
    this.data.rooms = this.data.rooms.map((s) => {
      const room = this.rooms.get(deriveRoom(hexToBytes(s.secret)).roomId)
      return room ? room.secrets() : s
    })
    this.saving = this.saving.then(() => this.store.save(this.data))
    await this.saving
  }

  // ------------------------------------------------------------ family

  /** Keep the statements that bear on this person or their children:
   *  guardian links and tier chains. Returns true when anything was new. */
  addStatements(events: Event[]): boolean {
    let added = false
    for (const e of events) {
      if (this.data.family.some((f) => f.id === e.id)) continue
      const link = readLinkStatement(e)
      const tier = readTier(e)
      const ours = (pk: string) => pk === this.me || this.isMyChild(pk)
      const relevant = link ? ours(link.signer) || ours(link.other) : tier ? ours(tier.statement.child) : false
      if (!relevant) continue
      this.data.family.push(e)
      this.family.add(e)
      this.tiers.add(e)
      added = true
    }
    if (added) this.changed()
    return added
  }

  isMyChild(pk: string | undefined): boolean {
    return !!pk && this.data.children.some((c) => c.pk === pk)
  }

  /** The statements a room needs about `child`: links and tier chain. */
  statementsFor(child: string): Event[] {
    return [...this.family.statementsFor(child), ...this.tiers.chainEvents(child)]
  }

  tierOf(child: string): Tier | undefined {
    return this.tiers.current(child)?.tier
  }

  /** Guardian: show a code for a child's phone to read. */
  offerPairing(opts: { childName?: string; tier: Tier }): { code: string; paired: Promise<string>; cancel(): void } {
    const offer = offerPairing({
      transport: this.transport, guardian: this.identity, guardianName: this.data.name, now: () => this.now(), ...opts,
    })
    return {
      code: offer.code,
      cancel: offer.cancel,
      paired: offer.paired.then((child) => {
        this.data.children = [...this.data.children.filter((c) => c.pk !== child.pk), { pk: child.pk, inbox: child.inbox, ...(child.name ? { name: child.name } : {}) }]
        this.addStatements(child.events)
        this.changed()
        return child.pk
      }),
    }
  }

  /** Child: read a guardian's code. */
  async acceptPairing(code: string): Promise<string> {
    const res = await acceptPairing({
      transport: this.transport, child: this.identity, childName: this.data.name, inboxSk: inboxKey(this.phoneSecret),
      code, existing: this.data.family, now: () => this.now(),
    })
    this.data.guardians = [...this.data.guardians.filter((g) => g.pk !== res.guardian), { pk: res.guardian, ...(res.guardianName ? { name: res.guardianName } : {}) }]
    this.addStatements(res.events)
    this.changed()
    await this.tellGuardians()
    // Any room this phone is already in learns about the new guardian.
    for (const room of this.rooms.values()) await room.postStatements(this.statementsFor(this.me))
    return res.guardian
  }

  // ------------------------------------------------------------ tiers

  /** Sign this person's half of a move to `tier` for `child` (themself, or
   *  a child of theirs), and carry it into every room the child is in. */
  async proposeTier(child: string, tier: Tier): Promise<void> {
    if (child !== this.me && !this.family.isGuardian(this.me, child)) throw new Error('only the child or their guardian agrees a tier')
    const pos = this.tiers.current(child)
    const statement = { child, tier, seq: pos ? pos.seq + 1 : 0, prev: pos?.digest ?? TIER_GENESIS }
    if (pos?.tier === tier) return
    const name = child === this.me ? this.data.name : this.data.children.find((c) => c.pk === child)?.name
    const event = await this.identity.signEvent(tierUnsigned(statement, this.now(), name))
    this.addStatements([event])
    await this.carryTier(child, [event])
  }

  /** Tier changes waiting on this person's signature. */
  pendingTiers(): PendingTier[] {
    const out: PendingTier[] = []
    const children = this.data.kind === 'child' ? [this.me] : this.data.children.map((c) => c.pk)
    const signed = new Set(this.data.family.filter((e) => e.pubkey === this.me).map((e) => readTier(e)?.digest).filter(Boolean))
    for (const child of children) {
      for (const half of this.tiers.pending(child)) {
        if (half.signer === this.me || signed.has(half.digest)) continue
        out.push({ child, tier: half.statement.tier, seq: half.statement.seq, prev: half.statement.prev, signedBy: half.signer })
      }
    }
    return out
  }

  async agreeTier(p: PendingTier): Promise<void> {
    const name = p.child === this.me ? this.data.name : this.data.children.find((c) => c.pk === p.child)?.name
    const event = await this.identity.signEvent(tierUnsigned({ child: p.child, tier: p.tier, seq: p.seq, prev: p.prev }, this.now(), name))
    this.addStatements([event])
    await this.carryTier(p.child, [event])
  }

  /** Put tier halves, and the chain as this phone knows it, into every room
   *  the child is in where the room does not already have it. */
  private async carryTier(child: string, fresh: Event[]): Promise<void> {
    const chain = this.tiers.chainEvents(child)
    for (const room of this.rooms.values()) {
      const s = room.view().state
      if (!s || !member(s, child)) continue
      const have = room.tiers.current(child)?.digest
      const want = this.tiers.current(child)?.digest
      const events = [...(have !== want ? chain : []), ...fresh].filter((e, i, all) => all.findIndex((x) => x.id === e.id) === i)
      if (events.length) await room.postStatements(events, 'Oversight for a child here changed.')
    }
  }

  private onRoomStatements(events: Event[]): void {
    if (!this.addStatements(events)) return
    // Something new about this family: make sure every room of each child
    // has the tier chain as it now stands, not only the rooms where the
    // other signer happens to be.
    const children = this.data.kind === 'child' ? [this.me] : this.data.children.map((c) => c.pk)
    for (const c of children) void this.carryTier(c, [])
  }

  // ------------------------------------------------------------ contacts

  setContacts(contacts: Contact[]): void {
    this.data.contacts = contacts.filter((c) => /^[0-9a-f]{64}$/.test(c.pk))
    this.changed()
  }

  private trusted(pk: string): boolean {
    return pk === this.me || this.data.contacts.some((c) => c.pk === pk) || this.data.guardians.some((g) => g.pk === pk) || this.isMyChild(pk)
  }

  // ------------------------------------------------------------ invitations

  private async onWrap(event: Event, to: 'identity' | 'inbox'): Promise<void> {
    if (this.data.handled.includes(event.id)) return
    this.data.handled.push(event.id)
    if (to === 'identity') {
      const opened = await unwrapGift(event, this.identity)
      if (opened?.rumor.kind === FAMILY_RUMOR_KIND) {
        // Only from a child's phone this person paired with.
        if (this.data.children.some((c) => c.inbox === opened.sender)) {
          try {
            const events = JSON.parse(opened.rumor.content) as unknown
            if (Array.isArray(events) && events.length <= 64 && events.every(isEventShape)) this.addStatements(events)
          } catch {
            // Not something this phone can read.
          }
        }
      } else if (opened) {
        const invitation = await unwrapInvitation(event, this.identity)
        if (invitation && this.trusted(invitation.sender)) {
          const { envelope, sender } = invitation
          const known = this.rooms.has(envelope.room)
          if (!known && envelope.role === 'adult' && envelope.invitee === this.me) {
            this.data.invites.push({ id: event.id, envelope, sender })
          } else if (envelope.role === 'child' && this.isMyChild(envelope.invitee) && envelope.guardians.includes(this.me)) {
            this.data.invites.push({ id: event.id, envelope, sender, forChild: envelope.invitee })
          }
        }
      }
    } else {
      const opened = await unwrapGift(event, localSigner(inboxKey(this.phoneSecret)))
      if (opened && opened.rumor.kind === FORWARD_RUMOR_KIND && this.data.guardians.some((g) => g.pk === opened.sender)) {
        try {
          const body = JSON.parse(opened.rumor.content) as { envelope: unknown; approvals: unknown }
          const envelope = readEnvelope(body.envelope)
          const approvals = Array.isArray(body.approvals) && body.approvals.every(isEventShape) ? body.approvals as Event[] : []
          if (envelope && envelope.invitee === this.me && !this.rooms.has(envelope.room) &&
            !this.data.invites.some((i) => i.envelope.room === envelope.room)) {
            this.data.invites.push({ id: event.id, envelope, sender: opened.sender, approvals })
          }
        } catch {
          // Not a forward this phone can read.
        }
      }
    }
    this.changed()
  }

  /** A child's phone: tell every guardian the whole family as this phone
   *  knows it. Sealed from the inbox key, which each guardian learned at
   *  pairing, since a child's identity cannot seal anything. */
  private async tellGuardians(): Promise<void> {
    const inbox = localSigner(inboxKey(this.phoneSecret))
    const content = JSON.stringify(this.statementsFor(this.me))
    for (const g of this.data.guardians) {
      await this.transport.publish(await wrapGift({ sender: inbox, recipient: g.pk, kind: FAMILY_RUMOR_KIND, content, now: this.now() }))
    }
  }

  async declineInvite(id: string): Promise<void> {
    this.data.invites = this.data.invites.filter((i) => i.id !== id)
    this.changed()
  }

  /**
   * Say yes to an invitation.
   *
   * For an adult, join. For a guardian whose child is invited, sign the
   * guardian's half of the child's own approval, forward it to the child's
   * phone, and join as the child's listed guardian. For a child, sign the
   * child's half and join.
   */
  async acceptInvite(id: string): Promise<KincludeRoom | undefined> {
    const invite = this.data.invites.find((i) => i.id === id)
    if (!invite) throw new Error('no such invitation')
    this.data.invites = this.data.invites.filter((i) => i.id !== id)
    const { envelope } = invite
    if (invite.forChild) {
      const child = this.data.children.find((c) => c.pk === invite.forChild)
      if (!child) throw new Error('not a child of this phone')
      const terms = { room: envelope.room, invitee: child.pk, child: child.pk, role: 'child' as const, guardians: envelope.guardians, expires: this.now() + APPROVAL_TTL_SECONDS }
      const half = await this.identity.signEvent(approvalUnsigned(terms, this.now(), child.name))
      await this.transport.publish(await wrapGift({
        sender: this.identity, recipient: child.inbox, kind: FORWARD_RUMOR_KIND,
        content: JSON.stringify({ envelope, approvals: [half] }), now: this.now(),
      }))
      this.changed()
      if (this.rooms.has(envelope.room)) return this.rooms.get(envelope.room)
      return this.join(envelope, this.statementsFor(child.pk))
    }
    if (this.data.kind === 'child') {
      const theirs = (invite.approvals ?? []).map(readApproval).find((a) => a && a.approval.invitee === this.me && a.approval.child === this.me)
      if (!theirs) throw new Error('this invitation came without your guardian\'s approval')
      const mine = await this.identity.signEvent(approvalUnsigned(theirs.approval, this.now(), this.data.name))
      return this.join(envelope, [...this.statementsFor(this.me), theirs.event, mine])
    }
    return this.join(envelope, [])
  }

  private async join(envelope: InvitationEnvelope, hello: Event[]): Promise<KincludeRoom> {
    const admission = await requestPersistentRoomAdmission({
      transport: this.transport,
      invitation: roomInvitation(hexToBytes(envelope.bearer), envelope.authority, true),
    })
    if (deriveRoom(admission.secret).roomId !== envelope.room) throw new Error('the invitation opened a different room')
    const secrets: RoomSecrets = { secret: bytesToHex(admission.secret), authority: envelope.authority, epochs: {} }
    this.data.rooms.push(secrets)
    const room = await this.openRoom(secrets, hello)
    this.changed()
    return room
  }

  private async openRoom(secrets: RoomSecrets, family: Event[] = []): Promise<KincludeRoom> {
    const room = new KincludeRoom({
      transport: this.transport, identity: this.identity, name: this.data.name, secrets, phoneSecret: this.phoneSecret,
      family: family.length ? family : (this.data.kind === 'child' ? this.statementsFor(this.me) : []),
      now: () => this.now(),
      onChange: () => this.changed(),
      onStatements: (events) => this.onRoomStatements(events),
    })
    this.rooms.set(room.roomId, room)
    await room.start()
    return room
  }

  async createRoom(name: string): Promise<KincludeRoom> {
    const room = await KincludeRoom.create({
      transport: this.transport, identity: this.identity, name: this.data.name, roomName: name, phoneSecret: this.phoneSecret,
      now: () => this.now(),
      onChange: () => this.changed(),
      onStatements: (events) => this.onRoomStatements(events),
    })
    this.rooms.set(room.roomId, room)
    this.data.rooms.push(room.secrets())
    this.changed()
    return room
  }

  /** Propose one of this person's children for a room: the invitation goes
   *  to the child's guardians, who forward it to the child's phone. */
  async addChild(room: KincludeRoom, child: string): Promise<void> {
    if (!this.family.isGuardian(this.me, child)) throw new Error('not your child')
    const name = this.data.children.find((c) => c.pk === child)?.name
    // The room needs the child's links to know their guardians.
    await room.postStatements(this.family.statementsFor(child), `${name ?? 'A child'}'s family.`)
    await room.propose({ invitee: child, role: 'child', guardians: this.family.guardiansOf(child), ...(name ? { name } : {}) })
  }
}
