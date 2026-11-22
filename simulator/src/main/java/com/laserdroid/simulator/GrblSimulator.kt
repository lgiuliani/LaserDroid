package com.laserdroid.simulator

class GrblSimulator {

    var disconnectAtCommand: Int? = null

    private val _receivedCommands =
        mutableListOf<String>()

    val receivedCommands: List<String>
        get() = _receivedCommands.toList()

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

    fun clearHistory() {
        _receivedCommands.clear()
    }
}