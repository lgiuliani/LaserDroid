package com.laserdroid.core.machine

import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState

/**
 * Outcome of a manual operation that waits for the motion to end
 * (jog, HOME, framing, move to).
 */
sealed interface MachineOperationResult {

    /** Machine Idle again at [finalPosition]. */
    data class Completed(
        val finalPosition: MachinePosition
    ) : MachineOperationResult

    /** The command was not acknowledged. */
    data class CommandRejected(
        val result: CommandResult
    ) : MachineOperationResult

    /** Refused, or ended in an unexpected [machineState] (Alarm, Hold...). */
    data class Interrupted(
        val machineState: MachineState
    ) : MachineOperationResult

    /** Still moving after [timeoutMillis]. */
    data class Timeout(
        val timeoutMillis: Long,
        val lastPosition: MachinePosition
    ) : MachineOperationResult

    /** Unexpected exception. */
    data class Failed(
        val message: String,
        val cause: Throwable? = null
    ) : MachineOperationResult
}
