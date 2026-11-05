package app.kinclude.core

/** NIP-19 `npub`: how a person's key is shown and shared. Bech32 (BIP-173). */
object Npub {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        (hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

    private fun convert(data: IntArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0; var bits = 0
        val out = ArrayList<Int>()
        val max = (1 shl to) - 1
        for (v in data) {
            if (v < 0 || v ushr from != 0) return null
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) { bits -= to; out += (acc ushr bits) and max }
        }
        if (pad) { if (bits > 0) out += (acc shl (to - bits)) and max }
        else if (bits >= from || ((acc shl (to - bits)) and max) != 0) return null
        return out.toIntArray()
    }

    fun encode(hex: String): String {
        require(isPubkey(hex)) { "a pubkey is 32-byte lower-case hex" }
        val bytes = hex.chunked(2).map { it.toInt(16) }.toIntArray()
        val data = convert(bytes, 8, 5, true)!!
        val values = hrpExpand("npub") + data + IntArray(6)
        val mod = polymod(values) xor 1
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return "npub1" + (data + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** A pubkey from `npub1…` or 64-character hex; null for anything else. */
    fun decode(text: String): String? {
        val s = text.trim().lowercase()
        if (isPubkey(s)) return s
        if (!s.startsWith("npub1") || s.length != 63) return null
        val data = s.substring(5).map { CHARSET.indexOf(it).takeIf { i -> i >= 0 } ?: return null }.toIntArray()
        if (polymod(hrpExpand("npub") + data) != 1) return null
        val bytes = convert(data.copyOfRange(0, data.size - 6), 5, 8, false) ?: return null
        if (bytes.size != 32) return null
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
