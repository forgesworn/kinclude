import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { hexToBytes, bytesToHex } from '@noble/hashes/utils'
import { getPublicKey, type Event } from 'nostr-tools/pure'
import { deriveChannel, deriveEpoch, deriveRoom } from '@forgesworn/fold-kit'
import { hkdf } from '@noble/hashes/hkdf'
import { sha256 } from '@noble/hashes/sha2'
import {
  readApproval,
  readEmergency,
  readLinkStatement,
  readTier,
  tierDigest,
  approvalKey,
  type Approval,
  type TierStatement,
} from '../src/statements.js'
import { FamilyGraph, TierLedger, ApprovalBook } from '../src/family.js'
import { readState } from '../src/state.js'
import { decodeMessage } from '../src/messages.js'
import { inboxKey, roomDeviceKey } from '../src/invite.js'
import { decodePairingCode, PAIRING_KEY_INFO, PAIRING_TAG_INFO } from '../src/pairing.js'
import { LOBBY_CHANNEL } from '../src/room.js'
import { presenceKeys } from '../src/call-wire.js'

const v = JSON.parse(readFileSync(new URL('./kinclude-vectors.json', import.meta.url), 'utf8'))
const T = 1_800_000_000

describe('kinclude vectors', () => {
  it('derives the room, lobby, epoch and keys exactly', () => {
    const { roomId, roomKey } = deriveRoom(hexToBytes(v.keys.roomSecret))
    expect(roomId).toBe(v.room.id)
    expect(deriveChannel(roomId, roomKey, LOBBY_CHANNEL).id).toBe(v.room.lobby)
    expect(deriveEpoch({ epoch: 1, secret: hexToBytes(v.keys.epoch1Secret) }).id).toBe(v.room.epoch1)
    expect(getPublicKey(roomDeviceKey(hexToBytes(v.keys.phoneSecret), roomId))).toBe(v.derived.roomDevicePubkey)
    expect(getPublicKey(inboxKey(hexToBytes(v.keys.phoneSecret)))).toBe(v.derived.inboxPubkey)
    expect(bytesToHex(hkdf(sha256, hexToBytes(v.keys.pairingSecret), undefined, PAIRING_KEY_INFO, 32))).toBe(v.derived.pairingKey)
    expect(bytesToHex(hkdf(sha256, hexToBytes(v.keys.pairingSecret), undefined, PAIRING_TAG_INFO, 32))).toBe(v.derived.pairingTag)
    const presence = presenceKeys(deriveEpoch({ epoch: 1, secret: hexToBytes(v.keys.epoch1Secret) }).key)
    expect(presence.tag).toBe(v.derived.presenceTag)
    expect(bytesToHex(presence.key)).toBe(v.derived.presenceKey)
    expect(decodePairingCode(v.derived.pairingCode)).toEqual({ guardian: v.pubkeys.guardian, secret: v.keys.pairingSecret, tier: 2, childName: 'Sam', guardianName: 'Mum' })
  })

  it('reads the link statements and makes a link of the pair', () => {
    expect(readLinkStatement(v.links.guardianOf)).toMatchObject({ type: 'guardian-of', signer: v.pubkeys.guardian, other: v.pubkeys.child })
    expect(readLinkStatement(v.links.dependantOf)).toMatchObject({ type: 'dependant-of', signer: v.pubkeys.child, other: v.pubkeys.guardian })
    expect(readLinkStatement(v.links.unlink)).toMatchObject({ type: 'unlink' })
    const g = new FamilyGraph()
    g.addAll([v.links.guardianOf, v.links.dependantOf])
    expect(g.isGuardian(v.pubkeys.guardian, v.pubkeys.child)).toBe(true)
    g.add(v.links.unlink)
    expect(g.isGuardian(v.pubkeys.guardian, v.pubkeys.child)).toBe(false)
  })

  it('reads the approval pair', () => {
    const terms = v.approval.terms as Approval
    for (const half of [v.approval.byChild, v.approval.byGuardian]) {
      expect(approvalKey(readApproval(half)!.approval)).toBe(approvalKey(terms))
    }
    const g = new FamilyGraph()
    g.addAll([v.links.guardianOf, v.links.dependantOf])
    const book = new ApprovalBook(g)
    book.addAll([v.approval.byChild, v.approval.byGuardian])
    expect(book.pair(terms.room, terms.invitee, terms.child, T)?.guardian.signer).toBe(v.pubkeys.guardian)
  })

  it('reads the tier chain and its digests', () => {
    expect(tierDigest(v.tier.genesis.statement as TierStatement)).toBe(v.tier.genesis.digest)
    expect(tierDigest(v.tier.up.statement as TierStatement)).toBe(v.tier.up.digest)
    expect(readTier(v.tier.upByChild)?.digest).toBe(v.tier.up.digest)
    const g = new FamilyGraph()
    g.addAll([v.links.guardianOf, v.links.dependantOf])
    const ledger = new TierLedger(g)
    ledger.addAll([v.tier.genesisByChild, v.tier.genesisByGuardian, v.tier.upByChild, v.tier.upByGuardian])
    expect(ledger.current(v.pubkeys.child)).toMatchObject({ tier: 3, seq: 1, digest: v.tier.up.digest })
  })

  it('reads the emergency notice', () => {
    expect(readEmergency(v.emergency.event)).toMatchObject({ signer: v.pubkeys.guardian, notice: v.emergency.notice })
  })

  it('reads the room state, from its authority only', () => {
    const read = readState(v.state.event, { room: v.room.id, authority: v.pubkeys.authority })
    expect(read?.state).toMatchObject({ seq: 0, name: 'Home', epoch: 1, readers: [v.pubkeys.guardian] })
    expect(readState(v.state.event, { room: v.room.id, authority: v.pubkeys.guardian })).toBeNull()
  })

  it('decodes the KithMoot envelope-1 message with its kinclude payload', () => {
    const msg = decodeMessage(v.message.event as Event, {
      roomId: v.room.id, stream: { id: v.message.stream.id, key: hexToBytes(v.message.stream.key) }, now: T,
    })
    expect(msg).toMatchObject({
      participant: v.pubkeys.child, device: v.derived.roomDevicePubkey, name: 'Sam', text: 'Mum read this conversation.',
      kinclude: { t: 'read', child: v.pubkeys.child, upTo: 'f0e1d2c3b4a5968778695a4b3c2d1e0f', at: T },
    })
    expect(v.message.event.kind).toBe(1460)
    expect(v.message.event.tags).toEqual([['d', v.room.epoch1]])
  })
})
