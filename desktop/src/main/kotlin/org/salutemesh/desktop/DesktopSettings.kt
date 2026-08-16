package org.salutemesh.desktop

import org.json.JSONObject
import java.io.File

data class DesktopSettings(
    val callsign: String = "EOC",
    val connectionType: String = "serial",
    val serialPort: String = "",
    val bleAddress: String = "",
    val channelName: String = "salute",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("callsign", callsign)
        .put("connectionType", connectionType)
        .put("serialPort", serialPort)
        .put("bleAddress", bleAddress)
        .put("channelName", channelName)

    companion object {
        fun file(): File {
            val home = System.getProperty("user.home") ?: "."
            return File(home, ".saluteMesh/settings.json")
        }

        fun load(): DesktopSettings {
            val target = file()
            if (!target.isFile) return DesktopSettings()
            return try {
                val raw = JSONObject(target.readText(Charsets.UTF_8))
                DesktopSettings(
                    callsign = raw.optString("callsign", "EOC"),
                    connectionType = raw.optString("connectionType", "serial"),
                    serialPort = raw.optString("serialPort", ""),
                    bleAddress = raw.optString("bleAddress", ""),
                    channelName = raw.optString("channelName", "salute").ifBlank { "salute" },
                )
            } catch (_: Exception) {
                DesktopSettings()
            }
        }

        fun save(settings: DesktopSettings) {
            val target = file()
            target.parentFile?.mkdirs()
            target.writeText(settings.toJson().toString(2), Charsets.UTF_8)
        }
    }
}
