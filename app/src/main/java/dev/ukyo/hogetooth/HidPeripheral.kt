package dev.ukyo.hogetooth

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.actor
import java.nio.charset.StandardCharsets
import java.util.*

// https://github.com/kshoji/BLE-HID-Peripheral-for-Android/blob/develop/lib/src/main/java/jp/kshoji/blehid/HidPeripheral.java

private const val TAG = "ukyo.hogetooth"

/**
 * Device Information Service
 */
private val SERVICE_DEVICE_INFORMATION = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_MANUFACTURER_NAME =
    UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_MODEL_NUMBER =
    UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_SERIAL_NUMBER =
    UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
private val DEVICE_INFO_MAX_LENGTH = 20

/**
 * Battery Service
 */
private val SERVICE_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_BATTERY_LEVEL =
    UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

/**
 * HID Service
 */
private val SERVICE_BLE_HID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_HID_INFORMATION =
    UUID.fromString("00002a4a-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_REPORT_MAP = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_HID_CONTROL_POINT =
    UUID.fromString("00002a4c-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_REPORT = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
private val CHARACTERISTIC_PROTOCOL_MODE =
    UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")

/**
 * Gatt Characteristic Descriptor
 */
private val DESCRIPTOR_REPORT_REFERENCE =
    UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")
private val DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private val EMPTY_BYTES = ByteArray(0)
private val RESPONSE_HID_INFORMATION = byteArrayOf(0x11, 0x01, 0x00, 0x03)

abstract class HidPeripheral {
    private var bluetoothManager: BluetoothManager
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private val registeredDevices = mutableSetOf<BluetoothDevice>()
    private var inputReportCharacteristic: BluetoothGattCharacteristic? = null
    private val inputReportActor = GlobalScope.actor<ByteArray> {
        for (bytes in channel) {
            val deviceSet = getDevices()
            inputReportCharacteristic?.setValue(bytes)
            Log.i(TAG, deviceSet.toString())
            for (device in deviceSet) {
                Log.i(TAG, bluetoothGattServer.toString())
                bluetoothGattServer?.notifyCharacteristicChanged(
                    device,
                    inputReportCharacteristic,
                    false
                )
            }
        }
    }
    private var applicationContext: Context? = null

    private val manufacturer = "hogetooth"
    private val deviceName = "BLE HID"
    private val serialNumber = "12345678"

    fun getDevices(): Set<BluetoothDevice> {
        return Collections.unmodifiableSet(registeredDevices)
    }

    abstract fun getReportMap(): ByteArray;

    abstract fun onOutputReport(outputReport: ByteArray?);

    protected suspend fun addInputReport(inputReport: ByteArray) {
        if (inputReport != null && inputReport.size > 0) {
            inputReportActor.send(inputReport)
        }
    }

    protected constructor(
        context: Context,
        packageManager: PackageManager,
        needInputReport: Boolean,
        needOutputReport: Boolean,
        needFeatureReport: Boolean,
        dataSendingRate: Int
    ) {
        applicationContext = context.applicationContext
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        val bluetoothAdapter = bluetoothManager.adapter
        if (!checkBluetoothSupport(bluetoothAdapter, packageManager)) {
            throw UnsupportedOperationException("Bluetooth is not supported!")
        }

        if (!bluetoothAdapter.isEnabled) {
            bluetoothAdapter.enable()
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            throw UnsupportedOperationException("Multiple Advertisement is not supported!")
        }

        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

        startAdvertising()

        bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        bluetoothGattServer?.addService(setupDeviceInformationService()) ?: Log.w(
            TAG,
            "Unable to add Device Information server"
        )
        bluetoothGattServer?.addService(
            setupHidService(
                needInputReport,
                needOutputReport,
                needFeatureReport
            )
        ) ?: Log.w(
            TAG,
            "Unable to add HID server"
        )
        bluetoothGattServer?.addService(setupBatteryService()) ?: Log.w(
            TAG,
            "Unable to add Battery server"
        )
    }

    private fun checkBluetoothSupport(
        bluetoothAdapter: BluetoothAdapter?,
        packageManager: PackageManager
    ): Boolean {
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth is not supported")
            return false
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(TAG, "Bluetooth LE is not supported")
            return false
        }

        return true
    }

    fun setupHidService(
        isNeedInputReport: Boolean,
        isNeedOutputReport: Boolean,
        isNeedFeatureReport: Boolean
    ): BluetoothGattService {
        val service =
            BluetoothGattService(SERVICE_BLE_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // HID Information
        BluetoothGattCharacteristic(
            CHARACTERISTIC_HID_INFORMATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).let { service.addCharacteristic(it) }

        // Report Map
        BluetoothGattCharacteristic(
            CHARACTERISTIC_REPORT_MAP,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).let { service.addCharacteristic(it) }

        // Protocol Mode
        BluetoothGattCharacteristic(
            CHARACTERISTIC_PROTOCOL_MODE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        ).let { service.addCharacteristic(it) }

        // Input Report
        if (isNeedInputReport) {
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_REPORT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
            )

            BluetoothGattDescriptor(
                DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
            ).let {
                it.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                characteristic.addDescriptor(it)
            }

            BluetoothGattDescriptor(
                DESCRIPTOR_REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
            ).let { characteristic.addDescriptor(it) }

            inputReportCharacteristic = characteristic

            service.addCharacteristic(characteristic)
        }

        // Output Report
        if (isNeedOutputReport) {
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
            )

            BluetoothGattDescriptor(
                DESCRIPTOR_REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
            ).let { characteristic.addDescriptor(it) }

            service.addCharacteristic(characteristic)
        }

        // Feature Report
        if (isNeedFeatureReport) {
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
            )

            BluetoothGattDescriptor(
                DESCRIPTOR_REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
            ).let { characteristic.addDescriptor(it) }

            service.addCharacteristic(characteristic)
        }

        return service
    }

    fun setupBatteryService(): BluetoothGattService {
        val service = BluetoothGattService(
            SERVICE_BATTERY,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )

        BluetoothGattDescriptor(
            DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ).let {
            it.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            characteristic.addDescriptor(it)
        }

        service.addCharacteristic(characteristic)

        return service
    }

    fun setupDeviceInformationService(): BluetoothGattService {
        val service = BluetoothGattService(
            SERVICE_DEVICE_INFORMATION,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        BluetoothGattCharacteristic(
            CHARACTERISTIC_MANUFACTURER_NAME,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).let { service.addCharacteristic(it) }

        BluetoothGattCharacteristic(
            CHARACTERISTIC_MODEL_NUMBER,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).let { service.addCharacteristic(it) }

        BluetoothGattCharacteristic(
            CHARACTERISTIC_SERIAL_NUMBER,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        ).let { service.addCharacteristic(it) }

        return service
    }

    fun startAdvertising() {
        bluetoothLeAdvertiser?.let {
            val settings = AdvertiseSettings.Builder()
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(SERVICE_DEVICE_INFORMATION))
                .addServiceUuid(ParcelUuid(SERVICE_BLE_HID))
                .addServiceUuid(ParcelUuid(SERVICE_BATTERY))
                .build()
            val scanResponse = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE_DEVICE_INFORMATION))
                .addServiceUuid(ParcelUuid(SERVICE_BLE_HID))
                .addServiceUuid(ParcelUuid(SERVICE_BATTERY))
                .build()

            it.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } ?: Log.w(TAG, "Failed to create advertiser")
    }

    fun stopAdvertising() {
        bluetoothLeAdvertiser?.let {
            it.stopAdvertising(advertiseCallback)
        } ?: Log.w(TAG, "Failed to create advertiser")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)

            Log.i(TAG, "$device, $status, $newState")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
