package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import io.papermc.paper.event.player.PlayerArmSwingEvent
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
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
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.logging.Level
import kotlin.math.floor
import kotlin.math.atan2
import kotlin.math.sqrt

internal enum class OriginWorkshopGameStage(val step: Int, val instruction: String) {
    STOCK(1, "Возьми заготовку со склада."),
    CARRY_RAW_TO_SAW(2, "Перетащи заготовку к пиле."),
    START_SAW(3, "Нажми рукоять пилы."),
    SAWING(3, "Пила распускает заготовку."),
    PICK_SAWN_BOARD(4, "Забери распиленную доску."),
    CARRY_BOARD_TO_DRILL(5, "Положи доску под сверло."),
    START_DRILL(6, "Нажми рукоять сверла."),
    DRILLING(6, "Сверло делает отверстия."),
    PICK_DRILLED_BOARD(7, "Забери просверленную доску."),
    CARRY_BOARD_TO_JIG(8, "Положи доску в сборочный шаблон."),
    LEG_LEFT(9, "Возьми левую ножку."),
    CARRY_LEG_LEFT(10, "Перетащи левую ножку в шаблон."),
    LEG_RIGHT(11, "Возьми правую ножку."),
    CARRY_LEG_RIGHT(12, "Перетащи правую ножку в шаблон."),
    CLAMP_LEFT(13, "Затяни левый зажим."),
    CLAMPING_LEFT(13, "Левый зажим стягивает детали."),
    CLAMP_RIGHT(14, "Затяни правый зажим."),
    CLAMPING_RIGHT(14, "Правый зажим стягивает детали."),
    FINISHING(15, "Готовый стул просыхает."),
    REWARDING(15, "Выдаю готовый стул."),
    CLOSED(15, "Сборка завершена.");

    companion object {
        const val TOTAL_STEPS = 15
    }
}

internal enum class OriginWorkshopGameAction {
    PICK_STOCK,
    PLACE_SAW,
    ACTIVATE_SAW,
    PICK_SAWN_BOARD,
    PLACE_DRILL,
    ACTIVATE_DRILL,
    PICK_DRILLED_BOARD,
    PLACE_JIG,
    PICK_LEFT_LEG,
    PLACE_LEFT_LEG,
    PICK_RIGHT_LEG,
    PLACE_RIGHT_LEG,
    TIGHTEN_LEFT,
    TIGHTEN_RIGHT,
}

internal data class OriginWorkshopGameRules(
    val sawTicks: Long = 60,
    val drillTicks: Long = 40,
    val clampTicks: Long = 20,
    val chairHoldTicks: Long = 60,
    val timeout: Long = 3_600,
) {
    init {
        require(sawTicks > 0 && drillTicks > 0 && clampTicks > 0 && chairHoldTicks > 0)
        require(timeout in 20L..12_000L)
    }
}

internal data class OriginWorkshopGameProgress(
    val stage: OriginWorkshopGameStage,
    val stageStartedAt: Long = 0,
)

internal data class OriginWorkshopGamePartSize(val x: Float, val y: Float, val z: Float) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite() && x > 0f && y > 0f && z > 0f)
    }
}

internal fun originWorkshopTransition(
    progress: OriginWorkshopGameProgress,
    action: OriginWorkshopGameAction,
    now: Long,
): OriginWorkshopGameProgress? {
    val next = when {
        progress.stage == OriginWorkshopGameStage.STOCK && action == OriginWorkshopGameAction.PICK_STOCK ->
            OriginWorkshopGameStage.CARRY_RAW_TO_SAW
        progress.stage == OriginWorkshopGameStage.CARRY_RAW_TO_SAW && action == OriginWorkshopGameAction.PLACE_SAW ->
            OriginWorkshopGameStage.START_SAW
        progress.stage == OriginWorkshopGameStage.START_SAW && action == OriginWorkshopGameAction.ACTIVATE_SAW ->
            OriginWorkshopGameStage.SAWING
        progress.stage == OriginWorkshopGameStage.PICK_SAWN_BOARD && action == OriginWorkshopGameAction.PICK_SAWN_BOARD ->
            OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL
        progress.stage == OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL && action == OriginWorkshopGameAction.PLACE_DRILL ->
            OriginWorkshopGameStage.START_DRILL
        progress.stage == OriginWorkshopGameStage.START_DRILL && action == OriginWorkshopGameAction.ACTIVATE_DRILL ->
            OriginWorkshopGameStage.DRILLING
        progress.stage == OriginWorkshopGameStage.PICK_DRILLED_BOARD && action == OriginWorkshopGameAction.PICK_DRILLED_BOARD ->
            OriginWorkshopGameStage.CARRY_BOARD_TO_JIG
        progress.stage == OriginWorkshopGameStage.CARRY_BOARD_TO_JIG && action == OriginWorkshopGameAction.PLACE_JIG ->
            OriginWorkshopGameStage.LEG_LEFT
        progress.stage == OriginWorkshopGameStage.LEG_LEFT && action == OriginWorkshopGameAction.PICK_LEFT_LEG ->
            OriginWorkshopGameStage.CARRY_LEG_LEFT
        progress.stage == OriginWorkshopGameStage.CARRY_LEG_LEFT && action == OriginWorkshopGameAction.PLACE_LEFT_LEG ->
            OriginWorkshopGameStage.LEG_RIGHT
        progress.stage == OriginWorkshopGameStage.LEG_RIGHT && action == OriginWorkshopGameAction.PICK_RIGHT_LEG ->
            OriginWorkshopGameStage.CARRY_LEG_RIGHT
        progress.stage == OriginWorkshopGameStage.CARRY_LEG_RIGHT && action == OriginWorkshopGameAction.PLACE_RIGHT_LEG ->
            OriginWorkshopGameStage.CLAMP_LEFT
        progress.stage == OriginWorkshopGameStage.CLAMP_LEFT && action == OriginWorkshopGameAction.TIGHTEN_LEFT ->
            OriginWorkshopGameStage.CLAMPING_LEFT
        progress.stage == OriginWorkshopGameStage.CLAMP_RIGHT && action == OriginWorkshopGameAction.TIGHTEN_RIGHT ->
            OriginWorkshopGameStage.CLAMPING_RIGHT
        else -> return null
    }
    return OriginWorkshopGameProgress(next, now)
}

internal fun originWorkshopAdvance(
    progress: OriginWorkshopGameProgress,
    now: Long,
    rules: OriginWorkshopGameRules,
): OriginWorkshopGameProgress {
    val (duration, next) = when (progress.stage) {
        OriginWorkshopGameStage.SAWING -> rules.sawTicks to OriginWorkshopGameStage.PICK_SAWN_BOARD
        OriginWorkshopGameStage.DRILLING -> rules.drillTicks to OriginWorkshopGameStage.PICK_DRILLED_BOARD
        OriginWorkshopGameStage.CLAMPING_LEFT -> rules.clampTicks to OriginWorkshopGameStage.CLAMP_RIGHT
        OriginWorkshopGameStage.CLAMPING_RIGHT -> rules.clampTicks to OriginWorkshopGameStage.FINISHING
        OriginWorkshopGameStage.FINISHING -> rules.chairHoldTicks to OriginWorkshopGameStage.REWARDING
        else -> return progress
    }
    return if (now - progress.stageStartedAt >= duration) OriginWorkshopGameProgress(next, now) else progress
}

