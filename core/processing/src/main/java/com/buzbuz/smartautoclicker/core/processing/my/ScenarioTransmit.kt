package com.buzbuz.smartautoclicker.core.processing.my

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class ScenarioTransmit internal constructor() : IScenarioTransmit {

//    companion object {
//        /** Singleton preventing multiple instances of the DebuggingRepository at the same time. */
//        @Volatile
//        private var INSTANCE: ScenarioTransmit? = null
//
//        /**
//         * Get the DetectionRepository singleton, or instantiates it if it wasn't yet.
//         *
//         * @return the DetectionRepository singleton.
//         */
//        fun getScenarioTransmit(): ScenarioTransmit {
//            return INSTANCE ?: synchronized(this) {
//                val instance = ScenarioTransmit()
//                INSTANCE = instance
//                instance
//            }
//        }
//    }

    override var isInversionOn: Flow<Int> = flowOf(0)
    private var _state = 0
    override var state: Int
        get() = _state
        set(value) { _state = value }
}