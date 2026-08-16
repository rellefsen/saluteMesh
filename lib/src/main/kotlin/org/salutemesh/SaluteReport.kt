package org.salutemesh

enum class ReportKind {
    SALUTE,
    SALT,
}

data class SaluteReport(
    val id: String,
    val callsign: String,
    val epochSeconds: Long,
    val size: String,
    val activity: String,
    val location: String,
    val unit: String = "",
    val equipment: String = "",
    val kind: ReportKind = ReportKind.SALUTE,
    val inbound: Boolean = false,
    val rawPacket: String = "",
) {
    fun isBlank(): Boolean {
        val core = size.isBlank() && activity.isBlank() && location.isBlank()
        return if (kind == ReportKind.SALT) core
        else core && unit.isBlank() && equipment.isBlank()
    }
}
