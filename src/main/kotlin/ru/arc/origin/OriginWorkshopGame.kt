package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import io.papermc.paper.event.player.PlayerArmSwingEvent
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.logging.Level
import kotlin.math.sqrt

internal enum class OriginWorkshopGameStage { PICK, CARRY, CLAMP, PAUSE, FINISH, DISPLAY, REWARDING, CLOSED }

internal data class OriginWorkshopGameRules(
    val beatPeriod: Long = 108,
    val windowStart: Long = 62,
    val windowWidth: Long = 16,
    val pause: Long = 20,
    val clampHits: Int = 4,
    val finishHits: Int = 5,
    val chairHold: Long = 60,
    val timeout: Long = 3_600,
) {
    init {
        require(beatPeriod in 40L..400L)
        require(windowStart in 1L until beatPeriod && windowWidth in 2L..40L && windowStart + windowWidth < beatPeriod)
        require(pause in 1L..200L && clampHits in 2..12 && finishHits in 2..12)
        require(chairHold >= 60)
        require(timeout in 1_000L..12_000L)
        require(minimumRhythm >= 900) { "Workshop rhythm must last at least 45 seconds" }
        require(timeout >= minimumRhythm + chairHold)
    }

    val minimumRhythm: Long get() = 2 * windowStart + (clampHits + finishHits - 2L) * beatPeriod + pause
}

internal data class OriginWorkshopGameProgress(
    val stage: OriginWorkshopGameStage,
    val hits: Int = 0,
    val nextBeat: Long? = null,
    val finishedAt: Long? = null,
)

internal enum class OriginWorkshopHitResult { HIT, EARLY, LATE, CLAMPED, FINISHED, IGNORED }
internal data class OriginWorkshopHit(val progress: OriginWorkshopGameProgress, val result: OriginWorkshopHitResult)

internal fun originWorkshopPick(progress: OriginWorkshopGameProgress) =
    progress.takeIf { it.stage == OriginWorkshopGameStage.PICK }?.copy(stage = OriginWorkshopGameStage.CARRY)

internal fun originWorkshopPlace(progress: OriginWorkshopGameProgress, now: Long, rules: OriginWorkshopGameRules) =
    progress.takeIf { it.stage == OriginWorkshopGameStage.CARRY }
        ?.copy(stage = OriginWorkshopGameStage.CLAMP, nextBeat = now + rules.windowStart)

internal fun originWorkshopHit(progress: OriginWorkshopGameProgress, now: Long, rules: OriginWorkshopGameRules): OriginWorkshopHit {
    val total = when (progress.stage) {
        OriginWorkshopGameStage.CLAMP -> rules.clampHits
        OriginWorkshopGameStage.FINISH -> rules.finishHits
        else -> return OriginWorkshopHit(progress, OriginWorkshopHitResult.IGNORED)
    }
    val beat = progress.nextBeat ?: return OriginWorkshopHit(progress, OriginWorkshopHitResult.IGNORED)
    if (now < beat) return OriginWorkshopHit(progress, OriginWorkshopHitResult.EARLY)
    if (now > beat + rules.windowWidth) {
        return OriginWorkshopHit(progress.copy(nextBeat = now + rules.beatPeriod), OriginWorkshopHitResult.LATE)
    }
    val count = progress.hits + 1
    if (count < total) return OriginWorkshopHit(
        progress.copy(hits = count, nextBeat = beat + rules.beatPeriod), OriginWorkshopHitResult.HIT,
    )
    return if (progress.stage == OriginWorkshopGameStage.CLAMP) {
        OriginWorkshopHit(
            progress.copy(stage = OriginWorkshopGameStage.PAUSE, hits = 0, nextBeat = now + rules.pause),
            OriginWorkshopHitResult.CLAMPED,
        )
    } else {
        OriginWorkshopHit(
            progress.copy(stage = OriginWorkshopGameStage.DISPLAY, hits = count, nextBeat = null, finishedAt = now),
            OriginWorkshopHitResult.FINISHED,
        )
    }
}

