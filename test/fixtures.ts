import { generateSecretKey, type Event } from 'nostr-tools/pure'
import { localSigner, dependantSigner, type KincludeSigner } from '../src/signer.js'
import {
  approvalUnsigned,
  dependantOfUnsigned,
  guardianOfUnsigned,
  tierDigest,
  tierUnsigned,
  APPROVAL_TTL_SECONDS,
  TIER_GENESIS,
  type Tier,
} from '../src/statements.js'

export const NOW = 1_800_000_000

export interface Person {
  name: string
  sk: Uint8Array
  pk: string
  signer: KincludeSigner
}

export function adult(name: string): Person {
  const sk = generateSecretKey()
  const signer = localSigner(sk)
  return { name, sk, pk: signer.pubkey, signer }
}

/** A child signs as My Signet lets a dependant: events only, no NIP-44. */
export function kid(name: string): Person {
  const sk = generateSecretKey()
  const signer = dependantSigner(sk)
  return { name, sk, pk: signer.pubkey, signer }
}

export async function linkEvents(guardian: Person, child: Person, at = NOW): Promise<Event[]> {
  return [
    await guardian.signer.signEvent(guardianOfUnsigned(child.pk, at)),
    await child.signer.signEvent(dependantOfUnsigned(guardian.pk, at)),
  ]
}

export async function tierEvents(child: Person, guardian: Person, tier: Tier, seq = 0, prev = TIER_GENESIS, at = NOW) {
  const s = { child: child.pk, tier, seq, prev }
  return {
    digest: tierDigest(s),
    events: [
      await child.signer.signEvent(tierUnsigned(s, at, child.name)),
      await guardian.signer.signEvent(tierUnsigned(s, at, child.name)),
    ],
  }
}

export async function approvalPair(opts: {
  room: string
  invitee: Person | string
  child: Person
  guardian: Person
  role?: 'adult' | 'child'
  guardians?: string[]
  at?: number
}): Promise<Event[]> {
  const at = opts.at ?? NOW
  const invitee = typeof opts.invitee === 'string' ? opts.invitee : opts.invitee.pk
  const a = {
    room: opts.room,
    invitee,
    child: opts.child.pk,
    role: opts.role ?? 'adult',
    guardians: opts.guardians ?? [],
    expires: at + APPROVAL_TTL_SECONDS,
  }
  return [
    await opts.child.signer.signEvent(approvalUnsigned(a, at)),
    await opts.guardian.signer.signEvent(approvalUnsigned(a, at)),
  ]
}
