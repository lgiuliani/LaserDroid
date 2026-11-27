package com.laserdroid.core.streaming

/**
 * State of a [SendResponseStreamingEngine].
 */
sealed interface StreamingState {

    data object Idle : StreamingState

    data object Running : StreamingState

    /** Every line was acknowledged (the motion may still run). */
    data class Completed(
        val commandsSent: Int
    ) : StreamingState

    /**
     * The streaming coroutine was canceled (job stopped).
     * [lineNumber] is the last line sent, if any.
     */
    data class Cancelled(
        val lineNumber: Long?,
        val commandsSent: Int,
        val commandsAcknowledged: Int
    ) : StreamingState

    /** Stopped at [lineNumber] (original line) for [reason]. */
    data class Failed(
        val lineNumber: Long,
        val command: String,
        val reason: StreamingFailure
    ) : StreamingState
}
