package com.feri.aolon_cyberpunk.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.util.UUID

enum class LinkState { Idle, Scanning, Connecting, Ready }

data class FoundWatch(val address: String, val name: String, val rssi: Int, val ute: Boolean)

/**
 * Single BLE link to a UTE watch. GATT allows one outstanding operation at a time,
 * so every write and notification setup goes through [queue] on the main looper.
 * Callers must hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT before calling [scan] or [connect].
 */
@SuppressLint("MissingPermission")
class WatchClient(context: Context) {
    private val app = context.applicationContext
    private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(LinkState.Idle)
    val state: StateFlow<LinkState> = _state
    private val _found = MutableStateFlow<List<FoundWatch>>(emptyList())
    val found: StateFlow<List<FoundWatch>> = _found
    private val _events = MutableSharedFlow<WatchEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<WatchEvent> = _events
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val _connected = MutableStateFlow<FoundWatch?>(null)
    val connected: StateFlow<FoundWatch?> = _connected

    private var gatt: BluetoothGatt? = null
    private var target: String? = null
    private var userClosed = false
    private var retries = 0

    private sealed interface Op {
        class Write(val char: UUID, val data: ByteArray) : Op
        class Notify(val service: UUID, val char: UUID) : Op
        class Read(val char: UUID) : Op
    }

    private val queue = ArrayDeque<Op>()
    private var busy = false
    private val opTimeout = Runnable { finishOp("timeout") }
    private val scanTimeout = Runnable { stopScan() }

    val isAvailable get() = adapter?.isEnabled == true

    fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return note("Bluetooth is off")
        if (_state.value == LinkState.Scanning) return
        _found.value = emptyList()
        _state.value = LinkState.Scanning
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        main.postDelayed(scanTimeout, 12_000)
        note("Scanning…")
    }

    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value == LinkState.Scanning) _state.value = LinkState.Idle
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val ute = result.scanRecord?.serviceUuids?.any { it.uuid == Ute.SERVICE } == true
            val watch = FoundWatch(result.device.address, name, result.rssi, ute)
            val others = _found.value.filterNot { it.address == watch.address }
            _found.value = (others + watch).sortedWith(compareByDescending<FoundWatch> { it.ute }.thenByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            note("Scan failed ($errorCode)")
            _state.value = LinkState.Idle
        }
    }

    fun connect(address: String, name: String = address) {
        stopScan()
        closeGatt()
        userClosed = false
        target = address
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: return note("Bluetooth is off")
        _connected.value = FoundWatch(address, name, 0, true)
        _state.value = LinkState.Connecting
        note("Connecting to $name")
        gatt = device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        userClosed = true
        target = null
        closeGatt()
        _connected.value = null
        _state.value = LinkState.Idle
        note("Disconnected")
    }

    fun send(data: ByteArray, bulk: Boolean = false) {
        if (_state.value != LinkState.Ready) return note("Not connected, dropped ${Ute.hex(data)}")
        enqueue(Op.Write(if (bulk) Ute.BULK_WRITE else Ute.WRITE, data))
    }

    private fun closeGatt() {
        main.removeCallbacks(opTimeout)
        queue.clear()
        busy = false
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = main.post {
            if (g != gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                retries = 0
                note("Link up, negotiating MTU")
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                note("Link lost (status $status)")
                closeGatt()
                _state.value = LinkState.Idle
                val address = target
                // Watches drop the link when they walk out of range; back off up to ~30s between tries.
                if (!userClosed && address != null && retries < 10) {
                    retries++
                    main.postDelayed({ if (!userClosed && target == address) connect(address, _connected.value?.name ?: address) }, 3_000L * retries)
                }
            }
        }.let { }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = main.post {
            note("MTU $mtu")
            g.discoverServices()
        }.let { }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = main.post {
            val services = g.services.map { it.uuid }
            note("Services: " + services.joinToString { it.toString().substring(4, 8) })
            if (g.getService(Ute.SERVICE) == null) {
                note("This watch does not expose the UTE service (55ff). Protocol not supported yet.")
                return@post
            }
            enqueue(Op.Notify(Ute.SERVICE, Ute.NOTIFY))
            if (g.getService(Ute.BULK_SERVICE)?.getCharacteristic(Ute.BULK_NOTIFY) != null) {
                enqueue(Op.Notify(Ute.BULK_SERVICE, Ute.BULK_NOTIFY))
            }
            // Reading 33f1 returns the feature bitmap the official app uses to hide unsupported sensors.
            enqueue(Op.Read(Ute.WRITE))
            main.post(::whenQueueIdle)
        }.let { }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = main.post {
            finishOp(if (status == BluetoothGatt.GATT_SUCCESS) null else "notify status $status")
        }.let { }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = main.post {
            finishOp(if (status == BluetoothGatt.GATT_SUCCESS) null else "write status $status")
        }.let { }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val copy = value.copyOf()
            main.post { featuresRead(status, copy) }
        }

        @Deprecated("Pre-Tiramisu callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val copy = c.value?.copyOf() ?: ByteArray(0)
                main.post { featuresRead(status, copy) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            received(c.uuid, value)
        }

        @Deprecated("Pre-Tiramisu callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                received(c.uuid, c.value ?: return)
            }
        }
    }

    // Notifications must be on before the watch accepts commands; mark ready once setup drains.
    private fun whenQueueIdle() {
        if (gatt == null) return
        if (busy || queue.isNotEmpty()) {
            main.postDelayed(::whenQueueIdle, 100)
            return
        }
        if (_state.value != LinkState.Ready) {
            _state.value = LinkState.Ready
            note("Ready")
        }
    }

    private fun featuresRead(status: Int, value: ByteArray) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            note("FEATURES ${Ute.hex(value)}")
            Ute.features(value)?.let { _events.tryEmit(it) }
        }
        finishOp(if (status == BluetoothGatt.GATT_SUCCESS) null else "read status $status")
    }

    private fun received(char: UUID, value: ByteArray) {
        val copy = value.copyOf()
        main.post {
            note("RX ${if (char == Ute.BULK_NOTIFY) "34f2" else "33f2"} ${Ute.hex(copy)}")
            Ute.parse(copy)?.let { _events.tryEmit(it) }
        }
    }

    private fun enqueue(op: Op) {
        queue.addLast(op)
        if (!busy) nextOp()
    }

    private fun nextOp() {
        val g = gatt ?: return
        val op = queue.removeFirstOrNull() ?: return
        busy = true
        main.postDelayed(opTimeout, 3_000)
        val started = when (op) {
            is Op.Notify -> {
                val c = g.getService(op.service)?.getCharacteristic(op.char)
                val d = c?.getDescriptor(Ute.CCCD)
                if (c == null || d == null || !g.setCharacteristicNotification(c, true)) false
                else writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            is Op.Read -> {
                val c = g.getService(Ute.SERVICE)?.getCharacteristic(op.char)
                c != null && c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 && g.readCharacteristic(c)
            }
            is Op.Write -> {
                val service = if (op.char == Ute.BULK_WRITE) Ute.BULK_SERVICE else Ute.SERVICE
                val c = g.getService(service)?.getCharacteristic(op.char)
                if (c == null) false else {
                    note("TX ${if (op.char == Ute.BULK_WRITE) "34f1" else "33f1"} ${Ute.loggable(op.data)}")
                    writeCharacteristic(g, c, op.data)
                }
            }
        }
        if (!started) finishOp("could not start")
    }

    private fun finishOp(error: String?) {
        main.removeCallbacks(opTimeout)
        if (error != null) note("GATT: $error")
        busy = false
        nextOp()
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        else { d.value = value; g.writeDescriptor(d) }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
        else { c.writeType = type; c.value = data; g.writeCharacteristic(c) }
    }

    private fun note(line: String) {
        Log.d("AolonBle", line)
        _log.value = (_log.value + "${LocalTime.now().withNano(0)}  $line").takeLast(120)
    }
}
