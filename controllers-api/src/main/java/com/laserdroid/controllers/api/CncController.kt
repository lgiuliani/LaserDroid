package com.laserdroid.controllers.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NO_WORK_OFFSET: StateFlow<MachinePosition> =
    MutableStateFlow(MachinePosition.ZERO)

interface CncController {

    val machineState:
            StateFlow<MachineState>

    val machinePosition:
            StateFlow<MachinePosition>

    val events:
            Flow<ControllerEvent>

    /**
     * Last known work coordinate offset (WCO).
     *
     * Work position = machine position - offset.
     * Drivers that do not report offsets expose zero.
     */
    val workCoordinateOffset:
            StateFlow<MachinePosition>
        get() = NO_WORK_OFFSET

    val capabilities:
            ControllerCapabilities

    suspend fun connect()

    suspend fun disconnect()

    suspend fun executeCommand(
        command: String
    ): CommandResult

    /**
     * Sends a line command and waits for its acknowledgement.
     *
     * The timeout is an inactivity timeout: it is restarted every
     * time the controller sends any data (status reports included).
     * This allows very long motions to fill the planner buffer
     * while still detecting a silent controller.
     *
     * Drivers that do not support this mode fall back to
     * [executeCommand].
     */
    suspend fun executeCommand(
        command: String,
        inactivityTimeoutMillis: Long
    ): CommandResult =
        executeCommand(command)

    suspend fun requestStatus()

    suspend fun pause()

    suspend fun resume()

    suspend fun stop()

    suspend fun jog(
        request: JogRequest
    ): CommandResult

    suspend fun jogToZero(
        feedRate: Double
    ): CommandResult

    suspend fun cancelJog()
}