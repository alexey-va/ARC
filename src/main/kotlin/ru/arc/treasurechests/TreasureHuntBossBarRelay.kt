package ru.arc.treasurechests

import com.google.gson.Gson
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.config.ConfigSection
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.network.LeasedNetworkDirectory
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.ValidatedRedisTopic
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil.mm
import java.util.Locale

internal data class TreasureHuntBossBarRelaySettings(
    val sourceServer: String = "spawn",
    val viewerWorlds: Set<String> = setOf("survival"),
    val heartbeatTicks: Long = 100L,
    val staleMillis: Long = 15_000L,
) {
    companion object {
        fun load(section: ConfigSection): TreasureHuntBossBarRelaySettings {
            val heartbeat = section.long("heartbeat-ticks", 100L).coerceIn(20L, 1_200L)
            return TreasureHuntBossBarRelaySettings(
                sourceServer = section.string("source-server", "spawn").trim().lowercase(Locale.ROOT),
                viewerWorlds = section.stringList("viewer-worlds", listOf("survival")).toSet(),
                heartbeatTicks = heartbeat,
                staleMillis = section.long("stale-seconds", 15L).coerceIn(3L, 300L)
                    .times(1_000L).coerceAtLeast(heartbeat * 150L),
            )
        }
    }
}

internal data class HuntBossBarState(
    val id: String,
    val message: String,
    val remaining: Int,
    val total: Int,
    val color: String,
    val overlay: String,
)

internal data class HuntBossBarSnapshot(
    val source: String,
    val sentAtMillis: Long,
    val hunts: List<HuntBossBarState>,
)

/** Closed, bounded status protocol. It carries presentation only and cannot start or reward a hunt. */
internal object HuntBossBarCodec {
    val codec = BoundedJsonCodec(
        gson = Gson(),
        type = HuntBossBarSnapshot::class.java,
        rootContract = JsonObjectContract(
            setOf("source", "sentAtMillis", "hunts"),
            fieldContracts = mapOf(
                "hunts" to JsonArrayContract(
                    maxEntries = 32,
                    elementContract = JsonObjectContract(setOf("id", "message", "remaining", "total", "color", "overlay")),
                ),
            ),
        ),
        bounds = JsonResourceBounds(65_536, maxDepth = 5, maxContainerEntries = 32, maxTotalNodes = 300, maxStringCharacters = 1_024),
        validate = { snapshot ->
            require(snapshot.source.matches(Regex("[a-z0-9_.-]{1,64}")))
            require(snapshot.sentAtMillis > 0)
            require(snapshot.hunts.size <= 32 && snapshot.hunts.map { it.id }.distinct().size == snapshot.hunts.size)
            snapshot.hunts.forEach { hunt ->
                require(hunt.id.length in 1..160 && hunt.id.none(Char::isISOControl))
                require(hunt.message.length <= 1_024 && hunt.message.none(Char::isISOControl))
                require(hunt.total > 0 && hunt.remaining in 1..hunt.total)
                BossBar.Color.valueOf(hunt.color)
                BossBar.Overlay.valueOf(hunt.overlay)
            }
        },
    )
}

/**
 * Mirrors Spawn hunt progress into the configured local worlds. All audience and hunt access stays
 * on Paper's thread; Redis delivery enters through the owned task scope. Full snapshots heal missed
 * updates, and the core lease expires a disconnected source without keeping a stale bossbar alive.
 */
