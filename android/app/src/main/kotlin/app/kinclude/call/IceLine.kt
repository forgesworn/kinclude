package app.kinclude.call

import app.kinclude.core.IceServer

/** A call-server line from settings: a URL, then optionally a TURN username and password. */
fun parseIceLine(line: String): IceServer {
    val parts = line.trim().split(Regex("\\s+"))
    return IceServer(parts[0], parts.getOrNull(1), parts.getOrNull(2))
}
