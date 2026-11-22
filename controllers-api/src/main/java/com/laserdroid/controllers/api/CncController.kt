package com.laserdroid.controllers.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CncController {

    val machineState:
            StateFlow<MachineState>

    val machinePosition:
            StateFlow<MachinePosition>

    val events:
            Flow<ControllerEvent>

    val capabilities:
            ControllerCapabilities

    suspend fun connect()

    suspend fun disconnect()

    suspend fun executeCommand(
        command: String
    ): CommandResult

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