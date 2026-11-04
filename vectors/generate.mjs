#!/usr/bin/env node
// Writes vectors/kinclude-vectors.json from the built library (`npm run
// build:lib` first). Signatures and NIP-44 nonces are random, so the events
// are generated once and committed; vectors.test.ts checks that a reader
// accepts each one and that every derived value matches exactly.
import { writeFileSync } from 'node:fs'
import { hexToBytes, bytesToHex } from '@noble/hashes/utils'
import { createDeviceCredential, deriveChannel, deriveRoom, deriveEpoch } from '@forgesworn/fold-kit'
import { getPublicKey } from 'nostr-tools/pure'
import {
  localSigner, dependantSigner, guardianOfUnsigned, dependantOfUnsigned, unlinkUnsigned, approvalUnsigned,
  tierUnsigned, tierDigest, emergencyUnsigned, signState, encodeMessage, roomDeviceKey, inboxKey,
  encodePairingCode, LOBBY_CHANNEL, TIER_GENESIS,
} from '../dist/src/index.js'
import { hkdf } from '@noble/hashes/hkdf'
import { sha256 } from '@noble/hashes/sha2'

const T = 1_800_000_000
const keys = {
  guardian: '1111111111111111111111111111111111111111111111111111111111111111',
  child: '2222222222222222222222222222222222222222222222222222222222222222',
  nan: '3333333333333333333333333333333333333333333333333333333333333333',
  authority: '4444444444444444444444444444444444444444444444444444444444444444',
  phoneSecret: '5555555555555555555555555555555555555555555555555555555555555555',
  roomSecret: '6666666666666666666666666666666666666666666666666666666666666666',
  epoch1Secret: '7777777777777777777777777777777777777777777777777777777777777777',
  pairingSecret: '88888888888888888888888888888888',
}
const guardian = localSigner(hexToBytes(keys.guardian))
const child = dependantSigner(hexToBytes(keys.child))
const nan = getPublicKey(hexToBytes(keys.nan))
const { roomId } = deriveRoom(hexToBytes(keys.roomSecret))
const lobby = deriveChannel(roomId, deriveRoom(hexToBytes(keys.roomSecret)).roomKey, LOBBY_CHANNEL)

const genesis = { child: child.pubkey, tier: 2, seq: 0, prev: TIER_GENESIS }
const genesisDigest = tierDigest(genesis)
const up = { child: child.pubkey, tier: 3, seq: 1, prev: genesisDigest }
const approval = { room: roomId, invitee: nan, child: child.pubkey, role: 'adult', guardians: [], expires: T + 604800 }

const deviceSk = roomDeviceKey(hexToBytes(keys.phoneSecret), roomId)
const credential = await createDeviceCredential({ identity: child, devicePubkey: getPublicKey(deviceSk), roomId, expiresAt: T + 365 * 86400, now: () => T })
const epoch1 = deriveEpoch({ epoch: 1, secret: hexToBytes(keys.epoch1Secret) })

const vectors = {
  description: 'Kinclude wire vectors. Events are signed once and committed; derived values are exact.',
  keys,
  pubkeys: { guardian: guardian.pubkey, child: child.pubkey, nan, authority: getPublicKey(hexToBytes(keys.authority)) },
  room: { id: roomId, lobby: lobby.id, epoch1: epoch1.id },
  derived: {
    roomDevicePubkey: getPublicKey(deviceSk),
    inboxPubkey: getPublicKey(inboxKey(hexToBytes(keys.phoneSecret))),
    pairingKey: bytesToHex(hkdf(sha256, hexToBytes(keys.pairingSecret), undefined, 'kinclude/v1/pairing-key', 32)),
    pairingTag: bytesToHex(hkdf(sha256, hexToBytes(keys.pairingSecret), undefined, 'kinclude/v1/pairing-tag', 32)),
    pairingCode: encodePairingCode({ guardian: guardian.pubkey, secret: keys.pairingSecret, tier: 2, childName: 'Sam', guardianName: 'Mum' }),
  },
  links: {
    guardianOf: await guardian.signEvent(guardianOfUnsigned(child.pubkey, T)),
    dependantOf: await child.signEvent(dependantOfUnsigned(guardian.pubkey, T)),
    unlink: await child.signEvent(unlinkUnsigned(guardian.pubkey, T + 10)),
  },
  approval: {
    terms: approval,
    byChild: await child.signEvent(approvalUnsigned(approval, T, 'Nan')),
    byGuardian: await guardian.signEvent(approvalUnsigned(approval, T, 'Nan')),
  },
  tier: {
    genesis: { statement: genesis, digest: genesisDigest },
    genesisByChild: await child.signEvent(tierUnsigned(genesis, T, 'Sam')),
    genesisByGuardian: await guardian.signEvent(tierUnsigned(genesis, T, 'Sam')),
    up: { statement: up, digest: tierDigest(up) },
    upByChild: await child.signEvent(tierUnsigned(up, T + 60, 'Sam')),
    upByGuardian: await guardian.signEvent(tierUnsigned(up, T + 60, 'Sam')),
  },
  emergency: {
    notice: { room: roomId, child: child.pubkey, epoch: 1, reason: 'Your phone has been off since 9' },
    event: await guardian.signEvent(emergencyUnsigned({ room: roomId, child: child.pubkey, epoch: 1, reason: 'Your phone has been off since 9' }, T)),
  },
  state: {
    event: signState(hexToBytes(keys.authority), {
      v: 1, room: roomId, seq: 0, prev: null, name: 'Home', epoch: 1,
      members: [{ pk: guardian.pubkey, role: 'adult', by: guardian.pubkey, at: T, approvals: [], links: [], name: 'Mum' }],
      readers: [guardian.pubkey], removed: [],
    }, T),
  },
  message: {
    stream: { id: epoch1.id, key: bytesToHex(epoch1.key) },
    credential,
    event: encodeMessage({
      id: 'a1b2c3d4e5f60718293a4b5c6d7e8f90', participant: child.pubkey, device: getPublicKey(deviceSk), credential,
      name: 'Sam', text: 'Mum read this conversation.', sentAt: T,
      kinclude: { t: 'read', child: child.pubkey, upTo: 'f0e1d2c3b4a5968778695a4b3c2d1e0f', at: T },
    }, { stream: { id: epoch1.id, key: epoch1.key }, deviceSk }),
  },
}

writeFileSync(new URL('./kinclude-vectors.json', import.meta.url), JSON.stringify(vectors, null, 2) + '\n')
console.log('wrote vectors/kinclude-vectors.json')
