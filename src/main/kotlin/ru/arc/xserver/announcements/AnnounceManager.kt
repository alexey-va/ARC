package ru.arc.xserver.announcements

import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.core.inWholeTicks
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.xserver.XActionManager
import ru.arc.xserver.XCondition
import ru.arc.xserver.XMessage
import ru.arc.xserver.playerlist.PlayerManager
import ru.arc.util.Logging
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import java.util.ArrayDeque
import java.util.Random
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import ru.arc.config.material
import ru.arc.config.materialSet
import ru.arc.config.particle
import ru.arc.config.sound

object AnnounceManager {

    private var recentlyUsed = ArrayDeque<XMessage>(2)
    private var rotationTask: ScheduledTask? = null
    private var messageTask: ScheduledTask? = null
    private var taskGeneration = 0
    private var totalWeight: Int = 0
    private val config get() = ConfigManager.ofModule(ARC.instance.dataPath, "announce.yml")

    private val announcements = TreeMap<Int, XMessage>()
    private val queue = ConcurrentLinkedDeque<XMessage>()

    @JvmStatic
    fun init() = reload()

    /** Reload announce config from disk and reschedule rotation/queue tasks. */
    @JvmStatic
    fun reload() {
        val node = XCondition.currentServerName()
        val previousGen = taskGeneration
        Logging.info(
            "Announce reload start node={} gen={} rotationTask={} queueTask={}",
            node,
            previousGen,
            taskSummary(rotationTask),
            taskSummary(messageTask),
        )
        try {
            stopTasks()
            config.load()
            val delaySeconds = config.integer("config.delay-seconds", 600)
            announcements.clear()
            totalWeight = 0

            val mainServer =
                ConfigManager.of(ARC.instance.dataFolder.toPath(), "misc.yml")
                    .bool("redis.main-server", false)
            val configuredMessageKeys = config.keys("messages")
            if (!mainServer) {
                if (configuredMessageKeys.isNotEmpty()) {
                    warn(
                        "Announce node={} has {} message(s) in announce.yml but redis.main-server=false — rotation disabled, messages ignored",
                        node,
                        configuredMessageKeys.size,
                    )
                }
            } else {
                loadXMessages()
            }

            val eligible = announcements.values.count { it.appliesToServer(node) }
            Logging.info(
                "Announce config loaded node={} main-server={} delay-seconds={} messages={} eligibleOnNode={}",
                node,
                mainServer,
                delaySeconds,
                announcements.size,
                eligible,
            )

            val generation = taskGeneration
            messageTask =
                Tasks.scheduler.repeating(period = 1.ticks, delay = 0.ticks) {
                    if (generation != taskGeneration) {
                        cancel()
                        return@repeating
                    }
                    drainQueue()
                }
            Logging.info(
                "Announce queue-drain task started node={} gen={} taskId={}",
                node,
                generation,
                messageTask?.id,
            )

            if (!mainServer) {
                Logging.info(
                    "Announce reload complete node={} gen={}: rotation disabled (redis.main-server=false), listening via Redis only",
                    node,
                    generation,
                )
                return
            }

            val delayTicks = (delaySeconds * 20L).ticks

            rotationTask =
                Tasks.scheduler.repeating(period = delayTicks, delay = delayTicks) {
                    if (generation != taskGeneration) {
                        cancel()
                        return@repeating
                    }
                    if (announcements.isEmpty()) return@repeating
                    val server = XCondition.currentServerName()
                    val pick = getRandom(server) ?: return@repeating
                    debug("Announce rotation pick server={} {}", server, pick.logSummary())
                    announce(pick)
                }
            Logging.info(
                "Announce reload complete node={} gen={}: rotation every {}s ({} ticks), taskId={}",
                node,
                generation,
                delaySeconds,
                delayTicks.inWholeTicks,
                rotationTask?.id,
            )
        } catch (e: Exception) {
            error("Announce reload failed on node={}: {}", node, e.message, e)
        }
    }

    private fun taskSummary(task: ScheduledTask?): String =
        when {
            task == null -> "none"
            task.isCancelled -> "cancelled#${task.id}"
            else -> "active#${task.id}"
        }

    private fun drainQueue() {
        while (queue.isNotEmpty()) {
            val data = queue.poll() ?: break
            if (!data.appliesToServer(XCondition.currentServerName())) continue
            if (data.serializedMessage.isNullOrBlank()) {
                debug("Announce queue skip reason=serialized-empty {}", data.logSummary())
                continue
            }
            for (player in PlayerManager.getOnlinePlayersThreadSafe()) {
                val fits = data.conditions?.all { it.test(player) } != false
                if (fits) send(data, player)
            }
        }
    }

