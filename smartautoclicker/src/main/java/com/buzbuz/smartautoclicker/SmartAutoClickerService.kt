/*
 * Copyright (C) 2023 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.AndroidRuntimeException
import android.util.Log
import android.view.accessibility.AccessibilityEvent

import androidx.core.app.NotificationCompat

import com.buzbuz.smartautoclicker.SmartAutoClickerService.Companion.LOCAL_SERVICE_INSTANCE
import com.buzbuz.smartautoclicker.SmartAutoClickerService.Companion.getLocalService
import com.buzbuz.smartautoclicker.SmartAutoClickerService.LocalService
import com.buzbuz.smartautoclicker.activity.ScenarioActivity
import com.buzbuz.smartautoclicker.core.display.DisplayMetrics
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.data.AndroidExecutor
import com.buzbuz.smartautoclicker.core.processing.domain.DetectionRepository
import com.buzbuz.smartautoclicker.core.ui.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.feature.floatingmenu.ui.MainMenu
import com.buzbuz.smartautoclicker.my.SampleGattAttributes
import com.buzbuz.smartautoclicker.my.SampleGattAttributes.MIO_MEASUREMENT_NEW_VM
import com.buzbuz.smartautoclicker.my.SampleGattAttributes.NOTIFY
import com.buzbuz.smartautoclicker.my.SampleGattAttributes.READ
import com.buzbuz.smartautoclicker.my.SampleGattAttributes.WRITE

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.UUID

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * AccessibilityService implementation for the SmartAutoClicker.
 *
 * Started automatically by Android once the user has defined this service has an accessibility service, it provides
 * an API to start and stop the DetectorEngine correctly in order to display the overlay UI and record the screen for
 * clicks detection.
 * This API is offered through the [LocalService] class, which is instantiated in the [LOCAL_SERVICE_INSTANCE] object.
 * This system is used instead of the usual binder interface because an [AccessibilityService] already has its own
 * binder and it can't be changed. To access this local service, use [getLocalService].
 *
 * We need this service to be an accessibility service in order to inject the detected event on the currently
 * displayed activity. This injection is made by the [dispatchGesture] method, which is called everytime an event has
 * been detected.
 */
class SmartAutoClickerService : AccessibilityService(), AndroidExecutor {

    companion object {
        const val ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED = "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_DATA_AVAILABLE = "com.example.bluetooth.le.ACTION_DATA_AVAILABLE"
        const val SENSORS_DATA_THREAD_FLAG = "com.example.bluetooth.le.SENSORS_DATA_THREAD_FLAG"

        const val MIO_DATA_NEW = "com.example.bluetooth.le.MIO_DATA_NEW"

        /** The identifier for the foreground notification of this service. */
        private const val NOTIFICATION_ID = 42
        /** The channel identifier for the foreground notification of this service. */
        private const val NOTIFICATION_CHANNEL_ID = "SmartAutoClickerService"
        /** The instance of the [LocalService], providing access for this service to the Activity. */
        private var LOCAL_SERVICE_INSTANCE: LocalService? = null
            set(value) {
                field = value
                LOCAL_SERVICE_CALLBACK?.invoke(field)
            }
        /** Callback upon the availability of the [LOCAL_SERVICE_INSTANCE]. */
        private var LOCAL_SERVICE_CALLBACK: ((LocalService?) -> Unit)? = null
            set(value) {
                field = value
                value?.invoke(LOCAL_SERVICE_INSTANCE)
            }

        /**
         * Static method allowing an activity to register a callback in order to monitor the availability of the
         * [LocalService]. If the service is already available upon registration, the callback will be immediately
         * called.
         *
         * @param stateCallback the object to be notified upon service availability.
         */
        fun getLocalService(stateCallback: ((LocalService?) -> Unit)?) {
            LOCAL_SERVICE_CALLBACK = stateCallback
        }
    }

    private var serviceScope: CoroutineScope? = null
    /** The metrics of the device screen. */
    private var displayMetrics: DisplayMetrics? = null
    /** The engine for the detection. */
    private var detectionRepository: DetectionRepository? = null
    /** Manages the overlays for the application. */
    private var overlayManager: OverlayManager? = null
    /** True if the overlay is started, false if not. */
    private var isStarted: Boolean = false

