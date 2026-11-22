package com.laserdroid.controllers.grbl

class GrblLineBuffer {

    private val buffer = StringBuilder()

    fun append(data: ByteArray): List<String> {
        return append(data.decodeToString())
    }

    fun append(data: String): List<String> {

        buffer.append(data)

        val completedLines = mutableListOf<String>()

        while (true) {

            val lineEndIndex = findLineEnd()

            if (lineEndIndex < 0) {
                break
            }

            val line = buffer
                .substring(0, lineEndIndex)
                .trim()

            removeCompletedLine(lineEndIndex)

            if (line.isNotEmpty()) {
                completedLines.add(line)
            }
        }

        return completedLines
    }

    fun clear() {
        buffer.clear()
    }

    fun pendingContent(): String {
        return buffer.toString()
    }

    private fun findLineEnd(): Int {

        for ((index, element) in buffer.withIndex()) {
            val character = element

            if (character == '\r' || character == '\n') {
                return index
            }
        }

        return -1
    }

    private fun removeCompletedLine(
        lineEndIndex: Int
    ) {
        var charactersToRemove = lineEndIndex + 1

        while (
            charactersToRemove < buffer.length &&
            (
                    buffer[charactersToRemove] == '\r' ||
                            buffer[charactersToRemove] == '\n'
                    )
        ) {
            charactersToRemove++
        }

        buffer.delete(
            0,
            charactersToRemove
        )
    }
}