package org.salutemesh.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.salutemesh.FragmentAssembler
import org.salutemesh.MessageHistoryStore
import org.salutemesh.ReportKind
import org.salutemesh.SaluteCodec
import org.salutemesh.SaluteReport
import org.salutemesh.SaluteTime
import java.time.Instant
import java.util.ArrayDeque

@Composable
fun CommandCenterApp() {
    val scope = rememberCoroutineScope()
    val history = remember { MessageHistoryStore(MessageHistoryStore.desktopFile()) }
    val assembler = remember { FragmentAssembler() }
    val sentIds = remember { ArrayDeque<String>() }
    var settings by remember { mutableStateOf(DesktopSettings.load()) }
    var tab by remember { mutableStateOf(0) }
    var callsign by remember { mutableStateOf(settings.callsign) }
    var size by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf(SaluteTime.nowLocal()) }
    var equipment by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ReportKind.SALUTE) }
    var status by remember { mutableStateOf("Command center. Connect a radio on the Radio tab.") }
    var reports by remember { mutableStateOf(history.load()) }
    var busy by remember { mutableStateOf(false) }
    var incomingBanner by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var radioLabel by remember { mutableStateOf("Not connected") }
    var serialPorts by remember { mutableStateOf(listOf<String>()) }
    var bleDevices by remember { mutableStateOf(listOf<BleDevice>()) }
    var channels by remember { mutableStateOf(listOf<MeshChannel>()) }
    var channelIndex by remember { mutableStateOf(-1) }
    val incomingHandler = remember { mutableStateOf<(String) -> Unit>({}) }
    val logHandler = remember { mutableStateOf<(String) -> Unit>({}) }

    fun persistSettings() {
        settings = settings.copy(callsign = callsign)
        DesktopSettings.save(settings)
    }

    fun rememberSent(id: String) {
        sentIds.addLast(id.trim().uppercase())
        while (sentIds.size > 40) sentIds.removeFirst()
    }

    fun onIncomingText(text: String) {
        val fragment = SaluteCodec.parseFragment(text)
        if (fragment != null) {
            status = "Receiving part ${fragment.index}/${fragment.count}…"
        }
        val decoded = assembler.offer(text) ?: return
        if (sentIds.contains(decoded.id.trim().uppercase())) return
        val known = reports.any { it.id.equals(decoded.id, ignoreCase = true) }
        reports = history.append(decoded)
        if (!known) {
            status = "Received ${decoded.kind.name} from ${decoded.callsign}"
            incomingBanner = "New ${decoded.kind.name} from ${decoded.callsign}"
        }
    }

    incomingHandler.value = { text -> onIncomingText(text) }
    logHandler.value = { line -> status = line }

    val bridge = remember {
        RadioBridge(
            onIncoming = { text, _ -> incomingHandler.value(text) },
            onLog = { line -> logHandler.value(line) },
        )
    }

    DisposableEffect(Unit) {
        try {
            bridge.start()
            status = "Radio helper started. Connect USB serial or Bluetooth on the Radio tab."
        } catch (exc: Exception) {
            status = exc.message ?: "Radio helper failed to start."
        }
        onDispose { bridge.close() }
    }

    fun send() {
        val epoch = SaluteTime.parseToEpochSeconds(timeText)
        if (epoch == null) {
            status = "T — Time must be yyyy-MM-dd HH:mm:ss (or click Now)."
            return
        }
        val report = SaluteReport(
            id = SaluteCodec.newId(),
            callsign = callsign,
            epochSeconds = epoch,
            size = size,
            activity = activity,
            location = location,
            unit = if (kind == ReportKind.SALT) "" else unit,
            equipment = if (kind == ReportKind.SALT) "" else equipment,
            kind = kind,
        )
        if (report.isBlank()) {
            status = "Fill at least one ${kind.name} field."
            return
        }
        if (!connected) {
            status = "Not connected. Open the Radio tab and connect USB or Bluetooth first."
            tab = 2
            return
        }
        persistSettings()
        scope.launch {
            busy = true
            try {
                val packets = SaluteCodec.encodePackets(report)
                rememberSent(report.id)
                packets.forEachIndexed { index, packet ->
                    status = "Sending ${index + 1}/${packets.size}…"
                    withContext(Dispatchers.IO) { bridge.send(packet) }
                    if (index < packets.lastIndex) delay(SaluteCodec.PACKET_GAP_MS)
                }
                val logical = SaluteCodec.encode(report)
                reports = history.append(report.copy(inbound = false, rawPacket = logical))
                status = if (packets.size == 1) {
                    "Sent ${logical.toByteArray().size} bytes on ${settings.channelName}."
                } else {
                    "Sent ${packets.size} packets (${logical.toByteArray().size} bytes) on ${settings.channelName}."
                }
                tab = 1
            } catch (exc: Exception) {
                status = "Send failed: ${exc.message}"
            } finally {
                busy = false
            }
        }
    }

    fun fakeIncoming() {
        val sample = SaluteReport(
            id = SaluteCodec.newId(),
            callsign = "PEER",
            epochSeconds = Instant.now().epochSecond,
            size = "2 pax",
            activity = "static",
            location = "grid 18TWL",
            unit = "demo",
            equipment = "HT",
            inbound = true,
        )
        SaluteCodec.encodePackets(sample).forEach(::onIncomingText)
    }

    fun connectRadio() {
        persistSettings()
        DesktopSettings.save(settings)
        scope.launch {
            busy = true
            status = if (settings.connectionType == "bluetooth") "Connecting Bluetooth…" else "Connecting serial…"
            try {
                val reply = withContext(Dispatchers.IO) {
                    bridge.connect(
                        type = settings.connectionType,
                        port = settings.serialPort,
                        address = settings.bleAddress,
                        channel = settings.channelName,
                    )
                }
                connected = reply.optBoolean("connected")
                radioLabel = reply.optString("port").ifBlank { "radio" }
                channelIndex = reply.optInt("channelIndex", -1)
                channels = parseChannels(reply.optJSONArray("channels"))
                status = "Connected to $radioLabel, channel ${settings.channelName} (index $channelIndex)."
            } catch (exc: Exception) {
                connected = false
                status = "Connect failed: ${exc.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (incomingBanner.isNotBlank()) {
            Surface(color = MaterialTheme.colors.secondary, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(incomingBanner, modifier = Modifier.weight(1f))
                    TextButton(onClick = { tab = 1 }) { Text("History") }
                    TextButton(onClick = { incomingBanner = "" }) { Text("Dismiss") }
                }
            }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }) {
                Text("Compose", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = tab == 1, onClick = { tab = 1 }) {
                Text("History (${reports.size})", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = tab == 2, onClick = { tab = 2 }) {
                Text(if (connected) "Radio · on" else "Radio", modifier = Modifier.padding(12.dp))
            }
        }
        when (tab) {
            0 -> ComposePane(
                status = status,
                busy = busy,
                connected = connected,
                callsign = callsign,
                onCallsign = { callsign = it },
                size = size,
                onSize = { size = it },
                activity = activity,
                onActivity = { activity = it },
                location = location,
                onLocation = { location = it },
                unit = unit,
                onUnit = { unit = it },
                timeText = timeText,
                onTime = { timeText = it },
                equipment = equipment,
                onEquipment = { equipment = it },
                kind = kind,
                onKind = { kind = it },
                onSend = ::send,
                onClear = {
                    size = ""
                    activity = ""
                    location = ""
                    unit = ""
                    timeText = SaluteTime.nowLocal()
                    equipment = ""
                    status = "Form cleared. Callsign kept."
                },
                onFake = ::fakeIncoming,
                onNow = { timeText = SaluteTime.nowLocal() },
            )
            1 -> HistoryPane(
                status = status,
                reports = reports,
                onClearHistory = {
                    history.clear()
                    reports = emptyList()
                    status = "History cleared."
                },
                onFill = { report ->
                    size = report.size
                    activity = report.activity
                    location = report.location
                    unit = report.unit
                    timeText = SaluteTime.formatEpoch(report.epochSeconds)
                    equipment = report.equipment
                    kind = report.kind
                    tab = 0
                    status = "Loaded ${report.kind.name} fields from ${report.callsign}. Callsign unchanged."
                },
            )
            else -> RadioPane(
                status = status,
                busy = busy,
                connected = connected,
                radioLabel = radioLabel,
                settings = settings,
                serialPorts = serialPorts,
                bleDevices = bleDevices,
                channels = channels,
                channelIndex = channelIndex,
                onType = { value -> settings = settings.copy(connectionType = value) },
                onPort = { value -> settings = settings.copy(serialPort = value) },
                onBle = { value -> settings = settings.copy(bleAddress = value) },
                onChannel = { value -> settings = settings.copy(channelName = value) },
                onPickChannel = { channel ->
                    channelIndex = channel.index
                    settings = settings.copy(channelName = channel.name.ifBlank { settings.channelName })
                    DesktopSettings.save(settings.copy(callsign = callsign))
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { bridge.setChannel(channel.index, channel.name) }
                            status = "Mesh channel: ${channel.label} (index ${channel.index})"
                        } catch (exc: Exception) {
                            status = "Could not select channel: ${exc.message}"
                        }
                    }
                },
                onRefreshSerial = {
                    scope.launch {
                        busy = true
                        try {
                            serialPorts = withContext(Dispatchers.IO) { bridge.listSerial() }
                            status = if (serialPorts.isEmpty()) {
                                "No USB Meshtastic radios found."
                            } else {
                                "Found ${serialPorts.size} serial port(s)."
                            }
                        } catch (exc: Exception) {
                            status = "Serial scan failed: ${exc.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                onScanBle = {
                    scope.launch {
                        busy = true
                        status = "Scanning Bluetooth (about 10 seconds)…"
                        try {
                            bleDevices = withContext(Dispatchers.IO) { bridge.listBle() }
                            status = if (bleDevices.isEmpty()) {
                                "No Bluetooth radios found. Pair the radio, then disconnect it in OS settings."
                            } else {
                                "Found ${bleDevices.size} Bluetooth radio(s)."
                            }
                        } catch (exc: Exception) {
                            status = "Bluetooth scan failed: ${exc.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                onConnect = ::connectRadio,
                onDisconnect = {
                    scope.launch {
                        withContext(Dispatchers.IO) { bridge.disconnect() }
                        connected = false
                        radioLabel = "Not connected"
                        status = "Disconnected."
                    }
                },
            )
        }
    }
}

@Composable
private fun ComposePane(
    status: String,
    busy: Boolean,
    connected: Boolean,
    callsign: String,
    onCallsign: (String) -> Unit,
    size: String,
    onSize: (String) -> Unit,
    activity: String,
    onActivity: (String) -> Unit,
    location: String,
    onLocation: (String) -> Unit,
    unit: String,
    onUnit: (String) -> Unit,
    timeText: String,
    onTime: (String) -> Unit,
    equipment: String,
    onEquipment: (String) -> Unit,
    kind: ReportKind,
    onKind: (ReportKind) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onFake: () -> Unit,
    onNow: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SALUTE Mesh — Command Center", style = MaterialTheme.typography.h6)
        Text(
            if (connected) status else "$status  Radio is not connected.",
            style = MaterialTheme.typography.body2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, enabled = !busy) { Text("Send ${kind.name}") }
            OutlinedButton(onClick = onClear, enabled = !busy) { Text("Clear data") }
            OutlinedButton(onClick = onFake, enabled = !busy) { Text("Fake incoming") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kind == ReportKind.SALUTE) {
                Button(onClick = { onKind(ReportKind.SALUTE) }) { Text("SALUTE") }
                OutlinedButton(onClick = { onKind(ReportKind.SALT) }) { Text("SALT") }
            } else {
                OutlinedButton(onClick = { onKind(ReportKind.SALUTE) }) { Text("SALUTE") }
                Button(onClick = { onKind(ReportKind.SALT) }) { Text("SALT") }
            }
        }
        if (kind == ReportKind.SALT) {
            Text("SALT is the short form: Size, Activity, Location, Time.")
        }
        OutlinedTextField(callsign, onCallsign, label = { Text("Callsign / from") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(size, onSize, label = { Text("S — Size") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(activity, onActivity, label = { Text("A — Activity") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(location, onLocation, label = { Text("L — Location") }, modifier = Modifier.fillMaxWidth())
        if (kind == ReportKind.SALUTE) {
            OutlinedTextField(unit, onUnit, label = { Text("U — Unit") }, modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                timeText,
                onTime,
                label = { Text("T — Time (local)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(onClick = onNow) { Text("Now") }
        }
        if (kind == ReportKind.SALUTE) {
            OutlinedTextField(equipment, onEquipment, label = { Text("E — Equipment") }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            SaluteCodec.sizeSummary(
                SaluteReport(
                    id = "PREVIEW0",
                    callsign = callsign,
                    epochSeconds = SaluteTime.parseToEpochSeconds(timeText) ?: 0L,
                    size = size,
                    activity = activity,
                    location = location,
                    unit = unit,
                    equipment = equipment,
                    kind = kind,
                ),
            ),
            style = MaterialTheme.typography.body2,
        )
    }
}

@Composable
private fun HistoryPane(
    status: String,
    reports: List<SaluteReport>,
    onClearHistory: () -> Unit,
    onFill: (SaluteReport) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(status, style = MaterialTheme.typography.body2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("History is saved on this computer (~/.saluteMesh/history.txt).")
            if (reports.isNotEmpty()) {
                TextButton(onClick = onClearHistory) { Text("Clear history") }
            }
        }
        if (reports.isEmpty()) {
            Text("No messages yet. Send, receive, or Fake incoming.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(reports, key = { it.id }) { report ->
                    Card(modifier = Modifier.fillMaxWidth().padding(2.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${if (report.inbound) "IN" else "OUT"} ${report.kind.name} ${report.callsign}")
                            Text("S ${report.size}")
                            Text("A ${report.activity}")
                            Text("L ${report.location}")
                            if (report.kind == ReportKind.SALUTE) Text("U ${report.unit}")
                            Text("T ${SaluteTime.formatEpoch(report.epochSeconds)}")
                            if (report.kind == ReportKind.SALUTE) Text("E ${report.equipment}")
                            TextButton(onClick = { onFill(report) }) { Text("Fill form") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioPane(
    status: String,
    busy: Boolean,
    connected: Boolean,
    radioLabel: String,
    settings: DesktopSettings,
    serialPorts: List<String>,
    bleDevices: List<BleDevice>,
    channels: List<MeshChannel>,
    channelIndex: Int,
    onType: (String) -> Unit,
    onPort: (String) -> Unit,
    onBle: (String) -> Unit,
    onChannel: (String) -> Unit,
    onPickChannel: (MeshChannel) -> Unit,
    onRefreshSerial: () -> Unit,
    onScanBle: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Radio (Linux / Windows command center)", style = MaterialTheme.typography.h6)
        Text(status, style = MaterialTheme.typography.body2)
        Text(if (connected) "Linked: $radioLabel" else "Not connected.")
        Text("Use USB serial when the radio is on the desk. Use Bluetooth when it is across the room. Channel name and secret key stay on the radios — this app does not set them.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.connectionType == "serial") {
                Button(onClick = { onType("serial") }) { Text("USB serial") }
                OutlinedButton(onClick = { onType("bluetooth") }) { Text("Bluetooth") }
            } else {
                OutlinedButton(onClick = { onType("serial") }) { Text("USB serial") }
                Button(onClick = { onType("bluetooth") }) { Text("Bluetooth") }
            }
        }
        OutlinedTextField(
            settings.channelName,
            onChannel,
            label = { Text("Preferred channel name (used at connect if it exists)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("Mesh channel — tap after Connect. This does not create channels or set the secret key.")
        if (channels.isEmpty()) {
            Text(if (connected) "No channels listed. Reconnect." else "Channels appear after you connect.")
        } else {
            channels.forEach { channel ->
                if (channel.index == channelIndex) {
                    Button(onClick = { onPickChannel(channel) }, enabled = !busy) {
                        Text("${channel.index}  ${channel.label}")
                    }
                } else {
                    OutlinedButton(onClick = { onPickChannel(channel) }, enabled = !busy) {
                        Text("${channel.index}  ${channel.label}")
                    }
                }
            }
        }
        if (settings.connectionType == "serial") {
            OutlinedTextField(
                settings.serialPort,
                onPort,
                label = { Text("Serial port (blank = auto if only one radio)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(onClick = onRefreshSerial, enabled = !busy) { Text("Find USB radios") }
            serialPorts.forEach { port ->
                TextButton(onClick = { onPort(port) }) { Text(if (port == settings.serialPort) "● $port" else port) }
            }
        } else {
            OutlinedTextField(
                settings.bleAddress,
                onBle,
                label = { Text("Bluetooth address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(onClick = onScanBle, enabled = !busy) { Text("Scan Bluetooth") }
            bleDevices.forEach { device ->
                TextButton(onClick = { onBle(device.address) }) {
                    Text(if (device.address.equals(settings.bleAddress, true)) "● ${device.label}" else device.label)
                }
            }
            Text("Linux: pair and trust the radio, then Disconnect it in Bluetooth settings so this app can hold it. Close the phone Meshtastic app. Windows: pair in Settings, then Connect here.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect, enabled = !busy) { Text(if (connected) "Reconnect" else "Connect") }
            if (connected) {
                OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect") }
            }
        }
    }
}

private fun parseChannels(array: JSONArray?): List<MeshChannel> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            add(MeshChannel(row.optInt("index"), row.optString("name")))
        }
    }
}