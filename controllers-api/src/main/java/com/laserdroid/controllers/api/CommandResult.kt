package com.laserdroid.controllers.api

sealed interface CommandResult {

    data object Acknowledged : CommandResult

    data class Rejected(
        val code: Int?
    ) : CommandResult

    data class Alarm(
        val code: Int?
    ) : CommandResult

    data object Timeout : CommandResult

    data object Disconnected : CommandResult

    /**
     * The controller restarted (soft/hard reset) before
     * acknowledging the command: it was discarded.
     */
    data object Reset : CommandResult
}