package org.salutemesh.mesh

object MeshTransportFactory {
    fun create(storageDir: String, bleAddress: String?): MeshTransport = MockMeshTransport()

    fun isMock(): Boolean = true
}
