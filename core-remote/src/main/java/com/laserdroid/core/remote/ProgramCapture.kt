package com.laserdroid.core.remote

import java.io.BufferedWriter
import java.io.File

/**
 * Records the program lines received from LaserGRBL into a
 * temporary file. Streaming write: memory does not depend on size.
 *
 * Not thread-safe: used by one session at a time.
 */
class ProgramCapture(
    private val directory: File,
    private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private companion object {
        val MOTION = Regex("(?i)[XYZ]\\s*[-+.0-9]")
    }

    private var writer: BufferedWriter? = null
    private var file: File? = null

    var lines: Long = 0L
        private set
    var bytes: Long = 0L
        private set
    private var motionLines = 0L
    private var lastLineAt = 0L

    val isCapturing: Boolean
        get() = writer != null

    /** Time since the last received line (0 when not capturing). */
    fun idleMillis(): Long = if (isCapturing) clock() - lastLineAt else 0L

    /**
     * Appends one line. Returns false when the size limit is reached
     * (the capture is then canceled).
     */
    fun append(line: String): Boolean {
        val current = writer ?: begin()
        val size = line.length + 1L
        if (bytes + size > maxBytes) {
            cancel()
            return false
        }
        current.write(line)
        current.write("\n")
        lines++
        bytes += size
        if (MOTION.containsMatchIn(line)) motionLines++
        lastLineAt = clock()
        return true
    }

    /**
     * Closes the file. Null if nothing with a motion was received
     * (console commands typed in LaserGRBL are not a program).
     */
    fun finish(): CapturedProgram? {
        val current = writer ?: return null
        val target = file
        current.close()
        writer = null
        file = null
        val result =
            if (target != null && motionLines > 0) {
                CapturedProgram(target, lines, bytes, motionLines)
            } else {
                target?.delete()
                null
            }
        reset()
        return result
    }

    /** Discards a partial program (reset, disconnection, size). */
    fun cancel() {
        runCatching { writer?.close() }
        file?.delete()
        writer = null
        file = null
        reset()
    }

    private fun begin(): BufferedWriter {
        directory.mkdirs()
        val target = File(directory, "received_${clock()}.part")
        file = target
        lastLineAt = clock()
        return target.bufferedWriter(Charsets.UTF_8).also { writer = it }
    }

    private fun reset() {
        lines = 0L
        bytes = 0L
        motionLines = 0L
        lastLineAt = 0L
    }
}
