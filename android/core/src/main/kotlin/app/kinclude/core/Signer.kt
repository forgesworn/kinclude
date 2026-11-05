package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent

/**
 * A person's identity, as Kinclude needs it: the Kotlin twin of the
 * TypeScript `KincludeSigner`.
 *
 * [signEvent] signs statements and room credentials. The NIP-44 pair is
 * optional because My Signet refuses it to a dependant below full autonomy,
 * and nothing a child does needs it ([canSeal] is false for a child).
 * On a phone, an implementation talks to My Signet over NIP-55 (same device)
 * or NIP-46; [LocalSigner] keeps a key in the process, for tests and trying
 * Kinclude out.
 */
interface KincludeSigner {
    val pubkey: String
    val canSeal: Boolean
    suspend fun signEvent(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent
    suspend fun nip44Encrypt(peer: String, plaintext: String): String
    suspend fun nip44Decrypt(peer: String, ciphertext: String): String
}

class SealRefused : IllegalStateException("This identity cannot seal or open anything: it needs NIP-44.")

/** A key held in this process. [dependant] makes it refuse NIP-44, as My
 *  Signet does for a child, so nothing on a child's path can come to need it. */
class LocalSigner(private val secretKey: ByteArray, private val dependant: Boolean = false) : KincludeSigner {
    init { require(secretKey.size == 32) }
    override val pubkey: String = Schnorr.publicKeyHex(secretKey)
    override val canSeal: Boolean get() = !dependant

    override suspend fun signEvent(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent =
        Events.sign(secretKey, kind, createdAt, tags, content)

    override suspend fun nip44Encrypt(peer: String, plaintext: String): String {
        if (dependant) throw SealRefused()
        return Nip44.encrypt(plaintext, Nip44.conversationKey(secretKey, peer.hexToBytes()))
    }

    override suspend fun nip44Decrypt(peer: String, ciphertext: String): String {
        if (dependant) throw SealRefused()
        return Nip44.decrypt(ciphertext, Nip44.conversationKey(secretKey, peer.hexToBytes()))
    }
}
