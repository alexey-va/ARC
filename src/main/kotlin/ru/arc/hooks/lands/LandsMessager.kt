package ru.arc.hooks.lands

import ru.arc.common.ServerLocation
import ru.arc.core.ScheduledTask
import ru.arc.core.repeatingAsync
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.arc.network.RedisSerializer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

class LandsMessager(
    private val redis: RedisOperations,
    val reqChannel: String,
    val respChannel: String,
) : ChannelListener {

    val futures: MutableMap<UUID, TimedRequest> = ConcurrentHashMap()
    private var cleanupTask: ScheduledTask? = null

    fun init() {
        close()
        cleanupTask =
            repeatingAsync(20.ticks, delay = 20.ticks) {
                futures.entries.forEach { (id, request) ->
                    val expired = Duration.between(request.instant, Instant.now()).seconds > 5
                    if (expired && futures.remove(id, request)) {
                        request.future.completeExceptionally(TimeoutException("Lands request timed out"))
                    }
                }
            }
    }

    fun close() {
        cleanupTask?.cancel()
        cleanupTask = null
        futures.values.forEach { it.future.cancel(true) }
        futures.clear()
    }

    override fun consume(channel: String, message: String, originServer: String) {
        if (channel == respChannel) {
            if (futures.isEmpty()) return
            val req = RedisSerializer.fromJson(message, LandsRequest::class.java) ?: return
            futures[req.uuid]?.future?.complete(req.serverLocation)
        } else if (channel == reqChannel) {
            if (HookRegistry.landsHook == null) return
            val req = RedisSerializer.fromJson(message, LandsRequest::class.java) ?: return
            val playerUuid = req.playerUuid ?: return
            HookRegistry.landsHook!!.getSpawnLocation(playerUuid)
                .thenApply { ServerLocation.of(it) }
                .thenApply { loc -> LandsRequest(req.uuid, playerUuid, loc) }
                .thenApply { RedisSerializer.toJson(it) }
                .thenAccept { json -> redis.publish(respChannel, json) }
        }
    }

    fun getSpawnLocation(playerUuid: UUID): CompletableFuture<ServerLocation> {
        val uuid = UUID.randomUUID()
        val future = CompletableFuture<ServerLocation>()
        futures[uuid] = TimedRequest(future, Instant.now())
        try {
            redis.publish(reqChannel, RedisSerializer.toJson(LandsRequest(uuid, playerUuid, null)))
        } catch (error: Exception) {
            futures.remove(uuid)
            future.completeExceptionally(error)
        }
        return future
    }

    data class TimedRequest(val future: CompletableFuture<ServerLocation>, val instant: Instant)
}
