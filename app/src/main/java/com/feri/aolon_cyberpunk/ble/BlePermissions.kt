package com.feri.aolon_cyberpunk.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

val BLE_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun hasBlePermissions(context: Context) = BLE_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

fun bluetoothOn(context: Context) =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

class BleGate(val granted: Boolean, val request: (onReady: () -> Unit) -> Unit)

/**
 * Asks for Bluetooth permission and, if the radio is off, for Bluetooth to be turned on,
 * then runs the pending action once both are satisfied.
 */
@Composable
fun rememberBleGate(): BleGate {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasBlePermissions(context)) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pending?.invoke()
        pending = null
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        if (!granted) {
            pending = null
            return@rememberLauncherForActivityResult
        }
        if (bluetoothOn(context)) {
            pending?.invoke()
            pending = null
        } else {
            enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
    return BleGate(granted) { onReady ->
        pending = onReady
        if (!hasBlePermissions(context)) {
            ask.launch(BLE_PERMISSIONS)
        } else if (!bluetoothOn(context)) {
            enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            granted = true
            onReady()
            pending = null
        }
    }
}
