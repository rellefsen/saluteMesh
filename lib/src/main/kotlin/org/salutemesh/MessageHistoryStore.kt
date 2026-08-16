package org.salutemesh

import java.io.File

class MessageHistoryStore(
    private val file: File,
    private val maxEntries: Int = 500,
) {
    @Synchronized
    fun load(): List<SaluteReport> {
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8)
            .mapNotNull(::parseLine)
            .asReversed()
    }

    @Synchronized
    fun append(report: SaluteReport): List<SaluteReport> {
        val current = load()
        if (current.any { it.id.equals(report.id, ignoreCase = true) }) return current
        val packet = report.rawPacket.ifBlank { SaluteCodec.encode(report.copy(inbound = false)) }
        val stored = report.copy(rawPacket = packet)
        file.parentFile?.mkdirs()
        file.appendText(toLine(stored) + "\n", Charsets.UTF_8)
        val next = listOf(stored) + current
        if (next.size <= maxEntries) return next
        val trimmed = next.take(maxEntries)
        writeAll(trimmed)
        return trimmed
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.writeText("", Charsets.UTF_8)
    }

    private fun writeAll(newestFirst: List<SaluteReport>) {
        file.parentFile?.mkdirs()
        file.writeText(
            newestFirst.asReversed().joinToString(separator = "\n", postfix = "\n", transform = ::toLine),
            Charsets.UTF_8,
        )
    }

    companion object {
        fun androidFile(filesDir: File): File = File(filesDir, "salute_history.txt")

        fun desktopFile(): File {
            val home = System.getProperty("user.home") ?: "."
            return File(home, ".saluteMesh/history.txt")
        }

        internal fun toLine(report: SaluteReport): String {
            val dir = if (report.inbound) "IN" else "OUT"
            return "$dir\t${report.rawPacket}"
        }

        internal fun parseLine(line: String): SaluteReport? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            val tab = trimmed.indexOf('\t')
            if (tab <= 0) return null
            val inbound = trimmed.substring(0, tab).equals("IN", ignoreCase = true)
            val packet = trimmed.substring(tab + 1)
            val decoded = SaluteCodec.decode(packet) ?: return null
            return decoded.copy(inbound = inbound, rawPacket = packet)
        }
    }
}
