package org.salutemesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaluteFragmentTest {
    @Test
    fun smallReportStaysOnePacket() {
        val report = sample(activity = "checkpoint")
        val packets = SaluteCodec.encodePackets(report)
        assertEquals(1, packets.size)
        assertTrue(packets[0].startsWith("SU:1:"))
        assertTrue(packets[0].toByteArray().size <= SaluteCodec.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun largeReportSplitsAndReassemblesOutOfOrder() {
        val report = sample(
            activity = "x".repeat(180),
            location = "y".repeat(180),
            equipment = "z".repeat(180),
        )
        val packets = SaluteCodec.encodePackets(report)
        assertTrue(packets.size >= 2)
        packets.forEach { packet ->
            assertTrue(packet.startsWith("SU:F:"))
            assertTrue(packet.toByteArray().size <= SaluteCodec.MAX_PAYLOAD_BYTES)
        }
        val assembler = FragmentAssembler()
        assertNull(assembler.offer(packets.last()))
        packets.dropLast(1).drop(1).forEach { assertNull(assembler.offer(it)) }
        val decoded = assembler.offer(packets.first())!!
        assertEquals(report.activity, decoded.activity)
        assertEquals(report.location, decoded.location)
        assertEquals(report.equipment, decoded.equipment)
        assertEquals(report.callsign, decoded.callsign)
    }

    @Test
    fun utf8DoesNotSplitMidCharacter() {
        val chunks = SaluteCodec.splitUtf8("aa😀bb", 4)
        assertEquals(listOf("aa", "😀", "bb"), chunks)
    }

    @Test
    fun incompleteFragmentsExpire() {
        var now = 1_000L
        val assembler = FragmentAssembler(nowMs = { now }, ttlMs = 50)
        val packets = SaluteCodec.encodePackets(
            sample(activity = "n".repeat(400), location = "n".repeat(400)),
        )
        assertTrue(packets.size >= 2)
        assertNull(assembler.offer(packets.first()))
        assertEquals(1, assembler.pendingCount())
        now = 1_100L
        assertEquals(0, assembler.pendingCount())
    }

    private fun sample(
        activity: String = "checkpoint",
        location: String = "Oak and 3rd",
        equipment: String = "1 truck",
    ) = SaluteReport(
        id = "ABC123EF",
        callsign = "N1",
        epochSeconds = 1_724_000_000,
        size = "4 pax",
        activity = activity,
        location = location,
        unit = "SOUTH",
        equipment = equipment,
    )
}
