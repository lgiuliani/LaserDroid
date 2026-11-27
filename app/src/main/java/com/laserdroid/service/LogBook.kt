package com.laserdroid.service

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Log levels (specification 16). A message is kept when its level
 * is at most the configured one; OFF disables everything.
 */
enum class LogLevel {
    OFF,
    ERROR,
    WARNING,
    INFO,
    DEBUG,
    TRACE
}

/**
 * Centralized log owned by the service: survives the Activity,
 * keeps the last lines in memory for the console and writes a
 * rotating file (laserdroid.log, .1, .2).
 */
class LogBook(
    private val directory: File,
    private val maxFileBytes: Long = 1_000_000L,
    private val maxFiles: Int = 3,
    private val memoryLines: Int = 2_000
) {

    @Volatile
    var level: LogLevel = LogLevel.INFO

    private val memory = ArrayDeque<String>()

    private val _entries =
        MutableSharedFlow<String>(
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    /** New lines, as they are logged. */
    val entries: SharedFlow<String> = _entries.asSharedFlow()

    private val writer = Executors.newSingleThreadExecutor()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fileTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    val currentFile: File
        get() = File(directory, "laserdroid.log")

    fun error(message: String) = log(LogLevel.ERROR, message)
    fun warning(message: String) = log(LogLevel.WARNING, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun trace(message: String) = log(LogLevel.TRACE, message)

    fun isEnabled(messageLevel: LogLevel): Boolean {
        val current = level
        return current != LogLevel.OFF &&
                messageLevel != LogLevel.OFF &&
                messageLevel.ordinal <= current.ordinal
    }

    fun log(messageLevel: LogLevel, message: String) {
        if (!isEnabled(messageLevel)) return

        val now = Date()
        val marker = when (messageLevel) {
            LogLevel.ERROR -> "E "
            LogLevel.WARNING -> "W "
            LogLevel.DEBUG -> "D "
            LogLevel.TRACE -> "T "
            else -> ""
        }
        val line = synchronized(timeFormat) { timeFormat.format(now) } + " " + marker + message

        synchronized(memory) {
            memory.addLast(line)
            while (memory.size > memoryLines) {
                memory.removeFirst()
            }
        }
        _entries.tryEmit(line)

        val fileLine = synchronized(fileTimeFormat) { fileTimeFormat.format(now) } +
                " " + messageLevel.name + " " + message
        try {
            writer.execute { writeToFile(fileLine) }
        } catch (exception: RejectedExecutionException) {
            // Closed: late messages (service shutdown) stay in memory only.
        }
    }

    /** Lines kept in memory, oldest first. */
    fun snapshot(): List<String> = synchronized(memory) { memory.toList() }

    /** Clears the console view; the file is kept. */
    fun clearMemory() {
        synchronized(memory) { memory.clear() }
    }

    /** Stops the file writer; later messages are kept in memory only. */
    fun close() {
        writer.shutdown()
    }

    private fun writeToFile(line: String) {
        runCatching {
            directory.mkdirs()
            val file = currentFile
            if (file.length() > maxFileBytes) {
                rotate()
            }
            file.appendText(line + "\n")
        }
    }

    private fun rotate() {
        for (index in maxFiles - 1 downTo 1) {
            val source = if (index == 1) currentFile else File(directory, "laserdroid.log.${index - 1}")
            val target = File(directory, "laserdroid.log.$index")
            if (source.exists()) {
                target.delete()
                source.renameTo(target)
            }
        }
    }
}
