package org.salutemesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.salutemesh.mesh.RadioChannel

class MainActivity : ComponentActivity() {
    private val viewModel: SaluteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by viewModel.ui.collectAsStateWithLifecycle()
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { granted ->
                    if (granted.values.all { it }) {
                        when (state.pendingBleAction) {
                            "scan" -> viewModel.startScan()
                            else -> viewModel.connectRadio()
                        }
                        viewModel.clearPendingBleAction()
                    }
                }

                fun withBle(action: String, run: () -> Unit) {
                    if (state.mockRadio) {
                        run()
                        return
                    }
                    val needed = blePermissions()
                    val missing = needed.filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) run()
                    else {
                        viewModel.setPendingBleAction(action)
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                }

                SaluteScreen(
                    state = state,
                    onField = viewModel::setField,
                    onSend = viewModel::send,
                    onStampNow = viewModel::stampNow,
                    onClearData = viewModel::clearData,
                    onClearHistory = viewModel::clearHistory,
                    onFillFromHistory = viewModel::fillFromHistory,
                    onDismissBanner = viewModel::dismissIncomingBanner,
                    onTab = viewModel::selectTab,
                    onInject = viewModel::injectSample,
                    onScan = { withBle("scan") { viewModel.startScan() } },
                    onStopScan = viewModel::stopScan,
                    onSelectRadio = viewModel::selectRadio,
                    onConnect = { withBle("connect") { viewModel.connectRadio() } },
                    onChannel = viewModel::selectChannel,
                )
            }
        }
    }

    private fun blePermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
            perms += Manifest.permission.BLUETOOTH
            perms += Manifest.permission.BLUETOOTH_ADMIN
        }
        return perms
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaluteScreen(
    state: SaluteUiState,
    onField: ((SaluteUiState) -> SaluteUiState) -> Unit,
    onSend: () -> Unit,
    onStampNow: () -> Unit,
    onClearData: () -> Unit,
    onClearHistory: () -> Unit,
    onFillFromHistory: (SaluteReport) -> Unit,
    onDismissBanner: () -> Unit,
    onTab: (Int) -> Unit,
    onInject: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectRadio: (org.salutemesh.mesh.BleRadio) -> Unit,
    onConnect: () -> Unit,
    onChannel: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("SALUTE Mesh") })
                if (state.incomingBanner.isNotBlank()) {
                    IncomingBanner(
                        message = state.incomingBanner,
                        onDismiss = onDismissBanner,
                        onOpenHistory = { onTab(1) },
                    )
                }
                TabRow(selectedTabIndex = state.tab) {
                    Tab(
                        selected = state.tab == 0,
                        onClick = { onTab(0) },
                        text = { Text("Compose") },
                    )
                    Tab(
                        selected = state.tab == 1,
                        onClick = { onTab(1) },
                        text = { Text("History (${state.reports.size})") },
                    )
                    Tab(
                        selected = state.tab == 2,
                        onClick = { onTab(2) },
                        text = { Text(radioTabLabel(state)) },
                    )
                }
            }
        },
    ) { padding ->
        when (state.tab) {
            0 -> ComposePane(
                state = state,
                onField = onField,
                onSend = onSend,
                onStampNow = onStampNow,
                onClearData = onClearData,
                onInject = onInject,
                modifier = Modifier.padding(padding),
            )
            1 -> HistoryPane(
                reports = state.reports,
                onClearHistory = onClearHistory,
                onFillFromHistory = onFillFromHistory,
                modifier = Modifier.padding(padding),
            )
            else -> RadioPane(
                state = state,
                onScan = onScan,
                onStopScan = onStopScan,
                onSelectRadio = onSelectRadio,
                onConnect = onConnect,
                onChannel = onChannel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun IncomingBanner(
    message: String,
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenHistory) { Text("History") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private fun radioTabLabel(state: SaluteUiState): String {
    return if (!state.mockRadio && state.radioConnected) "Radio · on" else "Radio"
}

@Composable
private fun ComposePane(
    state: SaluteUiState,
    onField: ((SaluteUiState) -> SaluteUiState) -> Unit,
    onSend: () -> Unit,
    onStampNow: () -> Unit,
    onClearData: () -> Unit,
    onInject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Text("Send ${state.kind.name}")
            }
            OutlinedButton(onClick = onClearData, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Text("Clear data")
            }
        }
        if (state.mockRadio) {
            OutlinedButton(onClick = onInject, modifier = Modifier.fillMaxWidth()) {
                Text("Fake incoming")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.kind == ReportKind.SALUTE,
                onClick = { onField { it.copy(kind = ReportKind.SALUTE) } },
                label = { Text("SALUTE") },
            )
            FilterChip(
                selected = state.kind == ReportKind.SALT,
                onClick = { onField { it.copy(kind = ReportKind.SALT) } },
                label = { Text("SALT") },
            )
        }
        if (state.kind == ReportKind.SALT) {
            Text(
                "SALT is the short form: Size, Activity, Location, Time.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = state.callsign,
            onValueChange = { value -> onField { it.copy(callsign = value) } },
            label = { Text("Callsign / from") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.size,
            onValueChange = { value -> onField { it.copy(size = value) } },
            label = { Text("S — Size") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.activity,
            onValueChange = { value -> onField { it.copy(activity = value) } },
            label = { Text("A — Activity") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.location,
            onValueChange = { value -> onField { it.copy(location = value) } },
            label = { Text("L — Location") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.kind == ReportKind.SALUTE) {
            OutlinedTextField(
                value = state.unit,
                onValueChange = { value -> onField { it.copy(unit = value) } },
                label = { Text("U — Unit") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.timeText,
                onValueChange = { value -> onField { it.copy(timeText = value) } },
                label = { Text("T — Time (local)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(onClick = onStampNow) {
                Text("Now")
            }
        }
        if (state.kind == ReportKind.SALUTE) {
            OutlinedTextField(
                value = state.equipment,
                onValueChange = { value -> onField { it.copy(equipment = value) } },
                label = { Text("E — Equipment") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(packetPreview(state), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HistoryPane(
    reports: List<SaluteReport>,
    onClearHistory: () -> Unit,
    onFillFromHistory: (SaluteReport) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sent and received SALUTE reports stay on this phone.", style = MaterialTheme.typography.bodySmall)
            if (reports.isNotEmpty()) {
                TextButton(onClick = onClearHistory) { Text("Clear history") }
            }
        }
        if (reports.isEmpty()) {
            Text("No messages yet. Send or receive a SALUTE.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(reports, key = { it.id + it.inbound }) { report ->
                    ReportCard(report, onFillFromHistory)
                }
            }
        }
    }
}

@Composable
private fun RadioPane(
    state: SaluteUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectRadio: (org.salutemesh.mesh.BleRadio) -> Unit,
    onConnect: () -> Unit,
    onChannel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
        if (state.mockRadio) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Practice build", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "This APK has no Bluetooth radio. Scan, connect, and mesh channel live on the Field (mesh) APK.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Use Fake incoming on Compose to drill History without a radio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            return@Column
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (state.radioConnected) "Linked: ${state.radioLabel}" else "Not connected.",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Radios already have the salute channel and PSK. This app only sends UTF-8 text.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScan) {
                        Text(if (state.scanning) "Scanning" else "Scan radios")
                    }
                    if (state.scanning) TextButton(onClick = onStopScan) { Text("Stop") }
                }
                state.radios.forEach { radio ->
                    FilterChip(
                        selected = state.bleAddress.equals(radio.address, ignoreCase = true),
                        onClick = { onSelectRadio(radio) },
                        label = { Text(radio.label) },
                    )
                }
                Button(onClick = onConnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.radioConnected) "Reconnect" else "Connect")
                }
                ChannelPicker(state.channels, state.channelIndex, onChannel)
            }
        }
    }
}

@Composable
private fun ChannelPicker(
    channels: List<RadioChannel>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Text("Mesh channel", style = MaterialTheme.typography.titleSmall)
    if (channels.isEmpty()) {
        Text(
            "Connect a radio to load its channels, then tap the one this net uses.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    channels.forEach { channel ->
        FilterChip(
            selected = channel.index == selected,
            onClick = { onSelect(channel.index) },
            label = { Text("${channel.index}  ${channel.label}") },
        )
    }
}

@Composable
private fun ReportCard(report: SaluteReport, onFillFromHistory: (SaluteReport) -> Unit) {
    val whenText = SaluteTime.formatEpoch(report.epochSeconds)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${if (report.inbound) "IN" else "OUT"}  ${report.kind.name}  ${report.callsign}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("S ${report.size}")
            Text("A ${report.activity}")
            Text("L ${report.location}")
            if (report.kind == ReportKind.SALUTE) {
                Text("U ${report.unit}")
            }
            Text("T $whenText")
            if (report.kind == ReportKind.SALUTE) {
                Text("E ${report.equipment}")
            }
            TextButton(onClick = { onFillFromHistory(report) }) { Text("Fill form") }
        }
    }
}

private fun packetPreview(state: SaluteUiState): String {
    val epoch = SaluteTime.parseToEpochSeconds(state.timeText) ?: 0L
    return SaluteCodec.sizeSummary(
        SaluteReport(
            id = "PREVIEW0",
            callsign = state.callsign,
            epochSeconds = epoch,
            size = state.size,
            activity = state.activity,
            location = state.location,
            unit = state.unit,
            equipment = state.equipment,
            kind = state.kind,
        ),
    )
}
