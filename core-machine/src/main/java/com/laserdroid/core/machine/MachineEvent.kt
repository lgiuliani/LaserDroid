package com.laserdroid.core.machine

import com.laserdroid.controllers.api.MachinePosition

/**
 * Notifications of [MachineManager] (logs, UI feedback).
 */
sealed interface MachineEvent {

    data object Connected :
        MachineEvent

    data object Disconnected :
        MachineEvent

    data class JogStarted(
        val description: String
    ) : MachineEvent

    data class PositionUpdated(
        val position: MachinePosition
    ) : MachineEvent

    data class JogCompleted(
        val position: MachinePosition
    ) : MachineEvent

    data object JogCancelled :
        MachineEvent

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : MachineEvent
}