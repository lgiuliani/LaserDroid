package com.laserdroid.controllers.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NO_WORK_OFFSET: StateFlow<MachinePosition> =
    MutableStateFlow(MachinePosition.ZERO)

/**
 * Common abstraction of a CNC controller driver (GRBL, grblHAL...).
 *
 * Implementations own the protocol only: the physical link is a
 * separate transport. All functions are main-safe and may be
 * called from any coroutine.
 */
interface CncController {

    /** Last known controller state. */
    val machineState:
            StateFlow<MachineState>

    /** Last known machine position (MPos), millimeters. */
    val machinePosition:
            StateFlow<MachinePosition>

    /** Controller messages, errors, alarms, settings and resets. */
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

    /** Features supported by the driver. */
    val capabilities:
            ControllerCapabilities

    /**
     * Opens the transport and requests a first status.
     * Throws if the link cannot be opened.
     */
    suspend fun connect()

    /** Closes the transport. A pending command gets [CommandResult.Disconnected]. */
    suspend fun disconnect()

    /**
     * Sends a line command and waits for its acknowledgement with
     * the driver default timeout.
     */
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

    /** Realtime status query (GRBL `?`). The answer updates the flows. */
    suspend fun requestStatus()

    /** Realtime feed hold (GRBL `!`). */
    suspend fun pause()

    /** Realtime cycle start (GRBL `~`). */
    suspend fun resume()

    /** Realtime soft reset (GRBL Ctrl-X): immediate stop, laser off. */
    suspend fun stop()

    /** Relative jog. Rejected when the machine is not Idle or Jog. */
    suspend fun jog(
        request: JogRequest
    ): CommandResult

    /** Jog to work X0 Y0 at [feedRate] mm/min. */
    suspend fun jogToZero(
        feedRate: Double
    ): CommandResult

    /** Realtime jog cancel: decelerates and flushes the jog motions. */
    suspend fun cancelJog()

    /**
     * Releases the driver resources (internal coroutines).
     * The controller must not be used afterwards.
     */
    fun close() = Unit
}