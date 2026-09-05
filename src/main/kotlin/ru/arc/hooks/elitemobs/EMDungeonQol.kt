package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.DungeonStartEvent
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.audience.NativePaperAudienceEffects
import ru.arc.paper.audience.PaperAudienceEffects
import java.time.Duration
import java.util.UUID
import java.util.WeakHashMap

/**
 * Resumes only an existing dungeon world/run. Native EliteMobs still owns admission,
 * match progress and exits. PDC survives ordinary-world reloads; an instance token does not.
 */
internal class EMDungeonQol(
    private val config: Config = ConfigManager.of(ARC.instance.dataPath, "modules/elitemobs.yml"),
    private val resolve: (World) -> DungeonVisit? = NativeDungeonVisits()::resolve,
    private val safe: (Location) -> Boolean = ::safeDungeonCheckpoint,
    private val audience: PaperAudienceEffects = NativePaperAudienceEffects,
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener, AutoCloseable {
    private val checkpoints = DungeonCheckpointStore()
    private val tasks = LifecycleTaskScope()
    private val enabled get() = config.bool("dungeon-qol.enabled", true)
    private val ttl get() = config.integer("dungeon-qol.resume-hours", 72).coerceIn(1, 720) * 3_600_000L

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun resumeOnEntry(event: PlayerTeleportEvent) {
        if (!enabled || event.isCancelled || event.player.isDead || event.from.world.uid == event.to.world.uid) return
        if (!config.bool("dungeon-qol.resume-enabled", true)) return
        val visit = resolve(event.to.world) ?: return
        if (!visit.canResume) return
        val destination = checkpoints.destination(event.player.persistentDataContainer, event.to.world, visit.run, clock(), ttl) ?: return
        if (safe(destination)) event.to = destination
        else checkpoints.forget(event.player.persistentDataContainer, destination.world.uid)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun rememberDeparture(event: PlayerTeleportEvent) {
        if (!enabled || event.isCancelled || event.player.isDead || event.from.world.uid == event.to.world.uid) return
        remember(event.player, event.from)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun rememberLogout(event: PlayerQuitEvent) {
        if (enabled) remember(event.player, event.player.location)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun clearOnDeath(event: PlayerDeathEvent) {
        checkpoints.forget(event.entity.persistentDataContainer, event.entity.world.uid)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun entered(event: PlayerChangedWorldEvent) {
        if (!enabled) return
        val player = event.player
        val world = player.world
        // Wait for native admission and entry text; no delayed teleport or bypass is created.
        tasks.runLater(30L) {
            if (!enabled || !player.isOnline || player.world.uid != world.uid) return@runLater
            val visit = resolve(world) ?: return@runLater
            val resumed = config.bool("dungeon-qol.resume-enabled", true) && visit.canResume && checkpoints.destination(player.persistentDataContainer, world, visit.run, clock(), ttl)
                ?.let { it.distanceSquared(player.location) < 16 } == true
            when {
                resumed -> show(player, "resumed", "<green>Продолжаем", "<white>Вы вернулись к месту выхода")
                visit.waiting -> {
                    show(player, "entry", "<gold>Готовы к данжу?", "<white>/данж начать <gray>— начать прохождение")
                    audience.sendMessage(player, config.component("dungeon-qol.messages.entry",
                        "<gold>Данж</gold> <gray>·</gray> <click:run_command:'/dungeon start'><green>[Начать]</green></click> <gray>или</gray> <click:run_command:'/dungeon quit'><white>[Выйти]</white></click>"))
                }
                else -> show(player, "open-entry", "<gold>Вы в данже", "<white>Место выхода запоминается")
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun started(event: DungeonStartEvent) {
        if (!enabled) return
        event.dungeonInstance.players.forEach { show(it, "started", "<gold>Данж начался", "<white>/данж выйти <gray>— покинуть данж") }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun completed(event: DungeonCompleteEvent) {
        val instance = event.dungeonInstance
        val players = instance.participants.toList()
        players.forEach { checkpoints.forget(it.persistentDataContainer, instance.world.uid) }
        if (!enabled) return
        tasks.runLater(60L) {
            players.filter { it.isOnline && it.world == instance.world }.forEach { player ->
                show(player, "complete", "<green>Данж пройден", "<white>Заберите добычу <gray>·</gray> <white>/данж выйти")
                audience.sendMessage(player, config.component("dungeon-qol.messages.complete",
                    "<gold>Данж</gold> <gray>·</gray> <white>Заберите добычу.</white> <click:run_command:'/dungeon quit'><green>[Выйти из данжа]</green></click>"))
            }
        }
    }

    private fun remember(player: Player, location: Location) {
        if (!config.bool("dungeon-qol.resume-enabled", true) || player.isDead) return
        val visit = resolve(location.world) ?: return
        if (!visit.canResume || !safe(location)) return
        checkpoints.remember(player.persistentDataContainer, location, visit.run, clock(), ttl)
    }

    private fun show(player: Player, key: String, title: String, subtitle: String) {
        if (!config.bool("dungeon-qol.titles-enabled", true)) return
        audience.showTitle(player, Title.title(config.component("dungeon-qol.titles.$key.title", title),
            config.component("dungeon-qol.titles.$key.subtitle", subtitle),
            Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(500))))
    }

    override fun close() = tasks.close()
}

internal data class DungeonVisit(val run: String, val waiting: Boolean = false, val canResume: Boolean = true)

private class NativeDungeonVisits {
    private val runs = WeakHashMap<DungeonInstance, String>()
    fun resolve(world: World): DungeonVisit? {
        val instance = DungeonInstance.getDungeonInstances().firstOrNull { it.world == world }
        if (instance != null) {
            val state = instance.state.name
            return DungeonVisit(runs.getOrPut(instance) { UUID.randomUUID().toString() },
                waiting = state == "WAITING", canResume = state == "ONGOING")
        }
        val fields = ContentPackagesConfig.getDungeonPackages().values.firstOrNull { it.worldName == world.name } ?: return null
        // Never treat a dynamic blueprint or the Adventurers Guild hub as a resumable open dungeon.
        return if (fields.contentType.name == "OPEN_DUNGEON") DungeonVisit("open") else null
    }
}

internal fun safeDungeonCheckpoint(location: Location): Boolean {
    val world = location.world
    if (!location.x.isFinite() || !location.y.isFinite() || !location.z.isFinite()) return false
    if (location.y < world.minHeight + 1 || location.y >= world.maxHeight - 2 || !world.worldBorder.isInside(location)) return false
    // This runs inside the native admitted teleport: load at most its one existing chunk, never generate terrain.
    val chunkX = location.blockX shr 4
    val chunkZ = location.blockZ shr 4
    if (!world.isChunkLoaded(chunkX, chunkZ)) {
        if (!world.isChunkGenerated(chunkX, chunkZ) || !world.getChunkAt(chunkX, chunkZ, false).load(false)) return false
    }
    val feet = location.block
    val head = feet.getRelative(0, 1, 0)
    val floor = feet.getRelative(0, -1, 0)
    val hazards = setOf(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
        Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.END_PORTAL, Material.NETHER_PORTAL)
    return feet.isPassable && head.isPassable && !feet.isLiquid && !head.isLiquid && floor.type.isSolid &&
        listOf(feet.type, head.type, floor.type).none { it in hazards }
}
