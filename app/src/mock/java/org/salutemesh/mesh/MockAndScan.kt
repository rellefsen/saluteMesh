package org.salutemesh.mesh

class MockMeshTransport : MeshTransport {
    override val name = "Mock (no radio)"
    private var connected = false
    private var incoming: ((String) -> Unit)? = null
    val sent = mutableListOf<String>()

    override suspend fun connect() {
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun sendText(text: String, channelIndex: Int) {
        sent += text
    }

    override fun isConnected() = connected

    override fun channels() = listOf(
        RadioChannel(0, "Primary"),
        RadioChannel(1, SaluteCodecChannel),
    )

    override fun setIncomingHandler(handler: ((String) -> Unit)?) {
        incoming = handler
    }

    fun inject(text: String) {
        incoming?.invoke(text)
    }

    companion object {
        const val SaluteCodecChannel = "salute"
    }
}

object BleRadioFinder {
    fun scan(): kotlinx.coroutines.flow.Flow<List<BleRadio>> =
        kotlinx.coroutines.flow.flowOf(emptyList())
}
