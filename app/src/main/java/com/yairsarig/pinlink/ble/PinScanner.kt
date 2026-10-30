package com.yairsarig.pinlink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Finds the pin. It doesn't reliably advertise its service UUID, so match by name too. */
@SuppressLint("MissingPermission")
class PinScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var scanCount = 0
    private val scanTimes = mutableListOf<Long>()

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

    /**
     * One background try: a low-power scan for [PinConfig.BACKGROUND_SCAN_WINDOW_MS]. Filtered,
     * because Android pauses unfiltered scans while the screen is off.
     */
    suspend fun findNearby(address: String?): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val filters = if (address != null) {
            listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        } else {
            listOf(ScanFilter.Builder().setDeviceName(PinConfig.PIN_NAME).build(),
                ScanFilter.Builder().setServiceUuid(ParcelUuid(PinConfig.SERVICE_UUID)).build())
        }
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { found.complete(result.device) }
            override fun onScanFailed(errorCode: Int) {
                PinLog.e("Scan failed: $errorCode")
                found.cancel()
            }
        }
        val started = System.currentTimeMillis()
        scanTimes += started
        scanTimes.removeAll { started - it > PinConfig.SCAN_LOG_WINDOW_MS }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        scanner.startScan(filters, settings, cb)
        return try {
            val device = withTimeoutOrNull(PinConfig.BACKGROUND_SCAN_WINDOW_MS) { runCatching { found.await() }.getOrNull() }
            val secs = (System.currentTimeMillis() - started) / 1000.0
            PinLog.i("Low-power scan #${++scanCount}: " + (if (device != null) "pin seen after %.1f s".format(secs) else "no pin in %.0f s".format(secs)) +
                " (${scanTimes.size} scans in the last hour)")
            device
        } finally {
            scanner.stopScan(cb)
        }
    }

    fun deviceFor(address: String): BluetoothDevice = adapter.getRemoteDevice(address)
}
