package ru.arc.xserver.announcements

import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.core.Tasks
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.xserver.XActionManager
import ru.arc.xserver.XCondition
import ru.arc.xserver.XMessage
import ru.arc.xserver.playerlist.PlayerManager
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.ArrayDeque
import java.util.Random
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

object AnnounceManager {

    private var recentlyUsed = ArrayDeque<XMessage>(2)
    private var task: Any? = null
    private var messageTask: Any? = null
    private var totalWeight: Int = 0
    private val config = ConfigManager.ofModule(ARC.instance.dataPath, "announce.yml")

    private val announcements = TreeMap<Int, XMessage>()
    private val queue = ConcurrentLinkedDeque<XMessage>()

    @JvmStatic
    fun init() {
        try {
            cancel()
            announcements.clear()
            totalWeight = 0
            loadXMessages()

            messageTask = Tasks.scheduler.repeating(period = 1.ticks, delay = 0.ticks) {
                while (queue.isNotEmpty()) {
                    val data = queue.poll() ?: break
                    for (player in PlayerManager.getOnlinePlayersThreadSafe()) {
                        val fits = data.conditions?.all { it.test(player) } != false
                        if (fits) send(data, player)
                    }
                }
            }

            val delayTicks = (config.integer("config.delay-seconds", 600) * 20L).ticks
            task = Tasks.scheduler.repeating(period = delayTicks, delay = delayTicks) {
                if (announcements.isEmpty()) return@repeating
                announce(getRandom())
            }
        } catch (e: Exception) {
            error("Error initializing AnnounceManager: {}", e.message)
        }
    }

    private fun loadXMessages() {
        val keys = config.keys("messages")
        for (key in keys) {
            val message = config.string("messages.$key.message")
            val type = XMessage.Type.valueOf(config.string("messages.$key.type", "chat").uppercase())
            val serializationType = runCatching {
                XMessage.SerializationType.valueOf(config.string("messages.$key.serialization-type", "mini_message").uppercase())
            }.getOrElse {
                error("Serialization type not found for message: {}", key)
                XMessage.SerializationType.MINI_MESSAGE
            }
            val weight = config.integer("messages.$key.weight", 1)

            val toastData = if (config.exists("messages.$key.toast")) XMessage.ToastData(
                title = config.string("messages.$key.toast.title"),
                material = config.material("messages.$key.toast.material", Material.STONE),
                modelData = config.integer("messages.$key.toast.model-data", 0)
            ) else null

            val bossBarData = if (config.exists("messages.$key.bossbar")) XMessage.BossBarData(
                name = config.string("messages.$key.bossbar.name"),
                color = BarColor.valueOf(config.string("messages.$key.bossbar.color", "red").uppercase()),
                seconds = config.integer("messages.$key.bossbar.seconds", 5)
            ) else null

            val servers = config.stringList("messages.$key.servers", listOf("all")).toHashSet()
            val xConditions = mutableListOf<XCondition>()

            if (!servers.contains("all")) {
                servers.forEach { xConditions.add(XCondition.ofServerName(it)) }
            }

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
                    announceData = XMessage.AnnounceData(weight = weight)
                )
            )
        }
    }

    @JvmStatic
    fun cancel() {
        (task as? ru.arc.core.ScheduledTask)?.cancel()
        (messageTask as? ru.arc.core.ScheduledTask)?.cancel()
    }

    @JvmStatic
    fun sendMessageGlobally(playerUuid: UUID, mmString: String) {
        announce(
            XMessage(
                serializedMessage = mmString,
                type = XMessage.Type.CHAT,
                serializationType = XMessage.SerializationType.MINI_MESSAGE,
                conditions = listOf(XCondition.ofPlayerUuid(playerUuid))
            )
        )
    }

    @JvmStatic
    fun announce(data: XMessage) {
        info("Announcing message: {}", data)
        XActionManager.publish(data)
    }

    private fun send(data: XMessage, player: Player) {
        when (data.type) {
            XMessage.Type.CHAT -> player.sendMessage(data.component(player))
            XMessage.Type.BOSS_BAR -> {
                val cmi = HookRegistry.cmiHook ?: run { error("I cant use bossbar without cmi... sorry"); return }
                val bbd = data.bossBarData ?: return
                cmi.sendBossbar("arcAnnounce", data.serializedMessage, player, bbd.color, bbd.seconds, bbd.keepFor)
            }
            XMessage.Type.ACTION_BAR -> {
                val cmi = HookRegistry.cmiHook ?: run { error("I cant use actionbar without cmi... sorry"); return }
                val abd = data.actionBarData ?: return
                cmi.sendActionbar(data.serializedMessage, listOf(player), abd.seconds)
            }
            else -> {}
        }
    }

    @JvmStatic
    fun addAnnouncement(data: XMessage) {
        totalWeight += data.announceData?.weight ?: 1
        announcements[totalWeight] = data
    }

    private fun getRandom(): XMessage {
        val rng = { Random().nextInt(totalWeight + 1) }
        var data = announcements.ceilingEntry(rng())!!.value
        repeat(10) {
            if (recentlyUsed.contains(data)) {
                data = announcements.ceilingEntry(rng())!!.value
            }
        }
        recentlyUsed.offerFirst(data)
        if (recentlyUsed.size > queueSize()) recentlyUsed.pollLast()
        return data
    }

    private fun queueSize() = minOf(announcements.size - 1, 3)

    @JvmStatic
    fun queue(data: XMessage) {
        queue.offer(data)
    }
}