    /** Local interface providing an API for the [SmartAutoClickerService]. */
    inner class LocalService {

        /** Coroutine job for the delayed start of engine & ui. */
        private var startJob: Job? = null

        /**
         * Start the overlay UI and instantiates the detection objects.
         *
         * This requires the media projection permission code and its data intent, they both can be retrieved using the
         * results of the activity intent provided by [MediaProjectionManager.createScreenCaptureIntent] (this Intent
         * shows the dialog warning about screen recording privacy). Any attempt to call this method without the
         * correct screen capture intent result will leads to a crash.
         *
         * @param resultCode the result code provided by the screen capture intent activity result callback
         * [android.app.Activity.onActivityResult]
         * @param data the data intent provided by the screen capture intent activity result callback
         * [android.app.Activity.onActivityResult]
         * @param scenario the identifier of the scenario of clicks to be used for detection.
         */
        fun start(resultCode: Int, data: Intent, scenario: Scenario) {
            val coroutineScope = serviceScope ?: return
            if (isStarted) {
                return
            }

            isStarted = true
            startForeground(NOTIFICATION_ID, createNotification(scenario.name))

            displayMetrics = DisplayMetrics.getInstance(this@SmartAutoClickerService).apply {
                startMonitoring(this@SmartAutoClickerService)
            }

            startJob = coroutineScope.launch {
                delay(500)

                detectionRepository = DetectionRepository.getDetectionRepository(this@SmartAutoClickerService).apply {
                    setScenarioId(scenario.id)
                    startScreenRecord(
                        context = this@SmartAutoClickerService,
                        resultCode = resultCode,
                        data = data,
                        androidExecutor = this@SmartAutoClickerService,
                    )
                }

                overlayManager = OverlayManager.getInstance(this@SmartAutoClickerService).apply {
                    navigateTo(
                        context = this@SmartAutoClickerService,
                        newOverlay = MainMenu { stop() },
                    )
                }
            }
        }

        /** Stop the overlay UI and release all associated resources. */
        fun stop() {
            val coroutineScope = serviceScope ?: return
            if (!isStarted) {
                return
            }

            isStarted = false

            coroutineScope.launch {
                startJob?.join()
                startJob = null

                overlayManager?.closeAll(this@SmartAutoClickerService)
                overlayManager = null

                detectionRepository?.stopScreenRecord()
                detectionRepository = null

                displayMetrics?.stopMonitoring(this@SmartAutoClickerService)
                displayMetrics = null

                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        LOCAL_SERVICE_INSTANCE = LocalService()

        //мой код
        initialize()
        connect("7C:E9:1A:25:4E:A5")//""F4:50:E7:B1:20:50")//тут указываем мак к которому подключаемся
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LOCAL_SERVICE_INSTANCE?.stop()
        LOCAL_SERVICE_INSTANCE = null
        serviceScope?.cancel()
        serviceScope = null
        close()
        return super.onUnbind(intent)
    }

    /**
     * Create the notification for this service allowing it to be set as foreground service.
     *
     * @param scenarioName the name to de displayed in the notification title
     *
     * @return the newly created notification.
     */
    private fun createNotification(scenarioName: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val intent = Intent(this, ScenarioActivity::class.java)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title, scenarioName))
            .setContentText(getString(R.string.notification_message))
            .setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setLocalOnly(true)
            .build()
    }