internal fun originWorkshopAdvance(progress: OriginWorkshopGameProgress, now: Long, rules: OriginWorkshopGameRules) =
    when (progress.stage) {
        OriginWorkshopGameStage.PAUSE ->
            if (now < (progress.nextBeat ?: Long.MAX_VALUE)) progress
            else progress.copy(stage = OriginWorkshopGameStage.FINISH, hits = 0, nextBeat = now + rules.windowStart)
        OriginWorkshopGameStage.DISPLAY ->
            if (now - (progress.finishedAt ?: now) < rules.chairHold) progress
            else progress.copy(stage = OriginWorkshopGameStage.REWARDING)
        else -> progress
    }

internal data class OriginWorkshopVec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(v: OriginWorkshopVec3) = OriginWorkshopVec3(x - v.x, y - v.y, z - v.z)
    operator fun times(k: Double) = OriginWorkshopVec3(x * k, y * k, z * k)
    fun dot(v: OriginWorkshopVec3) = x * v.x + y * v.y + z * v.z
}

internal fun originWorkshopRayHit(origin: OriginWorkshopVec3, direction: OriginWorkshopVec3, center: OriginWorkshopVec3, radius: Double, reach: Double): Double? {
    if (!listOf(origin.x, origin.y, origin.z, direction.x, direction.y, direction.z, center.x, center.y, center.z, radius, reach).all(Double::isFinite)) return null
    if (radius <= 0 || reach !in 0.0..4.5) return null
    val length = sqrt(direction.dot(direction))
    if (length < 1e-6) return null
    val ray = direction * (1.0 / length)
    val offset = center - origin
    val along = offset.dot(ray)
    if (along < 0) return null
    val perpendicular2 = offset.dot(offset) - along * along
    if (perpendicular2 > radius * radius) return null
    return (along - sqrt(radius * radius - perpendicular2)).takeIf { it in 0.0..reach }
}

private data class GameSettings(val enabled: Boolean, val rules: OriginWorkshopGameRules) {
    companion object {
        fun load(): GameSettings {
            val config = ConfigManager.ofModule(ARC.instance.dataPath, "origin-workshop-game.yml")
            config.mergeMissingFromBundled("modules/origin-workshop-game.yml")
            fun ticks(path: String, fallback: Int) = config.integer("origin-workshop-game." + path, fallback).toLong()
            return GameSettings(
                config.bool("origin-workshop-game.enabled", false),
                OriginWorkshopGameRules(
                    beatPeriod = ticks("rhythm.beat-period-ticks", 108),
                    windowStart = ticks("rhythm.window-start-ticks", 62),
                    windowWidth = ticks("rhythm.window-width-ticks", 16),
                    pause = ticks("rhythm.clamp-pause-ticks", 20),
                    clampHits = config.integer("origin-workshop-game.rhythm.clamp-hits", 4),
                    finishHits = config.integer("origin-workshop-game.rhythm.finish-hits", 5),
                    chairHold = ticks("rhythm.finished-hold-ticks", 60),
                    timeout = ticks("session-timeout-seconds", 180) * 20,
                ),
            )
        }
    }
}

/** One occupied, packet-only finisher station. Controls are eye-ray targets, never entities. */
internal object OriginWorkshopGame : PluginModule, Listener {
    override val name = "OriginWorkshopGame"
    override val priority = 25

    private const val TABLE = "finisher"
    private const val REACH = 4.5
    private const val TARGET_RADIUS = 0.34
    private const val BLOCK_EPSILON = 0.06
    private const val TICK_NANOS = 50_000_000L
    private const val STEP_TICKS = 4L
    private const val OFFER_TICKS = 200L
    private const val SESSION_RANGE_SQUARED = 36.0
    private const val PRODUCT = "furnituresplus:white_wooden_chair"
    private const val COOLDOWN = 86_400_000L
    private const val INPUT_DEDUPE_NANOS = 120_000_000L
    private const val FEEDBACK_NANOS = 450_000_000L
    private val cost = mapOf(Material.SPRUCE_LOG to 2, Material.YELLOW_WOOL to 1)
    private val start = OriginWorkshopPoint(0.0, 1.28, -1.35)
    private val pick = OriginWorkshopPoint(-0.58, 1.28, -1.18)
    private val socket = OriginWorkshopPoint(0.58, 1.28, -1.18)
    private val clamp = OriginWorkshopPoint(-0.48, 1.42, -0.88)
    private val finish = OriginWorkshopPoint(0.48, 1.42, -0.88)
    private val result = OriginWorkshopPoint(0.67, 1.28, -1.10)
    private val amber = Color.fromRGB(255, 186, 72)
    private val green = Color.fromRGB(105, 235, 126)

