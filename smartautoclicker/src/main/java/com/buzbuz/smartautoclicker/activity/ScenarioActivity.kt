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
package com.buzbuz.smartautoclicker.activity

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.my.BluetoothLeService
import com.buzbuz.smartautoclicker.my.IScenarioTransmit
import com.buzbuz.smartautoclicker.my.SampleGattAttributes.MIO_MEASUREMENT_NEW_VM
import com.buzbuz.smartautoclicker.my.SampleGattAttributes.REQUEST_ENABLE_BT
import com.buzbuz.smartautoclicker.my.SampleGattAttributes.lookup
import com.buzbuz.smartautoclicker.my.ScenarioTransmit
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.coroutines.flow.flowOf

/**
 * Entry point activity for the application.
 * Shown when the user clicks on the launcher icon for the application, this activity will displays the list of
 * available scenarios, if any.
 */
class ScenarioActivity : AppCompatActivity() {

    /// BLE
    private var mDeviceAddress = "45:4D:DA:63:8C:07"//"C1:54:5A:AF:4C:CB"//mac-подключаемого устройства

    private val STATE_DISCONNECTED = 0
    private val STATE_CONNECTING = 1
    private val STATE_CONNECTED = 2

    private var mScanning = false
    private var mConnected = false
    private var subscribeThreadFlag = true
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothLeService: BluetoothLeService? = null
    private var mConnectionState = this.STATE_DISCONNECTED
    private var mGattCharacteristics = ArrayList<ArrayList<BluetoothGattCharacteristic>>()
    // Code to manage Service lifecycle.
    private val mServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
//            System.err.println("my ServiceConnection onServiceConnected()")
            mBluetoothLeService = (service as BluetoothLeService.LocalBinder).service
            if (!mBluetoothLeService?.initialize()!!) {
                finish()
            }
//            System.err.println("my ServiceConnection connect!!!!!")
            mBluetoothLeService?.connect(mDeviceAddress)
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
//            System.err.println("my ServiceConnection onServiceDisconnected()")
            mBluetoothLeService = null
        }
    }
    private val mGattUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when {
                BluetoothLeService.ACTION_GATT_CONNECTED == action -> {
//                    System.err.println("my ACTION_GATT_CONNECTED")
                }
                BluetoothLeService.ACTION_GATT_DISCONNECTED == action -> {
                    System.err.println("my ACTION_GATT_DISCONNECTED")
                    mConnected = false
                    reconnect()
                }
                BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED == action -> {
                    System.err.println("my ACTION_GATT_SERVICES_DISCOVERED")
                    mConnected = true
                    if (mBluetoothLeService != null) {
                        displayGattServices(mBluetoothLeService!!.supportedGattServices)
                        startSubscribeSensorsDataThread()
                    }
                }
                BluetoothLeService.ACTION_DATA_AVAILABLE == action -> {
//                    System.err.println("my ACTION_DATA_AVAILABLE")

                    if (intent.getByteArrayExtra(BluetoothLeService.MIO_DATA_NEW) != null) {
                        displayData(intent.getByteArrayExtra(BluetoothLeService.MIO_DATA_NEW))
                        subscribeThreadFlag = false
                    }
                }
            }
        }
    }

    /** ViewModel providing the click scenarios data to the UI. */
    private val scenarioViewModel: ScenarioViewModel by viewModels()

    private enum class previousStates { STOP_SLIDE, LEFT_SLIDE, RIGHT_SLIDE }
    private var previousState = previousStates.STOP_SLIDE
    /** The repository for the pro mode billing. */
    private val scenarioTransmit: ScenarioTransmit = IScenarioTransmit.getScenarioTransmit()

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scenario)
        scenarioViewModel.stopScenario()



        //мой код
//        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
//        mBluetoothAdapter = bluetoothManager.adapter
//        val gattServiceIntent = Intent(this, BluetoothLeService::class.java)
//        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE)

        // foreground service
