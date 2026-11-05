package app.kinclude.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.kinclude.core.KincludeSigner
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * My Signet, or any NIP-55 signer app, on this phone: the identity key stays
 * in the signer, and every signature and every NIP-44 operation is a request
 * to it. Adapted from KithMoot's `Nip55Signer` (the silent content-provider
 * path first, then the signer's own screen).
 */
fun interface SignerBridge {
    /** Start the signer's activity and wait for its answer. */
    suspend fun request(intent: Intent): Intent?
}

class SignerException(message: String) : IllegalStateException(message)

data class InstalledSigner(val packageName: String, val label: String)

private const val SCHEME = "nostrsigner"
private const val SILENT_TIMEOUT_MS = 10_000L

fun installedSigners(context: Context): List<InstalledSigner> {
    val manager = context.packageManager
    return manager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:")), PackageManager.MATCH_DEFAULT_ONLY)
        .map { InstalledSigner(it.activityInfo.packageName, it.loadLabel(manager).toString().ifBlank { it.activityInfo.packageName }) }
        .distinctBy { it.packageName }
        .sortedBy { if (it.label.contains("Signet", ignoreCase = true)) "0" else it.label.lowercase() }
}

private fun permissions(): String = JsonArray(
    listOf(30078, 20460, 13).map { kind -> buildJsonObject { put("type", "sign_event"); put("kind", kind) } } +
        listOf(buildJsonObject { put("type", "nip44_encrypt") }, buildJsonObject { put("type", "nip44_decrypt") }),
).toString()

private val HEX64 = Regex("^[0-9a-f]{64}$")

private fun publicKeyFrom(result: String?): String? {
    val r = result?.trim()?.lowercase() ?: return null
    if (HEX64.matches(r)) return r
    return app.kinclude.core.Npub.decode(r)
}

class Nip55Signer(
    override val pubkey: String,
    val packageName: String,
    private val context: Context,
    private val bridge: () -> SignerBridge?,
) : KincludeSigner {
    override val canSeal: Boolean = true

    override suspend fun signEvent(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
        val unsigned = buildJsonObject {
            put("id", Events.eventId(pubkey, createdAt, kind, tags, content))
            put("pubkey", pubkey); put("created_at", createdAt); put("kind", kind)
            put("tags", JsonArray(tags.map { t -> JsonArray(t.map(::JsonPrimitive)) })); put("content", content)
        }.toString()
        val (result, event) = provider("sign_event", unsigned, null) ?: ask("sign_event", unsigned, null).let {
            (it.getStringExtra("result") ?: it.getStringExtra("signature")) to it.getStringExtra("event")
        }
        val signed = event?.let { runCatching { NostrEvent.fromJson(Json.parseToJsonElement(it)) }.getOrNull() }
            ?: result?.trim()?.takeIf { Regex("^[0-9a-fA-F]{128}$").matches(it) }?.let { sig ->
                NostrEvent.fromJson(buildJsonObject {
                    for ((k, v) in Json.parseToJsonElement(unsigned) as JsonObject) put(k, v)
                    put("sig", sig.lowercase())
                })
            }
            ?: throw SignerException("${label()} did not return a signed event.")
        if (signed.pubkey != pubkey) throw SignerException("${label()} answered for a different key.")
        if (signed.kind != kind || signed.createdAt != createdAt || signed.tags != tags || signed.content != content) throw SignerException("${label()} changed the event before signing it.")
        if (!Events.verify(signed)) throw SignerException("The signature from ${label()} does not verify.")
        return signed
    }

    override suspend fun nip44Encrypt(peer: String, plaintext: String) = crypt("nip44_encrypt", peer, plaintext)
    override suspend fun nip44Decrypt(peer: String, ciphertext: String) = crypt("nip44_decrypt", peer, ciphertext)

    private suspend fun crypt(type: String, peer: String, payload: String): String {
        provider(type, payload, peer)?.first?.takeIf { it.isNotEmpty() }?.let { return it }
        return ask(type, payload, peer).getStringExtra("result")?.takeIf { it.isNotEmpty() } ?: throw SignerException("${label()} returned nothing.")
    }

    private suspend fun ask(type: String, payload: String, peer: String?): Intent {
        val b = bridge() ?: throw SignerException("Open Kinclude to use ${label()}.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme(SCHEME).opaquePart(payload).build()).apply {
            `package` = packageName
            putExtra("type", type); putExtra("id", java.util.UUID.randomUUID().toString()); putExtra("current_user", pubkey)
            if (peer != null) putExtra("pubkey", peer)
        }
        val answer = b.request(intent) ?: throw SignerException("Cancelled in ${label()}.")
        if (answer.getBooleanExtra("rejected", false)) throw SignerException("${label()} declined.")
        return answer
    }

    /** The silent path: the signer's content provider, when it has already been allowed. */
    private suspend fun provider(type: String, payload: String, peer: String?): Pair<String?, String?>? = withTimeoutOrNull(SILENT_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(Uri.parse("content://$packageName.${type.uppercase()}"), arrayOf(payload, peer ?: "", pubkey), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    if (c.getColumnIndex("rejected").takeIf { it >= 0 }?.let(c::getString).equals("true", ignoreCase = true)) throw SignerException("${label()} declined.")
                    val result = (c.getColumnIndex("result").takeIf { it >= 0 } ?: c.getColumnIndex("signature").takeIf { it >= 0 })?.let(c::getString)
                    val event = c.getColumnIndex("event").takeIf { it >= 0 }?.let(c::getString)
                    if (result == null && event == null) null else result to event
                }
            }.getOrElse { if (it is SignerException) throw it else null }
        }
    }

    private fun label(): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault("Your signer")

    companion object {
        /** Ask the signer app which key it holds; the person picks the account there. */
        suspend fun connect(context: Context, bridge: SignerBridge, packageName: String, current: () -> SignerBridge?): Nip55Signer {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:")).apply {
                `package` = packageName
                putExtra("type", "get_public_key")
                putExtra("permissions", permissions())
            }
            val answer = bridge.request(intent) ?: throw SignerException("Sign-in was cancelled.")
            if (answer.getBooleanExtra("rejected", false)) throw SignerException("The signer app declined.")
            val pubkey = publicKeyFrom(answer.getStringExtra("result") ?: answer.getStringExtra("signature"))
                ?: throw SignerException("The signer app did not return a key.")
            val chosen = answer.getStringExtra("package")?.takeIf { it.isNotBlank() } ?: packageName
            if (chosen != packageName) throw SignerException("The answer named a different app.")
            return Nip55Signer(pubkey, packageName, context, current)
        }
    }
}
