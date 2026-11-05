package app.kinclude.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.kinclude.core.HouseholdData
import app.kinclude.core.HouseholdJson
import app.kinclude.core.HouseholdStore
import app.kinclude.core.KincludeSigner
import app.kinclude.core.LocalSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Files only this app can read, encrypted with an AES key that never leaves
 * the Android keystore. Holds the household (room secrets, a room
 * authority's key) and, for a phone set up without My Signet, its identity
 * key.
 */
class SealedFile(private val file: File) {
    private val alias = "kinclude.storage.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun read(): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size < 13) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun write(text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun delete() { file.delete() }
}

class FileHouseholdStore(context: Context) : HouseholdStore {
    private val sealed = SealedFile(File(context.filesDir, "household.sealed"))
    override suspend fun load(): HouseholdData? = withContext(Dispatchers.IO) { sealed.read()?.let(HouseholdJson::read) }
    override suspend fun save(data: HouseholdData) = withContext(Dispatchers.IO) { sealed.write(HouseholdJson.write(data)) }
    fun delete() = sealed.delete()
}

/**
 * Who this phone is. With My Signet the identity key is never here; until
 * that sign-in is wired, a phone can keep a key of its own ("trying it
 * out"), sealed as above. A child's key refuses NIP-44, as My Signet does
 * for a dependant.
 */
class IdentityStore(context: Context) {
    private val sealed = SealedFile(File(context.filesDir, "identity.sealed"))

    /** A key kept here ([secretKey]), or a signer app holding it ([signerPackage] and [pubkey]). */
    data class Saved(val child: Boolean, val name: String, val secretKey: String? = null, val signerPackage: String? = null, val pubkey: String? = null)

    fun load(): Saved? {
        val lines = sealed.read()?.split('\n') ?: return null
        return when {
            lines.size == 3 -> Saved(lines[0] == "child", lines[1], secretKey = lines[2])
            lines.size == 5 && lines[2] == "nip55" -> Saved(lines[0] == "child", lines[1], signerPackage = lines[3], pubkey = lines[4])
            else -> null
        }
    }

    private fun clean(name: String) = name.replace('\n', ' ').trim().ifEmpty { "Me" }

    fun create(child: Boolean, name: String): Saved {
        val saved = Saved(child, clean(name), secretKey = Entropy.bytes(32).toHex())
        sealed.write(listOf(if (child) "child" else "adult", saved.name, saved.secretKey!!).joinToString("\n"))
        return saved
    }

    fun createWithSigner(name: String, packageName: String, pubkey: String): Saved {
        val saved = Saved(false, clean(name), signerPackage = packageName, pubkey = pubkey)
        sealed.write(listOf("adult", saved.name, "nip55", packageName, pubkey).joinToString("\n"))
        return saved
    }

    fun signer(saved: Saved, context: Context, bridge: () -> SignerBridge?): KincludeSigner = when {
        saved.secretKey != null -> LocalSigner(saved.secretKey.hexToBytes(), dependant = saved.child)
        else -> Nip55Signer(saved.pubkey!!, saved.signerPackage!!, context, bridge)
    }

    fun delete() = sealed.delete()
}