//                Log.i(TAG, "BluetoothDevice CONNECTED: $device")
//                if (device?.bondState == BluetoothDevice.BOND_NONE) {
//                    applicationContext?.registerReceiver(
//                        object : BroadcastReceiver() {
//                            override fun onReceive(context: Context?, intent: Intent?) {
//                                val action = intent?.action
//
//                                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == action) {
//                                    val state =
//                                        intent.getIntExtra(
//                                            BluetoothDevice.EXTRA_BOND_STATE,
//                                            BluetoothDevice.ERROR
//                                        )
//
//                                    if (state == BluetoothDevice.BOND_BONDED) {
//                                        val bondedDevice =
//                                            intent.getParcelableExtra<BluetoothDevice>(
//                                                BluetoothDevice.EXTRA_DEVICE
//                                            )
//                                        context?.unregisterReceiver(this)
//
//                                        bluetoothGattServer?.connect(device, true)
//                                    }
//                                }
//                            }
//                        },
//                        IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
//                    )
//
//                    try {
//                        device.setPairingConfirmation(true)
//                    } catch (e: SecurityException) {
//                        Log.d(TAG, e.message, e)
//                    }
//                    device.createBond()
//                } else if (device?.bondState == BluetoothDevice.BOND_BONDED) {
//                    bluetoothGattServer?.connect(device, true)
//                    synchronized(registeredDevices) {
//                        registeredDevices.add(device)
//                    }
//                }
                bluetoothGattServer?.connect(device, true)
                device?.let {
                    Log.i(TAG, "BluetoothDevice CONNECTED: $device")
                    registeredDevices.add(it)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BluetoothDevice DISCONNECTED: $device")
                registeredDevices.remove(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)

            Log.i(TAG, "$device, $requestId, $offset, $characteristic")


            when (characteristic.uuid) {
                CHARACTERISTIC_HID_INFORMATION -> {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        RESPONSE_HID_INFORMATION
                    )
                }
                CHARACTERISTIC_REPORT_MAP -> {
                    if (offset == 0) {
                        bluetoothGattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            getReportMap()
                        )
                    } else {
                        val bytes = getReportMap()
                        val remainLength = bytes.size - offset
                        if (remainLength > 0) {
                            val data = bytes.sliceArray(offset..bytes.size)
                            bluetoothGattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                data
                            )
                        } else {
                            bluetoothGattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                null
                            )
                        }
                    }
                }
                CHARACTERISTIC_HID_CONTROL_POINT -> {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        byteArrayOf(0x00)
                    )
                }
                CHARACTERISTIC_REPORT -> {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        EMPTY_BYTES
                    )
                }
                CHARACTERISTIC_MANUFACTURER_NAME -> {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        manufacturer.toByteArray(StandardCharsets.UTF_8)

                    )
                }
                CHARACTERISTIC_SERIAL_NUMBER -> {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        serialNumber.toByteArray(StandardCharsets.UTF_8)
                    )
                }
                CHARACTERISTIC_MODEL_NUMBER -> {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        deviceName.toByteArray(StandardCharsets.UTF_8)
                    )
                }
                CHARACTERISTIC_BATTERY_LEVEL -> {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        byteArrayOf(0x64)
                    )
                }
                else -> {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        characteristic.value
                    )
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)

            if (DESCRIPTOR_REPORT_REFERENCE == descriptor?.uuid) {
                when (descriptor?.characteristic?.properties) {
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY -> {
                        bluetoothGattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            byteArrayOf(0x00, 0x01)
                        )
                    }
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE -> {
                        bluetoothGattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            byteArrayOf(0x00, 0x02)
                        )
                    }
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE -> {
                        bluetoothGattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            byteArrayOf(0x00, 0x03)
                        )
                    }
                    else -> {
                        bluetoothGattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            EMPTY_BYTES
                        )
                    }

                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            if (responseNeeded) {
                if (CHARACTERISTIC_REPORT == characteristic?.uuid) {
                    if (characteristic?.properties == (BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) {
                        onOutputReport(value)
                    }
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        EMPTY_BYTES
                    )
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(
                device,
                requestId,
                descriptor,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            descriptor?.setValue(value)

            if (responseNeeded) {
                if (DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION == descriptor?.uuid) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        EMPTY_BYTES
                    )
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)

            Log.d(TAG, "onServiceAdded: ${status}, service: ${service?.uuid}")

            if (status != 0) {
                Log.d(TAG, "onServiceAdded Adding Service failed..")
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "LE Advertise Started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "LE Advertise Failed $errorCode")
        }
    }
}
