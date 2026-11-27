package com.laserdroid.controllers.api

/**
 * Controller state, independent of the driver.
 *
 * GRBL mapping: Door -> [Hold], Home -> [Running].
 */
sealed interface MachineState {

    data object Disconnected : MachineState

    data object Connecting : MachineState

    data object Idle : MachineState

    data object Running : MachineState

    data object Hold : MachineState

    data object Alarm : MachineState

    /** Link or transport failure (not a rejected line). */
    data class Error(
        val message: String
    ) : MachineState

    data object Jog : MachineState
}