internal enum class OriginWorkshopCancelReason { OFFLINE, WORLD_CHANGED, TOO_FAR, SNEAKING, TIMEOUT }

internal fun originWorkshopCancelReason(
    online: Boolean,
    sameWorld: Boolean,
    sneaking: Boolean,
    distanceSquared: Double,
    elapsedTicks: Long,
    timeoutTicks: Long,
    maxDistanceSquared: Double,
): OriginWorkshopCancelReason? = when {
    !online -> OriginWorkshopCancelReason.OFFLINE
    !sameWorld -> OriginWorkshopCancelReason.WORLD_CHANGED
    !distanceSquared.isFinite() || distanceSquared > maxDistanceSquared ->
        OriginWorkshopCancelReason.TOO_FAR
    sneaking -> OriginWorkshopCancelReason.SNEAKING
    elapsedTicks >= timeoutTicks -> OriginWorkshopCancelReason.TIMEOUT
    else -> null
}

internal fun originWorkshopIsDuplicateClick(previousNanos: Long?, nowNanos: Long, windowNanos: Long): Boolean =
    previousNanos != null && nowNanos - previousNanos in 0L..windowNanos

internal data class OriginWorkshopVec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(v: OriginWorkshopVec3) = OriginWorkshopVec3(x - v.x, y - v.y, z - v.z)
    operator fun times(k: Double) = OriginWorkshopVec3(x * k, y * k, z * k)
    fun dot(v: OriginWorkshopVec3) = x * v.x + y * v.y + z * v.z
}

internal data class OriginWorkshopAabb(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    init {
        require(listOf(minX, minY, minZ, maxX, maxY, maxZ).all(Double::isFinite))
        require(minX <= maxX && minY <= maxY && minZ <= maxZ)
    }
}

/** Local-space click volume over the actual tabletop and its front edge. */
internal fun originWorkshopStartInteractionAabb(dimensions: OriginWorkshopTableDimensions): OriginWorkshopAabb =
    OriginWorkshopAabb(
        minX = -dimensions.width / 2.0,
        minY = dimensions.height - 0.15,
        minZ = -dimensions.depth / 2.0,
        maxX = dimensions.width / 2.0,
        maxY = dimensions.height + 0.55,
        maxZ = dimensions.depth / 2.0,
    )

/** Ray/AABB distance in blocks, normalized to the ray direction for focused interaction tests. */
internal fun originWorkshopRayAabbHit(
    origin: OriginWorkshopVec3,
    direction: OriginWorkshopVec3,
    bounds: OriginWorkshopAabb,
    reach: Double,
): Double? {
    if (!listOf(origin.x, origin.y, origin.z, direction.x, direction.y, direction.z, reach).all(Double::isFinite)) return null
    if (reach !in 0.0..4.5) return null
    if (origin.x in bounds.minX..bounds.maxX && origin.y in bounds.minY..bounds.maxY &&
        origin.z in bounds.minZ..bounds.maxZ) return null
    val length = sqrt(direction.dot(direction))
    if (length < 1e-6) return null
    val ray = direction * (1.0 / length)
    var near = 0.0
    var far = reach

    fun clip(originAxis: Double, directionAxis: Double, minimum: Double, maximum: Double): Boolean {
        if (kotlin.math.abs(directionAxis) < 1e-9) return originAxis in minimum..maximum
        val first = (minimum - originAxis) / directionAxis
        val second = (maximum - originAxis) / directionAxis
        near = maxOf(near, minOf(first, second))
        far = minOf(far, maxOf(first, second))
        return near <= far
    }

    if (!clip(origin.x, ray.x, bounds.minX, bounds.maxX) ||
        !clip(origin.y, ray.y, bounds.minY, bounds.maxY) ||
        !clip(origin.z, ray.z, bounds.minZ, bounds.maxZ)
    ) return null
    return near.takeIf { it in 0.0..reach }
}

internal fun originWorkshopRayHit(
    origin: OriginWorkshopVec3,
    direction: OriginWorkshopVec3,
    center: OriginWorkshopVec3,
    radius: Double,
    reach: Double,
): Double? {
    if (!listOf(
            origin.x, origin.y, origin.z,
            direction.x, direction.y, direction.z,
            center.x, center.y, center.z, radius, reach,
        ).all(Double::isFinite)
    ) return null
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
            val timeout = config.integer("origin-workshop-game.session-timeout-seconds", 180).toLong() * 20
            return GameSettings(
                config.bool("origin-workshop-game.enabled", false),
                OriginWorkshopGameRules(timeout = timeout),
            )
        }
    }
}

/** One occupied, packet-only carpenter station with explicit material handoffs. */
internal object OriginWorkshopGame : PluginModule, Listener {
    override val name = "OriginWorkshopGame"
    override val priority = 25

    private const val TABLE = "carpenter"
    private const val REACH = 4.5
    private const val TARGET_RADIUS = 0.36
    private const val BLOCK_EPSILON = 0.06
    private const val TICK_NANOS = 50_000_000L
    private const val STEP_TICKS = 2L
    private const val SESSION_RANGE_SQUARED = 144.0
    private const val PRODUCT = "furnituresplus:white_wooden_chair"
    private const val COOLDOWN = 86_400_000L
    private const val INPUT_DEDUPE_NANOS = 120_000_000L
    private const val FEEDBACK_NANOS = 500_000_000L
    private const val HOVER_SOUND_NANOS = 700_000_000L
    private const val HOVER_CHECK_TICKS = 2L
    private const val GUIDANCE_REFRESH_TICKS = 20L
    private const val BUSY_FEEDBACK_NANOS = 1_000_000_000L
    private const val MAX_RECENT_INPUTS = 64

    private val RAW_BOARD_SIZE = OriginWorkshopGamePartSize(1.0f, 0.08f, 0.22f)
    private val CUT_BOARD_SIZE = OriginWorkshopGamePartSize(0.72f, 0.08f, 0.22f)
    private val LEG_SIZE = OriginWorkshopGamePartSize(0.14f, 0.52f, 0.14f)

