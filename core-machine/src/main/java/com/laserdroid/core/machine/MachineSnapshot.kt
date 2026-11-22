package com.laserdroid.core.machine

import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState

data class MachineSnapshot(
    val connectionState: MachineConnectionState,
    val machineState: MachineState,
    val position: MachinePosition,
    val lastUpdateTimestamp: Long
) {

    val isConnected: Boolean
        get() =
            connectionState ==
                    MachineConnectionState.CONNECTED

    val canJog: Boolean
        get() =
            machineState == MachineState.Idle ||
                    machineState == MachineState.Jog

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
