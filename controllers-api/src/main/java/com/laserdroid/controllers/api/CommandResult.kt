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
}