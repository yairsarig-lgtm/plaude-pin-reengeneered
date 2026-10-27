package com.yairsarig.pinlink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Finds the pin. It doesn't reliably advertise its service UUID, so match by name too. */
@SuppressLint("MissingPermission")
class PinScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    fun isPin(name: String?, serviceUuids: List<java.util.UUID>): Boolean {
        val n = name?.lowercase().orEmpty()
        return n.startsWith(PinConfig.NAME_PREFIX) || n.contains(PinConfig.NAME_CONTAINS) ||
            PinConfig.SERVICE_UUID in serviceUuids
    }

    /** Already-bonded pins first (they may not advertise while bonded), then a live scan. */
    suspend fun find(): BluetoothDevice? {
        adapter.bondedDevices.firstOrNull { isPin(it.name, emptyList()) }?.let {
            PinLog.i("Found bonded pin ${it.name} ${it.address}")
            return it
        }
        val scanner = adapter.bluetoothLeScanner ?: return null.also { PinLog.e("Bluetooth is off") }
        val found = CompletableDeferred<BluetoothDevice>()
        val seen = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
                if (seen.add(result.device.address)) {
                    PinLog.i("seen ${result.device.address} '${name ?: "-"}' rssi=${result.rssi} uuids=$uuids")
                }
                if (isPin(name, uuids)) found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                PinLog.e("Scan failed: $errorCode")
                found.cancel()
            }
        }
        PinLog.i("Scanning…")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        return try {
            withTimeoutOrNull(PinConfig.SCAN_TIMEOUT_MS) { runCatching { found.await() }.getOrNull() }
                .also { PinLog.i(if (it != null) "Found pin ${it.address}" else "No pin found") }
        } finally {
            scanner.stopScan(cb)
        }
    }

    fun deviceFor(address: String): BluetoothDevice = adapter.getRemoteDevice(address)
}
