package com.laserdroid.core.streaming

/**
 * Progress of the current stream: commands sent and acknowledged.
 * An acknowledgement does not mean the motion was executed.
 */
data class StreamingProgress(
    val totalCommands: Int,
    val commandsSent: Int,
    val commandsAcknowledged: Int,
    val currentOriginalLine: Long?
) {

    /** Acknowledged commands, 0..100. */
    val percent: Double
        get() {
            if (totalCommands == 0) {
                return 100.0
            }

            return (
                    commandsAcknowledged.toDouble() /
                            totalCommands.toDouble()
                    ) * 100.0
        }

    companion object {

        fun initial(
            totalCommands: Int
        ): StreamingProgress {

            return StreamingProgress(
                totalCommands = totalCommands,
                commandsSent = 0,
                commandsAcknowledged = 0,
                currentOriginalLine = null
            )
        }
    }
}