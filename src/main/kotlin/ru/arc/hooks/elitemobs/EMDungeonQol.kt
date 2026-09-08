package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.DungeonStartEvent
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
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
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.Portal
import ru.arc.PortalData
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.helpcenter.HelpCenterModule
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
    private val openPortal: (Player, () -> Unit) -> Unit = { player, action ->
        Portal(player.uniqueId, PortalData(ownerAction = { action() }))
    },
    private val returnMove: (Player, Location) -> Unit = { player, destination ->
        com.magmaguy.elitemobs.api.PlayerTeleportEvent.teleportPlayer(player, destination)
    },
    private val leaveWormholeWorld: (Player, World) -> Unit = NativeWormholeCooldowns()::leftWorld,
) : Listener, AutoCloseable {
    internal val scoreboard = DungeonScoreboard(this)
    private val checkpoints = DungeonCheckpointStore()
    private val tasks = LifecycleTaskScope()
    private val combatUntil = mutableMapOf<UUID, Long>()
    private val lastHint = mutableMapOf<UUID, Long>()
    private val lastSave = mutableMapOf<UUID, Long>()
    private val pending = mutableMapOf<UUID, UUID>()
    internal val supplies by lazy { DungeonSupplyShop(
        offers = { DEFAULT_SUPPLY_OFFERS.filter { config.bool("dungeon-qol.shop.stock.${it.id}.enabled", true) }.map {
            it.copy(amount = config.integer("dungeon-qol.shop.stock.${it.id}.amount", it.amount),
                price = config.double("dungeon-qol.shop.stock.${it.id}.price", it.price))
        } }, current = ::panelView,
    ) }
    private val menus by lazy { DungeonSaveMenus(this) }
    private var closed = false
    private var autosavesStarted = false
    private val autosaveIntervalKey = NamespacedKey("arc", "dungeon_autosave_seconds")

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
        else checkpoints.forgetDestination(event.player.persistentDataContainer, destination.world.uid)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun rememberDeparture(event: PlayerTeleportEvent) {
        if (!enabled || event.isCancelled || event.player.isDead || event.from.world.uid == event.to.world.uid) return
        remember(event.player, event.from)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun rememberLogout(event: PlayerQuitEvent) {
        scoreboard.remove(event.player.uniqueId)
        if (enabled) remember(event.player, event.player.location)
        combatUntil.remove(event.player.uniqueId)
        lastHint.remove(event.player.uniqueId)
        lastSave.remove(event.player.uniqueId)
        pending.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun cancelTravelOnDeath(event: PlayerDeathEvent) {
        pending.remove(event.entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun entered(event: PlayerChangedWorldEvent) {
        scoreboard.remove(event.player.uniqueId)
        if (!enabled) return
        val player = event.player
        val world = player.world
        pending.remove(player.uniqueId)
        if (resolve(event.from)?.instanced == false) leaveWormholeWorld(player, event.from)
        // Wait for native admission and entry text; no delayed teleport or bypass is created.
        tasks.runLater(30L) {
            if (!enabled || !player.isOnline || player.world.uid != world.uid) return@runLater
            val visit = resolve(world) ?: return@runLater
            if (!member(player, visit)) return@runLater
            if (visit.waiting) show(player, "entry", "<gold>Готовы к данжу?", "<white>/начать <gray>— начать прохождение")
            else show(player, "open-entry", "<gold>Вы в данже", "<white>/данж <gray>— меню данжа")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun started(event: DungeonStartEvent) {
        if (!enabled) return
        event.dungeonInstance.players.forEach {
            show(it, "started", "<gold>Данж начался", "<white>/данж <gray>— меню данжа")
        }
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
        if (!config.bool("dungeon-qol.resume-enabled", true) || player.isDead || player.gameMode == GameMode.SPECTATOR) return
        val visit = resolve(location.world) ?: return
        // Native quit removes membership before emitting its departure teleport.
        // Recording that position grants no admission: resume/travel recheck membership.
        // A successful open-world exit must replace the old point even just after combat.
        if (!visit.canResume || (visit.instanced && inCombat(player)) || !safe(location)) return
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

    internal fun context(player: Player): Component {
        val visit = current(player) ?: return outside()
        val lore = visit.lore.filter { it.isNotBlank() }.take(8).joinToString("\n")
        val description = if (lore.isBlank()) text("context.fallback", "<#e8dfd2>Продвигайтесь осторожно, проверяйте боковые проходы и пополняйте припасы перед сильными противниками.")
            else net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(lore.replace('§', '&'))
        val key = visit.contentId?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: "unknown"
        return text("context.$key.body", "<description>", "description" to description)
    }

    internal fun canConfigureAutosaves(): Boolean = config.bool("dungeon-qol.saves.autosave-enabled", true)

    internal fun autosaveSeconds(player: Player): Int {
        if (!canConfigureAutosaves()) return 0
        return player.persistentDataContainer.get(autosaveIntervalKey, PersistentDataType.INTEGER)
            ?.takeIf { it in DUNGEON_AUTOSAVE_INTERVALS }
            ?: config.integer("dungeon-qol.saves.autosave-seconds", 120).coerceIn(30, 600)
    }

    internal fun setAutosaveSeconds(player: Player, seconds: Int): DungeonSaveEdit {
        if (seconds !in DUNGEON_AUTOSAVE_INTERVALS || !canConfigureAutosaves())
            return DungeonSaveEdit(false, text("saves.settings.unavailable", "<#aaa49a>Настройка автосохранения сейчас недоступна."))
        val data = player.persistentDataContainer
        val previous = data.get(autosaveIntervalKey, PersistentDataType.INTEGER)
        if (previous == seconds) return DungeonSaveEdit(true, Component.empty())
        data.set(autosaveIntervalKey, PersistentDataType.INTEGER, seconds)
        if (persist(player)) return DungeonSaveEdit(true, Component.empty())
        if (previous == null) data.remove(autosaveIntervalKey) else data.set(autosaveIntervalKey, PersistentDataType.INTEGER, previous)
        return DungeonSaveEdit(false, text("saves.messages.write-failed", "<red>Не удалось сохранить настройку. Повторите попытку позже."))
    }

    internal fun autosaveDescription(player: Player): Component {
        if (autosaveSeconds(player) == 0) return text("saves.dialog.autosaves-disabled", "<#aaa49a>Автосохранения отключены. Ручные точки доступны через «Сохранить здесь».")
        return text("saves.dialog.autosaves", "<#e8dfd2>Автосохранение запоминает вашу позицию, а не добычу или состояние монстров.<newline><#aaa49a>Проверка каждые 20 секунд: первая безопасная точка, затем не чаще раза в <seconds> секунд и после перемещения хотя бы на 8 блоков. Нужно стоять на безопасной поверхности, без полёта и транспорта, не гореть и 15 секунд не участвовать в бою.<newline>Хранятся 3 последние автоточки и до 5 ручных. Смерть их не удаляет. Точки действуют <hours> ч.; в новом инстансе места прошлого прохождения недоступны.",
            "seconds" to Component.text(autosaveSeconds(player)),
            "hours" to Component.text(ttl / 3_600_000))
    }

    private fun member(player: Player, visit: DungeonVisit): Boolean =
        visit.members?.contains(player.uniqueId) != false

    private fun current(player: Player): DungeonVisit? =
        if (!enabled || closed || !player.isOnline || player.isDead || player.gameMode == GameMode.SPECTATOR) null
        else resolve(player.world)?.takeIf { member(player, it) }

    internal fun panelView(player: Player): DungeonPanelView? = current(player)?.let {
        DungeonPanelView(player.world.uid, it, view(player))
    }

    internal fun partiesAvailable(): Boolean = nativeDungeonPartiesAvailable()

    /** Dialog callbacks must still belong to the exact world and native run shown. */
    internal fun panelAction(player: Player, expected: DungeonPanelView, action: String) {
        if (player.world.uid != expected.worldId || current(player)?.run != expected.visit.run) {
            audience.sendMessage(player, changed().message)
            return
        }
        action(player, action)
    }

    internal fun view(player: Player): DungeonSaveView? {
        val visit = current(player) ?: return null
        if (!visit.canResume) return null
        return DungeonSaveView(player.world.uid, visit.run,
            checkpoints.list(player.persistentDataContainer, player.world.uid, visit.run, clock(), ttl),
            visit.entry?.clone(), checkpoints.destination(player.persistentDataContainer, player.world, visit.run, clock(), ttl))
    }

    internal fun lastReturn(player: Player): DungeonDeparture? {
        if (!enabled || closed || !config.bool("dungeon-qol.resume-enabled", true) || !player.isOnline ||
            player.isDead || player.gameMode == GameMode.SPECTATOR || resolve(player.world) != null) return null
        val point = checkpoints.latestDeparture(player.persistentDataContainer, clock(), ttl) ?: return null
        val visit = resolve(point.location.world) ?: return null
        if (visit.instanced || !visit.canResume || visit.run != point.run ||
            visit.permission?.takeIf { it.isNotBlank() }?.let { !player.hasPermission(it) } == true) return null
        return point
    }

    internal fun returnToLast(player: Player, expected: DungeonDeparture? = lastReturn(player)) {
        val unavailable = text("panel.return-unavailable", "<#aaa49a>Нет доступного места выхода из обычного данжа. Сначала посетите данж и выйдите из него.")
        if (expected == null || lastReturn(player) != expected) { audience.sendMessage(player, unavailable); return }
        if (inCombat(player)) { audience.sendMessage(player, combatMessage()); return }
        val origin = player.world.uid
        val token = UUID.randomUUID()
        if (pending.putIfAbsent(player.uniqueId, token) != null) {
            audience.sendMessage(player, text("saves.messages.pending", "<gray>Сначала войдите в открытый портал или дождитесь его закрытия.")); return
        }
        tasks.runLater(420) { pending.remove(player.uniqueId, token) }
        runCatching { openPortal(player) portal@{
            if (!pending.remove(player.uniqueId, token)) return@portal
            if (player.world.uid != origin || lastReturn(player) != expected) {
                if (player.isOnline) audience.sendMessage(player, unavailable)
                return@portal
            }
            if (inCombat(player)) { audience.sendMessage(player, combatMessage()); return@portal }
            if (player.isInsideVehicle || player.isFlying || player.isGliding) {
                audience.sendMessage(player, text("saves.messages.travel-ground", "<gray>Войдите в портал пешком, без транспорта и полёта.")); return@portal
            }
            if (!safe(expected.location)) {
                audience.sendMessage(player, text("saves.messages.unavailable-point", "<red>Эта точка больше не подходит для безопасного перехода. Выберите другую или /данж выйти.")); return@portal
            }
            returnMove(player, expected.location.clone())
        } }.onFailure {
            pending.remove(player.uniqueId, token)
            Logging.error("Unable to return to last dungeon", it)
            audience.sendMessage(player, text("saves.messages.blocked", "<red>Перемещение отменено защитой. Для выхода используйте /данж выйти."))
        }
    }

    internal fun action(player: Player, action: String, args: List<String> = emptyList()) {
        when (action) {
            "menu", "меню" -> menus.panel(player)
            "return", "вернуться" -> returnToLast(player)
            "main" -> if (!HelpCenterModule.open(player)) audience.sendMessage(player, text("panel.main-unavailable", "<#d7b486>Главное меню сейчас недоступно. Попробуйте позже."))
            "party" -> if (partiesAvailable()) player.performCommand("elitemobs:em party menu")
                else audience.sendMessage(player, text("party.unavailable", "<#aaa49a>Группы EliteMobs на этом сервере пока недоступны."))
            "shops", "магазины" -> {
                if (resolve(player.world)?.instanced == true) audience.sendMessage(player, text("messages.leave-first", "<#d7b486>Сначала выйдите из текущего данжа: /данж выйти."))
                else player.performCommand(config.string("dungeon-qol.shops-command", "pw aguild"))
            }
            "tp", "тп", "порталы", "list", "список" -> {
                if (current(player)?.instanced == true) audience.sendMessage(player, text("messages.leave-first", "<#d7b486>Сначала выйдите из текущего данжа: /данж выйти."))
                else player.performCommand(if (action in setOf("list", "список")) "elitemobs:em" else "pw aguild")
            }
            "start", "начать" -> {
                val visit = current(player)
                when {
                    visit == null -> audience.sendMessage(player, outside())
                    !visit.waiting -> audience.sendMessage(player, text("messages.already-started", "<gray>Этот данж уже идёт или не требует запуска. <white>/сохранения</white> — ваши места."))
                    else -> player.performCommand("elitemobs:elitemobs start")
                }
            }
            "journal" -> if (current(player) != null) player.performCommand("elitemobs:em") else audience.sendMessage(player, outside())
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
            "saves", "сохранения" -> menus.open(player)
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

    /** Shared, non-mutating preflight for the panel, form, and authoritative save action. */
    internal fun saveBlockReason(player: Player, expected: DungeonSaveView?): Component? {
        if (expected == null) return text("panel.save-inactive", "<#aaa49a>Сохранения доступны только во время прохождения.")
        if (!matches(player, expected)) return changed().message
        if (inCombat(player)) return combatMessage()
        if (pending.containsKey(player.uniqueId)) return text("saves.messages.pending", "<gray>Сначала войдите в открытый портал или дождитесь его закрытия.")
        if (lastSave[player.uniqueId]?.let { clock() - it < 5_000 } == true)
            return text("saves.messages.cooldown", "<gray>Подождите 5 секунд между сохранениями.")
        if (player.isInsideVehicle) return text("saves.messages.vehicle", "<#aaa49a>Чтобы сохраниться, выйдите из транспорта или слезьте с ездового животного.")
        if (player.isFlying || player.isGliding) return text("saves.messages.flying", "<#aaa49a>Чтобы сохраниться, приземлитесь и выключите полёт.")
        if (!stable(player)) return text("saves.messages.unsafe", "<red>Встаньте на безопасную твёрдую поверхность, вдали от огня, воды и обрыва.")
        return null
    }

    internal fun save(player: Player, name: String, expected: DungeonSaveView): DungeonSaveEdit {
        saveBlockReason(player, expected)?.let { return DungeonSaveEdit(false, it) }
        val now = clock()
        val points = checkpoints.list(player.persistentDataContainer, expected.worldId, expected.run, now, ttl)
        val label = name.trim().ifEmpty {
            (1..5).map { "Место $it" }.firstOrNull { candidate -> points.none { it.kind == DungeonSaveKind.MANUAL && it.name.equals(candidate, true) } } ?: ""
        }
        if (label.isNotEmpty() && (label.length > 32 || label.any(Char::isISOControl))) return DungeonSaveEdit(false, text("saves.messages.invalid-name", "<red>Название должно содержать от 1 до 32 символов."))
        val snapshot = checkpoints.snapshotSaves(player.persistentDataContainer)
        val saved = checkpoints.save(player.persistentDataContainer, player.location, expected.run, label, DungeonSaveKind.MANUAL, now, ttl)
            ?: return DungeonSaveEdit(false, text("saves.messages.full", "<red>Уже есть 5 ручных мест. Удалите ненужное в /сохранения или сохранитесь с тем же названием, чтобы заменить его."))
        if (!persist(player)) {
            checkpoints.restoreSaves(player.persistentDataContainer, snapshot)
            return DungeonSaveEdit(false, text("saves.messages.write-failed", "<red>Не удалось записать сохранение на диск. Повторите попытку позже."))
        }
        lastSave[player.uniqueId] = now
        return DungeonSaveEdit(true, text("saves.messages.saved", "<green>Место «<name>» сохранено. <white>/сохранения</white> — открыть список.", "name" to Component.text(saved.name)))
    }

    internal fun remove(player: Player, point: DungeonSavePoint, expected: DungeonSaveView): DungeonSaveEdit {
        if (!matches(player, expected)) return changed()
        val actual = checkpoints.list(player.persistentDataContainer, expected.worldId, expected.run, clock(), ttl).firstOrNull { it.id == point.id }
        val snapshot = checkpoints.snapshotSaves(player.persistentDataContainer)
        if (actual != point || !checkpoints.remove(player.persistentDataContainer, expected.worldId, expected.run, point.id)) return changed()
        if (!persist(player)) {
            checkpoints.restoreSaves(player.persistentDataContainer, snapshot)
            return DungeonSaveEdit(false, text("saves.messages.write-failed", "<red>Не удалось записать сохранение на диск. Повторите попытку позже."))
        }
        return DungeonSaveEdit(true, text("saves.messages.removed", "<gray>Сохранение удалено."))
    }

    internal fun travel(player: Player, expected: DungeonSaveView, id: String) {
        if (!matches(player, expected)) { audience.sendMessage(player, changed().message); return }
        if (inCombat(player)) { audience.sendMessage(player, combatMessage()); return }
        val token = UUID.randomUUID()
        if (pending.putIfAbsent(player.uniqueId, token) != null) { audience.sendMessage(player, text("saves.messages.pending", "<gray>Сначала войдите в открытый портал или дождитесь его закрытия.")); return }
        // Expire only this request; an old portal must not consume a later one.
        tasks.runLater(420) { pending.remove(player.uniqueId, token) }
        runCatching { openPortal(player) portal@{
            if (!pending.remove(player.uniqueId, token)) return@portal
            if (!matches(player, expected)) { if (player.isOnline) audience.sendMessage(player, changed().message); return@portal }
            if (inCombat(player)) { audience.sendMessage(player, combatMessage()); return@portal }
            if (player.isInsideVehicle || player.isFlying || player.isGliding) {
                audience.sendMessage(player, text("saves.messages.travel-ground", "<gray>Войдите в портал пешком, без транспорта и полёта.")); return@portal
            }
            val visit = current(player) ?: return@portal
            val destination = when (id) {
                "entry" -> visit.entry?.takeIf { it == expected.entry }
                "exit" -> checkpoints.destination(player.persistentDataContainer, player.world, expected.run, clock(), ttl)?.takeIf { it == expected.exit }
                else -> checkpoints.list(player.persistentDataContainer, expected.worldId, expected.run, clock(), ttl)
                    .firstOrNull { it.id == id && it == expected.points.firstOrNull { point -> point.id == id } }?.location
            }
            if (destination == null || destination.world.uid != player.world.uid || !safe(destination)) {
                audience.sendMessage(player, text("saves.messages.unavailable-point", "<red>Эта точка больше не подходит для безопасного перехода. Выберите другую или /данж выйти.")); return@portal
            }
            val moved = runCatching { move(player, destination.clone(), visit.instanced) }.getOrElse {
                Logging.error("Dungeon checkpoint teleport failed", it); false
            }
            if (!moved) audience.sendMessage(player, text("saves.messages.blocked", "<red>Перемещение отменено защитой. Для выхода используйте /данж выйти."))
        } }.onFailure {
            pending.remove(player.uniqueId, token)
            Logging.error("Unable to open dungeon checkpoint portal", it)
            audience.sendMessage(player, text("saves.messages.blocked", "<red>Перемещение отменено защитой. Для выхода используйте /данж выйти."))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun explainCancelledTeleport(event: PlayerTeleportEvent) {
        if (!enabled || !event.isCancelled || teleporter.owns(event)) return
        val visit = resolve(event.from.world) ?: return
        if (!visit.instanced || event.cause !in setOf(PlayerTeleportEvent.TeleportCause.COMMAND, PlayerTeleportEvent.TeleportCause.PLUGIN)) return
        val now = clock()
        if (lastHint[event.player.uniqueId]?.let { now - it < 3_000 } == true) return
        lastHint[event.player.uniqueId] = now
        audience.sendMessage(event.player, text("messages.teleport-blocked", "<gold>Вы внутри данжа.</gold> <gray>Для выхода:</gray> <click:run_command:'/dungeon quit'><green>[/данж выйти]</green></click><gray>. Начало данжа и сохранения:</gray> <click:run_command:'/данж'><white>[/данж — меню]</white></click>"))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun combat(event: EntityDamageByEntityEvent) {
        val attacker = (event.damager as? Player) ?: (event.damager as? Projectile)?.shooter as? Player
        listOfNotNull(event.entity as? Player, attacker).forEach { player ->
            if (resolve(player.world) != null) combatUntil[player.uniqueId] = clock() + 15_000
        }
    }

    internal fun startAutosaves() {
        if (autosavesStarted) return
        autosavesStarted = true
        tasks.runTimer(20, 20) { scoreboard.refresh(Bukkit.getOnlinePlayers()) }
        tasks.runTimer(400, 400) {
            // Native player persistence must stay on the server thread; stagger its disk writes.
            Bukkit.getOnlinePlayers().toList().forEachIndexed { index, player ->
                tasks.runLater(index.toLong() + 1) {
                    if (player.isOnline) autoSave(player)
                }
            }
        }
    }

    internal fun autoSave(player: Player) {
        val seconds = autosaveSeconds(player)
        if (seconds == 0 || inCombat(player) || pending.containsKey(player.uniqueId)) return
        val visit = current(player)?.takeIf { it.canResume } ?: return
        val now = clock()
        val latest = checkpoints.list(player.persistentDataContainer, player.world.uid, visit.run, now, ttl)
            .filter { it.kind == DungeonSaveKind.AUTO }.maxByOrNull { it.savedAt }
        val interval = seconds * 1_000L
        if (latest != null && (now - latest.savedAt < interval || latest.location.distanceSquared(player.location) < 64)) return
        if (!stable(player)) return
        val snapshot = checkpoints.snapshotSaves(player.persistentDataContainer)
        checkpoints.save(player.persistentDataContainer, player.location, visit.run, "Автосохранение", DungeonSaveKind.AUTO, now, ttl) ?: return
        if (persist(player)) audience.sendActionBar(player, text("saves.messages.auto-saved", "<gray>Место сохранено автоматически · /сохранения"))
        else checkpoints.restoreSaves(player.persistentDataContainer, snapshot)
    }

    private fun stable(player: Player): Boolean = !player.isInsideVehicle && !player.isGliding && !player.isFlying &&
        player.fallDistance <= 0f && player.fireTicks <= 0 && safe(player.location)

    private fun persist(player: Player): Boolean = runCatching { persistence.persist(player) }.fold({ true }, {
        Logging.error("Unable to persist dungeon checkpoints for {}", player.uniqueId, it); false
    })

    override fun close() {
        closed = true
        scoreboard.clear()
        pending.clear()
        combatUntil.clear()
        lastSave.clear()
        lastHint.clear()
        tasks.close()
    }
}

internal data class DungeonPanelView(val worldId: UUID, val visit: DungeonVisit, val saves: DungeonSaveView?)
internal data class DungeonSaveView(val worldId: UUID, val run: String, val points: List<DungeonSavePoint>, val entry: Location?, val exit: Location?)
internal data class DungeonSaveEdit(val success: Boolean, val message: Component)

internal val DUNGEON_AUTOSAVE_INTERVALS = listOf(60, 120, 300, 0)

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
