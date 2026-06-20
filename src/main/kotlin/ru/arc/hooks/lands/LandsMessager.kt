package ru.arc.hooks.lands

import ru.arc.ARC
import ru.arc.common.ServerLocation
import ru.arc.hooks.HookRegistry
import ru.arc.network.ChannelListener
import ru.arc.network.RedisManager
import ru.arc.network.RedisSerializer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class LandsMessager(
    private val redisManager: RedisManager,
    val reqChannel: String,
    val respChannel: String,
) : ChannelListener {

    val futures: MutableMap<UUID, TimedRequest> = ConcurrentHashMap()

    fun init() {
        ARC.instance.server.scheduler.runTaskTimerAsynchronously(
            ARC.instance,
            Runnable {
                futures.entries.removeIf { (_, v) ->
                    Duration.between(v.instant, Instant.now()).seconds > 5
                }
            },
            20L,
            20L,
        )
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
                .thenAccept { json -> redisManager.publish(respChannel, json) }
        }
    }

    fun getSpawnLocation(playerUuid: UUID): CompletableFuture<ServerLocation> {
        val uuid = UUID.randomUUID()
        val future = CompletableFuture<ServerLocation>()
        futures[uuid] = TimedRequest(future, Instant.now())
        redisManager.publish(reqChannel, RedisSerializer.toJson(LandsRequest(uuid, playerUuid, null)))
        return future
    }

    data class TimedRequest(val future: CompletableFuture<ServerLocation>, val instant: Instant)
}
