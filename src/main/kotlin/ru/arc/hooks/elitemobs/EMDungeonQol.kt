package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.DungeonStartEvent
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.entity.Projectile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.util.Logging
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.audience.NativePaperAudienceEffects
import ru.arc.paper.audience.PaperAudienceEffects
import java.time.Duration
import java.util.UUID

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
    internal val teleporter: EMCheckpointTeleporter = EMCheckpointTeleporter(),
    private val move: (Player, Location, Boolean) -> Boolean = teleporter::teleport,
    private val persistence: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,

) : Listener, AutoCloseable {
    private val checkpoints = DungeonCheckpointStore()
    private val tasks = LifecycleTaskScope()
    private val combatUntil = mutableMapOf<UUID, Long>()
    private val lastHint = mutableMapOf<UUID, Long>()
    private val lastSave = mutableMapOf<UUID, Long>()
    private val pending = mutableMapOf<UUID, UUID>()
    private val menus by lazy { DungeonSaveMenus(this) }
    private var closed = false
    private var autosavesStarted = false

    private val enabled get() = config.bool("dungeon-qol.enabled", true)
    private val ttl get() = config.integer("dungeon-qol.resume-hours", 72).coerceIn(1, 720) * 3_600_000L

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun resumeOnEntry(event: PlayerTeleportEvent) {
        if (!enabled || event.isCancelled || event.player.isDead || event.from.world.uid == event.to.world.uid) return
        if (!config.bool("dungeon-qol.resume-enabled", true)) return
        val visit = resolve(event.to.world) ?: return
        if (!visit.canResume || !member(event.player, visit)) return
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
        combatUntil.remove(event.player.uniqueId)
        lastHint.remove(event.player.uniqueId)
        lastSave.remove(event.player.uniqueId)
        pending.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun clearOnDeath(event: PlayerDeathEvent) {
        checkpoints.forget(event.entity.persistentDataContainer, event.entity.world.uid)
        pending.remove(event.entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun entered(event: PlayerChangedWorldEvent) {
        if (!enabled) return
        val player = event.player
        val world = player.world
        pending.remove(player.uniqueId)
        // Wait for native admission and entry text; no delayed teleport or bypass is created.
        tasks.runLater(30L) {
            if (!enabled || !player.isOnline || player.world.uid != world.uid) return@runLater
            val visit = resolve(world) ?: return@runLater
            val resumed = config.bool("dungeon-qol.resume-enabled", true) && visit.canResume && checkpoints.destination(player.persistentDataContainer, world, visit.run, clock(), ttl)
                ?.let { it.distanceSquared(player.location) < 16 } == true
            when {
                resumed -> show(player, "resumed", "<green>Продолжаем", "<white>Вы вернулись к месту выхода")
                visit.waiting -> {
                    show(player, "entry", "<gold>Готовы к данжу?", "<white>/начать <gray>— начать прохождение")
                    audience.sendMessage(player, config.component("dungeon-qol.messages.entry",
                        "<gold>Данж</gold> <gray>·</gray> <click:run_command:'/начать'><green>[Начать]</green></click> <gray>или</gray> <click:run_command:'/dungeon quit'><white>[Выйти]</white></click>"))
                }
                else -> show(player, "open-entry", "<gold>Вы в данже", "<white>Место выхода запоминается")
            }
            if (visit.canResume && !visit.waiting && member(player, visit)) savesHint(player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun started(event: DungeonStartEvent) {
        if (!enabled) return
        event.dungeonInstance.players.forEach {
            show(it, "started", "<gold>Данж начался", "<white>/сохранения <gray>— ваши места")
            savesHint(it)
        }
    }

    private fun savesHint(player: Player) = audience.sendMessage(player, text("messages.saves-hint",
        "<click:run_command:'/сохранения'><gold>[/сохранения]</gold></click> <gray>— ваши места и начало данжа. <white>/сохраниться [название]</white> — запомнить место. <white>/данж выйти</white> — выйти."))

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
        if (!config.bool("dungeon-qol.resume-enabled", true) || player.isDead || player.gameMode == GameMode.SPECTATOR) return
        val visit = resolve(location.world) ?: return
        // Native quit removes membership before emitting its departure teleport.
        // Recording that position grants no admission: resume/travel recheck membership.
        if (!visit.canResume || inCombat(player) || !safe(location)) return
        checkpoints.remember(player.persistentDataContainer, location, visit.run, clock(), ttl)
    }

    private fun show(player: Player, key: String, title: String, subtitle: String) {
        if (!config.bool("dungeon-qol.titles-enabled", true)) return
        audience.showTitle(player, Title.title(config.component("dungeon-qol.titles.$key.title", title),
            config.component("dungeon-qol.titles.$key.subtitle", subtitle),
            Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(500))))
    }

    internal fun text(key: String, fallback: String, vararg values: Pair<String, Component>): Component =
        config.component("dungeon-qol.$key", fallback) { values.forEach { (name, value) -> tag(name, value) } }
            .decoration(TextDecoration.ITALIC, false)

    private fun member(player: Player, visit: DungeonVisit): Boolean =
        visit.members?.contains(player.uniqueId) != false

    private fun current(player: Player): DungeonVisit? =
        if (!enabled || closed || !player.isOnline || player.isDead || player.gameMode == GameMode.SPECTATOR) null
        else resolve(player.world)?.takeIf { member(player, it) }

    internal fun view(player: Player): DungeonSaveView? {
        val visit = current(player) ?: return null
        if (!visit.canResume) return null
        return DungeonSaveView(player.world.uid, visit.run,
            checkpoints.list(player.persistentDataContainer, player.world.uid, visit.run, clock(), ttl),
            visit.entry?.clone(), checkpoints.destination(player.persistentDataContainer, player.world, visit.run, clock(), ttl))
    }

    internal fun action(player: Player, action: String, args: List<String> = emptyList()) {
        when (action) {
            "start", "начать" -> {
                val visit = current(player)
                when {
                    visit == null -> audience.sendMessage(player, outside())
                    !visit.waiting -> audience.sendMessage(player, text("messages.already-started", "<gray>Этот данж уже идёт или не требует запуска. <white>/сохранения</white> — ваши места."))
                    else -> player.performCommand("elitemobs:elitemobs start")
                }
            }
            "quit", "leave", "выйти" -> quit(player)
            "save", "сохраниться" -> {
                val expected = view(player)
                if (expected == null) audience.sendMessage(player, outside())
                else audience.sendMessage(player, save(player, args.joinToString(" "), expected).message)
            }
            "entry", "вход" -> {
                val expected = view(player)
                if (expected == null) audience.sendMessage(player, outside()) else travel(player, expected, "entry")
            }
            "saves", "сохранения" -> if (view(player) == null) audience.sendMessage(player, outside()) else menus.open(player)
            else -> audience.sendMessage(player, text("messages.help", "<gold>Данжи:</gold> <white>/начать</white> · <white>/сохраниться [название]</white> · <white>/сохранения</white> · <white>/данж вход</white> · <white>/данж выйти</white>"))
        }
    }

    internal fun quit(player: Player) {
        pending.remove(player.uniqueId)
        val visit = resolve(player.world)
        if (!enabled || visit == null) { audience.sendMessage(player, outside()); return }
        // Native quit removes membership and performs the configured safe return.
        player.performCommand(if (visit.instanced) "elitemobs:elitemobs quit" else config.string("dungeon-qol.open-exit-command", "spawn"))
    }

    private fun outside() = text("messages.outside", "<gray>Сохранения доступны во время прохождения данжа. В его лобби: <white>/начать</white>. Для выхода: <white>/данж выйти</white>.")
    private fun changed() = DungeonSaveEdit(false, text("saves.messages.changed", "<red>Данж или сохранение изменились. Откройте /сохранения заново."))
    private fun matches(player: Player, expected: DungeonSaveView): Boolean =
        player.world.uid == expected.worldId && current(player)?.let { it.run == expected.run && it.canResume } == true
    private fun inCombat(player: Player): Boolean = clock() < (combatUntil[player.uniqueId] ?: 0L)
    private fun combatMessage() = text("saves.messages.combat", "<red>Во время боя сохраняться и перемещаться нельзя. Подождите 15 секунд без боя. <gray>Для выхода: /данж выйти.")

    internal fun save(player: Player, name: String, expected: DungeonSaveView): DungeonSaveEdit {
        if (!matches(player, expected)) return changed()
        if (inCombat(player)) return DungeonSaveEdit(false, combatMessage())
        val now = clock()
        if (lastSave[player.uniqueId]?.let { now - it < 5_000 } == true) return DungeonSaveEdit(false, text("saves.messages.cooldown", "<gray>Подождите 5 секунд между сохранениями."))
        if (!stable(player)) return DungeonSaveEdit(false, text("saves.messages.unsafe", "<red>Встаньте на безопасную твёрдую поверхность, вдали от огня, воды и обрыва."))
        val points = checkpoints.list(player.persistentDataContainer, expected.worldId, expected.run, now, ttl)
        val label = name.trim().ifEmpty {
            (1..5).map { "Место $it" }.firstOrNull { candidate -> points.none { it.kind == DungeonSaveKind.MANUAL && it.name.equals(candidate, true) } } ?: ""
        }
        if (label.isNotEmpty() && (label.length > 32 || label.any(Char::isISOControl))) return DungeonSaveEdit(false, text("saves.messages.invalid-name", "<red>Название должно содержать от 1 до 32 символов."))
        val saved = checkpoints.save(player.persistentDataContainer, player.location, expected.run, label, DungeonSaveKind.MANUAL, now, ttl)
            ?: return DungeonSaveEdit(false, text("saves.messages.full", "<red>Уже есть 5 ручных мест. Удалите ненужное в /сохранения или сохранитесь с тем же названием, чтобы заменить его."))
        lastSave[player.uniqueId] = now
        return if (persist(player)) DungeonSaveEdit(true, text("saves.messages.saved", "<green>Место «<name>» сохранено. <white>/сохранения</white> — открыть список.", "name" to Component.text(saved.name)))
        else DungeonSaveEdit(false, text("saves.messages.write-failed", "<red>Не удалось записать сохранение на диск. Повторите попытку позже."))
    }

    internal fun remove(player: Player, point: DungeonSavePoint, expected: DungeonSaveView): DungeonSaveEdit {
        if (!matches(player, expected)) return changed()
        val actual = checkpoints.list(player.persistentDataContainer, expected.worldId, expected.run, clock(), ttl).firstOrNull { it.id == point.id }
        if (actual != point || !checkpoints.remove(player.persistentDataContainer, expected.worldId, expected.run, point.id)) return changed()
        return if (persist(player)) DungeonSaveEdit(true, text("saves.messages.removed", "<gray>Сохранение удалено."))
        else DungeonSaveEdit(false, text("saves.messages.write-failed", "<red>Не удалось записать сохранение на диск. Повторите попытку позже."))
    }

    internal fun travel(player: Player, expected: DungeonSaveView, id: String) {
        if (!matches(player, expected)) { audience.sendMessage(player, changed().message); return }
        if (inCombat(player)) { audience.sendMessage(player, combatMessage()); return }
        val token = UUID.randomUUID()
        if (pending.putIfAbsent(player.uniqueId, token) != null) { audience.sendMessage(player, text("saves.messages.pending", "<gray>Перемещение уже готовится.")); return }
        val from = player.location.clone()
        audience.sendMessage(player, text("saves.messages.countdown", "<gray>Перемещение через 3 секунды. Не двигайтесь; бой отменит переход."))
        tasks.runLater(60) {
            if (!pending.remove(player.uniqueId, token)) return@runLater
            if (!matches(player, expected)) { if (player.isOnline) audience.sendMessage(player, changed().message); return@runLater }
            if (inCombat(player) || player.location.distanceSquared(from) > 0.09 || player.isInsideVehicle) {
                audience.sendMessage(player, text("saves.messages.cancelled", "<gray>Перемещение отменено: вы сдвинулись или вступили в бой.")); return@runLater
            }
            val visit = current(player) ?: return@runLater
            val destination = when (id) {
                "entry" -> visit.entry?.takeIf { it == expected.entry }
                "exit" -> checkpoints.destination(player.persistentDataContainer, player.world, expected.run, clock(), ttl)?.takeIf { it == expected.exit }
                else -> checkpoints.list(player.persistentDataContainer, expected.worldId, expected.run, clock(), ttl)
                    .firstOrNull { it.id == id && it == expected.points.firstOrNull { point -> point.id == id } }?.location
            }
            if (destination == null || destination.world.uid != player.world.uid || !safe(destination)) {
                audience.sendMessage(player, text("saves.messages.unavailable-point", "<red>Эта точка больше не подходит для безопасного перехода. Выберите другую или /данж выйти.")); return@runLater
            }
            val moved = runCatching { move(player, destination.clone(), visit.instanced) }.getOrElse {
                Logging.error("Dungeon checkpoint teleport failed", it); false
            }
            audience.sendMessage(player, if (moved) text("saves.messages.arrived", "<green>Вы на месте.")
                else text("saves.messages.blocked", "<red>Перемещение отменено защитой. Для выхода используйте /данж выйти."))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun explainCancelledTeleport(event: PlayerTeleportEvent) {
        if (!enabled || !event.isCancelled || pending.containsKey(event.player.uniqueId) || teleporter.owns(event)) return
        val visit = resolve(event.from.world) ?: return
        if (!visit.instanced || event.cause !in setOf(PlayerTeleportEvent.TeleportCause.COMMAND, PlayerTeleportEvent.TeleportCause.PLUGIN)) return
        val now = clock()
        if (lastHint[event.player.uniqueId]?.let { now - it < 3_000 } == true) return
        lastHint[event.player.uniqueId] = now
        audience.sendMessage(event.player, text("messages.teleport-blocked", "<gold>Вы внутри данжа.</gold> <gray>Для выхода:</gray> <click:run_command:'/dungeon quit'><green>[/данж выйти]</green></click><gray>. Вернуться ко входу или сохранённому месту:</gray> <click:run_command:'/сохранения'><white>[/сохранения]</white></click>"))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun combat(event: EntityDamageByEntityEvent) {
        val attacker = (event.damager as? Player) ?: (event.damager as? Projectile)?.shooter as? Player
        listOfNotNull(event.entity as? Player, attacker).forEach { player ->
            if (resolve(player.world) != null) combatUntil[player.uniqueId] = clock() + 15_000
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun cancelOnMovement(event: PlayerMoveEvent) {
        if (event.hasChangedPosition() && pending.remove(event.player.uniqueId) != null) {
            audience.sendMessage(event.player, text("saves.messages.cancelled", "<gray>Перемещение отменено: вы сдвинулись или вступили в бой."))
        }
    }

    internal fun startAutosaves() {
        if (autosavesStarted) return
        autosavesStarted = true
        tasks.runTimer(400, 400) { Bukkit.getOnlinePlayers().forEach(::autoSave) }
    }

    internal fun autoSave(player: Player) {
        if (!config.bool("dungeon-qol.saves.autosave-enabled", true) || inCombat(player) || pending.containsKey(player.uniqueId)) return
        val visit = current(player)?.takeIf { it.canResume } ?: return
        val now = clock()
        val latest = checkpoints.list(player.persistentDataContainer, player.world.uid, visit.run, now, ttl)
            .filter { it.kind == DungeonSaveKind.AUTO }.maxByOrNull { it.savedAt }
        val interval = config.integer("dungeon-qol.saves.autosave-seconds", 120).coerceIn(30, 600) * 1_000L
        if (latest != null && (now - latest.savedAt < interval || latest.location.distanceSquared(player.location) < 64)) return
        if (!stable(player)) return
        checkpoints.save(player.persistentDataContainer, player.location, visit.run, "Автосохранение", DungeonSaveKind.AUTO, now, ttl) ?: return
        if (persist(player)) audience.sendActionBar(player, text("saves.messages.auto-saved", "<gray>Место сохранено автоматически · /сохранения"))
    }

    private fun stable(player: Player): Boolean = !player.isInsideVehicle && !player.isGliding && !player.isFlying &&
        player.fallDistance <= 0f && player.fireTicks <= 0 && safe(player.location)

    private fun persist(player: Player): Boolean = runCatching { persistence.persist(player) }.fold({ true }, {
        Logging.error("Unable to persist dungeon checkpoints for {}", player.uniqueId, it); false
    })

    override fun close() {
        closed = true
        pending.clear()
        combatUntil.clear()
        lastSave.clear()
        lastHint.clear()
        tasks.close()
    }
}

internal data class DungeonSaveView(val worldId: UUID, val run: String, val points: List<DungeonSavePoint>, val entry: Location?, val exit: Location?)
internal data class DungeonSaveEdit(val success: Boolean, val message: Component)

internal fun safeDungeonCheckpoint(location: Location): Boolean {
    return !isNativeWormholeTrigger(location) && safeDungeonTerrain(location)
}

internal fun safeDungeonTerrain(location: Location): Boolean {
    val world = location.world
    if (!location.x.isFinite() || !location.y.isFinite() || !location.z.isFinite() || !location.yaw.isFinite() || !location.pitch.isFinite()) return false
    if (location.y < world.minHeight + 1 || location.y >= world.maxHeight - 2 || !world.worldBorder.isInside(location)) return false
    val xs = kotlin.math.floor(location.x - 0.3).toInt()..kotlin.math.floor(location.x + 0.3).toInt()
    val zs = kotlin.math.floor(location.z - 0.3).toInt()..kotlin.math.floor(location.z + 0.3).toInt()
    // Check the standing player's whole body, including adjacent chunks at an edge.
    // Load only already-generated chunks (at most four), never generate terrain.
    for (chunkX in (xs.first shr 4)..(xs.last shr 4)) for (chunkZ in (zs.first shr 4)..(zs.last shr 4)) {
        if (!world.isChunkLoaded(chunkX, chunkZ) &&
            (!world.isChunkGenerated(chunkX, chunkZ) || !world.getChunkAt(chunkX, chunkZ, false).load(false))) return false
    }
    val floor = location.block.getRelative(0, -1, 0)
    val hazards = setOf(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
        Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.END_PORTAL, Material.NETHER_PORTAL,
        Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.COBWEB)
    if (!floor.type.isSolid || floor.type in hazards) return false
    for (x in xs) for (z in zs) for (y in location.blockY..kotlin.math.floor(location.y + 1.8).toInt()) {
        val block = world.getBlockAt(x, y, z)
        if (!block.isPassable || block.isLiquid || block.type in hazards) return false
    }
    return true
}
