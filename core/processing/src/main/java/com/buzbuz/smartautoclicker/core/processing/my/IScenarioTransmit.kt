package com.buzbuz.smartautoclicker.core.processing.my

import kotlinx.coroutines.flow.Flow


interface IScenarioTransmit {
    companion object {
        /** Singleton preventing multiple instances of the repository at the same time. */
        @Volatile
        private var INSTANCE: IScenarioTransmit? = null

        /**
         * Get the repository singleton, or instantiates it if it wasn't yet.
         *
         * @return the repository singleton.
         */
        fun getScenarioTransmit(): IScenarioTransmit {
            return INSTANCE ?: synchronized(this) {
                val instance = ScenarioTransmit()
                INSTANCE = instance
                instance
            }
        }
    }

    var isInversionOn: Flow<Int>
    var state: Int
}