    private val start = OriginWorkshopPoint(0.0, 1.28, -1.35)
    private val stock = OriginWorkshopPoint(-7.5, 0.44, 0.30)
    private val sawInput = OriginWorkshopPoint(-1.35, 1.29, -0.55)
    private val sawInputCenter = OriginWorkshopPoint(-1.35, 1.25, -0.55)
    private val sawOutput = OriginWorkshopPoint(-0.35, 1.29, -0.55)
    private val sawOutputCenter = OriginWorkshopPoint(-0.35, 1.25, -0.55)
    private val sawControl = OriginWorkshopPoint(-1.65, 1.42, -0.70)
    private val drillInput = OriginWorkshopPoint(0.0, 1.24, -0.55)
    private val drillInputCenter = OriginWorkshopPoint(0.0, 1.12, -0.55)
    private val drillControl = OriginWorkshopPoint(0.30, 1.66, -0.42)
    private val assembly = OriginWorkshopPoint(1.35, 1.22, -0.35)
    private val legLeftMount = OriginWorkshopPoint(1.13, 1.24, -0.35)
    private val legRightMount = OriginWorkshopPoint(1.57, 1.24, -0.35)
    private val clampLeft = OriginWorkshopPoint(0.95, 1.25, -0.65)
    private val clampRight = OriginWorkshopPoint(1.95, 1.25, -0.65)
    private val legLeft = OriginWorkshopPoint(1.3, 1.22, 0.5)
    private val legRight = OriginWorkshopPoint(1.75, 1.22, 0.5)
    private val woodGlow = Color.fromRGB(191, 139, 82)
    private val hoverGlow = Color.fromRGB(255, 210, 99)

    private data class Session(
        val id: UUID,
        val playerId: UUID,
        val worldId: UUID,
        val began: Long,
        var progress: OriginWorkshopGameProgress,
        val parts: MutableList<PacketDisplay> = mutableListOf(),
        var workpiece: PacketBlockDisplay? = null,
        var boardSize: OriginWorkshopGamePartSize = RAW_BOARD_SIZE,
        var carried: PacketDisplay? = null,
        var carriedSize: OriginWorkshopGamePartSize? = null,
        var leftLeg: PacketBlockDisplay? = null,
        var rightLeg: PacketBlockDisplay? = null,
        var lastCarriedPosition: Location? = null,
        var highlightedControl: String? = null,
        var hoveredControl: Boolean = false,
        var lastGuideTick: Long = Long.MIN_VALUE,
        var lastFeedback: Long = 0,
        var lastHoverSound: Long = 0,
        var rewardSent: Boolean = false,
        val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    )

