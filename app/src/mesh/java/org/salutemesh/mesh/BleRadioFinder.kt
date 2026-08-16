@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package org.salutemesh.mesh

import com.juul.kable.Scanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.meshtastic.sdk.transport.ble.BleConstants

object BleRadioFinder {
    fun scan(): Flow<List<BleRadio>> = flow {
        val found = linkedMapOf<String, BleRadio>()
        Scanner {
            filters {
                match { services = listOf(BleConstants.MESH_SERVICE_UUID) }
            }
        }.advertisements.collect { ad ->
            val address = ad.identifier.toString()
            val name = ad.name?.trim().orEmpty().ifBlank {
                ad.peripheralName?.trim().orEmpty()
            }
            found[address] = BleRadio(address, name, ad.rssi)
            emit(found.values.sortedByDescending { it.rssi })
        }
    }
}
