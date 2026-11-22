package com.laserdroid.core.streaming

sealed interface StreamingState {

    data object Idle : StreamingState

    data object Running : StreamingState

    data class Completed(
        val commandsSent: Int
    ) : StreamingState

    /**
     * The streaming coroutine was cancelled (job stopped).
     * [lineNumber] is the last line sent, if any.
     */
    data class Cancelled(
        val lineNumber: Long?,
        val commandsSent: Int,
        val commandsAcknowledged: Int
    ) : StreamingState

    data class Failed(
        val lineNumber: Long,
        val command: String,
        val reason: StreamingFailure
    ) : StreamingState
}