package com.laserdroid.core.gcode

import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/**
 * Re-readable source of G-code text.
 *
 * Files are never fully loaded in memory: analysis and
 * streaming each open the source and read it line by line.
 */
interface GCodeSource {

    /** Display name (usually the file name). */
    val name: String

    /** Size in bytes, or -1 when unknown. */
    val sizeBytes: Long

    /**
     * Opens a new reader positioned at the beginning of the
     * program. The caller must close it (see [useLines]).
     */
    fun openReader(): BufferedReader
}

/**
 * Opens the source, passes its raw lines to [block] and always
 * closes it afterwards. Inline: [block] may call suspend functions.
 */
inline fun <T> GCodeSource.useLines(
    block: (Sequence<String>) -> T
): T {
    return openReader().use { reader ->
        block(reader.lineSequence())
    }
}

/**
 * [GCodeSource] backed by a local file.
 */
class FileGCodeSource(
    val file: File
) : GCodeSource {

    override val name: String
        get() = file.name

    override val sizeBytes: Long
        get() = file.length()

    override fun openReader(): BufferedReader {
        return file.bufferedReader(Charsets.UTF_8)
    }
}

/**
 * In-memory [GCodeSource], intended for tests and small programs.
 */
class TextGCodeSource(
    override val name: String,
    private val text: String
) : GCodeSource {

    override val sizeBytes: Long
        get() = text.length.toLong()

    override fun openReader(): BufferedReader {
        return BufferedReader(StringReader(text))
    }
}

/**
 * Normalizes raw lines lazily. Line numbers start at 1.
 */
fun Sequence<String>.toGCodeLines(
    normalizer: GCodeNormalizer = GCodeNormalizer()
): Sequence<GCodeLine> {
    return mapIndexed { index, content ->
        normalizer.normalize(
            lineNumber = index + 1L,
            content = content
        )
    }
}
