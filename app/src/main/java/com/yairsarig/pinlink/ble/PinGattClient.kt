package com.yairsarig.pinlink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class LinkState { DISCONNECTED, CONNECTING, READY }

/**
 * One GATT connection to the pin. Android allows a single outstanding GATT operation, so every
 * op takes [opLock] and suspends until its callback fires.
 */
@SuppressLint("MissingPermission") // Permissions are checked by MainActivity before anything runs.
class PinGattClient(
    private val context: Context,
    private val onNotification: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private val _state = MutableStateFlow(LinkState.DISCONNECTED)
    val state: StateFlow<LinkState> = _state

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val opLock = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    @Volatile private var connectSignal: CompletableDeferred<Boolean>? = null

    var mtu = 23
        private set

    /** Connects, negotiates MTU, discovers services, waits, subscribes. Throws on failure. */
    suspend fun connect(device: BluetoothDevice) {
        close()
        _state.value = LinkState.CONNECTING
        PinLog.i("Connecting to ${device.address} (${device.name})")
        try {
            val connected = CompletableDeferred<Boolean>().also { connectSignal = it }
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            check(withTimeout(PinConfig.GATT_OP_TIMEOUT_MS * 2) { connected.await() }) { "connect failed" }

            val g = gatt!!
            gattOp("requestMtu") { g.requestMtu(PinConfig.REQUESTED_MTU) }
            gattOp("discoverServices") { g.discoverServices() }

            val service = g.getService(PinConfig.SERVICE_UUID)
                ?: error("service ${PinConfig.SERVICE_UUID} not found; services=${g.services.map { it.uuid }}")
            logServices(g)
            writeChar = service.getCharacteristic(PinConfig.WRITE_CHAR_UUID) ?: error("write characteristic missing")
            val notifyChar = service.getCharacteristic(PinConfig.NOTIFY_CHAR_UUID) ?: error("notify characteristic missing")

            delay(PinConfig.POST_CONNECT_DELAY_MS)
            enableNotifications(g, notifyChar)
            _state.value = LinkState.READY
            PinLog.i("Link ready (mtu=$mtu)")
        } catch (t: Throwable) {
            PinLog.e("Connect failed", t)
            close()
            throw t
        }
    }

    suspend fun write(bytes: ByteArray) {
        val g = gatt ?: error("not connected")
        val c = writeChar ?: error("not connected")
        val withResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gattOp("write") {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    fun close() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        writeChar = null
        pendingOp?.cancel()
        connectSignal?.cancel()
        _state.value = LinkState.DISCONNECTED
    }

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        check(g.setCharacteristicNotification(c, true)) { "setCharacteristicNotification failed" }
        val cccd = c.getDescriptor(PinConfig.CCCD_UUID) ?: error("CCCD missing")
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        gattOp("writeCCCD") {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    /** Runs one GATT op and suspends until its callback reports a status. */
    private suspend fun gattOp(name: String, start: () -> Boolean) = opLock.withLock {
        val op = CompletableDeferred<Int>().also { pendingOp = it }
        check(start()) { "$name could not be started" }
        val status = withTimeout(PinConfig.GATT_OP_TIMEOUT_MS) { op.await() }
        check(status == BluetoothGatt.GATT_SUCCESS) { "$name failed, status=$status" }
    }

    private fun logServices(g: BluetoothGatt) {
        for (s in g.services) {
            PinLog.i("service ${s.uuid}")
            for (c in s.characteristics) PinLog.i("  char ${c.uuid} props=0x${c.properties.toString(16)}")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            PinLog.i("GATT state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectSignal?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectSignal?.complete(false)
                pendingOp?.complete(-1)
                val wasLinked = _state.value != LinkState.DISCONNECTED
                runCatching { g.close() }
                if (gatt === g) gatt = null
                _state.value = LinkState.DISCONNECTED
                if (wasLinked) onDisconnected()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this@PinGattClient.mtu = mtu
            PinLog.i("MTU=$mtu status=$status")
            // A refused MTU still leaves a usable link at 23; don't fail the connect over it.
            pendingOp?.complete(BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { pendingOp?.complete(status) }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotification(value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") onNotification(c.value.copyOf())
        }
    }
}
