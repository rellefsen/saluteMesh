package org.salutemesh.mesh

object MeshTransportFactory {
    fun create(storageDir: String, bleAddress: String?): MeshTransport =
        MeshtasticBleTransport(storageDir = storageDir, preferredAddress = bleAddress)

    fun isMock(): Boolean = false
}
