package com.laserdroid.core.jobs

/**
 * Job lifecycle (specification section 8).
 *
 * EMPTY -> LOADING -> READY -> STARTING -> RUNNING
 *     -> HOLDING -> PAUSED -> RESUMING -> RUNNING
 *     -> COMPLETING -> COMPLETED
 *
 * Alternative end states: ERROR, ABORTED, DISCONNECTED.
 * A job is never resumed automatically.
 */
enum class JobState {
    EMPTY,
    LOADING,
    READY,
    STARTING,
    RUNNING,
    HOLDING,
    PAUSED,
    RESUMING,
    COMPLETING,
    COMPLETED,
    ERROR,
    ABORTED,
    DISCONNECTED,

    /** Reserved for the future manual recovery feature. */
    RECOVERY_REQUIRED;

    /** The job owns the machine. */
    val isActive: Boolean
        get() = this == STARTING ||
                this == RUNNING ||
                this == HOLDING ||
                this == PAUSED ||
                this == RESUMING ||
                this == COMPLETING

    /** A loaded program can be (re)started from this state. */
    val canStart: Boolean
        get() = this == READY ||
                this == COMPLETED ||
                this == ERROR ||
                this == ABORTED ||
                this == DISCONNECTED

    val canPause: Boolean
        get() = this == RUNNING || this == RESUMING

    val canResume: Boolean
        get() = this == HOLDING || this == PAUSED
}
