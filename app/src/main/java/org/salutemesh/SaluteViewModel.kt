package org.salutemesh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.salutemesh.mesh.BleRadio
import org.salutemesh.mesh.BleRadioFinder
import org.salutemesh.mesh.MeshTransport
import org.salutemesh.mesh.MeshTransportFactory
import org.salutemesh.mesh.RadioChannel
import java.time.Instant

data class SaluteUiState(
    val callsign: String = "N1",
    val size: String = "",
    val activity: String = "",
    val location: String = "",
    val unit: String = "",
    val timeText: String = SaluteTime.nowLocal(),
    val equipment: String = "",
    val kind: ReportKind = ReportKind.SALUTE,
    val reports: List<SaluteReport> = emptyList(),
    val tab: Int = 0,
    val statusMessage: String = "Set a callsign, fill SALUTE, then send.",
    val mockRadio: Boolean = MeshTransportFactory.isMock(),
    val radioConnected: Boolean = false,
    val radioLabel: String = if (MeshTransportFactory.isMock()) "Mock (no radio)" else "Meshtastic BLE",
    val channelIndex: Int = 1,
    val channels: List<RadioChannel> = emptyList(),
    val bleAddress: String = "",
    val radios: List<BleRadio> = emptyList(),
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val pendingBleAction: String = "",
)

class SaluteViewModel(app: Application) : AndroidViewModel(app) {
    private var transport: MeshTransport = MeshTransportFactory.create(
        storageDir = app.filesDir.absolutePath,
        bleAddress = null,
    )
    private var scanJob: Job? = null
    private val history = MessageHistoryStore(
        MessageHistoryStore.androidFile(app.filesDir),
    )
    private val assembler = FragmentAssembler()
    private val sentIds = ArrayDeque<String>()

    private val _ui = MutableStateFlow(
        SaluteUiState(channels = transport.channels(), reports = history.load()),
    )
    val ui: StateFlow<SaluteUiState> = _ui

    init {
        bindIncoming()
        if (_ui.value.mockRadio) {
            viewModelScope.launch { transport.connect() }
            _ui.update { it.copy(radioConnected = true) }
        }
    }

    fun setField(update: (SaluteUiState) -> SaluteUiState) {
        _ui.update(update)
    }

    fun selectTab(tab: Int) {
        _ui.update { it.copy(tab = tab) }
    }

    fun clearHistory() {
        history.clear()
        _ui.update { it.copy(reports = emptyList(), statusMessage = "History cleared.") }
    }

    fun fillFromHistory(report: SaluteReport) {
        _ui.update {
            it.copy(
                callsign = report.callsign,
                size = report.size,
                activity = report.activity,
                location = report.location,
                unit = report.unit,
                timeText = SaluteTime.formatEpoch(report.epochSeconds),
                equipment = report.equipment,
                kind = report.kind,
                tab = 0,
                statusMessage = "Loaded ${report.kind.name} from ${report.callsign}.",
            )
        }
    }

    fun stampNow() {
        _ui.update { it.copy(timeText = SaluteTime.nowLocal()) }
    }

    fun clearData() {
        _ui.update {
            it.copy(
                size = "",
                activity = "",
                location = "",
                unit = "",
                timeText = SaluteTime.nowLocal(),
                equipment = "",
                statusMessage = "Form cleared. Callsign kept.",
            )
        }
    }

    fun send() {
        val state = _ui.value
        val epoch = SaluteTime.parseToEpochSeconds(state.timeText)
        if (epoch == null) {
            _ui.update { it.copy(statusMessage = "T — Time must be yyyy-MM-dd HH:mm:ss (or tap Now).") }
            return
        }
        val report = SaluteReport(
            id = SaluteCodec.newId(),
            callsign = state.callsign,
            epochSeconds = epoch,
            size = state.size,
            activity = state.activity,
            location = state.location,
            unit = if (state.kind == ReportKind.SALT) "" else state.unit,
            equipment = if (state.kind == ReportKind.SALT) "" else state.equipment,
            kind = state.kind,
        )
        if (report.isBlank()) {
            _ui.update { it.copy(statusMessage = "Fill at least one ${state.kind.name} field.") }
            return
        }
        viewModelScope.launch {
            try {
                _ui.update { it.copy(busy = true) }
                val packets = SaluteCodec.encodePackets(report)
                rememberSent(report.id)
                if (!transport.isConnected()) {
                    transport = MeshTransportFactory.create(
                        getApplication<Application>().filesDir.absolutePath,
                        state.bleAddress.ifBlank { null },
                    )
                    bindIncoming()
                    transport.connect()
                }
                packets.forEachIndexed { index, packet ->
                    _ui.update {
                        it.copy(statusMessage = "Sending ${index + 1}/${packets.size}…")
                    }
                    transport.sendText(packet, state.channelIndex)
                    if (index < packets.lastIndex) delay(SaluteCodec.PACKET_GAP_MS)
                }
                val logical = SaluteCodec.encode(report)
                val outbound = report.copy(inbound = false, rawPacket = logical)
                val reports = history.append(outbound)
                val summary = if (packets.size == 1) {
                    "Sent ${logical.toByteArray().size} bytes on ${channelLabel(_ui.value)}"
                } else {
                    "Sent ${packets.size} packets (${logical.toByteArray().size} bytes) on ${channelLabel(_ui.value)}"
                }
                _ui.update {
                    it.copy(
                        reports = reports,
                        statusMessage = summary,
                        radioConnected = transport.isConnected(),
                        busy = false,
                    )
                }
            } catch (exc: Exception) {
                _ui.update {
                    it.copy(busy = false, statusMessage = "Send failed: ${exc.message}")
                }
            }
        }
    }

