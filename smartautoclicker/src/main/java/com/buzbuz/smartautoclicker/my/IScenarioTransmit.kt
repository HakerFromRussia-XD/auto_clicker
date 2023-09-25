package com.buzbuz.smartautoclicker.my

import kotlinx.coroutines.flow.Flow

interface IScenarioTransmit {

    companion object {

        /** Singleton preventing multiple instances of the repository at the same time. */
        @Volatile
        private var INSTANCE: ScenarioTransmit? = null

        /**
         * Get the repository singleton, or instantiates it if it wasn't yet.
         *
         * @return the repository singleton.
         */
        fun getScenarioTransmit(): ScenarioTransmit {
            return INSTANCE ?: synchronized(this) {
                val instance = ScenarioTransmit()
                INSTANCE = instance
                instance
            }
        }
//        lateinit var isActionHappens: MutableStateFlow<Boolean>
    }

//    var isActionHappens: Flow<Boolean>
    var isInversionOn: Flow<Boolean>
}