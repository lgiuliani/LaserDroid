package com.laserdroid.core.streaming

sealed interface StreamingFailure {

    data class Rejected(
        val code: Int?
    ) : StreamingFailure

    data class Alarm(
        val code: Int?
    ) : StreamingFailure

    data object Timeout : StreamingFailure

    data object Disconnected : StreamingFailure
}