    private fun loadXMessages() {
        val keys = config.keys("messages")
        for (key in keys) {
            val message = config.string("messages.$key.message")
            if (message.isBlank()) {
                warn("Skipping announce message '{}': empty text", key)
                continue
            }
            val type = XMessage.Type.valueOf(config.string("messages.$key.type", "chat").uppercase())
            val serializationType =
                runCatching {
                    XMessage.SerializationType.valueOf(
                        config.string("messages.$key.serialization-type", "mini_message").uppercase(),
                    )
                }.getOrElse {
                    error("Serialization type not found for message: {}", key)
                    XMessage.SerializationType.MINI_MESSAGE
                }
            val weight = config.integer("messages.$key.weight", 1)

            val toastData =
                if (config.exists("messages.$key.toast")) {
                    XMessage.ToastData(
                        title = config.string("messages.$key.toast.title"),
                        material = config.material("messages.$key.toast.material", Material.STONE),
                        modelData = config.integer("messages.$key.toast.model-data", 0),
                    )
                } else {
                    null
                }

            val bossBarData =
                if (config.exists("messages.$key.bossbar")) {
                    XMessage.BossBarData(
                        name = config.string("messages.$key.bossbar.name"),
                        color = BarColor.valueOf(config.string("messages.$key.bossbar.color", "red").uppercase()),
                        seconds = config.integer("messages.$key.bossbar.seconds", 5),
                    )
                } else {
                    null
                }

            val servers = config.stringList("messages.$key.servers", listOf("all")).toHashSet()
            val targetServers =
                if (servers.contains("all")) {
                    null
                } else {
                    servers.map { it.lowercase() }.toSet()
                }
            val xConditions = mutableListOf<XCondition>()

            if (config.exists("messages.$key.conditions")) {
                val conditions = config.list<Map<String, Any>>("messages.$key.conditions")
                for (map in conditions) {
                    when (map["type"] as? String) {
                        "permission" -> xConditions.add(XCondition.ofPermission(map["permission"] as String))
                        "player" -> xConditions.add(XCondition.ofPlayerUuid(UUID.fromString(map["uuid"] as String)))
                    }
                }
            }

            addAnnouncement(
                XMessage(
                    type = type,
                    serializedMessage = message,
                    serializationType = serializationType,
                    conditions = xConditions,
                    toastData = toastData,
                    bossBarData = bossBarData,
                    announceData = XMessage.AnnounceData(weight = weight, targetServers = targetServers),
                ),
            )
        }
    }

    @JvmStatic
    fun cancel() = stopTasks()

    private fun stopTasks() {
        val previousGen = taskGeneration
        val rotationSummary = taskSummary(rotationTask)
        val queueSummary = taskSummary(messageTask)
        taskGeneration++
        rotationTask?.takeUnless { it.isCancelled }?.cancel()
        messageTask?.takeUnless { it.isCancelled }?.cancel()
        rotationTask = null
        messageTask = null
        Logging.info(
            "Announce stopTasks node={} gen {} -> {} cancelled rotation={} queue={}",
            XCondition.currentServerName(),
            previousGen,
            taskGeneration,
            rotationSummary,
            queueSummary,
        )
    }

    @JvmStatic
    fun sendMessageGlobally(playerUuid: UUID, mmString: String) {
        if (mmString.isBlank()) return
        announce(
            XMessage(
                serializedMessage = mmString,
                type = XMessage.Type.CHAT,
                serializationType = XMessage.SerializationType.MINI_MESSAGE,
                conditions = listOf(XCondition.ofPlayerUuid(playerUuid)),
            ),
        )
    }

    @JvmStatic
    fun announce(data: XMessage) {
        if (data.serializedMessage.isNullOrBlank()) {
            warn("Skipping announce with empty text")
            return
        }
        debug("Announce publish {}", data.logSummary())
        XActionManager.publish(data)
    }

    private fun send(data: XMessage, player: Player) {
        when (data.type) {
            XMessage.Type.CHAT -> {
                val reason = data.skipReason(player)
                if (reason != null) {
                    debug("Announce deliver skip player={} reason={} {}", player.name, reason, data.logSummary())
                    return
                }
                debug(
                    "Announce deliver CHAT player={} plainLen={} plain=\"{}\" {}",
                    player.name,
                    data.plainText(player).length,
                    data.plainText(player).take(120),
                    data.logSummary(),
                )
                player.sendMessage(data.component(player))
            }
            XMessage.Type.BOSS_BAR -> {
                val cmi = HookRegistry.cmiHook ?: run { error("I cant use bossbar without cmi... sorry"); return }
                val bbd = data.bossBarData ?: return
                val reason = data.skipReason(player)
                if (reason != null) {
                    debug("Announce deliver skip player={} reason={} {}", player.name, reason, data.logSummary())
                    return
                }
                debug(
                    "Announce deliver BOSS_BAR player={} plainLen={} plain=\"{}\" {}",
                    player.name,
                    data.plainText(player).length,
                    data.plainText(player).take(120),
                    data.logSummary(),
                )
                cmi.sendBossbar("arcAnnounce", data.serializedMessage ?: "", player, bbd.color, bbd.seconds, bbd.keepFor)
            }
            XMessage.Type.ACTION_BAR -> {
                val cmi = HookRegistry.cmiHook ?: run { error("I cant use actionbar without cmi... sorry"); return }
                val abd = data.actionBarData ?: return
                cmi.sendActionbar(data.serializedMessage ?: "", listOf(player), abd.seconds)
            }
            else -> {}
        }
    }

    @JvmStatic
    fun addAnnouncement(data: XMessage) {
        totalWeight += data.announceData?.weight ?: 1
        announcements[totalWeight] = data
    }

    private fun getRandom(serverName: String? = XCondition.currentServerName()): XMessage? {
        val pool = announcements.values.filter { it.appliesToServer(serverName) }
        if (pool.isEmpty()) return null

        val weighted = TreeMap<Int, XMessage>()
        var weightSum = 0
        for (message in pool) {
            weightSum += message.announceData?.weight ?: 1
            weighted[weightSum] = message
        }

        val rng = { Random().nextInt(weightSum + 1) }
        var data = weighted.ceilingEntry(rng())!!.value
        repeat(10) {
            if (recentlyUsed.contains(data)) {
                data = weighted.ceilingEntry(rng())!!.value
            }
        }
        recentlyUsed.offerFirst(data)
        if (recentlyUsed.size > queueSize(weighted.size)) recentlyUsed.pollLast()
        return data
    }

    private fun queueSize(poolSize: Int) = minOf(poolSize - 1, 3).coerceAtLeast(0)

    @JvmStatic
    fun queue(data: XMessage) {
        queue.offer(data)
    }
}
