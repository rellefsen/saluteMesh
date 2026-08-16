package org.salutemesh

class FragmentAssembler(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 180_000,
) {
    private data class Pending(
        val count: Int,
        val chunks: Array<String?>,
        val startedMs: Long,
    )

    private val pending = LinkedHashMap<String, Pending>()

    @Synchronized
    fun offer(text: String): SaluteReport? {
        expire()
        val trimmed = text.trim()
        SaluteCodec.decode(trimmed)?.let { return it }
        val fragment = SaluteCodec.parseFragment(trimmed) ?: return null
        val existing = pending[fragment.id]
        val bucket = if (existing == null || existing.count != fragment.count) {
            Pending(
                count = fragment.count,
                chunks = arrayOfNulls(fragment.count),
                startedMs = nowMs(),
            ).also { pending[fragment.id] = it }
        } else {
            existing
        }
        bucket.chunks[fragment.index - 1] = fragment.chunk
        if (bucket.chunks.any { it == null }) return null
        pending.remove(fragment.id)
        val body = bucket.chunks.joinToString(separator = "") { it.orEmpty() }
        return SaluteCodec.decode(body)
    }

    @Synchronized
    fun pendingCount(): Int {
        expire()
        return pending.size
    }

    private fun expire() {
        val cutoff = nowMs() - ttlMs
        val stale = pending.entries.filter { it.value.startedMs < cutoff }.map { it.key }
        stale.forEach { pending.remove(it) }
    }
}
