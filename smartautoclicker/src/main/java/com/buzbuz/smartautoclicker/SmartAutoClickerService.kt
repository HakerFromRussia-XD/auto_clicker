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
import android.annotation.TargetApi
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
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
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
import com.buzbuz.smartautoclicker.core.processing.my.IScenarioTransmit
import com.buzbuz.smartautoclicker.core.ui.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.feature.floatingmenu.ui.MainMenu
import com.buzbuz.smartautoclicker.my.BluetoothLeService
import com.buzbuz.smartautoclicker.my.SampleGattAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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


    private var mDeviceAddress = "45:4D:DA:63:8C:07"

    private var mBluetoothManager: BluetoothManager? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothDeviceAddress: String? = null
    private var mBluetoothGatt: BluetoothGatt? = null
    private var mGattCharacteristics = ArrayList<ArrayList<BluetoothGattCharacteristic>>()
    private var subscribeThreadFlag = true
    enum class PreviousStates { STOP_SLIDE, LEFT_SLIDE, RIGHT_SLIDE }
    private var previousState = PreviousStates.STOP_SLIDE

    private val scenarioTransmit = IScenarioTransmit.getScenarioTransmit()

    private var serviceScope: CoroutineScope? = null
    /** The metrics of the device screen. */
    private var displayMetrics: DisplayMetrics? = null
    /** The engine for the detection. */
    private var detectionRepository: DetectionRepository? = null
    /** Manages the overlays for the application. */
    private var overlayManager: OverlayManager? = null
    /** True if the overlay is started, false if not. */
    private var isStarted: Boolean = false
    private var informThread: Boolean = true

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

    private val mServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {}

        override fun onServiceDisconnected(componentName: ComponentName) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        LOCAL_SERVICE_INSTANCE = LocalService()

        initialize()
        scanLeDevice(true)
        Log.d("my", "SmartAutoClickerService started")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LOCAL_SERVICE_INSTANCE?.stop()
        LOCAL_SERVICE_INSTANCE = null
        serviceScope?.cancel()
        serviceScope = null
        Log.d("my", "SmartAutoClickerService onUnbind")
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}



    @SuppressLint("MissingPermission")
    private fun scanLeDevice(enable: Boolean) {
        if (mBluetoothAdapter != null) {
            if (enable) {
                System.err.println("my scanLeDevice mBluetoothAdapter enable = true")
                mBluetoothAdapter!!.startLeScan(mLeScanCallback)
            } else {
                System.err.println("my scanLeDevice mBluetoothAdapter  enable = false")
                mBluetoothAdapter!!.stopLeScan(mLeScanCallback)
            }
        } else {
            System.err.println("my scanLeDevice mBluetoothAdapter == null")
        }
    }
    @SuppressLint("NewApi", "MissingPermission")
    @TargetApi(Build.VERSION_CODES.M)
    private val mLeScanCallback =
        BluetoothAdapter.LeScanCallback { device: BluetoothDevice, rssi: Int, scanRecord: ByteArray? ->
            if (device.name != null) {
                System.err.println("my scanLeDevice Callback ${device.name} : ${device.address}")
                if (device.name.contains("FEST-X")) {
                    mDeviceAddress = device.address
                    scanLeDevice(false)
                    connect(mDeviceAddress)
                }
            }
        }

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private val mGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("my", "STATE_CONNECTED")
                actionUpdate(BluetoothLeService.ACTION_GATT_CONNECTED)
                mBluetoothGatt!!.discoverServices()
            } else
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("my", "ACTION_GATT_DISCONNECTED")
                actionUpdate(BluetoothLeService.ACTION_GATT_DISCONNECTED)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            System.err.println("my вошли в onServicesDiscovered status = $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                actionUpdate(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mBluetoothGatt!!.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED
                    )
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                actionUpdate(characteristic, SampleGattAttributes.READ)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                actionUpdate(characteristic, SampleGattAttributes.WRITE)
            } else if (status == BluetoothGatt.GATT_FAILURE) {
                System.err.println("запись не удалась")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            actionUpdate(characteristic, SampleGattAttributes.NOTIFY)
        }
    }

    private fun actionUpdate (action: String) {
        when {
            BluetoothLeService.ACTION_GATT_CONNECTED == action -> {
                Log.d("my", "actionUpdate ACTION_GATT_CONNECTED")
            }
            BluetoothLeService.ACTION_GATT_DISCONNECTED == action -> {
                Log.d("my", "actionUpdate ACTION_GATT_DISCONNECTED")
                subscribeThreadFlag = true
                disconnect()
                initialize()
                scanLeDevice(true)
            }
            BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED == action -> {
//                Log.d("my", "actionUpdate ACTION_GATT_SERVICES_DISCOVERED")
                Log.d("my", "CONNECTING!!!  $mDeviceAddress")
                displayGattServices(getSupportedGattServices())
                startSubscribeSensorsDataThread()
            }
        }
    }

    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private fun displayGattServices(gattServices: List<BluetoothGattService?>?) {
        if (gattServices == null) return
        var uuid: String?
        val unknownServiceString = ("unknown_service")
        val unknownCharaString =("unknown_characteristic")
        val gattServiceData = ArrayList<HashMap<String, String?>>()
        val gattCharacteristicData = ArrayList<ArrayList<HashMap<String, String?>>>()
        mGattCharacteristics = ArrayList()


        // Loops through available GATT Services.
        for (gattService in gattServices) {
            val currentServiceData = HashMap<String, String?>()
            uuid = gattService?.uuid.toString()
            currentServiceData["NAME"] = SampleGattAttributes.lookup(uuid, unknownServiceString)
            currentServiceData["UUID"] = uuid
            gattServiceData.add(currentServiceData)
            val gattCharacteristicGroupData = ArrayList<HashMap<String, String?>>()
            val gattCharacteristics = gattService?.characteristics
            val charas = ArrayList<BluetoothGattCharacteristic>()

            // Loops through available Characteristics.
            if (gattCharacteristics != null) {
                for (gattCharacteristic in gattCharacteristics) {
                    charas.add(gattCharacteristic)
                    val currentCharaData = HashMap<String, String?>()
                    uuid = gattCharacteristic.uuid.toString()
                    currentCharaData["NAME"] = SampleGattAttributes.lookup(uuid, unknownCharaString)
                    currentCharaData["UUID"] = uuid
                    gattCharacteristicGroupData.add(currentCharaData)
//                    System.err.println("my ------->   ХАРАКТЕРИСТИКА: $uuid")
                }
            }
            mGattCharacteristics.add(charas)
            gattCharacteristicData.add(gattCharacteristicGroupData)
        }
    }

    private fun startSubscribeSensorsDataThread() {
        val subscribeThread = Thread {
            while (subscribeThreadFlag) {
                bleCommand(SampleGattAttributes.MIO_MEASUREMENT_NEW_VM)
                try {
                    Thread.sleep(500)
                } catch (ignored: Exception) { }
            }
        }
        subscribeThread.start()
    }

    private fun bleCommand(uuid: String) {
        Log.d("my", "startSubscribeSensorsDataThread")
        for (i in mGattCharacteristics.indices) {
            for (j in mGattCharacteristics[i].indices) {
                if (mGattCharacteristics[i][j].uuid.toString() == uuid) {
                    setCharacteristicNotification(mGattCharacteristics[i][j], true)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun actionUpdate (characteristic: BluetoothGattCharacteristic, action: String) {
        val data = characteristic.value

        if (data != null) {
            val dataSens1 = castUnsignedCharToInt(data[0])
            val dataSens2 = castUnsignedCharToInt(data[1])
            val sensorLevel = 100
            val rightSlide: Boolean = dataSens1 > sensorLevel
            val leftSlide: Boolean = dataSens2 > sensorLevel



            //если решение лететь налево
            if (rightSlide && leftSlide && previousState == PreviousStates.LEFT_SLIDE || !rightSlide && leftSlide){
                previousState = PreviousStates.LEFT_SLIDE
            }


            //если решение лететь направо
            if (rightSlide && leftSlide && previousState == PreviousStates.RIGHT_SLIDE || rightSlide && !leftSlide) {
                previousState = PreviousStates.RIGHT_SLIDE
            }


            //если решение не поворачивать
            if (!rightSlide && !leftSlide) {
                previousState = PreviousStates.STOP_SLIDE
            }

//            System.err.println("my $previousState")


            //TODO отправлять результат
            /** Tells if the limitation in scenario count have been reached. */
            if (previousState == PreviousStates.STOP_SLIDE) scenarioTransmit.state = 3
            if (previousState == PreviousStates.RIGHT_SLIDE) scenarioTransmit.state = 2
            if (previousState == PreviousStates.LEFT_SLIDE) scenarioTransmit.state = 1
        }

        subscribeThreadFlag = false
    }

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    private fun initialize(): Boolean {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            if (mBluetoothManager == null) {
                return false
            }
        }
        mBluetoothAdapter = mBluetoothManager!!.adapter
        return mBluetoothAdapter != null
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
        if (address == mBluetoothDeviceAddress && mBluetoothGatt != null) {
            return mBluetoothGatt!!.connect()
        }

        //TODO раскомментить после завершения теста с сохранением имён жестов
        val device: BluetoothDevice = mBluetoothAdapter!!.getRemoteDevice(address) ?: return false
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback)
        mBluetoothDeviceAddress = address
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
    private fun getSupportedGattServices(): List<BluetoothGattService?>? {
        return if (mBluetoothGatt == null) null else mBluetoothGatt!!.services
    }
    private fun castUnsignedCharToInt(ubyte: Byte): Int {
        var cast = ubyte.toInt()
        if (cast < 0) {
            cast += 256
        }
        return cast
    }
}

/** Tag for the logs. */
private const val TAG = "SmartAutoClickerService"