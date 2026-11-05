package app.kinclude

import app.kinclude.call.parseIceLine
import app.kinclude.core.IceServer
import org.junit.Assert.assertEquals
import org.junit.Test

class IceLineTest {
    @Test fun readsStunAndTurnLines() {
        assertEquals(IceServer("stun:stun.cloudflare.com:3478"), parseIceLine("  stun:stun.cloudflare.com:3478 "))
        assertEquals(IceServer("turn:turn.example.org:3478", "sam", "s3cret"), parseIceLine("turn:turn.example.org:3478 sam s3cret"))
    }
}
