package com.buzbuz.smartautoclicker.my

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ScenarioTransmit: IScenarioTransmit {

    override var isInversionOn: Flow<Boolean> = flowOf(false)
}