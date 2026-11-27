package com.laserdroid.core.machine

import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState

/**
 * Immutable view of the machine published by [MachineManager].
 */
data class MachineSnapshot(
    val connectionState: MachineConnectionState,
    val machineState: MachineState,
    val position: MachinePosition,
    val lastUpdateTimestamp: Long,
    /**
     * True while a job owns the machine. Manual commands
     * (Jog, HOME, settings) are refused.
     */
    val jobActive: Boolean = false
) {

    val isConnected: Boolean
        get() =
            connectionState ==
                    MachineConnectionState.CONNECTED

    /** Manual motion allowed: no job and machine Idle or jogging. */
    val canJog: Boolean
        get() =
            !jobActive &&
                    (
                            machineState == MachineState.Idle ||
                                    machineState == MachineState.Jog
                            )

    companion object {

        fun initial(): MachineSnapshot {

            return MachineSnapshot(
                connectionState =
                    MachineConnectionState.DISCONNECTED,

                machineState =
                    MachineState.Disconnected,

                position =
                    MachinePosition.ZERO,

                lastUpdateTimestamp =
                    System.currentTimeMillis()
            )
        }
    }
}