    override suspend fun executeGesture(gestureDescription: GestureDescription) {
        suspendCoroutine<Unit?> { continuation ->
            dispatchGesture(
                gestureDescription,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = continuation.resume(null)
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Gesture cancelled: $gestureDescription")
                        continuation.resume(null)
                    }
                },
                null,
            )
        }
    }

    override fun executeStartActivity(intent: Intent) {
        try {
            startActivity(intent)
        } catch (anfe: ActivityNotFoundException) {
            Log.w(TAG, "Can't start activity, it is not found.")
        } catch (arex: AndroidRuntimeException) {
            Log.w(TAG, "Can't start activity, Intent is invalid: $intent", arex)
        }
    }

    override fun executeSendBroadcast(intent: Intent) {
        try {
            sendBroadcast(intent)
        } catch (iaex: IllegalArgumentException) {
            Log.w(TAG, "Can't send broadcast, Intent is invalid: $intent", iaex)
        }
    }

    /**
     * Dump the state of the service via adb.
     * adb shell "dumpsys activity service com.buzbuz.smartautoclicker.debug/com.buzbuz.smartautoclicker.SmartAutoClickerService"
     */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        super.dump(fd, writer, args)

        if (writer == null) return

        writer.println("* UI:")
        val prefix = "\t"

        overlayManager?.dump(writer, prefix) ?: writer.println("$prefix None")
    }

    override fun onInterrupt() { /* Unused */ }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Unused */ }




    private val STATE_DISCONNECTED = 0
    private val STATE_CONNECTING = 1
    private val STATE_CONNECTED = 2

    private var mBluetoothManager: BluetoothManager? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothDeviceAddress: String? = null
    private var mBluetoothGatt: BluetoothGatt? = null
    private var mConnectionState = this.STATE_DISCONNECTED

    private fun broadcastUpdate(characteristic: BluetoothGattCharacteristic, state: String) {
        val intent = Intent(ACTION_DATA_AVAILABLE)
        val data = characteristic.value
        if (data != null && data.isNotEmpty()) {
            if (characteristic.uuid.toString() == MIO_MEASUREMENT_NEW_VM) {
                System.err.println("MIO_DATA_NEW from service data=" + data[0])
                intent.putExtra(MIO_DATA_NEW, data)
                intent.putExtra(SENSORS_DATA_THREAD_FLAG, false)
            }
        }
        sendBroadcast(intent)
    }

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private val mGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val intentAction: String
            System.err.println("my mGattCallback newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED
                mConnectionState = STATE_CONNECTED
                broadcastUpdate(intentAction)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED
                mConnectionState = STATE_DISCONNECTED
                broadcastUpdate(intentAction)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    System.err.println("установили доп параметры соединения")
                    mBluetoothGatt!!.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED
                    )
                }
            } else { }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(characteristic, READ)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(characteristic, WRITE)
            } else if (status == BluetoothGatt.GATT_FAILURE) {
                System.err.println("запись не удалась")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            broadcastUpdate(characteristic, NOTIFY)
        }
    }

    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    fun initialize(): Boolean {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            if (mBluetoothManager == null) {
                return false
            }
        }
        mBluetoothAdapter = mBluetoothManager!!.adapter
        if (mBluetoothAdapter == null) {
            return false
        }
        return true
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * `BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)`
     * callback.
     */
    @SuppressLint("MissingPermission")
    fun connect(address: String?): Boolean {
        if (mBluetoothAdapter == null || address == null) {
            return false
        }

        // Previously connected device.  Try to reconnect.
        System.err.println("my connect address=$address")
        if ( mBluetoothGatt != null) {//(address == mBluetoothDeviceAddress &&
            return if (mBluetoothGatt!!.connect()) {
                System.err.println("my connect true")
                mConnectionState = STATE_CONNECTING
                true
            } else {
                System.err.println("my connect false mBluetoothGatt=null")
                false
            }
        }

        val device = mBluetoothAdapter!!.getRemoteDevice(address) ?: return false
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback)
        mBluetoothDeviceAddress = address
        mConnectionState = STATE_CONNECTING
        return true
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * `BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)`
     * callback.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            return
        }
        mBluetoothGatt!!.disconnect()
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    @SuppressLint("MissingPermission")
    fun close() {
        if (mBluetoothGatt == null) {
            return
        }
        mBluetoothGatt!!.close()
        mBluetoothGatt = null
    }

    /**
     * Request a read on a given `BluetoothGattCharacteristic`. The read result is reported
     * asynchronously through the `BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)`
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    @SuppressLint("MissingPermission")
    fun readCharacteristic(characteristic: BluetoothGattCharacteristic?) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            return
        }
        mBluetoothGatt!!.readCharacteristic(characteristic)
    }

    /**
     * Request a write `BluetoothGattCharacteristic`. The read result is reported
     * asynchronously through the `BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)`
     * callback.
     *
     * @param characteristic The characteristic a write.
     */
    @SuppressLint("MissingPermission")
    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic?) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            return
        }
        mBluetoothGatt!!.writeCharacteristic(characteristic)
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    @SuppressLint("MissingPermission")
    fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean
    ) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {

            return
        }
        mBluetoothGatt!!.setCharacteristicNotification(characteristic, enabled)
        val descriptor = characteristic.getDescriptor(
            UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG)
        )
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        mBluetoothGatt!!.writeDescriptor(descriptor)
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after `BluetoothGatt#discoverServices()` completes successfully.
     *
     * @return A `List` of supported services.
     */
    fun getSupportedGattServices(): List<BluetoothGattService?>? {
        return if (mBluetoothGatt == null) null else mBluetoothGatt!!.services
    }
}

/** Tag for the logs. */
private const val TAG = "SmartAutoClickerService"