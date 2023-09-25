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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.SmartAutoClickerService
import com.buzbuz.smartautoclicker.my.IScenarioTransmit
import com.buzbuz.smartautoclicker.my.ScenarioTransmit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Entry point activity for the application.
 * Shown when the user clicks on the launcher icon for the application, this activity will displays the list of
 * available scenarios, if any.
 */
class ScenarioActivity : AppCompatActivity() {

    /** ViewModel providing the click scenarios data to the UI. */
    private val scenarioViewModel: ScenarioViewModel by viewModels()

    private enum class previousStates { STOP_SLIDE, LEFT_SLIDE, RIGHT_SLIDE }
    private var previousState = previousStates.STOP_SLIDE
    /** The repository for the pro mode billing. */
    private val scenarioTransmit: ScenarioTransmit = IScenarioTransmit.getScenarioTransmit()

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scenario)

        scenarioViewModel.stopScenario()

        CoroutineScope(Dispatchers.Main).launch {
            testFun()
        }

        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter())
    }
    private suspend fun testFun() {
        val flow = flowOf(1, 2).onEach { delay(1000) }
        val flow2 = flowOf("a", "b", "c").onEach { delay(1500) }
        flow.combine(flow2) { flow1_data, flow2_data -> "$flow1_data $flow2_data" }.collect {
            println("my first coroutine $it")
        }
    }


    private val mGattUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SmartAutoClickerService.ACTION_GATT_CONNECTED -> {}
                SmartAutoClickerService.ACTION_GATT_DISCONNECTED -> {Toast.makeText(context, "ACTION_GATT_DISCONNECTED", Toast.LENGTH_SHORT).show()}
                SmartAutoClickerService.ACTION_GATT_SERVICES_DISCOVERED -> {
                    Toast.makeText(context, "ACTION_GATT_SERVICES_DISCOVERED", Toast.LENGTH_SHORT).show()
                }
                SmartAutoClickerService.ACTION_DATA_AVAILABLE -> {
                    if(intent.getByteArrayExtra(SmartAutoClickerService.MIO_DATA_NEW) != null) displayDataNew(intent.getByteArrayExtra(SmartAutoClickerService.MIO_DATA_NEW))
                }
            }
        }
    }
    private fun displayDataNew(data: ByteArray?) {
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
//            isActionHappens = if (previousState == previousStates.STOP_SLIDE) {
//                flowOf(false)
//            } else {
//                flowOf(true)
//            }
            scenarioTransmit.isInversionOn = if (previousState == previousStates.LEFT_SLIDE) {
                flowOf(true)
            } else {
                flowOf(false)
            }
        }
    }
//    override var isActionHappens: Flow<Boolean> = flowOf(true)



    private fun makeGattUpdateIntentFilter(): IntentFilter {
        val intentFilter = IntentFilter()
        intentFilter.addAction(SmartAutoClickerService.ACTION_GATT_CONNECTED)
        intentFilter.addAction(SmartAutoClickerService.ACTION_GATT_DISCONNECTED)
        intentFilter.addAction(SmartAutoClickerService.ACTION_GATT_SERVICES_DISCOVERED)
        intentFilter.addAction(SmartAutoClickerService.ACTION_DATA_AVAILABLE)
        return intentFilter
    }


    private fun castUnsignedCharToInt(Ubyte: Byte): Int {
        var cast = Ubyte.toInt()
        if (cast < 0) {
            cast += 256
        }
        return cast
    }
}