internal class TreasureHuntBossBarRelay(
    redis: RedisOperations,
    scheduler: TaskScheduler,
    private val localServer: String,
    private val settings: TreasureHuntBossBarRelaySettings,
    private val now: () -> Long = System::currentTimeMillis,
    initialLastSentAt: Long = 0L,
) : AutoCloseable {
    private val tasks = LifecycleTaskScope(scheduler)
    private val remote = LeasedNetworkDirectory<String, HuntBossBarSnapshot>(settings.staleMillis, 1, now)
    private val bars = mutableMapOf<String, RemoteBar>()
    private var localHunts = emptyList<HuntBossBarState>()
    /** Retained by the registry across relay generations, including the final empty snapshot. */
    var lastPublishedAt: Long = initialLastSentAt
        private set
    private var closed = false
    private val topic = ValidatedRedisTopic.open(
        redis = redis,
        channel = CHANNEL,
        codec = HuntBossBarCodec.codec,
        originAllowed = { it == settings.sourceServer && it != localServer },
        embeddedOrigin = HuntBossBarSnapshot::source,
        onMessage = { snapshot, origin ->
            tasks.runSync {
                if (snapshot.sentAtMillis <= now() + 5_000L) {
                    remote.observe(origin, origin, snapshot, observedAtMillis = snapshot.sentAtMillis)
                    refreshAudience()
                }
            }
        },
        onHandlerFailure = { warn("Treasure hunt bossbar relay could not receive a snapshot", it) },
    )

    init {
        tasks.runTimer(settings.heartbeatTicks, settings.heartbeatTicks) { publish() }
        tasks.runTimer(20L, 20L) { refreshAudience() }
    }

    fun onHuntsChanged(hunts: List<ActiveHunt>) {
        localHunts = hunts.filter { it.config.bossBar.visible && it.remainingChests > 0 }.map { hunt ->
            HuntBossBarState(
                id = "${hunt.config.locationPoolId}:${hunt.startTime}",
                message = hunt.config.bossBar.message.replace("%left%", hunt.remainingChests.toString()),
                remaining = hunt.remainingChests,
                total = hunt.totalChests,
                color = hunt.config.bossBar.color.name,
                overlay = hunt.config.bossBar.overlay.name,
            )
        }
        publish()
    }

    private fun publish() {
        if (closed || localServer != settings.sourceServer) return
        // Redis publishes asynchronously; distinct observation times reject reordered older updates.
        lastPublishedAt = maxOf(now(), lastPublishedAt + 1L)
        runCatching { topic.publish(HuntBossBarSnapshot(localServer, lastPublishedAt, localHunts)) }
            .onFailure { warn("Treasure hunt bossbar relay could not publish status from {}", localServer, it) }
    }

    fun refreshAudience() {
        if (closed) return
        val states = remote.snapshot().flatMap { it.value.hunts }.associateBy { it.id }
        bars.keys.filter { it !in states }.forEach { id -> bars.remove(id)?.hide() }
        val viewers = Bukkit.getOnlinePlayers().filter { it.world.name in settings.viewerWorlds }.toSet()
        states.forEach { (id, state) ->
            val entry = bars.getOrPut(id) {
                RemoteBar(BossBar.bossBar(mm(state.message), 1f, BossBar.Color.valueOf(state.color), BossBar.Overlay.valueOf(state.overlay)))
            }
            entry.bar.name(mm(state.message))
            entry.bar.progress((state.remaining.toFloat() / state.total).coerceIn(0f, 1f))
            entry.bar.color(BossBar.Color.valueOf(state.color))
            entry.bar.overlay(BossBar.Overlay.valueOf(state.overlay))
            entry.audience.filter { !it.isOnline || it !in viewers }.forEach { player ->
                player.hideBossBar(entry.bar)
                entry.audience.remove(player)
            }
            viewers.filter { it !in entry.audience }.forEach { player ->
                player.showBossBar(entry.bar)
                entry.audience.add(player)
            }
        }
    }

    fun onPlayerQuit(player: Player) {
        bars.values.forEach { if (it.audience.remove(player)) player.hideBossBar(it.bar) }
    }

    override fun close() {
        if (closed) return
        localHunts = emptyList()
        publish()
        closed = true
        tasks.close()
        topic.close()
        remote.clear()
        bars.values.forEach(RemoteBar::hide)
        bars.clear()
    }

    private class RemoteBar(val bar: BossBar, val audience: MutableSet<Player> = mutableSetOf()) {
        fun hide() {
            audience.forEach { it.hideBossBar(bar) }
            audience.clear()
        }
    }

    companion object {
        const val CHANNEL = "arc:treasure-hunt:bossbar:v1"
    }
}
