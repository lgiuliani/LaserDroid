package com.laserdroid.core.streaming

import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.core.gcode.GCodeProgram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SendResponseStreamingEngine(
    private val controller: CncController
) {

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

        val executableLines =
            program.executableLines().toList()

        _state.value =
            StreamingState.Running

        _progress.value =
            StreamingProgress.initial(
                executableLines.size
            )

        var commandsSent = 0
        var commandsAcknowledged = 0

        for ((originalLineNumber, _, command1) in executableLines) {

            val command = command1
                ?: continue

            commandsSent++

            _progress.value =
                StreamingProgress(
                    totalCommands =
                        executableLines.size,
                    commandsSent =
                        commandsSent,
                    commandsAcknowledged =
                        commandsAcknowledged,
                    currentOriginalLine =
                        originalLineNumber
                )

            val result =
                controller.executeCommand(command)

            when (result) {

                CommandResult.Acknowledged -> {

                    commandsAcknowledged++

                    _progress.value =
                        StreamingProgress(
                            totalCommands =
                                executableLines.size,
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