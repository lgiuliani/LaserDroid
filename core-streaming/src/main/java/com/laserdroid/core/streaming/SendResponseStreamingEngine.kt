package com.laserdroid.core.streaming

import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.core.gcode.GCodeLine
import com.laserdroid.core.gcode.GCodeProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Send/Response streaming: one line is sent, then the engine waits
 * for its ok/error before sending the next one.
 *
 * @param ackInactivityTimeoutMillis timeout used while waiting for an
 * acknowledgement. With GRBL it is an inactivity timeout restarted by
 * every incoming byte (status reports included), so long motions do
 * not fail as long as the machine is polled.
 */
class SendResponseStreamingEngine(
    private val controller: CncController,
    private val ackInactivityTimeoutMillis: Long = DEFAULT_ACK_TIMEOUT_MILLIS
) {

    companion object {
        const val DEFAULT_ACK_TIMEOUT_MILLIS = 2_000L
    }

    private val _state =
        MutableStateFlow<StreamingState>(
            StreamingState.Idle
        )

    val state: StateFlow<StreamingState>
        get() = _state

    private val _progress =
        MutableStateFlow(
            StreamingProgress.initial(0)
        )

    val progress: StateFlow<StreamingProgress>
        get() = _progress

    suspend fun execute(
        program: GCodeProgram
    ): StreamingState {

        return execute(
            lines = program.executableLines(),
            totalCommands = program.executableCommandCount
        )
    }

    /**
     * Streams [lines] lazily. Non executable lines are skipped.
     *
     * [totalCommands] is the number of executable lines, used for
     * the progress only (usually known from the analysis).
     *
     * Cancelling the calling coroutine stops the feeding immediately
     * and publishes [StreamingState.Cancelled]. Commands already in
     * the controller buffer are not affected: stopping the machine is
     * the caller's responsibility (feed hold / reset).
     */
    suspend fun execute(
        lines: Sequence<GCodeLine>,
        totalCommands: Int
    ): StreamingState {

        _state.value =
            StreamingState.Running

        _progress.value =
            StreamingProgress.initial(
                totalCommands
            )

        var commandsSent = 0
        var commandsAcknowledged = 0
        var currentLine: Long? = null

        try {

            for (line in lines) {

                if (!line.isExecutable) {
                    continue
                }

                val originalLineNumber =
                    line.originalLineNumber

                val command = line.command
                    ?: continue

                currentLine = originalLineNumber

                commandsSent++

                _progress.value =
                    StreamingProgress(
                        totalCommands =
                            totalCommands,
                        commandsSent =
                            commandsSent,
                        commandsAcknowledged =
                            commandsAcknowledged,
                        currentOriginalLine =
                            originalLineNumber
                    )

                val result =
                    controller.executeCommand(
                        command,
                        ackInactivityTimeoutMillis
                    )

                when (result) {

                    CommandResult.Acknowledged -> {

                        commandsAcknowledged++

                        _progress.value =
                            StreamingProgress(
                                totalCommands =
                                    totalCommands,
                                commandsSent =
                                    commandsSent,
                                commandsAcknowledged =
                                    commandsAcknowledged,
                                currentOriginalLine =
                                    originalLineNumber
                            )
                    }

                    is CommandResult.Rejected -> {
                        return fail(
                            originalLineNumber,
                            command,
                            StreamingFailure.Rejected(
                                result.code
                            )
                        )
                    }

                    is CommandResult.Alarm -> {
                        return fail(
                            originalLineNumber,
                            command,
                            StreamingFailure.Alarm(
                                result.code
                            )
                        )
                    }

                    CommandResult.Timeout -> {
                        return fail(
                            originalLineNumber,
                            command,
                            StreamingFailure.Timeout
                        )
                    }

                    CommandResult.Disconnected -> {
                        return fail(
                            originalLineNumber,
                            command,
                            StreamingFailure.Disconnected
                        )
                    }
                }
            }

        } catch (
            exception: CancellationException
        ) {
            _state.value =
                StreamingState.Cancelled(
                    lineNumber = currentLine,
                    commandsSent = commandsSent,
                    commandsAcknowledged = commandsAcknowledged
                )

            throw exception
        }

        return StreamingState.Completed(
            commandsSent = commandsSent
        ).also {
            _state.value = it
        }
    }

    private fun fail(
        lineNumber: Long,
        command: String,
        reason: StreamingFailure
    ): StreamingState {

        val result =
            StreamingState.Failed(
                lineNumber = lineNumber,
                command = command,
                reason = reason
            )

        _state.value = result

        return result
    }
}