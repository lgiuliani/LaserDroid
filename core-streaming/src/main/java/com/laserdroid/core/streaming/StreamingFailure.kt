package com.laserdroid.core.streaming

/**
 * Why a stream stopped before its end.
 */
sealed interface StreamingFailure {

    data class Rejected(
        val code: Int?
    ) : StreamingFailure

    data class Alarm(
        val code: Int?
    ) : StreamingFailure

    data object Timeout : StreamingFailure

    data object Disconnected : StreamingFailure

    /** The controller restarted during the stream. */
    data object Reset : StreamingFailure
}