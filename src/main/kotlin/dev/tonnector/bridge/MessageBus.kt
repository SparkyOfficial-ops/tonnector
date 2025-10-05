package dev.tonnector.bridge

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class BridgeEvent(
    val id: Long,
    val to: String,
    val from: String,
    val message: String,
    val expiresAt: Long,
)

class Subscriber(
    val ids: Set<String>,
    val channel: Channel<BridgeEvent>,
)

/**
 * In-memory TonConnect bridge. Messages stay until their TTL so a reconnect
 * with last_event_id can replay them.
 */
class MessageBus {
    private val nextId = AtomicLong(1)
    private val lock = Any()
    private val events = ArrayDeque<BridgeEvent>()
    private val subscribers = mutableListOf<Subscriber>()

    fun enqueue(to: String, from: String, message: String, ttlSeconds: Long): BridgeEvent {
        val event = BridgeEvent(
            id = nextId.getAndIncrement(),
            to = to.lowercase(),
            from = from.lowercase(),
            message = message,
            expiresAt = System.currentTimeMillis() + ttlSeconds * 1000,
        )
        val targets: List<Subscriber>
        synchronized(lock) {
            purgeLocked()
            events.addLast(event)
            while (events.size > 2000) events.removeFirst()
            targets = subscribers.filter { event.to in it.ids }
        }
        targets.forEach { it.channel.trySend(event) }
        return event
    }

    fun subscribe(ids: Collection<String>): Subscriber {
        val sub = Subscriber(ids.map { it.lowercase() }.toSet(), Channel(Channel.UNLIMITED))
        synchronized(lock) { subscribers += sub }
        return sub
    }

    fun unsubscribe(sub: Subscriber) {
        synchronized(lock) { subscribers.remove(sub) }
        sub.channel.close()
    }

    fun backlog(ids: Set<String>, lastEventId: Long): List<BridgeEvent> = synchronized(lock) {
        purgeLocked()
        events.filter { it.to in ids && it.id > lastEventId }
    }

    private fun purgeLocked() {
        val now = System.currentTimeMillis()
        while (events.isNotEmpty() && events.first().expiresAt < now) events.removeFirst()
    }
}

suspend fun MessageBus.stream(sub: Subscriber, lastEventId: Long, emit: suspend (BridgeEvent) -> Unit) {
    val seen = HashSet<Long>()
    for (event in backlog(sub.ids, lastEventId)) {
        if (seen.add(event.id)) emit(event)
    }
    while (coroutineContext.isActive) {
        val event = sub.channel.receiveCatching().getOrNull() ?: break
        if (event.id > lastEventId && seen.add(event.id)) emit(event)
    }
}
