package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.deriveRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Room state and messages written by the TypeScript library, read here. */
class WireVectorsTest {
    private val t = 1_800_000_000L

    @Test fun roomLobbyAndEpochDeriveTheSame() {
        val room = deriveRoom(Vectors.str("keys", "roomSecret").hexToBytes())
        assertEquals(Vectors.str("room", "id"), room.roomId)
        assertEquals(Vectors.str("room", "lobby"), deriveChannel(room.roomId, room.roomKey, LOBBY_CHANNEL).id)
        assertEquals(Vectors.str("room", "epoch1"), deriveEpoch(RoomEpoch(1, Vectors.str("keys", "epoch1Secret").hexToBytes())).id)
    }

    @Test fun stateReadsFromItsAuthorityOnly() {
        val e = Vectors.event("state", "event")
        val s = readState(e, Vectors.str("room", "id"), Vectors.str("pubkeys", "authority"))
        assertNotNull(s)
        assertEquals("Home", s!!.state.name)
        assertEquals(listOf(Vectors.str("pubkeys", "guardian")), s.state.readers)
        assertNull(readState(e, Vectors.str("room", "id"), Vectors.str("pubkeys", "guardian")))
        // And what Kotlin writes, Kotlin reads back the same.
        val again = signState(Vectors.str("keys", "authority").hexToBytes(), s.state, t)
        assertEquals(s.state, readState(again, Vectors.str("room", "id"), Vectors.str("pubkeys", "authority"))!!.state)
    }

    @Test fun messageWithReadReceiptDecodes() {
        val stream = Stream(Vectors.str("message", "stream", "id"), Vectors.str("message", "stream", "key").hexToBytes())
        val msg = decodeMessage(Vectors.event("message", "event"), Vectors.str("room", "id"), stream, t)!!
        assertEquals(Vectors.str("pubkeys", "child"), msg.participant)
        assertEquals("Sam", msg.name)
        assertEquals(Payload.Read(Vectors.str("pubkeys", "child"), "f0e1d2c3b4a5968778695a4b3c2d1e0f", t), msg.kinclude)
    }
}
