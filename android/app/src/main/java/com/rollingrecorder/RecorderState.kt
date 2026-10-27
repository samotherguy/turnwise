package com.rollingrecorder

import kotlinx.coroutines.flow.MutableStateFlow

enum class Status { STOPPED, STARTING, RECORDING, PAUSED, RETRYING }

/** The chunk being written right now: its start and loudness per second so far. */
class LiveChunk(val startMs: Long, val levels: ByteArray)

/** Process-wide state shared between the service, the engine thread and the UI. */
object RecorderState {
    val status = MutableStateFlow(Status.STOPPED)

    /** Bumped every time a chunk is finalised or deleted, so the UI can refresh. */
    val chunkTick = MutableStateFlow(0)

    /** Levels of the in-progress chunk, so the timeline reaches "now". Null between chunks. */
    val live = MutableStateFlow<LiveChunk?>(null)

    /** The running engine, or null when recording is stopped. */
    @Volatile
    var engine: RecordingEngine? = null
}
