package com.laserdroid.core.machine

import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState

sealed interface MachineOperationResult {

    data class Completed(
        val finalPosition: MachinePosition
    ) : MachineOperationResult

    data class CommandRejected(
        val result: CommandResult
    ) : MachineOperationResult

    data class Interrupted(
        val machineState: MachineState
    ) : MachineOperationResult

    data class Timeout(
        val timeoutMillis: Long,
        val lastPosition: MachinePosition
    ) : MachineOperationResult

    data class Failed(
        val message: String,
        val cause: Throwable? = null
    ) : MachineOperationResult
}
