package org.salutemesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MessageHistoryStoreTest {
    @Test
    fun persistsNewestFirstAndSkipsDuplicateIds() {
        val file = Files.createTempFile("salute-history", ".txt").toFile()
        file.deleteOnExit()
        val store = MessageHistoryStore(file, maxEntries = 10)
        val first = sample("AAAA1111", inbound = false)
        val second = sample("BBBB2222", inbound = true)
        store.append(first)
        store.append(second)
        store.append(first.copy(size = "changed"))
        val loaded = store.load()
        assertEquals(listOf("BBBB2222", "AAAA1111"), loaded.map { it.id })
        assertTrue(loaded[0].inbound)
        assertEquals("4 pax", loaded[1].size)
    }

    @Test
    fun trimsToMaxEntries() {
        val file = Files.createTempFile("salute-history", ".txt").toFile()
        file.deleteOnExit()
        val store = MessageHistoryStore(file, maxEntries = 3)
        repeat(5) { index ->
            store.append(sample("%08X".format(index), inbound = index % 2 == 0))
        }
        assertEquals(3, store.load().size)
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    private fun sample(id: String, inbound: Boolean) = SaluteReport(
        id = id,
        callsign = "N1",
        epochSeconds = 1_724_000_000,
        size = "4 pax",
        activity = "checkpoint",
        location = "Oak and 3rd",
        unit = "SOUTH",
        equipment = "1 truck",
        inbound = inbound,
    )
}
