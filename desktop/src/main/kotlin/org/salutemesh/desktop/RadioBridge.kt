package org.salutemesh.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class BleDevice(val address: String, val name: String) {
    val label: String get() = if (name.isBlank() || name.equals(address, ignoreCase = true)) address else "$name  $address"
}

data class MeshChannel(val index: Int, val name: String) {
    val label: String get() = name.ifBlank { "Channel $index" }
}

class RadioBridge(
    private val onIncoming: (text: String, fromId: String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(1)
    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null

    val running: Boolean get() = process?.isAlive == true

    fun start() {
        if (running) return
        val python = findPython()
        val script = findBridgeScript()
            ?: throw IllegalStateException("Could not find python/radio_bridge.py. Run from the saluteMesh folder.")
        val builder = ProcessBuilder(python, "-u", script.absolutePath)
            .redirectErrorStream(false)
        builder.directory(script.parentFile.parentFile)
        builder.environment()["PYTHONUNBUFFERED"] = "1"
        val started = builder.start()
        process = started
        writer = started.outputStream.bufferedWriter(Charsets.UTF_8)
        thread(name = "salute-radio-stdout", isDaemon = true) {
            BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).use { reader ->
                reader.lineSequence().forEach(::handleLine)
            }
        }
        thread(name = "salute-radio-stderr", isDaemon = true) {
            BufferedReader(InputStreamReader(started.errorStream, Charsets.UTF_8)).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.isNotBlank()) onLog(line)
                }
            }
        }
        thread(name = "salute-radio-wait", isDaemon = true) {
            val code = started.waitFor()
            onLog("Radio helper exited ($code).")
        }
    }

    fun close() {
        try {
            requestAsync(JSONObject().put("cmd", "disconnect"))
        } catch (_: Exception) {
        }
        writer?.close()
        process?.destroy()
        process = null
        writer = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    suspend fun ping(): Boolean = try {
        request(JSONObject().put("cmd", "ping"), timeoutMs = 8_000).optBoolean("ok")
    } catch (_: Exception) {
        false
    }

    suspend fun listSerial(): List<String> {
        val reply = request(JSONObject().put("cmd", "list_serial"))
        return jsonStrings(reply.optJSONArray("ports"))
    }

    suspend fun listBle(): List<BleDevice> {
        val reply = request(JSONObject().put("cmd", "list_ble"), timeoutMs = 25_000)
        val devices = reply.optJSONArray("devices") ?: return emptyList()
        return buildList {
            for (i in 0 until devices.length()) {
                val row = devices.optJSONObject(i) ?: continue
                val address = row.optString("address")
                if (address.isNotBlank()) add(BleDevice(address, row.optString("name")))
            }
        }
    }

    suspend fun connect(type: String, port: String, address: String, channel: String): JSONObject =
        request(
            JSONObject()
                .put("cmd", "connect")
                .put("type", type)
                .put("port", port)
                .put("address", address)
                .put("channel", channel),
            timeoutMs = 90_000,
        )

    suspend fun send(text: String) {
        val reply = request(JSONObject().put("cmd", "send").put("text", text), timeoutMs = 20_000)
        if (!reply.optBoolean("ok")) {
            throw IllegalStateException(reply.optString("error").ifBlank { "Send failed" })
        }
    }

    suspend fun setChannel(index: Int, name: String = ""): JSONObject =
        request(
            JSONObject().put("cmd", "set_channel").put("index", index).put("name", name),
            timeoutMs = 10_000,
        )

    suspend fun disconnect() {
        try {
            request(JSONObject().put("cmd", "disconnect"), timeoutMs = 10_000)
        } catch (_: Exception) {
        }
    }

    private fun handleLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        val obj = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            onLog(trimmed)
            return
        }
        when (obj.optString("event")) {
            "incoming" -> onIncoming(obj.optString("text"), obj.optString("fromId"))
            "ready" -> onLog(obj.optString("message").ifBlank { "Radio helper ready." })
            "status" -> onLog(obj.optString("message"))
        }
        if (obj.has("id")) {
            pending.remove(obj.getInt("id"))?.complete(obj)
        }
    }

    private fun requestAsync(body: JSONObject) {
        val stream = writer ?: return
        synchronized(this) {
            stream.write(body.toString())
            stream.newLine()
            stream.flush()
        }
    }

    private suspend fun request(body: JSONObject, timeoutMs: Long = 15_000): JSONObject {
        if (!running) start()
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        body.put("id", id)
        requestAsync(body)
        val reply = withTimeout(timeoutMs) { deferred.await() }
        if (!reply.optBoolean("ok")) {
            throw IllegalStateException(reply.optString("error").ifBlank { "Radio helper failed" })
        }
        return reply
    }

    companion object {
        fun findProjectRoot(start: File = File(System.getProperty("user.dir") ?: ".")): File? {
            var cursor: File? = start.canonicalFile
            repeat(8) {
                val here = cursor ?: return null
                if (File(here, "python/radio_bridge.py").isFile && File(here, "settings.gradle.kts").isFile) {
                    return here
                }
                cursor = here.parentFile
            }
            return null
        }

        fun findBridgeScript(): File? {
            val env = System.getenv("SALUTE_MESH_ROOT")
            if (!env.isNullOrBlank()) {
                val script = File(env, "python/radio_bridge.py")
                if (script.isFile) return script
            }
            return findProjectRoot()?.let { File(it, "python/radio_bridge.py") }
        }

        fun findPython(): String {
            val env = System.getenv("SALUTE_PYTHON")
            if (!env.isNullOrBlank()) return env
            val root = findProjectRoot()
            if (root != null) {
                val unix = File(root, "python/.venv/bin/python")
                if (unix.canExecute()) return unix.absolutePath
                val windows = File(root, "python/.venv/Scripts/python.exe")
                if (windows.isFile) return windows.absolutePath
            }
            return if (File.separatorChar == '\\') "python" else "python3"
        }

        private fun jsonStrings(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    if (value.isNotBlank()) add(value)
                }
            }
        }
    }
}
