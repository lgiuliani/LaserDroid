package com.laserdroid.core.gcode

/**
 * Removes comments (`;` and `( )`) and surrounding spaces.
 * The command itself is never altered.
 */
class GCodeNormalizer {

    fun normalize(
        lineNumber: Long,
        content: String
    ): GCodeLine {

        val trimmed = content.trim()

        if (trimmed.isEmpty()) {
            return GCodeLine(
                originalLineNumber = lineNumber,
                originalContent = content,
                command = null,
                type = GCodeLineType.EMPTY
            )
        }

        if (
            trimmed.startsWith(";") ||
            isParenthesisComment(trimmed)
        ) {
            return GCodeLine(
                originalLineNumber = lineNumber,
                originalContent = content,
                command = null,
                type = GCodeLineType.COMMENT
            )
        }

        val withoutComments =
            removeComments(trimmed)
                .trim()

        if (withoutComments.isEmpty()) {
            return GCodeLine(
                originalLineNumber = lineNumber,
                originalContent = content,
                command = null,
                type = GCodeLineType.COMMENT
            )
        }

        return GCodeLine(
            originalLineNumber = lineNumber,
            originalContent = content,
            command = withoutComments,
            type = GCodeLineType.COMMAND
        )
    }

    private fun isParenthesisComment(
        content: String
    ): Boolean {
        return content.startsWith("(") &&
                content.endsWith(")") &&
                findClosingParenthesis(content, 0) ==
                content.lastIndex
    }

    private fun removeComments(
        content: String
    ): String {

        val result = StringBuilder()
        var insideParenthesisComment = false

        content.forEach { character ->

            when {
                character == '(' -> {
                    insideParenthesisComment = true
                }

                character == ')' -> {
                    insideParenthesisComment = false
                }

                character == ';' &&
                        !insideParenthesisComment -> {
                    return result.toString()
                }

                !insideParenthesisComment -> {
                    result.append(character)
                }
            }
        }

        return result.toString()
    }

    private fun findClosingParenthesis(
        content: String,
        startIndex: Int
    ): Int {
        return content.indexOf(
            char = ')',
            startIndex = startIndex + 1
        )
    }
}