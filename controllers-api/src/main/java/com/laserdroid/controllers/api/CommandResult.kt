package com.laserdroid.controllers.api

/**
 * Outcome of a line command sent to the controller.
 */
sealed interface CommandResult {

    /** `ok`: the line was accepted (not necessarily executed). */
    data object Acknowledged : CommandResult

    /** `error:N`, or refused locally ([code] null). */
    data class Rejected(
        val code: Int?
    ) : CommandResult

    /** `ALARM:N` received while waiting. */
    data class Alarm(
        val code: Int?
    ) : CommandResult

    /** No data from the controller during the timeout. */
    data object Timeout : CommandResult

    /** The link is closed or was lost. */
    data object Disconnected : CommandResult

    /**
     * The controller restarted (soft/hard reset) before
     * acknowledging the command: it was discarded.
     */
    data object Reset : CommandResult
}