    private var settings: GameSettings? = null
    private var owner: PaperPacketDisplays? = null
    private var label: PacketTextDisplay? = null
    private var occupancyLabel: PacketTextDisplay? = null
    private var rewards: OriginWorkshopCraftRewards? = null
    private var commonTasks = LifecycleTaskScope()
    private var session: Session? = null
    private val pendingStatus = mutableSetOf<UUID>()
    private val accepted = linkedMapOf<UUID, Long>()
    private val idleHoverers = mutableSetOf<UUID>()
    private val hoverSounds = linkedMapOf<UUID, Long>()
    private val busyFeedbackNanos = linkedMapOf<UUID, Long>()
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
        val loaded = try {
            GameSettings.load()
        } catch (failure: Exception) {
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
        val displayOwner = try {
            PaperPacketDisplays(ARC.instance)
        } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop game display owner unavailable", failure)
            return
        }
        owner = displayOwner
        rewards = try {
            OriginWorkshopCraftRewards(COOLDOWN, PRODUCT)
        } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop game reward handler unavailable", failure)
            displayOwner.close()
            owner = null
            return
        }
        OriginWorkshopTablesModule.highlightCraftControl(TABLE, "start")
        val labelLocation = OriginWorkshopTablesModule.pointAt(TABLE, frontGuidanceAnchor(OriginWorkshopPoint(0.0, 0.0, 0.0)))
        label = labelLocation?.let { spawnLabel(it, "Столярная мастерская · ЛКМ", visibleByDefault = true) }
        idleHoverers.clear()
        commonTasks.runTimer(HOVER_CHECK_TICKS, HOVER_CHECK_TICKS) { updateIdleHover() }
        ARC.instance.logger.info("ORIGIN_WORKSHOP_GAME phase=READY player=none session=none stage=STOCK")
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
        pendingStatus.remove(event.player.uniqueId)
        accepted.remove(event.player.uniqueId)
        idleHoverers.remove(event.player.uniqueId)
        hoverSounds.remove(event.player.uniqueId)
        busyFeedbackNanos.remove(event.player.uniqueId)
    }

    private fun click(player: Player): Boolean {
        if (owner == null || settings?.enabled != true) return false
        val nowNanos = System.nanoTime()
        accepted.entries.removeIf { nowNanos - it.value > INPUT_DEDUPE_NANOS }
        if (originWorkshopIsDuplicateClick(accepted[player.uniqueId], nowNanos, INPUT_DEDUPE_NANOS)) return true
        accepted.remove(player.uniqueId)
        if (accepted.size >= MAX_RECENT_INPUTS) accepted.entries.firstOrNull()?.let { accepted.remove(it.key) }

        val current = session
        if (current != null && current.playerId != player.uniqueId) {
            if (!isWorkshopInteraction(player)) return false
            sendBusyFeedback(player, current.playerId, nowNanos)
            return true
        }
        if (current != null) {
            val station = OriginWorkshopTablesModule.pointAt(TABLE, start)
            val reason = originWorkshopCancelReason(
                online = player.isOnline,
                sameWorld = station != null && station.world.uid == current.worldId && player.world.uid == current.worldId,
                sneaking = player.isSneaking,
                distanceSquared = if (station != null && station.world.uid == player.world.uid) {
                    player.location.distanceSquared(station)
                } else Double.POSITIVE_INFINITY,
                elapsedTicks = nowTick() - current.began,
                timeoutTicks = settings?.rules?.timeout ?: 3_600,
                maxDistanceSquared = SESSION_RANGE_SQUARED,
            )
            if (reason != null) {
                cancel(current, reason.name.lowercase(), true)
                return true
            }
            advanceTimedStage(current, player, nowTick())
        }
        if (current == null) {
            if (startInteractionDistance(player) == null) return false
        } else {
            val location = OriginWorkshopTablesModule.pointAt(TABLE, target(current.progress.stage)) ?: return false
            if (targetDistance(player, location) == null) return false
        }

        accepted[player.uniqueId] = nowNanos
        if (current == null) startClick(player) else sessionClick(player, current)
        return true
    }

    private fun startClick(player: Player) {
        val handler = rewards ?: return
        if (!pendingStatus.add(player.uniqueId)) return
        if (pendingStatus.size > 32) {
            pendingStatus.remove(player.uniqueId)
            player.sendActionBar(Component.text("Сейчас проверяется много заявок. Попробуй ещё раз через минуту."))
            return
        }
        player.sendActionBar(Component.text("Проверяю 24-часовой перерыв."))
        val token = commonTasks.token()
        val generationAtQuery = generation
        handler.status(player.uniqueId).whenComplete { remaining, failure ->
            commonTasks.runSync(token) {
                pendingStatus.remove(player.uniqueId)
                if (generationAtQuery != generation || !player.isOnline || owner == null) return@runSync
                val startLocation = OriginWorkshopTablesModule.pointAt(TABLE, start)
                if (startLocation == null || player.world.uid != startLocation.world.uid ||
                    player.location.distanceSquared(startLocation) > SESSION_RANGE_SQUARED
                ) return@runSync
                if (failure != null) {
                    ARC.instance.logger.log(
                        Level.WARNING,
                        "ORIGIN_WORKSHOP_GAME phase=STATUS_ERROR player=" + player.uniqueId + " session=none stage=STOCK",
                        failure,
                    )
                    player.sendActionBar(Component.text("Не удалось проверить перерыв. Попробуй позже."))
                } else if ((remaining ?: Long.MAX_VALUE) > 0) {
                    val minutes = kotlin.math.ceil((remaining ?: 0L) / 60_000.0).toLong().coerceAtLeast(1)
                    player.sendActionBar(Component.text("Недавно уже собирали мебель. Подожди ещё " + minutes + " мин."))
                } else {
                    val missing = handler.missing(player)
                    if (missing != null) {
                        player.sendActionBar(Component.text(missing))
                        return@runSync
                    }
                    if (session != null) {
                        sendBusyFeedback(player, session!!.playerId)
                        return@runSync
                    }
                    begin(player)
                }
            }
        }
    }

    private fun begin(player: Player) {
        val rules = settings?.rules ?: return
        val startLocation = OriginWorkshopTablesModule.pointAt(TABLE, start) ?: return
        if (player.world.uid != startLocation.world.uid || player.location.distanceSquared(startLocation) > SESSION_RANGE_SQUARED) return

        val active = Session(
            UUID.randomUUID(), player.uniqueId, player.world.uid, nowTick(),
            OriginWorkshopGameProgress(OriginWorkshopGameStage.STOCK, nowTick()),
        )
        session = active
        idleHoverers.clear()
        label?.remove()
        label = OriginWorkshopTablesModule.pointAt(TABLE, labelAnchor(OriginWorkshopGameStage.STOCK))?.let {
            spawnLabel(it, "Этап 1/15 · Возьми заготовку со склада · ЛКМ", visibleByDefault = false)
        }
        label?.showTo(player)
        setOccupancyLabel(player)
        active.tasks.runTimer(STEP_TICKS, STEP_TICKS) { step(active, rules) }
        showStage(active, player, "START")
    }

    private fun sessionClick(player: Player, active: Session) {
        val action = action(active.progress.stage) ?: run {
            feedback(player, active, "Подожди, станок ещё работает.")
            return
        }
        val now = nowTick()
        val next = originWorkshopTransition(active.progress, action, now) ?: return
        if (!applyAction(player, active, action)) return
        active.progress = next
        showStage(active, player, "STAGE")
        animateMachine(active, now, settings?.rules ?: return)
        player.playSound(
            player.location,
            if (action == OriginWorkshopGameAction.PICK_STOCK ||
                action == OriginWorkshopGameAction.PICK_SAWN_BOARD ||
                action == OriginWorkshopGameAction.PICK_DRILLED_BOARD ||
                action == OriginWorkshopGameAction.PICK_LEFT_LEG ||
                action == OriginWorkshopGameAction.PICK_RIGHT_LEG
            ) Sound.ENTITY_ITEM_PICKUP else Sound.BLOCK_WOOD_PLACE,
            SoundCategory.PLAYERS,
            0.35f,
            1.0f,
        )
    }

    private fun applyAction(player: Player, active: Session, action: OriginWorkshopGameAction): Boolean {
        when (action) {
            OriginWorkshopGameAction.PICK_STOCK -> {
                val part = newCarriedBlock(active, player, Material.OAK_PLANKS, RAW_BOARD_SIZE) ?: return false
                active.boardSize = RAW_BOARD_SIZE
                active.carried = part
                active.carriedSize = RAW_BOARD_SIZE
            }
            OriginWorkshopGameAction.PLACE_SAW,
            OriginWorkshopGameAction.PLACE_DRILL,
            OriginWorkshopGameAction.PLACE_JIG -> {
                val point = when (action) {
                    OriginWorkshopGameAction.PLACE_SAW -> sawInputCenter
                    OriginWorkshopGameAction.PLACE_DRILL -> drillInputCenter
                    else -> assembly
                }
                val item = active.carried as? PacketBlockDisplay ?: return false
                val size = active.carriedSize ?: return false
                settle(active, item, point, size) ?: return false
                active.workpiece = item
                if (action == OriginWorkshopGameAction.PLACE_SAW) active.boardSize = RAW_BOARD_SIZE
                if (action == OriginWorkshopGameAction.PLACE_DRILL) active.boardSize = CUT_BOARD_SIZE
                active.carried = null
                active.carriedSize = null
            }
            OriginWorkshopGameAction.ACTIVATE_SAW,
            OriginWorkshopGameAction.ACTIVATE_DRILL,
            OriginWorkshopGameAction.TIGHTEN_LEFT,
            OriginWorkshopGameAction.TIGHTEN_RIGHT -> Unit
            OriginWorkshopGameAction.PICK_SAWN_BOARD,
            OriginWorkshopGameAction.PICK_DRILLED_BOARD -> {
                val item = active.workpiece ?: return false
                carryExisting(active, player, item, active.boardSize)
                active.workpiece = null
                active.carried = item
                active.carriedSize = active.boardSize
            }
            OriginWorkshopGameAction.PICK_LEFT_LEG -> {
                val item = newCarriedBlock(active, player, Material.STRIPPED_SPRUCE_LOG, LEG_SIZE) ?: return false
                active.carried = item
                active.carriedSize = LEG_SIZE
                OriginWorkshopTablesModule.setCraftPartVisible(TABLE, "leg-left", false)
            }
            OriginWorkshopGameAction.PLACE_LEFT_LEG,
            OriginWorkshopGameAction.PLACE_RIGHT_LEG -> {
                val item = active.carried as? PacketBlockDisplay ?: return false
                val size = active.carriedSize ?: return false
                val point = if (action == OriginWorkshopGameAction.PLACE_LEFT_LEG) legLeftMount else legRightMount
                settle(active, item, point, size) ?: return false
                if (action == OriginWorkshopGameAction.PLACE_LEFT_LEG) active.leftLeg = item else active.rightLeg = item
                active.carried = null
                active.carriedSize = null
            }
            OriginWorkshopGameAction.PICK_RIGHT_LEG -> {
                val item = newCarriedBlock(active, player, Material.STRIPPED_SPRUCE_LOG, LEG_SIZE) ?: return false
                active.carried = item
                active.carriedSize = LEG_SIZE
                OriginWorkshopTablesModule.setCraftPartVisible(TABLE, "leg-right", false)
            }
        }
        return true
    }

    private fun step(active: Session, rules: OriginWorkshopGameRules) {
        if (active !== session) return
        val player = Bukkit.getPlayer(active.playerId)
        val station = OriginWorkshopTablesModule.pointAt(TABLE, start)
        val reason = originWorkshopCancelReason(
            online = player?.isOnline == true,
            sameWorld = station != null && station.world.uid == active.worldId && player?.world?.uid == active.worldId,
            sneaking = player?.isSneaking == true,
            distanceSquared = if (player != null && station != null && player.world.uid == station.world.uid) {
                player.location.distanceSquared(station)
            } else Double.POSITIVE_INFINITY,
            elapsedTicks = nowTick() - active.began,
            timeoutTicks = rules.timeout,
            maxDistanceSquared = SESSION_RANGE_SQUARED,
        )
        if (reason != null) {
            cancel(active, reason.name.lowercase(), reason != OriginWorkshopCancelReason.OFFLINE)
            return
        }
        val onlinePlayer = player ?: return
        advanceTimedStage(active, onlinePlayer, nowTick())
        animateMachine(active, nowTick(), rules)
        updateActiveHover(active, onlinePlayer)
        if (active.progress.stage == OriginWorkshopGameStage.CARRY_RAW_TO_SAW ||
            active.progress.stage == OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL ||
            active.progress.stage == OriginWorkshopGameStage.CARRY_BOARD_TO_JIG ||
            active.progress.stage == OriginWorkshopGameStage.CARRY_LEG_LEFT ||
            active.progress.stage == OriginWorkshopGameStage.CARRY_LEG_RIGHT
        ) carry(active, onlinePlayer)
        refreshGuidance(active, onlinePlayer, nowTick())
        if (active.progress.stage == OriginWorkshopGameStage.REWARDING) reward(active, onlinePlayer)
    }

    private fun updateIdleHover() {
        if (session != null || owner == null) return
        val station = OriginWorkshopTablesModule.pointAt(TABLE, start) ?: return
        val now = System.nanoTime()
        val next = Bukkit.getOnlinePlayers().asSequence()
            .filter { it.world.uid == station.world.uid && it.location.distanceSquared(station) <= 100.0 }
            .filter { startInteractionDistance(it) != null }
            .mapTo(linkedSetOf()) { it.uniqueId }
        next.forEach { id ->
            if (id !in idleHoverers) {
                val prior = hoverSounds[id]
                if (prior == null || now - prior >= HOVER_SOUND_NANOS) {
                    Bukkit.getPlayer(id)?.let { player ->
                        player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.20f, 1.45f)
                        hoverSounds[id] = now
                    }
                }
            }
        }
        if (next != idleHoverers) {
            idleHoverers.clear()
            idleHoverers.addAll(next)
            OriginWorkshopTablesModule.highlightCraftControl(TABLE, "start", hovered = next.isNotEmpty())
        }
        hoverSounds.entries.removeIf { Bukkit.getPlayer(it.key) == null }
    }

    private fun updateActiveHover(active: Session, player: Player) {
        val targetLocation = OriginWorkshopTablesModule.pointAt(TABLE, target(active.progress.stage))
        val hovered = targetLocation != null && targetDistance(player, targetLocation) != null
        if (active.hoveredControl == hovered) return
        active.hoveredControl = hovered
        OriginWorkshopTablesModule.highlightCraftControl(TABLE, active.highlightedControl, hovered)
        currentPickableDisplay(active)?.let { part ->
            part.isGlowing = true
            part.glowColorOverride = if (hovered) hoverGlow else woodGlow
        }
        if (hovered) {
            val now = System.nanoTime()
            if (now - active.lastHoverSound >= HOVER_SOUND_NANOS) {
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.20f, 1.45f)
                active.lastHoverSound = now
            }
        }
    }

    private fun advanceTimedStage(active: Session, player: Player, now: Long) {
        val before = active.progress
        val after = originWorkshopAdvance(before, now, settings?.rules ?: return)
        if (before == after) return
        when (before.stage) {
            OriginWorkshopGameStage.SAWING -> {
                active.boardSize = CUT_BOARD_SIZE
                active.workpiece?.let { part ->
                    part.transformation = cuboidTransform(active.boardSize)
                    OriginWorkshopTablesModule.pointAt(TABLE, sawOutputCenter)?.let { output ->
                        moveBlockCenter(part, output, active.boardSize)
                        active.lastCarriedPosition = output.clone()
                    }
                }
            }
            OriginWorkshopGameStage.DRILLING -> Unit
            OriginWorkshopGameStage.CLAMPING_LEFT ->
                OriginWorkshopTablesModule.animateCraftMachine(TABLE, "clamp-left", 1.0)
            OriginWorkshopGameStage.CLAMPING_RIGHT -> {
                OriginWorkshopTablesModule.animateCraftMachine(TABLE, "clamp-right", 1.0)
                finishChair(active)
            }
            OriginWorkshopGameStage.FINISHING -> Unit
            else -> Unit
        }
        active.progress = after
        showStage(active, player, "STAGE")
    }

    private fun finishChair(active: Session) {
        val chair = CustomStack.getInstance(PRODUCT)?.itemStack?.clone()
        if (chair == null) {
            ARC.instance.logger.warning("ORIGIN_WORKSHOP_GAME phase=RESULT_MISSING player=" + active.playerId + " session=" + active.id)
        } else {
            val player = Bukkit.getPlayer(active.playerId)
            active.workpiece?.remove()
            active.workpiece = null
            val at = OriginWorkshopTablesModule.pointAt(TABLE, OriginWorkshopPoint(1.35, 1.405, -0.35))
            if (at != null && player != null) {
                val display = runCatching { owner?.spawnItem(at, chair) }.onFailure {
                    ARC.instance.logger.log(Level.WARNING, "Origin workshop chair display unavailable player=" + active.playerId, it)
                }.getOrNull()
                if (display != null) {
                    display.isVisibleByDefault = false
                    display.showTo(player)
                    display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                    display.billboard = Display.Billboard.FIXED
                    display.viewRange = 0.8f
                    display.displayWidth = 1.0f
                    display.displayHeight = 1.0f
                    display.shadowRadius = 0f
                    display.interpolationDelay = -1
                    display.interpolationDuration = 2
                    display.teleportDuration = 2
                    display.transformation = Transformation(
                        Vector3f(), AxisAngle4f(), Vector3f(0.65f), AxisAngle4f(),
                    )
                    active.parts += display
                }
            }
        }
        active.leftLeg?.remove()
        active.leftLeg = null
        active.rightLeg?.remove()
        active.rightLeg = null
    }

    private fun action(stage: OriginWorkshopGameStage): OriginWorkshopGameAction? = when (stage) {
        OriginWorkshopGameStage.STOCK -> OriginWorkshopGameAction.PICK_STOCK
        OriginWorkshopGameStage.CARRY_RAW_TO_SAW -> OriginWorkshopGameAction.PLACE_SAW
        OriginWorkshopGameStage.START_SAW -> OriginWorkshopGameAction.ACTIVATE_SAW
        OriginWorkshopGameStage.PICK_SAWN_BOARD -> OriginWorkshopGameAction.PICK_SAWN_BOARD
        OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL -> OriginWorkshopGameAction.PLACE_DRILL
        OriginWorkshopGameStage.START_DRILL -> OriginWorkshopGameAction.ACTIVATE_DRILL
        OriginWorkshopGameStage.PICK_DRILLED_BOARD -> OriginWorkshopGameAction.PICK_DRILLED_BOARD
        OriginWorkshopGameStage.CARRY_BOARD_TO_JIG -> OriginWorkshopGameAction.PLACE_JIG
        OriginWorkshopGameStage.LEG_LEFT -> OriginWorkshopGameAction.PICK_LEFT_LEG
        OriginWorkshopGameStage.CARRY_LEG_LEFT -> OriginWorkshopGameAction.PLACE_LEFT_LEG
        OriginWorkshopGameStage.LEG_RIGHT -> OriginWorkshopGameAction.PICK_RIGHT_LEG
        OriginWorkshopGameStage.CARRY_LEG_RIGHT -> OriginWorkshopGameAction.PLACE_RIGHT_LEG
        OriginWorkshopGameStage.CLAMP_LEFT -> OriginWorkshopGameAction.TIGHTEN_LEFT
        OriginWorkshopGameStage.CLAMP_RIGHT -> OriginWorkshopGameAction.TIGHTEN_RIGHT
        else -> null
    }

    private fun target(stage: OriginWorkshopGameStage) = when (stage) {
        OriginWorkshopGameStage.STOCK -> stock
        OriginWorkshopGameStage.CARRY_RAW_TO_SAW -> sawInput
        OriginWorkshopGameStage.START_SAW, OriginWorkshopGameStage.SAWING -> sawControl
        OriginWorkshopGameStage.PICK_SAWN_BOARD -> sawOutput
        OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL -> drillInput
        OriginWorkshopGameStage.START_DRILL, OriginWorkshopGameStage.DRILLING -> drillControl
        OriginWorkshopGameStage.PICK_DRILLED_BOARD -> drillInput
        OriginWorkshopGameStage.CARRY_BOARD_TO_JIG,
        OriginWorkshopGameStage.CARRY_LEG_LEFT,
        OriginWorkshopGameStage.CARRY_LEG_RIGHT,
        OriginWorkshopGameStage.FINISHING,
        OriginWorkshopGameStage.REWARDING -> assembly
        OriginWorkshopGameStage.LEG_LEFT -> legLeft
        OriginWorkshopGameStage.LEG_RIGHT -> legRight
        OriginWorkshopGameStage.CLAMP_LEFT, OriginWorkshopGameStage.CLAMPING_LEFT -> clampLeft
        OriginWorkshopGameStage.CLAMP_RIGHT, OriginWorkshopGameStage.CLAMPING_RIGHT -> clampRight
        OriginWorkshopGameStage.CLOSED -> start
    }

    private fun control(stage: OriginWorkshopGameStage): String? = when (stage) {
        OriginWorkshopGameStage.STOCK -> "stock"
        OriginWorkshopGameStage.START_SAW, OriginWorkshopGameStage.SAWING -> "saw"
        OriginWorkshopGameStage.START_DRILL, OriginWorkshopGameStage.DRILLING -> "drill"
        OriginWorkshopGameStage.LEG_LEFT -> "leg-left"
        OriginWorkshopGameStage.LEG_RIGHT -> "leg-right"
        OriginWorkshopGameStage.CLAMP_LEFT, OriginWorkshopGameStage.CLAMPING_LEFT -> "clamp-left"
        OriginWorkshopGameStage.CLAMP_RIGHT, OriginWorkshopGameStage.CLAMPING_RIGHT -> "clamp-right"
        OriginWorkshopGameStage.CARRY_RAW_TO_SAW -> "saw"
        OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL -> "drill"
        OriginWorkshopGameStage.CARRY_BOARD_TO_JIG,
        OriginWorkshopGameStage.CARRY_LEG_LEFT,
        OriginWorkshopGameStage.CARRY_LEG_RIGHT,
        OriginWorkshopGameStage.FINISHING,
        OriginWorkshopGameStage.REWARDING -> "assembly"
        else -> null
    }

    private fun showStage(active: Session, player: Player, phase: String) {
        val stage = active.progress.stage
        val nextControl = control(stage)
        if (active.highlightedControl != nextControl || active.hoveredControl) {
            OriginWorkshopTablesModule.highlightCraftControl(TABLE, nextControl, hovered = false)
            active.highlightedControl = nextControl
            active.hoveredControl = false
        }
        currentPickableDisplay(active)?.let { part ->
            part.isGlowing = true
            part.glowColorOverride = woodGlow
        }
        refreshGuidance(active, player, nowTick(), force = true)
        log(active, phase, stage)
    }

    private fun currentPickableDisplay(active: Session): PacketDisplay? = when (active.progress.stage) {
        OriginWorkshopGameStage.PICK_SAWN_BOARD,
        OriginWorkshopGameStage.PICK_DRILLED_BOARD -> active.workpiece
        else -> null
    }

    private fun refreshGuidance(active: Session, player: Player, now: Long, force: Boolean = false) {
        if (!force && active.lastGuideTick != Long.MIN_VALUE && now - active.lastGuideTick < GUIDANCE_REFRESH_TICKS) return
        active.lastGuideTick = now
        val stage = active.progress.stage
        val timed = timedProgress(active.progress, now, settings?.rules ?: return)
        val stepLabel = "Этап ${stage.step}/${OriginWorkshopGameStage.TOTAL_STEPS} · ${stage.instruction}"
        val taskLabel = if (timed == null) {
            "$stepLabel · ${if (action(stage) == null) "Станок работает" else "ЛКМ"} · Shift — отмена"
        } else {
            "$stepLabel · ${floor(timed * 100.0).toInt()}% · Shift — отмена"
        }
        player.sendActionBar(Component.text(taskLabel))
        label?.takeIf(PacketTextDisplay::isValid)?.let { currentLabel ->
            val at = OriginWorkshopTablesModule.pointAt(TABLE, labelAnchor(stage))
            if (at != null && at.world.uid == active.worldId) currentLabel.teleport(at)
            val shortLabel = buildString {
                append(stage.step).append('/').append(OriginWorkshopGameStage.TOTAL_STEPS)
                append(" · ").append(stage.instruction)
                if (timed != null) append(" · ").append(floor(timed * 100.0).toInt()).append('%')
                else if (action(stage) != null) append(" · ЛКМ")
            }
            currentLabel.text(Component.text(shortLabel))
        }
    }

    private fun timedProgress(progress: OriginWorkshopGameProgress, now: Long, rules: OriginWorkshopGameRules): Double? {
        val duration = when (progress.stage) {
            OriginWorkshopGameStage.SAWING -> rules.sawTicks
            OriginWorkshopGameStage.DRILLING -> rules.drillTicks
            OriginWorkshopGameStage.CLAMPING_LEFT, OriginWorkshopGameStage.CLAMPING_RIGHT -> rules.clampTicks
            OriginWorkshopGameStage.FINISHING -> rules.chairHoldTicks
            else -> return null
        }
        return ((now - progress.stageStartedAt).coerceAtLeast(0L).toDouble() / duration).coerceIn(0.0, 1.0)
    }

    private fun animateMachine(active: Session, now: Long, rules: OriginWorkshopGameRules) {
        val (machine, duration) = when (active.progress.stage) {
            OriginWorkshopGameStage.SAWING -> "saw" to rules.sawTicks
            OriginWorkshopGameStage.DRILLING -> "drill" to rules.drillTicks
            OriginWorkshopGameStage.CLAMPING_LEFT -> "clamp-left" to rules.clampTicks
            OriginWorkshopGameStage.CLAMPING_RIGHT -> "clamp-right" to rules.clampTicks
            else -> return
        }
        val elapsed = (now - active.progress.stageStartedAt).coerceAtLeast(0L)
        val progress = (elapsed.toDouble() / duration).coerceIn(0.0, 1.0)
        OriginWorkshopTablesModule.animateCraftMachine(TABLE, machine, progress)
        if (machine == "saw") animateSawWorkpiece(active, progress)
    }

    private fun animateSawWorkpiece(active: Session, progress: Double) {
        val board = active.workpiece ?: return
        val intake = OriginWorkshopTablesModule.pointAt(TABLE, sawInputCenter) ?: return
        val outfeed = OriginWorkshopTablesModule.pointAt(TABLE, sawOutputCenter) ?: return
        if (intake.world.uid != active.worldId || outfeed.world.uid != active.worldId) return
        val center = intake.clone().add(outfeed.toVector().subtract(intake.toVector()).multiply(progress))
        val previous = active.lastCarriedPosition
        if (previous == null || previous.world?.uid != center.world.uid || previous.distanceSquared(center) >= 0.0001) {
            moveBlockCenter(board, center, active.boardSize)
            active.lastCarriedPosition = center.clone()
        }
    }

    private fun newCarriedBlock(
        active: Session,
        player: Player,
        material: Material,
        size: OriginWorkshopGamePartSize,
    ): PacketBlockDisplay? {
        val displayOwner = owner ?: return null
        return try {
            val center = carryLocation(player)
            spawnBlock(displayOwner, material, center, size).apply {
                isVisibleByDefault = false
                isGlowing = true
                glowColorOverride = woodGlow
                showTo(player)
            }.also {
                active.parts += it
                active.carried = it
                active.carriedSize = size
                active.lastCarriedPosition = center.clone()
            }
        } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop material display unavailable player=" + player.uniqueId, failure)
            player.sendActionBar(Component.text("Мастерская временно недоступна. Попробуй ещё раз."))
            null
        }
    }

    private fun carryExisting(active: Session, player: Player, item: PacketBlockDisplay, size: OriginWorkshopGamePartSize) {
        val center = carryLocation(player)
        item.isVisibleByDefault = false
        item.isGlowing = true
        item.glowColorOverride = woodGlow
        item.showTo(player)
        item.transformation = cuboidTransform(size)
        moveBlockCenter(item, center, size)
        active.lastCarriedPosition = center.clone()
    }

    private fun settle(
        active: Session,
        item: PacketBlockDisplay,
        point: OriginWorkshopPoint,
        size: OriginWorkshopGamePartSize,
    ): Location? {
        val center = OriginWorkshopTablesModule.pointAt(TABLE, point) ?: return null
        item.transformation = cuboidTransform(size)
        moveBlockCenter(item, center, size)
        item.isGlowing = false
        item.isVisibleByDefault = false
        active.carriedSize = null
        active.lastCarriedPosition = null
        return center
    }

    private fun carry(active: Session, player: Player) {
        val item = active.carried as? PacketBlockDisplay ?: return
        val size = active.carriedSize ?: return
        val center = carryLocation(player)
        val previous = active.lastCarriedPosition
        if (previous == null || previous.world?.uid != center.world.uid || previous.distanceSquared(center) >= 0.0025) {
            moveBlockCenter(item, center, size)
            active.lastCarriedPosition = center.clone()
        }
    }

    private fun moveBlockCenter(item: PacketBlockDisplay, center: Location, size: OriginWorkshopGamePartSize) {
        val half = boardRotation().transform(Vector3f(size.x / 2f, size.y / 2f, size.z / 2f))
        item.teleport(center.clone().subtract(half.x.toDouble(), half.y.toDouble(), half.z.toDouble()))
    }

    private fun carryLocation(player: Player): Location {
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val obstruction = player.world.rayTraceBlocks(eye, direction, 1.25, FluidCollisionMode.NEVER, true)
        val distance = obstruction?.let {
            (it.hitPosition.toLocation(player.world).distance(eye) - 0.25).coerceAtLeast(0.35)
        } ?: 1.25
        return eye.clone().add(direction.multiply(distance))
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

    private fun startInteractionDistance(player: Player): Double? {
        val station = OriginWorkshopTablesModule.pointAt(TABLE, start) ?: return null
        if (player.world.uid != station.world.uid) return null
        val bounds = startInteractionBounds() ?: return null
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val reach = originWorkshopRayAabbHit(
            OriginWorkshopVec3(eye.x, eye.y, eye.z),
            OriginWorkshopVec3(direction.x, direction.y, direction.z),
            bounds,
            REACH,
        ) ?: return null
        if (player.world.rayTraceBlocks(eye, direction, reach + BLOCK_EPSILON, FluidCollisionMode.NEVER, true) != null) return null
        return reach
    }

    private fun startInteractionBounds(): OriginWorkshopAabb? {
        val dimensions = OriginWorkshopTablesModule.dimensionsFor(TABLE) ?: return null
        val localBounds = originWorkshopStartInteractionAabb(dimensions)
        val localCorners = buildList {
            for (x in listOf(localBounds.minX, localBounds.maxX))
                for (y in listOf(localBounds.minY, localBounds.maxY))
                    for (z in listOf(localBounds.minZ, localBounds.maxZ)) {
                add(OriginWorkshopPoint(x, y, z))
            }
        }
        val corners = localCorners.map { OriginWorkshopTablesModule.pointAt(TABLE, it) ?: return null }
        return OriginWorkshopAabb(
            minX = corners.minOf { it.x }, minY = corners.minOf { it.y }, minZ = corners.minOf { it.z },
            maxX = corners.maxOf { it.x }, maxY = corners.maxOf { it.y }, maxZ = corners.maxOf { it.z },
        )
    }

    private fun isWorkshopInteraction(player: Player): Boolean {
        if (startInteractionDistance(player) != null) return true
        val anchors = listOf(stock, sawInput, sawOutput, sawControl, drillInput, drillControl,
            assembly, clampLeft, clampRight, legLeft, legRight)
        return anchors.any { anchor ->
            OriginWorkshopTablesModule.pointAt(TABLE, anchor)?.let { targetDistance(player, it) } != null
        }
    }

    private fun sendBusyFeedback(player: Player, ownerId: UUID, now: Long = System.nanoTime()) {
        val previous = busyFeedbackNanos[player.uniqueId]
        if (previous == null || now - previous >= BUSY_FEEDBACK_NANOS) {
            val ownerName = Bukkit.getPlayer(ownerId)?.name ?: "другой игрок"
            player.sendActionBar(Component.text("За верстаком работает $ownerName."))
            busyFeedbackNanos[player.uniqueId] = now
        }
        busyFeedbackNanos.entries.removeIf { now - it.value > 60_000_000_000L || Bukkit.getPlayer(it.key) == null }
        while (busyFeedbackNanos.size > MAX_RECENT_INPUTS) {
            busyFeedbackNanos.entries.firstOrNull()?.let { busyFeedbackNanos.remove(it.key) } ?: break
        }
    }

    private fun spawnBlock(
        displays: PaperPacketDisplays,
        material: Material,
        center: Location,
        size: OriginWorkshopGamePartSize,
    ) = displays.spawnBlock(center, material.createBlockData()).apply {
            isVisibleByDefault = false
            billboard = Display.Billboard.FIXED
            viewRange = 0.8f
            shadowRadius = 0f
            interpolationDelay = -1
            interpolationDuration = 2
            teleportDuration = 2
            transformation = cuboidTransform(size)
            moveBlockCenter(this, center, size)
        }

    private fun cuboidTransform(size: OriginWorkshopGamePartSize) = Transformation(
        Vector3f(), boardRotation(), Vector3f(size.x, size.y, size.z), Quaternionf(),
    )

    private fun boardRotation(): Quaternionf {
        val origin = OriginWorkshopTablesModule.pointAt(TABLE, OriginWorkshopPoint(0.0, 0.0, 0.0)) ?: return Quaternionf()
        val localX = OriginWorkshopTablesModule.pointAt(TABLE, OriginWorkshopPoint(1.0, 0.0, 0.0)) ?: return Quaternionf()
        val dx = localX.x - origin.x
        val dz = localX.z - origin.z
        return Quaternionf().rotationY(atan2(-dz, dx).toFloat())
    }

    private fun finishSession(active: Session) {
        if (active !== session) return
        session = null
        active.tasks.close()
        active.parts.forEach(PacketDisplay::remove)
        active.parts.clear()
        active.workpiece = null
        active.boardSize = RAW_BOARD_SIZE
        active.carried = null
        active.carriedSize = null
        active.leftLeg = null
        active.rightLeg = null
        OriginWorkshopTablesModule.resetCraft(TABLE)
        showStart()
    }

    private fun cancel(active: Session, reason: String, notify: Boolean) {
        if (active !== session) return
        val player = Bukkit.getPlayer(active.playerId)
        log(active, "CANCEL", active.progress.stage, reason)
        session = null
        active.progress = OriginWorkshopGameProgress(OriginWorkshopGameStage.CLOSED, nowTick())
        active.tasks.close()
        active.parts.forEach(PacketDisplay::remove)
        active.parts.clear()
        active.workpiece = null
        active.boardSize = RAW_BOARD_SIZE
        active.carried = null
        active.carriedSize = null
        active.leftLeg = null
        active.rightLeg = null
        OriginWorkshopTablesModule.resetCraft(TABLE)
        showStart()
        if (notify && player?.isOnline == true) {
            player.sendActionBar(Component.text("Сборка прервана. Заготовки остались на складе."))
        }
    }

    private fun showStart() {
        OriginWorkshopTablesModule.highlightCraftControl(TABLE, "start")
        occupancyLabel?.remove()
        occupancyLabel = null
        label?.remove()
        label = OriginWorkshopTablesModule.pointAt(TABLE, frontGuidanceAnchor(OriginWorkshopPoint(0.0, 0.0, 0.0)))?.let {
            spawnLabel(it, "Столярная мастерская · ЛКМ", visibleByDefault = true)
        }
    }

    private fun frontGuidanceAnchor(target: OriginWorkshopPoint): OriginWorkshopPoint =
        OriginWorkshopPoint(target.x.coerceIn(-1.5, 1.5), 2.25, -1.5)

    private fun labelAnchor(stage: OriginWorkshopGameStage): OriginWorkshopPoint =
        if (stage == OriginWorkshopGameStage.STOCK) OriginWorkshopPoint(stock.x, 1.45, -0.8)
        else frontGuidanceAnchor(target(stage))

    private fun setOccupancyLabel(player: Player) {
        occupancyLabel?.remove()
        val local = OriginWorkshopPoint(0.0, 2.65, -1.5)
        occupancyLabel = OriginWorkshopTablesModule.pointAt(TABLE, local)?.let {
            spawnLabel(
                it,
                "За верстаком: ${player.name}",
                visibleByDefault = true,
                scale = 0.38f,
                lineWidth = 220,
            )
        }
    }

    private fun spawnLabel(
        at: Location,
        text: String,
        visibleByDefault: Boolean,
        scale: Float = 0.52f,
        lineWidth: Int = 260,
    ): PacketTextDisplay? {
        val displayOwner = owner ?: return null
        return runCatching {
            displayOwner.spawnText(at, Component.text(text)).apply {
                isVisibleByDefault = visibleByDefault
                billboard = Display.Billboard.CENTER
                viewRange = 0.55f
                shadowRadius = 0f
                this.lineWidth = lineWidth
                isShadowed = true
                backgroundColor = Color.fromARGB(150, 12, 10, 8)
                isSeeThrough = false
                isDefaultBackground = false
                textOpacity = 230.toByte()
                transformation = Transformation(
                    Vector3f(), AxisAngle4f(), Vector3f(scale), AxisAngle4f(),
                )
            }
        }.onFailure {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop game station label unavailable", it)
        }.getOrNull()
    }

    private fun feedback(player: Player, active: Session, text: String) {
        val now = System.nanoTime()
        if (now - active.lastFeedback < FEEDBACK_NANOS) return
        active.lastFeedback = now
        player.sendActionBar(Component.text(text))
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
            active === session && generationAtRequest == generation &&
                active.progress.stage == OriginWorkshopGameStage.REWARDING &&
                player.isOnline && player.world.uid == active.worldId &&
                (OriginWorkshopTablesModule.pointAt(TABLE, start)?.takeIf { it.world.uid == active.worldId }
                    ?.let { player.location.distanceSquared(it) } ?: Double.POSITIVE_INFINITY) <= SESSION_RANGE_SQUARED
        }
        log(active, "REWARD_REQUEST", active.progress.stage)
        handler.complete(player, request, valid) { error ->
            if (active !== session || generationAtRequest != generation) return@complete
            if (error == null) {
                player.sendMessage(
                    Component.text("\n  Стул собран и добавлен в инвентарь.\n  Новую сборку можно начать через 24 часа.\n"),
                )
                log(active, "REWARD_DELIVERED", active.progress.stage)
            } else {
                player.sendMessage(Component.text("\n  " + error + "\n  Если стул не появился, сообщи администрации.\n"))
                log(active, "REWARD_FAILED", active.progress.stage)
            }
            finishSession(active)
        }
    }

    private fun stop(reason: String) {
        generation++
        session?.let { cancel(it, reason, false) }
        pendingStatus.clear()
        accepted.clear()
        commonTasks.close()
        commonTasks = LifecycleTaskScope()
        rewards?.close()
        rewards = null
        occupancyLabel?.remove()
        occupancyLabel = null
        label?.remove()
        label = null
        busyFeedbackNanos.clear()
        owner?.close()
        owner = null
        OriginWorkshopTablesModule.resetCraft(TABLE)
    }

    private fun log(active: Session, phase: String, stage: OriginWorkshopGameStage, detail: String = "") {
        ARC.instance.logger.info(
            "ORIGIN_WORKSHOP_GAME phase=" + phase + " player=" + active.playerId + " session=" + active.id +
                " stage=" + stage + if (detail.isEmpty()) "" else " detail=" + detail,
        )
    }

    private fun nowTick() = System.nanoTime() / TICK_NANOS
}
