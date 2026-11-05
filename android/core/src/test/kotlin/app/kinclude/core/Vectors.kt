package app.kinclude.core

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** The TypeScript library's wire vectors (`vectors/kinclude-vectors.json`):
 *  what the two implementations are checked against each other with. */
object Vectors {
    val json: JsonObject by lazy {
        val path = System.getProperty("kinclude.vectors") ?: "../../vectors/kinclude-vectors.json"
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    fun obj(vararg path: String): JsonObject = path.fold(json) { o, k -> o.getValue(k).jsonObject }
    fun str(vararg path: String): String {
        val parent = obj(*path.dropLast(1).toTypedArray())
        return parent.getValue(path.last()).jsonPrimitive.content
    }
    fun event(vararg path: String): NostrEvent = NostrEvent.fromJson(obj(*path))
}
