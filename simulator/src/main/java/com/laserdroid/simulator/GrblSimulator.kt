package com.laserdroid.simulator

/**
 * Minimal GRBL 1.1 line protocol simulator: answers `ok` to the
 * supported commands, `error:1` otherwise, and `error:[errorCode]`
 * at command number [errorAtCommand].
 */
class GrblSimulator {

    private val _receivedCommands =
        mutableListOf<String>()

    val receivedCommands: List<String>
        get() = _receivedCommands.toList()

    /** 1-based command number answered with an error, or null. */
    var errorAtCommand: Int? = null

    var errorCode: Int = 2

    fun processCommand(command: String): String {

        val normalizedCommand = command.trim()

        if (normalizedCommand.isNotEmpty()) {
            _receivedCommands.add(normalizedCommand)
        }

        val currentCommandNumber =
            _receivedCommands.size

        if (
            errorAtCommand != null &&
            currentCommandNumber == errorAtCommand
        ) {
            return "error:$errorCode"
        }

        return when {

            normalizedCommand == "?" ->
                "<Idle|MPos:0.000,0.000,0.000|FS:0,0>"

            normalizedCommand == "\$\$" ->
                "ok"

            normalizedCommand.startsWith(
                "\$J="
            ) ->
                "ok"

            normalizedCommand.startsWith("G0") ->
                "ok"

            normalizedCommand.startsWith("G1") ->
                "ok"

            normalizedCommand == "M5" ->
                "ok"

            normalizedCommand.isEmpty() ->
                "ok"

            else ->
                "error:1"
        }
    }
}