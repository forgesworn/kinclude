import { localIdentity, type ParticipantIdentity } from '@forgesworn/fold-kit'
import { getPublicKey } from 'nostr-tools/pure'
import { nip44 } from 'nostr-tools'
import { hexToBytes } from '@noble/hashes/utils'

/**
 * A person's identity, as Kinclude needs it.
 *
 * `signEvent` is fold-kit's `ParticipantIdentity`: it signs device
 * credentials and every statement in this library. The NIP-44 pair is
 * optional because a My Signet dependant below full autonomy cannot use it:
 * My Signet refuses `nip44_*` on dependant routes. Nothing a child does in
 * Kinclude needs it. A guardian needs `nip44Decrypt` to receive invitations
 * and to open an emergency escrow; an adult needs both to receive and send
 * invitations.
 *
 * signet-login's `SignetSigner` and a NIP-07 extension both fit this shape
 * (NIP-07 spells the pair `nip44.encrypt` / `nip44.decrypt`; adapt it).
 */
export interface KincludeSigner extends ParticipantIdentity {
  nip44Encrypt?(peer: string, plaintext: string): Promise<string>
  nip44Decrypt?(peer: string, ciphertext: string): Promise<string>
}

/** A signer over a key held in this process. For tests, development and
 *  the `kinclude` CLI; a real family signs through My Signet. */
export function localSigner(sk: Uint8Array): KincludeSigner {
  const identity = localIdentity(sk)
  const conversation = (peer: string) => nip44.v2.utils.getConversationKey(sk, peer)
  return {
    pubkey: identity.pubkey,
    signEvent: identity.signEvent,
    async nip44Encrypt(peer, plaintext) {
      return nip44.v2.encrypt(plaintext, conversation(peer))
    },
    async nip44Decrypt(peer, ciphertext) {
      return nip44.v2.decrypt(ciphertext, conversation(peer))
    },
  }
}

/** A signer that refuses NIP-44, as My Signet does for a dependant below
 *  full autonomy. What every test of a child's device uses, so nothing in
 *  the child's path can quietly come to depend on it. */
export function dependantSigner(sk: Uint8Array): KincludeSigner {
  const identity = localIdentity(sk)
  return { pubkey: identity.pubkey, signEvent: identity.signEvent }
}

export function pubkeyOf(sk: Uint8Array | string): string {
  return getPublicKey(typeof sk === 'string' ? hexToBytes(sk) : sk)
}
