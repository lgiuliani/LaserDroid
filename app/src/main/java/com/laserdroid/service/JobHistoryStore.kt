package com.laserdroid.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * One job execution (specification 8 / 14: Job + JobExecution).
 */
data class JobHistoryEntry(
    val id: Long = 0L,
    val name: String,
    val sizeBytes: Long,
    val commands: Int,
    val width: Double?,
    val height: Double?,
    val estimatedMillis: Long,
    val machine: String?,
    val startedAt: Long,
    val endedAt: Long,
    val state: String,
    val percent: Double,
    val laserOff: Boolean,
    val message: String?
)

/**
 * Job history stored in SQLite.
 *
 * Plain SQLiteOpenHelper rather than Room: no annotation processor
 * (KSP) in the build, same database underneath. A migration to Room
 * stays possible with the same table.
 */
class JobHistoryStore(
    context: Context
) : SQLiteOpenHelper(context.applicationContext, "laserdroid.db", null, 1) {

    companion object {
        private const val TABLE = "job_history"
        private const val MAX_ENTRIES = 500
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                commands INTEGER NOT NULL,
                width REAL,
                height REAL,
                estimated_ms INTEGER NOT NULL,
                machine TEXT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                state TEXT NOT NULL,
                percent REAL NOT NULL,
                laser_off INTEGER NOT NULL,
                message TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 only.
    }

    /** Blocking: call from a background dispatcher. */
    fun insert(entry: JobHistoryEntry) {
        val values = ContentValues().apply {
            put("name", entry.name)
            put("size_bytes", entry.sizeBytes)
            put("commands", entry.commands)
            put("width", entry.width)
            put("height", entry.height)
            put("estimated_ms", entry.estimatedMillis)
            put("machine", entry.machine)
            put("started_at", entry.startedAt)
            put("ended_at", entry.endedAt)
            put("state", entry.state)
            put("percent", entry.percent)
            put("laser_off", if (entry.laserOff) 1 else 0)
            put("message", entry.message)
        }
        val db = writableDatabase
        db.insert(TABLE, null, values)
        db.execSQL(
            "DELETE FROM $TABLE WHERE id NOT IN " +
                    "(SELECT id FROM $TABLE ORDER BY id DESC LIMIT $MAX_ENTRIES)"
        )
    }

    /** Most recent first. Blocking. */
    fun recent(limit: Int = 100): List<JobHistoryEntry> {
        val entries = mutableListOf<JobHistoryEntry>()
        readableDatabase.rawQuery(
            "SELECT id, name, size_bytes, commands, width, height, estimated_ms, machine, " +
                    "started_at, ended_at, state, percent, laser_off, message " +
                    "FROM $TABLE ORDER BY id DESC LIMIT $limit",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += JobHistoryEntry(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    sizeBytes = cursor.getLong(2),
                    commands = cursor.getInt(3),
                    width = if (cursor.isNull(4)) null else cursor.getDouble(4),
                    height = if (cursor.isNull(5)) null else cursor.getDouble(5),
                    estimatedMillis = cursor.getLong(6),
                    machine = if (cursor.isNull(7)) null else cursor.getString(7),
                    startedAt = cursor.getLong(8),
                    endedAt = cursor.getLong(9),
                    state = cursor.getString(10),
                    percent = cursor.getDouble(11),
                    laserOff = cursor.getInt(12) != 0,
                    message = if (cursor.isNull(13)) null else cursor.getString(13)
                )
            }
        }
        return entries
    }

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }
}
