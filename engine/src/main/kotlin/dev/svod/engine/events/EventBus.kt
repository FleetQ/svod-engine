package dev.svod.engine.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/** Live event types surfaced over the App API WebSocket (see contract `Event.type`). */
object EventTypes {
    const val FILE_CHANGED = "file.changed"
    const val INDEX_UPDATED = "index.updated"
    const val INDEX_PROGRESS = "index.progress"
    const val COMMIT_CREATED = "commit.created"
    const val CONFLICT = "conflict"
    const val ENGINE_STATUS = "engine.status"
    const val AGENT_ACTIVITY = "agent.activity"
    const val SOURCE_SYNCED = "source.synced"
}

/** One event: a type, a timestamp, and a free-form data object (matches the contract). */
data class SvodEvent(val type: String, val ts: Long, val data: JsonObject)

/**
 * In-process pub/sub for live events. Publishing is non-blocking and lossy under extreme
 * backpressure (drops oldest) so that a slow WebSocket consumer can never stall the write
 * path, the indexer, or the watcher.
 */
class EventBus(private val clock: () -> Long = System::currentTimeMillis) {

    private val flow = MutableSharedFlow<SvodEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<SvodEvent> = flow.asSharedFlow()

    fun publish(type: String, build: JsonObjectBuilder.() -> Unit = {}) {
        flow.tryEmit(SvodEvent(type, clock(), buildJsonObject(build)))
    }
}
