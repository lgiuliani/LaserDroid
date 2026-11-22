package com.laserdroid.controllers.api

sealed interface MachineState {

    data object Disconnected : MachineState

    data object Connecting : MachineState

    data object Idle : MachineState

    data object Running : MachineState

    data object Hold : MachineState

    data object Alarm : MachineState

    data class Error(
        val message: String
    ) : MachineState

    data object Jog : MachineState
}