    fun injectSample() {
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
        val packets = SaluteCodec.encodePackets(sample)
        packets.forEach(::onIncoming)
    }

    fun setMockRadio(mock: Boolean) {
        if (!MeshTransportFactory.isMock() && mock) {
            _ui.update { it.copy(statusMessage = "This APK is the mesh build. Use mockDebug for no-radio UI.") }
            return
        }
        viewModelScope.launch {
            transport.disconnect()
            transport = MeshTransportFactory.create(
                getApplication<Application>().filesDir.absolutePath,
                _ui.value.bleAddress.ifBlank { null },
            )
            bindIncoming()
            if (mock) transport.connect()
            _ui.update {
                it.copy(
                    mockRadio = mock,
                    radioConnected = mock,
                    radioLabel = transport.name,
                    channels = transport.channels(),
                    statusMessage = "Radio: ${transport.name}",
                )
            }
        }
    }

    fun selectChannel(index: Int) {
        _ui.update { it.copy(channelIndex = index) }
    }

    fun selectRadio(radio: BleRadio) {
        _ui.update { it.copy(bleAddress = radio.address, statusMessage = "Selected ${radio.label}") }
    }

    fun startScan() {
        if (MeshTransportFactory.isMock()) {
            _ui.update { it.copy(statusMessage = "Mock APK has no BLE scan.") }
            return
        }
        scanJob?.cancel()
        _ui.update { it.copy(scanning = true, radios = emptyList(), statusMessage = "Scanning…") }
        scanJob = viewModelScope.launch {
            try {
                BleRadioFinder.scan().collect { list ->
                    _ui.update { it.copy(radios = list) }
                }
            } catch (exc: Exception) {
                _ui.update { it.copy(scanning = false, statusMessage = "Scan failed: ${exc.message}") }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _ui.update { it.copy(scanning = false) }
    }

    fun connectRadio() {
        viewModelScope.launch {
            try {
                _ui.update { it.copy(busy = true, statusMessage = "Connecting…") }
                stopScan()
                transport.disconnect()
                transport = MeshTransportFactory.create(
                    getApplication<Application>().filesDir.absolutePath,
                    _ui.value.bleAddress.ifBlank { null },
                )
                bindIncoming()
                transport.connect()
                val channels = transport.channels()
                val salute = channels.firstOrNull { it.name.equals(SaluteCodec.DEFAULT_CHANNEL, true) }
                _ui.update {
                    it.copy(
                        busy = false,
                        radioConnected = true,
                        radioLabel = transport.name,
                        channels = channels,
                        channelIndex = salute?.index ?: it.channelIndex,
                        statusMessage = "Connected. Channel ${salute?.label ?: it.channelIndex}",
                    )
                }
            } catch (exc: Exception) {
                _ui.update {
                    it.copy(busy = false, radioConnected = false, statusMessage = "Connect failed: ${exc.message}")
                }
            }
        }
    }

    fun setPendingBleAction(action: String) {
        _ui.update { it.copy(pendingBleAction = action) }
    }

    fun clearPendingBleAction() {
        _ui.update { it.copy(pendingBleAction = "") }
    }

    private fun bindIncoming() {
        transport.setIncomingHandler(::onIncoming)
    }

    private fun rememberSent(id: String) {
        sentIds.addLast(id.trim().uppercase())
        while (sentIds.size > 40) sentIds.removeFirst()
    }

    private fun onIncoming(text: String) {
        val fragment = SaluteCodec.parseFragment(text)
        if (fragment != null) {
            _ui.update {
                it.copy(statusMessage = "Receiving part ${fragment.index}/${fragment.count}…")
            }
        }
        val decoded = assembler.offer(text) ?: return
        if (sentIds.contains(decoded.id.trim().uppercase())) return
        if (_ui.value.reports.any { it.id.equals(decoded.id, ignoreCase = true) }) return
        val reports = history.append(decoded)
        _ui.update {
            it.copy(
                reports = reports,
                statusMessage = "Received ${decoded.kind.name} from ${decoded.callsign}",
            )
        }
    }

    private fun channelLabel(state: SaluteUiState): String {
        val selected = state.channels.firstOrNull { it.index == state.channelIndex }
        return selected?.label ?: "channel ${state.channelIndex}"
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}
