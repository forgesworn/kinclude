package app.kinclude.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NpubTest {
    // NIP-19's own example.
    private val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    @Test fun encodesAndDecodesNip19sExample() {
        assertEquals(npub, Npub.encode(hex))
        assertEquals(hex, Npub.decode(npub))
        assertEquals(hex, Npub.decode(" ${npub.uppercase()} "))
        assertEquals(hex, Npub.decode(hex))
    }

    @Test fun refusesAMistypedKey() {
        assertNull(Npub.decode(npub.dropLast(1) + "7"))
        assertNull(Npub.decode("nsec1" + npub.drop(5)))
        assertNull(Npub.decode("hello"))
    }
}