//        val serviceIntent = Intent(
//            this,
//            BluetoothLeService::class.java
//        )
//        startForegroundService(serviceIntent)

//        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter())



//        askPermissions()
//        scanLeDevice(true)
        System.err.println("my ScenarioActivity onCreate")
    }
    override fun onResume() {
        super.onResume()
        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
//        if (!mBluetoothAdapter!!.isEnabled) {
//            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
//            if (ActivityCompat.checkSelfPermission(
//                    this,
//                    Manifest.permission.BLUETOOTH_CONNECT
//                ) != PackageManager.PERMISSION_GRANTED
//            ) { return } else {
//                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
//            }
//        }
//
//        if (mBluetoothLeService != null) {}
    }
    override fun onDestroy() {
        super.onDestroy()
//        if (mBluetoothLeService != null) {
//            unbindService(mServiceConnection)
//            mBluetoothLeService = null
//            unregisterReceiver(mGattUpdateReceiver)
//        }
//        if (mScanning) {
//            if (ActivityCompat.checkSelfPermission(
//                    this,
//                    Manifest.permission.BLUETOOTH_SCAN
//                ) != PackageManager.PERMISSION_GRANTED
//            ) {
//                Toast.makeText(this,"Permissions not granted", Toast.LENGTH_SHORT).show()
//                return
//            }
//            mBluetoothAdapter!!.stopLeScan(mLeScanCallback) }
    }


    @SuppressLint("MissingPermission")
    private fun scanLeDevice(enable: Boolean) {
        if (mBluetoothAdapter != null) {
            if (enable) {
                System.err.println("my scanLeDevice mBluetoothAdapter enable = true")
                mScanning = true
                mBluetoothAdapter!!.startLeScan(mLeScanCallback)
            } else {
                System.err.println("my scanLeDevice mBluetoothAdapter  enable = false")
                mScanning = false
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
            runOnUiThread {
                if (device.name != null) {
                    System.err.println("my scanLeDevice Callback ${device.name} : ${device.address}")
                    if (device.name.contains("FEST-X")) {
                        mDeviceAddress = device.address
//                        mDeviceAddress = device.toString()
                        scanLeDevice(false)
                        reconnect()
                    }
                }
            }
        }

    fun disconnect () {
        System.err.println("Check disconnect()")
        if (mBluetoothLeService != null) {
            mBluetoothLeService!!.disconnect()
            unbindService(mServiceConnection)
            mBluetoothLeService = null
        }
        mConnected = false
    }
    private fun reconnect () {
        //полное завершение сеанса связи и создание нового в onResume
//        System.err.println("my reconnect()")
        if (mBluetoothLeService != null) {
            unbindService(mServiceConnection)
            mBluetoothLeService = null
        }

        val gattServiceIntent = Intent(this, BluetoothLeService::class.java)
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE)

        //BLE
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter())
        if (mBluetoothLeService != null) {
            System.err.println("my connect!!!!!")
            mBluetoothLeService!!.connect(mDeviceAddress)
        }
    }


    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private fun displayGattServices(gattServices: List<BluetoothGattService>?) {
//        System.err.println("my ------->   момент начала выстраивания списка параметров")
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
            uuid = gattService.uuid.toString()
            currentServiceData["NAME"] = lookup(uuid, unknownServiceString)
            currentServiceData["UUID"] = uuid
            gattServiceData.add(currentServiceData)
            val gattCharacteristicGroupData = ArrayList<HashMap<String, String?>>()
            val gattCharacteristics = gattService.characteristics
            val charas = ArrayList<BluetoothGattCharacteristic>()

            // Loops through available Characteristics.
            for (gattCharacteristic in gattCharacteristics) {
                charas.add(gattCharacteristic)
                val currentCharaData = HashMap<String, String?>()
                uuid = gattCharacteristic.uuid.toString()
                currentCharaData["NAME"] = lookup(uuid, unknownCharaString)
                currentCharaData["UUID"] = uuid
                gattCharacteristicGroupData.add(currentCharaData)
//                System.err.println("my ------->   ХАРАКТЕРИСТИКА: $uuid")
            }
            mGattCharacteristics.add(charas)
            gattCharacteristicData.add(gattCharacteristicGroupData)
        }
    }
    private fun bleCommand(uuid: String): Boolean {
        if (mBluetoothLeService != null) {
//            System.err.println("my найденные характеристики ===============")
            for (i in mGattCharacteristics.indices) {
                for (j in mGattCharacteristics[i].indices) {
//                    System.err.println("my найденные характеристики " + i + " " + j + " :" + mGattCharacteristics[i][j].uuid.toString() + "    искомая: " + uuid)
                    if (mGattCharacteristics[i][j].uuid.toString() == uuid) {
//                        System.err.println("my нужная характеристика найдена")
                        val mCharacteristic = mGattCharacteristics[i][j]
//                        if (mBluetoothAdapter == null ) {
////                            System.err.println("my mBluetoothAdapter = null")
//                            return false
//                        } else {
////                            System.err.println("my mBluetoothAdapter существуют")
//                        }

                        mBluetoothLeService!!.setCharacteristicNotification(
                            mCharacteristic, true)
                    }
                }
            }
        }
        return true
    }
    private fun startSubscribeSensorsDataThread() {
        val subscribeThread = Thread {
            while (subscribeThreadFlag) {
                runOnUiThread {
                    bleCommand(MIO_MEASUREMENT_NEW_VM)
//                    System.err.println("my startSubscribeSensorsDataThread попытка подписки")
                }
                try {
                    Thread.sleep(500)
                } catch (ignored: Exception) { }
            }
        }
        subscribeThread.start()
    }

    private fun displayData(data: ByteArray?) {
        if (data != null) {
            val dataSens1 = castUnsignedCharToInt(data[0])
            val dataSens2 = castUnsignedCharToInt(data[1])
            val sensorLevel = 100
            val rightSlide: Boolean = dataSens1 > sensorLevel
            val leftSlide: Boolean = dataSens2 > sensorLevel


            //если решение лететь налево
            if (rightSlide && leftSlide && previousState === previousStates.LEFT_SLIDE || !rightSlide && leftSlide){
                previousState = previousStates.LEFT_SLIDE
            }


            //если решение лететь направо
            if (rightSlide && leftSlide && previousState === previousStates.RIGHT_SLIDE || rightSlide && !leftSlide) {
                previousState = previousStates.RIGHT_SLIDE
            }


            //если решение не поворачивать
            if (!rightSlide && !leftSlide) {
                previousState = previousStates.STOP_SLIDE
            }

            System.err.println("my $previousState")


            //TODO отправлять результат
            /** Tells if the limitation in scenario count have been reached. */
            scenarioTransmit.isInversionOn = if (previousState == previousStates.LEFT_SLIDE) {
                flowOf(true)
            } else {
                flowOf(false)
            }
        }
    }

    private fun makeGattUpdateIntentFilter(): IntentFilter {
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED)
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED)
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE)
        return intentFilter
    }
    private fun castUnsignedCharToInt(ubyte: Byte): Int {
        var cast = ubyte.toInt()
        if (cast < 0) {
            cast += 256
        }
        return cast
    }
    fun askPermissions() {
//        System.err.println("my askPermissions()")
        Dexter.withActivity(this).withPermissions(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    if (report.areAllPermissionsGranted()) {

                    } else {
                        askPermissions()
                    }
//                    System.err.println("my onPermissionsChecked()")
                }

                override fun onPermissionRationaleShouldBeShown(
                    permissions: MutableList<com.karumi.dexter.listener.PermissionRequest>?,
                    token: PermissionToken?
                ) {
                    System.err.println("my onPermissionRationaleShouldBeShown()")
                    token?.continuePermissionRequest()
                }
            }).check()
    }
}
