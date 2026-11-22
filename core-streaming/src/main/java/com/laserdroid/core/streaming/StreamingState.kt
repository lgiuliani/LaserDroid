package com.laserdroid.core.streaming

sealed interface StreamingState {

    data object Idle : StreamingState

    data object Running : StreamingState

    data class Completed(
        val commandsSent: Int
    ) : StreamingState

    data class Failed(
        val lineNumber: Long,
        val command: String,
        val reason: StreamingFailure
    ) : StreamingState
}