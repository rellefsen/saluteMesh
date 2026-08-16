package org.salutemesh

import java.security.SecureRandom

data class SaluteFragment(
    val id: String,
    val index: Int,
    val count: Int,
    val chunk: String,
)

object SaluteCodec {
    const val PREFIX = "SU"
    const val VERSION = "1"
    const val SALT_VERSION = "S"
    const val FRAGMENT = "F"
    const val MAX_PAYLOAD_BYTES = 233
    const val MAX_FRAGMENTS = 9
    const val PACKET_GAP_MS = 2_000L
    const val DEFAULT_CHANNEL = "salute"

    private val rng = SecureRandom()

    fun newId(): String = buildString(8) {
        repeat(8) { append("0123456789ABCDEF"[rng.nextInt(16)]) }
    }

    fun logicalPacket(report: SaluteReport): String {
        val id = report.id.trim().uppercase().ifBlank { newId() }
        val callsign = clip(sanitize(report.callsign), 12)
        val epoch = report.epochSeconds.toString()
        return if (report.kind == ReportKind.SALT) {
            listOf(
                PREFIX,
                SALT_VERSION,
                id,
                callsign,
                epoch,
                sanitize(report.size),
                sanitize(report.activity),
                sanitize(report.location),
            ).joinToString(":")
        } else {
            listOf(
                PREFIX,
                VERSION,
                id,
                callsign,
                epoch,
                sanitize(report.size),
                sanitize(report.activity),
                sanitize(report.location),
                sanitize(report.unit),
                sanitize(report.equipment),
            ).joinToString(":")
        }
    }

    fun encode(report: SaluteReport): String = logicalPacket(report)

    fun sizeSummary(report: SaluteReport): String {
        return try {
            val body = logicalPacket(report)
            val bytes = body.toByteArray(Charsets.UTF_8).size
            val packets = encodePackets(report).size
            if (packets == 1) {
                "$bytes / $MAX_PAYLOAD_BYTES bytes · 1 packet"
            } else {
                "$bytes bytes · $packets packets (${PACKET_GAP_MS / 1000}s apart)"
            }
        } catch (exc: Exception) {
            exc.message ?: "Message too large"
        }
    }

    fun encodePackets(report: SaluteReport): List<String> {
        val body = logicalPacket(report)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        if (bodyBytes.size <= MAX_PAYLOAD_BYTES) return listOf(body)
        val id = body.split(":").getOrNull(2)?.uppercase().orEmpty().ifBlank { newId() }
        val headerBytes = fragmentHeader(id, 1, 1).toByteArray(Charsets.UTF_8).size
        val budget = MAX_PAYLOAD_BYTES - headerBytes
        if (budget < 16) {
            throw IllegalArgumentException("Fragment header leaves no room for payload.")
        }
        val chunks = splitUtf8(body, budget)
        if (chunks.size > MAX_FRAGMENTS) {
            throw IllegalArgumentException(
                "Report is ${bodyBytes.size} bytes and needs ${chunks.size} packets " +
                    "(max $MAX_FRAGMENTS). Shorten a field.",
            )
        }
        return chunks.mapIndexed { offset, chunk ->
            val packet = fragmentHeader(id, offset + 1, chunks.size) + chunk
            val size = packet.toByteArray(Charsets.UTF_8).size
            if (size > MAX_PAYLOAD_BYTES) {
                throw IllegalArgumentException("Fragment ${offset + 1} is $size bytes (limit $MAX_PAYLOAD_BYTES).")
            }
            packet
        }
    }

    fun decode(text: String): SaluteReport? {
        val raw = text.trim()
        val parts = raw.split(":")
        if (parts.isEmpty() || parts[0] != PREFIX) return null
        return when (parts.getOrNull(1)) {
            VERSION -> decodeSalute(parts, raw)
            SALT_VERSION -> decodeSalt(parts, raw)
            else -> null
        }
    }

    private fun decodeSalute(parts: List<String>, raw: String): SaluteReport? {
        if (parts.size < 10) return null
        val epoch = parts[4].toLongOrNull() ?: return null
        return SaluteReport(
            id = parts[2],
            callsign = parts[3],
            epochSeconds = epoch,
            size = parts[5],
            activity = parts[6],
            location = parts[7],
            unit = parts[8],
            equipment = parts.drop(9).joinToString(":"),
            kind = ReportKind.SALUTE,
            inbound = true,
            rawPacket = raw,
        )
    }

    private fun decodeSalt(parts: List<String>, raw: String): SaluteReport? {
        if (parts.size < 8) return null
        val epoch = parts[4].toLongOrNull() ?: return null
        return SaluteReport(
            id = parts[2],
            callsign = parts[3],
            epochSeconds = epoch,
            size = parts[5],
            activity = parts[6],
            location = parts.drop(7).joinToString(":"),
            kind = ReportKind.SALT,
            inbound = true,
            rawPacket = raw,
        )
    }

    fun parseFragment(text: String): SaluteFragment? {
        val parts = text.trim().split(":", limit = 6)
        if (parts.size < 6) return null
        if (parts[0] != PREFIX) return null
        if (parts[1] != FRAGMENT) return null
        val index = parts[3].toIntOrNull() ?: return null
        val count = parts[4].toIntOrNull() ?: return null
        if (index < 1 || count < 1 || index > count || count > MAX_FRAGMENTS) return null
        val id = parts[2].trim().uppercase()
        if (id.isEmpty()) return null
        return SaluteFragment(id = id, index = index, count = count, chunk = parts[5])
    }

    fun isSalutePacket(text: String): Boolean =
        decode(text) != null || parseFragment(text) != null

    internal fun fragmentHeader(id: String, index: Int, count: Int): String =
        "$PREFIX:$FRAGMENT:$id:${pad2(index)}:${pad2(count)}:"

    internal fun splitUtf8(text: String, maxBytes: Int): List<String> {
        if (maxBytes < 1) throw IllegalArgumentException("maxBytes must be positive")
        val chunks = ArrayList<String>()
        val buf = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val extra = if (
                text[i].isHighSurrogate() &&
                i + 1 < text.length &&
                text[i + 1].isLowSurrogate()
            ) 2 else 1
            val piece = text.substring(i, i + extra)
            val n = piece.toByteArray(Charsets.UTF_8).size
            if (n > maxBytes) {
                throw IllegalArgumentException("A character is $n bytes and cannot fit in a mesh packet.")
            }
            if (used + n > maxBytes) {
                chunks.add(buf.toString())
                buf.setLength(0)
                used = 0
            }
            buf.append(piece)
            used += n
            i += extra
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString())
        return chunks
    }

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')

    private fun sanitize(value: String): String =
        value.trim().replace(":", " ").replace('\n', ' ').replace('\r', ' ')

    private fun clip(value: String, maxChars: Int): String =
        if (value.length <= maxChars) value else value.take(maxChars)
}
