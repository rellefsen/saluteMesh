package org.salutemesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaluteCodecTest {
    @Test
    fun roundTrip() {
        val report = SaluteReport(
            id = "ABC123EF",
            callsign = "N1",
            epochSeconds = 1_724_000_000,
            size = "4 pax",
            activity = "checkpoint",
            location = "Oak and 3rd",
            unit = "SOUTH",
            equipment = "1 truck",
        )
        val packet = SaluteCodec.encode(report)
        assertTrue(packet.startsWith("SU:1:ABC123EF:N1:"))
        assertTrue(packet.toByteArray().size <= SaluteCodec.MAX_PAYLOAD_BYTES)
        val decoded = SaluteCodec.decode(packet)!!
        assertEquals("4 pax", decoded.size)
        assertEquals("checkpoint", decoded.activity)
        assertEquals("Oak and 3rd", decoded.location)
        assertEquals("SOUTH", decoded.unit)
        assertEquals("1 truck", decoded.equipment)
        assertEquals(ReportKind.SALUTE, decoded.kind)
    }

    @Test
    fun saltRoundTripOmitsUnitAndEquipment() {
        val report = SaluteReport(
            id = "SALT0001",
            callsign = "N1",
            epochSeconds = 1_724_000_000,
            size = "2 pax",
            activity = "moving",
            location = "bridge",
            kind = ReportKind.SALT,
        )
        val packet = SaluteCodec.encode(report)
        assertTrue(packet.startsWith("SU:S:SALT0001:N1:"))
        assertTrue(!packet.contains("SOUTH"))
        val decoded = SaluteCodec.decode(packet)!!
        assertEquals(ReportKind.SALT, decoded.kind)
        assertEquals("2 pax", decoded.size)
        assertEquals("moving", decoded.activity)
        assertEquals("bridge", decoded.location)
        assertEquals("", decoded.unit)
        assertEquals("", decoded.equipment)
    }

    @Test
    fun ignoresForeignPackets() {
        assertNull(SaluteCodec.decode("NS:SOUTH01:H014:Y"))
        assertNull(SaluteCodec.decode("hello mesh"))
    }

    @Test
    fun timeRoundTripLocalDisplay() {
        val zone = java.time.ZoneId.of("UTC")
        val epoch = 1_724_000_000L
        val display = SaluteTime.formatEpoch(epoch, zone)
        assertEquals("2024-08-18 16:53:20", display)
        assertEquals(epoch, SaluteTime.parseToEpochSeconds(display, zone))
        assertEquals(epoch, SaluteTime.parseToEpochSeconds(epoch.toString(), zone))
        assertNull(SaluteTime.parseToEpochSeconds("not a time", zone))
    }
}
