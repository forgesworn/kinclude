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

    data class Saved(val child: Boolean, val name: String, val secretKey: String)

    fun load(): Saved? = sealed.read()?.split('\n')?.takeIf { it.size == 3 }?.let { Saved(it[0] == "child", it[1], it[2]) }

    fun create(child: Boolean, name: String): Saved {
        val saved = Saved(child, name.replace('\n', ' ').trim().ifEmpty { "Me" }, Entropy.bytes(32).toHex())
        sealed.write(listOf(if (child) "child" else "adult", saved.name, saved.secretKey).joinToString("\n"))
        return saved
    }

    fun signer(saved: Saved): KincludeSigner = LocalSigner(saved.secretKey.hexToBytes(), dependant = saved.child)

    fun delete() = sealed.delete()
}