    private data class Offer(val until: Long)
    private data class Session(
        val id: UUID,
        val playerId: UUID,
        val worldId: UUID,
        val began: Long,
        var progress: OriginWorkshopGameProgress,
        var part: PacketItemDisplay?,
        var cueBeat: Long? = null,
        var lastFeedback: Long = 0,
        var rewardSent: Boolean = false,
        val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    )

    private var settings: GameSettings? = null
    private var owner: PaperPacketDisplays? = null
    private var marker: PacketItemDisplay? = null
    private var label: PacketTextDisplay? = null
    private var rewards: OriginWorkshopCraftRewards? = null
    private var commonTasks = LifecycleTaskScope()
    private var session: Session? = null
    private val offers = mutableMapOf<UUID, Offer>()
    private val pendingStatus = mutableSetOf<UUID>()
    private val accepted = mutableMapOf<UUID, Long>()
    private var registered = false
    private var generation = 0L

    override fun init() {
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, ARC.instance)
            registered = true
        }
        reload()
    }

    override fun reload() {
        val loaded = try { GameSettings.load() } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop game config rejected; keeping current runtime", failure)
            return
        }
        stop("reload")
        settings = loaded
        if (!loaded.enabled) {
            ARC.instance.logger.info("ORIGIN_WORKSHOP_GAME phase=DISABLED player=none session=none stage=NONE")
            return
        }
        val startLocation = OriginWorkshopTablesModule.pointAt(TABLE, start)
        if (startLocation == null) {
            ARC.instance.logger.warning("ORIGIN_WORKSHOP_GAME phase=UNAVAILABLE player=none session=none stage=TABLE_NOT_READY")
            return
        }
        val displayOwner = try { PaperPacketDisplays(ARC.instance) } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop game display owner unavailable", failure)
            return
        }
        owner = displayOwner
        rewards = try { OriginWorkshopCraftRewards(cost, COOLDOWN, PRODUCT) } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop game reward handler unavailable", failure)
            displayOwner.close()
            owner = null
            return
        }
        marker = try { spawn(displayOwner, Material.LIME_STAINED_GLASS_PANE, startLocation).apply {
            isVisibleByDefault = true
            isGlowing = true
            glowColorOverride = amber
        } } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop game marker unavailable", failure)
            rewards?.close()
            rewards = null
            displayOwner.close()
            owner = null
            return
        }
        OriginWorkshopTablesModule.pointAt(TABLE, OriginWorkshopPoint(0.0, 1.78, -1.35))?.let { labelAt ->
            label = runCatching {
                displayOwner.spawnText(labelAt, Component.text("Собрать стул · ПКМ")).apply {
                    isVisibleByDefault = true
                    billboard = Display.Billboard.CENTER
                    viewRange = 0.55f
                    shadowRadius = 0f
                    lineWidth = 120
                    isSeeThrough = true
                    isDefaultBackground = false
                    textOpacity = 230.toByte()
                }
            }.onFailure {
                ARC.instance.logger.log(Level.WARNING, "Origin workshop game station label unavailable", it)
            }.getOrNull()
        }
        ARC.instance.logger.info("ORIGIN_WORKSHOP_GAME phase=READY player=none session=none stage=START")
    }

    override fun shutdown() {
        stop("shutdown")
        settings = null
        if (registered) HandlerList.unregisterAll(this)
        registered = false
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwing(event: PlayerArmSwingEvent) {
        if (event.hand == EquipmentSlot.HAND && click(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND ||
            event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK
        ) return
        if (click(event.player)) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        session?.takeIf { it.playerId == event.player.uniqueId }?.let { cancel(it, "quit", false) }
        offers.remove(event.player.uniqueId)
        pendingStatus.remove(event.player.uniqueId)
        accepted.remove(event.player.uniqueId)
    }

    private fun click(player: Player): Boolean {
        if (marker == null || settings?.enabled != true) return false
        val nowNanos = System.nanoTime()
        val previous = accepted[player.uniqueId]
        if (previous != null && nowNanos - previous <= INPUT_DEDUPE_NANOS) {
            accepted.remove(player.uniqueId)
            return true
        }
        accepted.remove(player.uniqueId)
        val current = session
        if (current != null && current.playerId != player.uniqueId) return false
        val anchor = if (current == null) start else target(current.progress.stage)
        val location = OriginWorkshopTablesModule.pointAt(TABLE, anchor) ?: return false
        if (targetDistance(player, location) == null) return false
        accepted[player.uniqueId] = nowNanos
        if (current == null) startClick(player) else sessionClick(player, current)
        return true
    }

    private fun startClick(player: Player) {
        val reward = rewards ?: return
        val now = nowTick()
        offers.entries.removeIf { now > it.value.until }
        player.sendActionBar(Component.text("Стул: 2 бревна ели + 1 жёлтая шерсть · перерыв 24 часа"))
        val offer = offers.remove(player.uniqueId)
        if (offer != null && now <= offer.until) {
            val missing = reward.missing(player)
            if (missing != null) {
                player.sendActionBar(Component.text(missing))
                return
            }
            if (session != null) {
                player.sendActionBar(Component.text("Верстак занят. Попробуй чуть позже."))
                return
            }
            begin(player)
            return
        }
        if (!pendingStatus.add(player.uniqueId)) return
        if (offers.size + pendingStatus.size > 32) {
            pendingStatus.remove(player.uniqueId)
            player.sendActionBar(Component.text("Верстак занят. Попробуй ещё раз через минуту."))
            return
        }
        val missing = reward.missing(player)
        if (missing != null) {
            pendingStatus.remove(player.uniqueId)
            player.sendActionBar(Component.text(missing))
            return
        }
        val token = commonTasks.token()
        val generationAtQuery = generation
        reward.status(player.uniqueId).whenComplete { remaining, failure ->
            commonTasks.runSync(token) {
                pendingStatus.remove(player.uniqueId)
                if (generationAtQuery != generation || !player.isOnline || marker == null) return@runSync
                if (failure != null) {
                    ARC.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP_GAME phase=STATUS_ERROR player=" + player.uniqueId + " session=none stage=START", failure)
                    player.sendActionBar(Component.text("Не удалось проверить перерыв. Попробуй позже."))
                } else if ((remaining ?: Long.MAX_VALUE) > 0) {
                    val minutes = kotlin.math.ceil((remaining ?: 0L) / 60_000.0).toLong().coerceAtLeast(1)
                    player.sendActionBar(Component.text("Недавно уже собирали мебель. Подожди ещё " + minutes + " мин."))
                } else {
                    offers[player.uniqueId] = Offer(nowTick() + OFFER_TICKS)
                    player.sendActionBar(Component.text("Всё готово. Нажми на рукоять ещё раз, чтобы начать."))
                }
            }
        }
    }

    private fun begin(player: Player) {
        val rules = settings?.rules ?: return
        val startLocation = OriginWorkshopTablesModule.pointAt(TABLE, start) ?: return
        if (player.world.uid != startLocation.world.uid) return
        val partLocation = OriginWorkshopTablesModule.pointAt(TABLE, pick) ?: return
        val part = try { spawn(owner ?: return, Material.SPRUCE_LOG, partLocation) } catch (failure: Exception) {
            player.sendActionBar(Component.text("Мастерская временно недоступна."))
            return
        }
        val now = nowTick()
        val active = Session(
            UUID.randomUUID(), player.uniqueId, player.world.uid, now,
            OriginWorkshopGameProgress(OriginWorkshopGameStage.PICK), part,
        )
        session = active
        marker?.isVisibleByDefault = false
        label?.isVisibleByDefault = false
        marker?.showTo(player)
        marker?.teleport(partLocation)
        part.isVisibleByDefault = false
        part.isGlowing = true
        part.glowColorOverride = Color.fromRGB(157, 113, 67)
        part.showTo(player)
        active.tasks.runTimer(STEP_TICKS, STEP_TICKS) { step(active, rules) }
        player.sendActionBar(Component.text("Возьми еловую заготовку со стола."))
        log(active, "START", active.progress.stage)
    }

    private fun sessionClick(player: Player, active: Session) {
        val rules = settings?.rules ?: return
        val now = nowTick()
        advance(active, now, rules)
        when (active.progress.stage) {
            OriginWorkshopGameStage.PICK -> {
                active.progress = originWorkshopPick(active.progress) ?: return
                marker?.teleport(OriginWorkshopTablesModule.pointAt(TABLE, socket) ?: return)
                marker?.glowColorOverride = amber
                player.sendActionBar(Component.text("Перенеси заготовку в подсвеченное гнездо."))
                player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.4f, 1.0f)
                log(active, "STAGE", active.progress.stage)
            }
            OriginWorkshopGameStage.CARRY -> {
                active.part?.teleport(OriginWorkshopTablesModule.pointAt(TABLE, socket) ?: return)
                active.part?.isGlowing = false
                active.progress = originWorkshopPlace(active.progress, now, rules) ?: return
                marker?.teleport(OriginWorkshopTablesModule.pointAt(TABLE, clamp) ?: return)
                marker?.glowColorOverride = amber
                player.sendActionBar(Component.text("Жди зелёный сигнал и бей по зажиму."))
                player.playSound(player.location, Sound.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.4f, 1.0f)
                log(active, "STAGE", active.progress.stage)
            }
            OriginWorkshopGameStage.CLAMP, OriginWorkshopGameStage.FINISH -> rhythmClick(player, active, rules, now)
            OriginWorkshopGameStage.PAUSE -> player.sendActionBar(Component.text("Зажим отпускается. Готовься к шлифовке."))
                OriginWorkshopGameStage.DISPLAY, OriginWorkshopGameStage.REWARDING ->
                player.sendActionBar(Component.text("Стул готов — оставь его на просушке."))
            else -> Unit
        }
    }

    private fun rhythmClick(player: Player, active: Session, rules: OriginWorkshopGameRules, now: Long) {
        val hit = originWorkshopHit(active.progress, now, rules)
        when (hit.result) {
            OriginWorkshopHitResult.EARLY -> {
                feedback(player, active, "Рано. Жди зелёный огонёк.")
                return
            }
            OriginWorkshopHitResult.LATE -> {
                active.progress = hit.progress
                active.cueBeat = null
                marker?.glowColorOverride = amber
                feedback(player, active, "Поздно. Подготовь следующий удар.")
                return
            }
            OriginWorkshopHitResult.IGNORED -> return
            else -> Unit
        }
        active.progress = hit.progress
        active.cueBeat = null
        marker?.glowColorOverride = amber
        val at = OriginWorkshopTablesModule.pointAt(TABLE, target(active.progress.stage)) ?: player.location
        player.world.spawnParticle(Particle.CRIT, at, 4, 0.08, 0.06, 0.08, 0.02)
        player.playSound(at, Sound.BLOCK_SMITHING_TABLE_USE, SoundCategory.BLOCKS, 0.35f, 0.95f)
        when (hit.result) {
            OriginWorkshopHitResult.HIT -> {
                player.sendActionBar(Component.text("Хорошо. " + active.progress.hits))
                log(active, "HIT", active.progress.stage)
            }
            OriginWorkshopHitResult.CLAMPED -> {
                marker?.teleport(OriginWorkshopTablesModule.pointAt(TABLE, finish) ?: at)
                marker?.glowColorOverride = amber
                player.sendActionBar(Component.text("Зажим готов. Скоро проведи щёткой по стулу."))
                log(active, "STAGE", active.progress.stage)
            }
            OriginWorkshopHitResult.FINISHED -> {
                CustomStack.getInstance(PRODUCT)?.itemStack?.clone()?.let { active.part?.itemStack = it }
                active.part?.isGlowing = false
                active.part?.teleport(OriginWorkshopTablesModule.pointAt(TABLE, this.result) ?: at)
                OriginWorkshopTablesModule.resetWork(TABLE)
                marker?.glowColorOverride = amber
                player.sendActionBar(Component.text("Стул готов. Он просохнет за несколько секунд."))
                log(active, "STAGE", active.progress.stage)
            }
            else -> Unit
        }
    }

    private fun step(active: Session, rules: OriginWorkshopGameRules) {
        if (active !== session) return
        val player = Bukkit.getPlayer(active.playerId)
        if (player == null || !player.isOnline) {
            cancel(active, "offline", false)
            return
        }
        val station = OriginWorkshopTablesModule.pointAt(TABLE, start)
        if (station == null || station.world.uid != active.worldId || player.world.uid != active.worldId || player.location.distanceSquared(station) > SESSION_RANGE_SQUARED) {
            cancel(active, "world-or-distance", true)
            return
        }
        if (player.isSneaking) {
            cancel(active, "sneak", true)
            return
        }
        val now = nowTick()
        if (now - active.began > rules.timeout) {
            cancel(active, "timeout", true)
            return
        }
        advance(active, now, rules)
        expireBeat(active, now, rules)
        when (active.progress.stage) {
            OriginWorkshopGameStage.CARRY -> carry(active, player)
            OriginWorkshopGameStage.CLAMP, OriginWorkshopGameStage.FINISH -> {
                cue(active, player, now)
                OriginWorkshopTablesModule.animateWork(TABLE, OriginWorkshopMechanism.FINISH, (now % rules.beatPeriod).toDouble() / rules.beatPeriod, 0.65)
            }
            OriginWorkshopGameStage.PAUSE -> OriginWorkshopTablesModule.animateWork(TABLE, OriginWorkshopMechanism.FINISH, 1.0, 0.0)
            OriginWorkshopGameStage.REWARDING -> reward(active, player)
            else -> Unit
        }
    }

    private fun advance(active: Session, now: Long, rules: OriginWorkshopGameRules) {
        val before = active.progress
        val after = originWorkshopAdvance(before, now, rules)
        if (before != after) {
            active.progress = after
            active.cueBeat = null
            marker?.teleport(OriginWorkshopTablesModule.pointAt(TABLE, target(after.stage)) ?: return)
            marker?.glowColorOverride = amber
            log(active, "STAGE", after.stage)
            if (after.stage == OriginWorkshopGameStage.FINISH) {
                Bukkit.getPlayer(active.playerId)?.sendActionBar(Component.text("Зажим отпущен. Жди сигнал для шлифовки."))
            }
        }
    }

    private fun expireBeat(active: Session, now: Long, rules: OriginWorkshopGameRules) {
        if (active.progress.stage != OriginWorkshopGameStage.CLAMP && active.progress.stage != OriginWorkshopGameStage.FINISH) return
        val beat = active.progress.nextBeat ?: return
        if (now <= beat + rules.windowWidth) return
        active.progress = active.progress.copy(nextBeat = now + rules.beatPeriod)
        active.cueBeat = null
        marker?.glowColorOverride = amber
    }

    private fun cue(active: Session, player: Player, now: Long) {
        val beat = active.progress.nextBeat ?: return
        if (now < beat || active.cueBeat == beat) return
        active.cueBeat = beat
        marker?.glowColorOverride = green
        player.sendActionBar(Component.text(if (active.progress.stage == OriginWorkshopGameStage.CLAMP) "Сигнал! Ударь по зажиму." else "Сигнал! Проведи щёткой."))
    }

    private fun reward(active: Session, player: Player) {
        if (active.rewardSent) return
        active.rewardSent = true
        val handler = rewards
        if (handler == null) {
            cancel(active, "reward-unavailable", true)
            return
        }
        val request = UUID.randomUUID()
        val generationAtRequest = generation
        val valid = {
            active === session && generationAtRequest == generation && active.progress.stage == OriginWorkshopGameStage.REWARDING &&
                player.isOnline && player.world.uid == active.worldId &&
                (OriginWorkshopTablesModule.pointAt(TABLE, start)?.takeIf { it.world.uid == active.worldId }
                    ?.let { player.location.distanceSquared(it) } ?: Double.POSITIVE_INFINITY) <= SESSION_RANGE_SQUARED
        }
        log(active, "REWARD_REQUEST", active.progress.stage)
        handler.complete(player, request, valid) { error ->
            if (active !== session || generationAtRequest != generation) return@complete
            if (error == null) {
                player.sendMessage(Component.text("  Стул собран и добавлен в инвентарь.\n\n  Новую сборку можно начать через 24 часа."))
                log(active, "REWARD_DELIVERED", active.progress.stage)
            } else {
                player.sendMessage(Component.text("  " + error + "\n\n  Если стул не появился, сообщи администрации."))
                log(active, "REWARD_FAILED", active.progress.stage)
            }
            finishSession(active)
        }
    }

    private fun carry(active: Session, player: Player) {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val obstruction = player.world.rayTraceBlocks(eye, direction, 1.25, FluidCollisionMode.NEVER, true)
        val distance = obstruction?.let { (it.hitPosition.toLocation(player.world).distance(eye) - 0.25).coerceAtLeast(0.35) } ?: 1.25
        active.part?.teleport(eye.clone().add(direction.multiply(distance)))
    }

    private fun target(stage: OriginWorkshopGameStage) = when (stage) {
        OriginWorkshopGameStage.PICK -> pick
        OriginWorkshopGameStage.CARRY -> socket
        OriginWorkshopGameStage.CLAMP -> clamp
        OriginWorkshopGameStage.PAUSE, OriginWorkshopGameStage.FINISH, OriginWorkshopGameStage.DISPLAY,
        OriginWorkshopGameStage.REWARDING -> finish
        OriginWorkshopGameStage.CLOSED -> start
    }

    private fun targetDistance(player: Player, target: Location): Double? {
        if (player.world.uid != target.world?.uid) return null
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val reach = originWorkshopRayHit(
            OriginWorkshopVec3(eye.x, eye.y, eye.z),
            OriginWorkshopVec3(direction.x, direction.y, direction.z),
            OriginWorkshopVec3(target.x, target.y, target.z),
            TARGET_RADIUS,
            REACH,
        ) ?: return null
        if (player.world.rayTraceBlocks(eye, direction, reach + BLOCK_EPSILON, FluidCollisionMode.NEVER, true) != null) return null
        return reach
    }

    private fun spawn(displays: PaperPacketDisplays, material: Material, at: Location) =
        displays.spawnItem(at, ItemStack(material)).apply {
            isVisibleByDefault = false
            itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            billboard = Display.Billboard.FIXED
            displayWidth = 0.5f
            displayHeight = 0.5f
            viewRange = 0.8f
            shadowRadius = 0f
            interpolationDelay = -1
            interpolationDuration = 2
            teleportDuration = 2
            transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(0.38f), AxisAngle4f())
        }

    private fun finishSession(active: Session) {
        if (active !== session) return
        session = null
        active.tasks.close()
        active.part?.remove()
        OriginWorkshopTablesModule.resetWork(TABLE)
        showStart()
    }

    private fun cancel(active: Session, reason: String, notify: Boolean) {
        if (active !== session) return
        val player = Bukkit.getPlayer(active.playerId)
        log(active, "CANCEL", active.progress.stage, reason)
        session = null
        active.progress = active.progress.copy(stage = OriginWorkshopGameStage.CLOSED)
        active.tasks.close()
        active.part?.remove()
        OriginWorkshopTablesModule.resetWork(TABLE)
        showStart()
        if (notify && player?.isOnline == true) player.sendActionBar(Component.text("Сборка прервана. Материалы остались при тебе."))
    }

    private fun showStart() {
        val at = OriginWorkshopTablesModule.pointAt(TABLE, start) ?: return
        // A fresh public marker drops the previous player's explicit visibility grant.
        marker?.remove()
        marker = owner?.let { spawn(it, Material.LIME_STAINED_GLASS_PANE, at).apply {
            isVisibleByDefault = true
            isGlowing = true
            glowColorOverride = amber
        }
        }
        label?.isVisibleByDefault = true
    }

    private fun feedback(player: Player, active: Session, text: String) {
        val now = System.nanoTime()
        if (now - active.lastFeedback < FEEDBACK_NANOS) return
        active.lastFeedback = now
        player.sendActionBar(Component.text(text))
    }

    private fun stop(reason: String) {
        generation++
        session?.let { cancel(it, reason, false) }
        offers.clear()
        pendingStatus.clear()
        accepted.clear()
        commonTasks.close()
        commonTasks = LifecycleTaskScope()
        rewards?.close()
        rewards = null
        marker?.remove()
        marker = null
        label?.remove()
        label = null
        owner?.close()
        owner = null
        OriginWorkshopTablesModule.resetWork(TABLE)
    }

    private fun log(active: Session, phase: String, stage: OriginWorkshopGameStage, detail: String = "") {
        ARC.instance.logger.info(
            "ORIGIN_WORKSHOP_GAME phase=" + phase + " player=" + active.playerId + " session=" + active.id +
                " stage=" + stage + if (detail.isEmpty()) "" else " detail=" + detail,
        )
    }

    private fun nowTick() = System.nanoTime() / TICK_NANOS
}
