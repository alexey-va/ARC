package ru.arc.spy

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.Tasks
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.UUID

class CrossServerSpyBridge(
    private val plugin: Plugin,
    private val redis: RedisOperations,
    private val localServer: String,
    private val settings: CrossServerSpySettings,
    private val cmi: SpyStateAccess,
    private val now: () -> Long = System::currentTimeMillis,
) : Listener, AutoCloseable {
    private val log = LoggerFactory.getLogger(CrossServerSpyBridge::class.java)
    private val ingress = SpyRelayIngress(localServer, settings)
    private val recentPrivateMessages = LinkedHashMap<String, Long>()
    private val listener = ChannelListener(::onRedisMessage)

    @Volatile
    private var active = false

    fun start() {
        check(!active) { "Cross-server spy bridge is already active" }
        active = true
        try {
            redis.registerChannelUnique(settings.channel, listener)
            Bukkit.getPluginManager().registerEvents(this, plugin)
        } catch (failure: Throwable) {
            active = false
            runCatching { redis.unregisterChannel(settings.channel, listener) }
            HandlerList.unregisterAll(this)
            throw failure
        }
        log.info("Cross-server spy bridge enabled on {} via {}", localServer, settings.channel)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        if (!active) return
        val command = SpyRelayCodec.sanitizeContent(event.message, settings.maxContentLength)
        if (command.isEmpty()) return

        val blacklist = cmi.commandBlacklist()
        if (
            SpyRelayPolicy.shouldPublishCommand(
                command = command,
                senderHidden = cmi.senderHidesCommandSpy(event.player),
                cmiBlacklisted = blacklist,
                sensitiveCommands = settings.sensitiveCommands,
            )
        ) {
            publish(
                SpyRelayMessage(
                    id = UUID.randomUUID(),
                    type = SpyRelayType.COMMAND,
                    senderUuid = event.player.uniqueId,
                    senderName = event.player.name,
                    targetUuid = null,
                    targetName = null,
                    content = command,
                    createdAt = now(),
                ),
            )
        }

        if (cmi.senderHidesChatSpy(event.player)) return
        val privateMessage =
            SpyRelayPolicy.parsePrivateMessage(
                commandLine = command,
                directCommands = settings.privateMessageCommands,
                replyCommands = settings.replyCommands,
                replyTarget = { cmi.replyTarget(event.player.name) },
            ) ?: return
        val content = SpyRelayCodec.sanitizeContent(privateMessage.content, settings.maxContentLength)
        if (content.isEmpty() || isDuplicatePrivateMessage(event.player.uniqueId, privateMessage.targetName, content)) return
        publish(
            SpyRelayMessage(
                id = UUID.randomUUID(),
                type = SpyRelayType.CHAT,
                senderUuid = event.player.uniqueId,
                senderName = event.player.name,
                targetUuid = cmi.targetUuid(privateMessage.targetName),
                targetName = privateMessage.targetName,
                content = content,
                createdAt = now(),
            ),
        )
    }

    override fun close() {
        if (!active) return
        active = false
        HandlerList.unregisterAll(this)
        runCatching { redis.unregisterChannel(settings.channel, listener) }
            .onFailure { log.warn("Unable to unregister cross-server spy Redis listener", it) }
        synchronized(recentPrivateMessages) { recentPrivateMessages.clear() }
        log.info("Cross-server spy bridge disabled on {}", localServer)
    }

    private fun publish(message: SpyRelayMessage) {
        val encoded = SpyRelayCodec.encode(message)
        if (encoded.toByteArray(Charsets.UTF_8).size > settings.maxPayloadBytes) {
            log.warn("Dropped oversized outbound {} spy event", message.type.name.lowercase())
            return
        }
        runCatching { redis.publish(settings.channel, encoded) }
            .onFailure { log.warn("Unable to publish {} spy event", message.type.name.lowercase(), it) }
    }

    private fun onRedisMessage(channel: String, raw: String, originServer: String) {
        if (!active || channel != settings.channel) return
        val receivedAt = now()
        val accepted = ingress.accept(originServer, raw, receivedAt) ?: return

        runCatching {
            Tasks.scheduler.runSync(Runnable { if (active) deliver(accepted.origin, accepted.message) })
        }.onFailure {
            log.warn("Unable to schedule cross-server spy delivery from {}", accepted.origin, it)
        }
    }

    private fun deliver(origin: String, message: SpyRelayMessage) {
        check(Bukkit.isPrimaryThread()) { "Cross-server spy delivery must run on the Paper main thread" }
        val commandList =
            runCatching { if (message.type == SpyRelayType.COMMAND) cmi.commandList() else emptyList() }
                .getOrElse {
                    log.warn("Unable to read CMI spy policy for remote {} event", message.type.name.lowercase(), it)
                    return
                }
        val rendered = SpyMessageRenderer.render(message, settings.serverLabel(origin))
        Bukkit.getOnlinePlayers().forEach { viewer ->
            runCatching {
                val shouldDeliver =
                    SpyRelayPolicy.shouldDeliver(
                        message = message,
                        viewerUuid = viewer.uniqueId,
                        viewerName = viewer.name,
                        chatSpyEnabled = message.type == SpyRelayType.CHAT && cmi.isChatSpy(viewer),
                        commandSpyEnabled = message.type == SpyRelayType.COMMAND && cmi.isCommandSpy(viewer),
                        canSeeUnlistedCommands = message.type == SpyRelayType.COMMAND && cmi.canSeeUnlistedCommands(viewer),
                        commandList = commandList,
                    )
                if (shouldDeliver) viewer.sendMessage(rendered)
            }.onFailure {
                log.warn(
                    "Unable to deliver remote {} spy event to viewer {}",
                    message.type.name.lowercase(),
                    viewer.name,
                    it,
                )
            }
        }
    }

    private fun isDuplicatePrivateMessage(sender: UUID, target: String, content: String): Boolean {
        val timestamp = now()
        val fingerprint = "$sender\u0000${target.lowercase(Locale.ROOT)}\u0000$content"
        synchronized(recentPrivateMessages) {
            recentPrivateMessages.entries.removeIf { timestamp - it.value > PRIVATE_MESSAGE_DEDUP_MILLIS }
            val previous = recentPrivateMessages.put(fingerprint, timestamp)
            while (recentPrivateMessages.size > PRIVATE_MESSAGE_DEDUP_CAPACITY) {
                val eldest = recentPrivateMessages.entries.iterator()
                if (!eldest.hasNext()) break
                eldest.next()
                eldest.remove()
            }
            return previous != null && timestamp - previous <= PRIVATE_MESSAGE_DEDUP_MILLIS
        }
    }

    companion object {
        private const val PRIVATE_MESSAGE_DEDUP_MILLIS = 250L
        private const val PRIVATE_MESSAGE_DEDUP_CAPACITY = 256
    }
}
