package org.salutemesh.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.meshtastic.proto.Channel
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendOutcome
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.transport.ble.BleTransport

class MeshtasticBleTransport(
    private val storageDir: String,
    private val preferredAddress: String? = null,
) : MeshTransport {
    override val name = "Meshtastic BLE"
    private var client: RadioClient? = null
    private var cachedChannels: List<RadioChannel> = emptyList()
    private var incoming: ((String) -> Unit)? = null
    private var listenJob: Job? = null

    override suspend fun connect() {
        disconnect()
        val address = BleRadio.normalizeMac(preferredAddress.orEmpty())
            ?: throw IllegalStateException("Select a Meshtastic radio from the scan list first.")
        delay(500)
        val transport = BleTransport(address) {
            autoConnectIf { true }
            this.transport = com.juul.kable.Transport.Le
        }
        val radio = RadioClient.Builder()
            .transport(transport)
            .storage(SqlDelightStorageProvider(baseDir = storageDir))
            .build()
        try {
            withTimeout(60_000) { radio.connect() }
        } catch (exc: Exception) {
            try {
                radio.disconnect()
            } catch (_: Exception) {
            }
            throw IllegalStateException(
                "BLE $address: ${exc.message ?: exc::class.simpleName}. " +
                    "Close the official Meshtastic app, pair the radio, then disconnect it in Android Bluetooth.",
                exc,
            )
        }
        client = radio
        cachedChannels = mapChannels(radio.channels.value)
        startPacketListen(radio)
        Log.i(TAG, "BLE connected to $address channels=${cachedChannels.map { it.label }}")
    }

    override suspend fun disconnect() {
        listenJob?.cancel()
        listenJob = null
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        client = null
        cachedChannels = emptyList()
    }

    override suspend fun sendText(text: String, channelIndex: Int) {
        val radio = client ?: throw IllegalStateException("Radio not connected")
        val outcome = radio.sendText(text, channel = ChannelIndex(channelIndex)).await()
        if (outcome is SendOutcome.Failure) {
            throw IllegalStateException("Send failed: ${outcome.reason}")
        }
    }

    override fun isConnected() = client != null

    override fun channels(): List<RadioChannel> {
        if (cachedChannels.isNotEmpty()) return cachedChannels
        return mapChannels(client?.channels?.value)
    }

    override fun setIncomingHandler(handler: ((String) -> Unit)?) {
        incoming = handler
    }

    private fun startPacketListen(radio: RadioClient) {
        listenJob?.cancel()
        listenJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                radio.packets.collect { packet ->
                    val decoded = packet.decoded ?: return@collect
                    if (decoded.portnum != PortNum.TEXT_MESSAGE_APP) return@collect
                    val text = decoded.payload.toByteArray().decodeToString()
                    incoming?.invoke(text)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun mapChannels(raw: List<Channel>?): List<RadioChannel> {
        return raw.orEmpty()
            .filter { it.role != Channel.Role.DISABLED }
            .mapIndexed { position, slot ->
                RadioChannel(
                    index = if (slot.index != 0 || position == 0) slot.index else position,
                    name = slot.settings?.name.orEmpty(),
                )
            }
            .sortedBy { it.index }
    }

    companion object {
        private const val TAG = "SaluteBle"
    }
}
