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
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Matrix4f
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class OriginWorkshopGameStage(val step: Int, val instruction: String) {
    STOCK(1, "Возьми заготовку со склада."),
    CARRY_RAW_TO_SAW(2, "Перетащи заготовку к пиле."),
    START_SAW(3, "Нажми рукоять пилы."),
    SAWING(3, "Пила распускает заготовку."),
    SAW_REPOSITION(4, "Разверни доску для второго распила."),
    START_SAW_SECOND(5, "Запусти второй распил рукоятью."),
    SAWING_SECOND(5, "Пила подравнивает второй торец."),
    PICK_SAWN_BOARD(6, "Забери распиленную доску."),
    CARRY_BOARD_TO_DRILL(7, "Положи доску под сверло."),
    START_DRILL(8, "Нажми рукоять сверла."),
    DRILLING(8, "Сверло делает отверстия."),
    DRILL_ALIGN_CENTER(9, "Сдвинь доску к центральной отметке."),
    START_DRILL_SECOND(10, "Опусти сверло для второго отверстия."),
    DRILLING_SECOND(10, "Сверло делает второе отверстие."),
    DRILL_ALIGN_LAST(11, "Сдвинь доску к последней отметке."),
    START_DRILL_THIRD(12, "Опусти сверло для третьего отверстия."),
    DRILLING_THIRD(12, "Сверло делает третье отверстие."),
    PICK_DRILLED_BOARD(13, "Забери просверленную доску."),
    CARRY_BOARD_TO_JIG(14, "Положи доску в сборочный шаблон."),
    LEG_LEFT(15, "Возьми левую ножку."),
    CARRY_LEG_LEFT(16, "Перетащи левую ножку в шаблон."),
    LEG_RIGHT(17, "Возьми правую ножку."),
    CARRY_LEG_RIGHT(18, "Перетащи правую ножку в шаблон."),
    CLAMP_LEFT(19, "Затяни левый зажим."),
    CLAMPING_LEFT(19, "Левый зажим стягивает детали."),
    CLAMP_RIGHT(20, "Затяни правый зажим."),
    CLAMPING_RIGHT(20, "Правый зажим стягивает детали."),
    FINISHING(21, "Готовый стул просыхает."),
    REWARDING(21, "Готовая мебель отправляется в инвентарь."),
    CLOSED(21, "Сборка завершена."),

    UPHOLSTER_FABRIC(1, "Возьми красную ткань со склада."),
    UPHOLSTER_PLACE_FABRIC(2, "Положи ткань под прижим."),
    UPHOLSTER_START_PRESS(3, "Опусти прижим, чтобы прострочить чехол."),
    UPHOLSTER_PRESSING(3, "Станок прострачивает чехол."),
    UPHOLSTER_PICK_COVER(4, "Забери простроченный чехол."),
    UPHOLSTER_PLACE_COVER(5, "Надень чехол на подушку дивана."),
    UPHOLSTER_FINISHING(5, "Проверь готовую обивку."),

    ASSEMBLER_STOCK(1, "Возьми столешницу со склада."),
    ASSEMBLER_PLACE_VISE(2, "Положи столешницу в тиски."),
    ASSEMBLER_START_VISE(3, "Зажми столешницу винтовой рукоятью."),
    ASSEMBLER_VISING(3, "Тиски выравнивают кромку."),
    ASSEMBLER_PICK_VISED(4, "Забери выровненную столешницу."),
    ASSEMBLER_PLACE_ANVIL(4, "Положи столешницу на наковальню."),
    ASSEMBLER_START_ANVIL(5, "Запусти сборочный молот."),
    ASSEMBLER_HAMMERING(5, "Молот фиксирует соединение."),
    ASSEMBLER_FINISHING(5, "Проверь готовую столешницу."),

    FINISHER_START_PANEL(1, "Опусти панель сушилки на стол."),
    FINISHER_WITHDRAWING(1, "Панель ложится на шлифовальную площадку."),
    FINISHER_DIP_BRUSH(2, "Окуни кисть в защитный состав."),
    FINISHER_COAT_NEAR(3, "Покрой ближний край панели."),
    FINISHER_COAT_CENTER(4, "Проведи кистью по середине."),
    FINISHER_COAT_FAR(5, "Покрой дальний край панели."),
    FINISHER_DRYING(5, "Панель возвращается на сушилку."),
    FINISHER_FINISHING(5, "Проверь готовое покрытие.");

    companion object {
        const val TOTAL_STEPS = 21
    }
}

internal enum class OriginWorkshopGameAction {
    PICK_STOCK,
    PLACE_SAW,
    ACTIVATE_SAW,
    REPOSITION_SAW,
    PICK_SAWN_BOARD,
    PLACE_DRILL,
    ACTIVATE_DRILL,
    ALIGN_DRILL_CENTER,
    ALIGN_DRILL_LAST,
    PICK_DRILLED_BOARD,
    PLACE_JIG,
    PICK_LEFT_LEG,
    PLACE_LEFT_LEG,
    PICK_RIGHT_LEG,
    PLACE_RIGHT_LEG,
    TIGHTEN_LEFT,
    TIGHTEN_RIGHT,
    PICK_FABRIC,
    PLACE_FABRIC,
    ACTIVATE_PRESS,
    PICK_PRESSED_COVER,
    PLACE_CUSHION_COVER,
    PICK_TABLETOP,
    PLACE_VISE,
    TIGHTEN_VISE,
    PICK_VISED_TABLETOP,
    PLACE_ANVIL,
    ACTIVATE_ANVIL,
    START_FINISH_PANEL,
    DIP_FINISH_BRUSH,
    COAT_PANEL_NEAR,
    COAT_PANEL_CENTER,
    COAT_PANEL_FAR,
}

internal data class OriginWorkshopGameRules(
    val sawTicks: Long = 60,
    val drillTicks: Long = 40,
    val clampTicks: Long = 20,
    val chairHoldTicks: Long = 60,
    val pressTicks: Long = 60,
    val viseTicks: Long = 40,
    val hammerTicks: Long = 60,
    val finishingTicks: Long = 60,
    val timeout: Long = 3_600,
) {
    init {
        require(
            listOf(sawTicks, drillTicks, clampTicks, chairHoldTicks, pressTicks, viseTicks, hammerTicks, finishingTicks)
                .all { it in 1L..1_200L },
        )
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
    return originWorkshopTransition(progress, action, now, defaultCarpenterRecipe())
}

internal fun originWorkshopAdvance(
    progress: OriginWorkshopGameProgress,
    now: Long,
    rules: OriginWorkshopGameRules,
): OriginWorkshopGameProgress = originWorkshopAdvance(progress, now, defaultCarpenterRecipe(rules))

private fun defaultCarpenterRecipe(rules: OriginWorkshopGameRules = OriginWorkshopGameRules()) =
    originWorkshopGameRecipe(OriginWorkshopTableRole.CARPENTER, "furnituresplus:white_wooden_chair",
        OriginWorkshopTableDimensions.DEFAULT, OriginWorkshopMachineTuning(), OriginWorkshopPoint(-7.5, 0.44, 0.30), rules)

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

/** Ray against the rendered block cube. Inverse scaling preserves world-distance ray parameters. */
internal fun originWorkshopRayTransformedCube(
    origin: OriginWorkshopVec3,
    direction: OriginWorkshopVec3,
    transform: Matrix4f,
    reach: Double,
): Double? {
    if (reach !in 0.0..4.5 || !listOf(origin.x, origin.y, origin.z, direction.x, direction.y, direction.z).all(Double::isFinite)) return null
    val length = sqrt(direction.dot(direction))
    if (length < 1e-9 || abs(transform.determinant()) < 1e-9f) return null
    val inverse = Matrix4f(transform).invert()
    val point = inverse.transformPosition(Vector3f(origin.x.toFloat(), origin.y.toFloat(), origin.z.toFloat()))
    val ray = inverse.transformDirection(Vector3f((direction.x / length).toFloat(), (direction.y / length).toFloat(), (direction.z / length).toFloat()))
    var near = 0.0
    var far = reach
    for ((position, velocity) in listOf(point.x to ray.x, point.y to ray.y, point.z to ray.z)) {
        if (!position.isFinite() || !velocity.isFinite()) return null
        if (abs(velocity) < 1e-9f) {
            if (position !in 0f..1f) return null
        } else {
            val first = -position.toDouble() / velocity
            val second = (1.0 - position) / velocity
            near = max(near, min(first, second))
            far = min(far, max(first, second))
            if (near > far) return null
        }
    }
    return near.takeIf { it > 0.0 && it <= reach }
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

/** One player owns the sleeping worker's station; each role uses physical packet-only handoffs. */
internal object OriginWorkshopGame : PluginModule, Listener {
    override val name = "OriginWorkshopGame"
    override val priority = 25

    private const val REACH = 4.5
    private const val TARGET_RADIUS = 0.36
    private const val BLOCK_EPSILON = 0.06
    private const val TICK_NANOS = 50_000_000L
    private const val STEP_TICKS = 1L
    private const val SESSION_RANGE_SQUARED = 144.0
    private const val COOLDOWN = 86_400_000L
    private const val INPUT_DEDUPE_NANOS = 120_000_000L
    private const val FEEDBACK_NANOS = 500_000_000L
    private const val HOVER_SOUND_NANOS = 700_000_000L
    private const val HOVER_CHECK_TICKS = 2L
    private const val GUIDANCE_REFRESH_TICKS = 20L
    private const val BUSY_FEEDBACK_NANOS = 1_000_000_000L
    private const val MAX_RECENT_INPUTS = 64

    private val CUT_BOARD_SIZE = OriginWorkshopGamePartSize(0.72f, 0.08f, 0.22f)

    private val COATING_ACTIONS = setOf(OriginWorkshopGameAction.COAT_PANEL_NEAR,
        OriginWorkshopGameAction.COAT_PANEL_CENTER, OriginWorkshopGameAction.COAT_PANEL_FAR)
    private val PLACEMENT_ACTIONS = COATING_ACTIONS + setOf(
        OriginWorkshopGameAction.PLACE_SAW, OriginWorkshopGameAction.PLACE_DRILL, OriginWorkshopGameAction.PLACE_JIG,
        OriginWorkshopGameAction.PLACE_LEFT_LEG, OriginWorkshopGameAction.PLACE_RIGHT_LEG,
        OriginWorkshopGameAction.PLACE_FABRIC, OriginWorkshopGameAction.PLACE_CUSHION_COVER,
        OriginWorkshopGameAction.PLACE_VISE, OriginWorkshopGameAction.PLACE_ANVIL)

    private val start = OriginWorkshopPoint(0.0, 1.28, -1.35)
    private val woodGlow = Color.fromRGB(191, 139, 82)
    private val hoverGlow = Color.fromRGB(255, 210, 99)

    private data class PropPiece(val display: PacketBlockDisplay, val geometry: OriginWorkshopWorkpiecePiece)
    private data class Prop(val pieces: List<PropPiece>) {
        fun remove() = pieces.forEach { it.display.remove() }
        fun glow(color: Color?) = pieces.forEach {
            it.display.isGlowing = color != null
            it.display.glowColorOverride = color
        }
    }

    private data class Session(
        val id: UUID,
        val playerId: UUID,
        val worldId: UUID,
        val began: Long,
        val tableId: String,
        val recipe: OriginWorkshopGameRecipe,
        val rewardHandler: OriginWorkshopCraftRewards,
        var progress: OriginWorkshopGameProgress,
        var lastProgressAt: Long = began,
        val parts: MutableList<PacketDisplay> = mutableListOf(),
        var workpiece: Prop? = null,
        var carried: Prop? = null,
        var leftLeg: Prop? = null,
        var rightLeg: Prop? = null,
        var brush: PacketItemDisplay? = null,
        var targetMarker: Prop? = null,
        val coating: MutableList<PacketDisplay> = mutableListOf(),
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
    private val rewards = mutableMapOf<String, OriginWorkshopCraftRewards>()
    private var idleTable: String? = null
    private val tableId: String get() = session?.tableId ?: idleTable.orEmpty()
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
        val displayOwner = try {
            PaperPacketDisplays(ARC.instance)
        } catch (failure: Exception) {
            ARC.instance.logger.log(Level.WARNING, "Origin workshop game display owner unavailable", failure)
            return
        }
        owner = displayOwner
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val active = session?.takeIf { it.playerId == event.player.uniqueId } ?: return
        if (event.to.world.uid != active.worldId) return
        if (active.carried != null || active.brush != null) carry(active, event.player, event.to)
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

    private fun click(player: Player): Boolean = try {
        handleClick(player)
    } catch (failure: Exception) {
        ARC.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP_GAME phase=INPUT_ERROR player=${player.uniqueId}", failure)
        session?.takeIf { it.playerId == player.uniqueId }?.let { cancel(it, "input-error", true) }
        true
    }

    private fun handleClick(player: Player): Boolean {
        if (owner == null || settings?.enabled != true) return false
        val nowNanos = System.nanoTime()
        accepted.entries.removeIf { nowNanos - it.value > INPUT_DEDUPE_NANOS }
        if (originWorkshopIsDuplicateClick(accepted[player.uniqueId], nowNanos, INPUT_DEDUPE_NANOS)) return true
        accepted.remove(player.uniqueId)
        if (accepted.size >= MAX_RECENT_INPUTS) accepted.entries.firstOrNull()?.let { accepted.remove(it.key) }

        if (session == null) reconcileIdleStation()
        val current = session
        if (current == null && idleTable == null) return false
        if (current != null && !OriginFurnitureWorkshopModule.ownsPlayerTable(current.tableId, player.uniqueId) && current.playerId == player.uniqueId) {
            cancel(current, "station-reservation-lost", true)
            return true
        }
        if (current != null && current.playerId != player.uniqueId) {
            if (!isWorkshopInteraction(player)) return false
            sendBusyFeedback(player, current.playerId, nowNanos)
            return true
        }
        if (current != null) {
            val station = OriginWorkshopTablesModule.pointAt(tableId, start)
            val reason = originWorkshopCancelReason(
                online = player.isOnline,
                sameWorld = station != null && station.world.uid == current.worldId && player.world.uid == current.worldId,
                sneaking = player.isSneaking,
                distanceSquared = if (station != null && station.world.uid == player.world.uid) {
                    player.location.distanceSquared(station)
                } else Double.POSITIVE_INFINITY,
                elapsedTicks = nowTick() - current.lastProgressAt,
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
            if (activeTargetDistance(current, player) == null) return false
        }

        accepted[player.uniqueId] = nowNanos
        if (current == null) startClick(player) else sessionClick(player, current)
        return true
    }

    private fun startClick(player: Player) {
        val selectedTable = idleTable ?: return
        val productId = OriginFurnitureWorkshopModule.productIdFor(selectedTable) ?: return
        val recipe = OriginWorkshopTablesModule.recipeFor(selectedTable, productId, settings?.rules ?: return) ?: return
        val handler = rewards.getOrPut(productId) { OriginWorkshopCraftRewards(COOLDOWN, productId) }
        if (!pendingStatus.add(player.uniqueId)) return
        if (pendingStatus.size > 32) {
            pendingStatus.remove(player.uniqueId)
            player.sendActionBar(Component.text("Сейчас проверяется много заявок. Попробуй ещё раз через минуту."))
            return
        }
        player.sendActionBar(Component.text("Проверяю доступ к сборке."))
        val token = commonTasks.token()
        val generationAtQuery = generation
        handler.status(player).whenComplete { remaining, failure ->
            commonTasks.runSync(token) {
                pendingStatus.remove(player.uniqueId)
                if (generationAtQuery != generation || !player.isOnline || owner == null) return@runSync
                if (OriginFurnitureWorkshopModule.availablePlayerTable() != selectedTable ||
                    OriginFurnitureWorkshopModule.productIdFor(selectedTable) != productId) {
                    if (session != null) sendBusyFeedback(player, session!!.playerId)
                    else player.sendActionBar(Component.text("Мастер уже вернулся. Подожди, пока следующий отправится отдыхать."))
                    return@runSync
                }
                val startLocation = OriginWorkshopTablesModule.pointAt(selectedTable, start)
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
                    player.sendActionBar(Component.text("Недавно уже собирали мебель. Подожди ещё " + workshopCooldownText(remaining!!) + "."))
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
                    begin(player, selectedTable, recipe, handler)
                }
            }
        }
    }

    private fun begin(player: Player, selectedTable: String, recipe: OriginWorkshopGameRecipe, handler: OriginWorkshopCraftRewards) {
        val rules = settings?.rules ?: return
        val startLocation = OriginWorkshopTablesModule.pointAt(selectedTable, start) ?: return
        if (player.world.uid != startLocation.world.uid || player.location.distanceSquared(startLocation) > SESSION_RANGE_SQUARED) return

        if (!OriginFurnitureWorkshopModule.acquirePlayerTable(selectedTable, player.uniqueId)) return
        val active = Session(
            UUID.randomUUID(), player.uniqueId, player.world.uid, nowTick(), selectedTable, recipe, handler,
            OriginWorkshopGameProgress(recipe.initialStage, nowTick()),
        )
        session = active
        try {
            idleHoverers.clear()
            label?.remove()
            label = OriginWorkshopTablesModule.pointAt(tableId, labelAnchor(active, recipe.initialStage))?.let {
                spawnLabel(it, recipe.initialStage.instruction + " · ЛКМ", visibleByDefault = false)
            }
            label?.showTo(player)
            setOccupancyLabel(player)
            active.tasks.runTimer(STEP_TICKS, STEP_TICKS) {
                try { step(active, rules) } catch (failure: Exception) {
                    ARC.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP_GAME phase=TICK_ERROR table=${active.tableId} session=${active.id}", failure)
                    cancel(active, "tick-error", true)
                }
            }
            showStage(active, player, "START")
        } catch (failure: Exception) {
            cancel(active, "start-error", true)
            ARC.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP_GAME phase=START_ERROR table=$selectedTable", failure)
        }
    }

    private fun sessionClick(player: Player, active: Session) {
        val interaction = active.recipe.interactions[active.progress.stage] ?: run {
            feedback(player, active, "Подожди, станок ещё работает.")
            return
        }
        val now = nowTick()
        val action = interaction.action
        val next = originWorkshopTransition(active.progress, action, now, active.recipe) ?: return
        if (!applyAction(player, active, action)) return
        active.progress = next
        active.lastProgressAt = now
        showStage(active, player, "STAGE")
        animateMachine(active, now)
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
        val interaction = active.recipe.interactions[active.progress.stage] ?: return false
        when (action) {
            OriginWorkshopGameAction.PICK_STOCK,
            OriginWorkshopGameAction.PICK_FABRIC,
            OriginWorkshopGameAction.PICK_TABLETOP,
            OriginWorkshopGameAction.PICK_LEFT_LEG,
            OriginWorkshopGameAction.PICK_RIGHT_LEG -> {
                val size = active.recipe.partSizes[action] ?: return false
                val material = active.recipe.materials[action] ?: return false
                val part = spawnProp(active, player, listOf(OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(0.0, 0.0, 0.0), size, material)))
                active.carried = part
                carry(active, player)
                if (action == OriginWorkshopGameAction.PICK_LEFT_LEG || action == OriginWorkshopGameAction.PICK_RIGHT_LEG)
                    OriginWorkshopTablesModule.setCraftPartVisible(tableId, interaction.control ?: return false, false)
            }
            OriginWorkshopGameAction.PLACE_SAW,
            OriginWorkshopGameAction.PLACE_DRILL,
            OriginWorkshopGameAction.PLACE_JIG,
            OriginWorkshopGameAction.PLACE_FABRIC,
            OriginWorkshopGameAction.PLACE_VISE,
            OriginWorkshopGameAction.PLACE_ANVIL,
            OriginWorkshopGameAction.PLACE_CUSHION_COVER,
            OriginWorkshopGameAction.PLACE_LEFT_LEG,
            OriginWorkshopGameAction.PLACE_RIGHT_LEG -> {
                val item = active.carried ?: return false
                settle(active, item, interaction.target) ?: return false
                when (action) {
                    OriginWorkshopGameAction.PLACE_LEFT_LEG -> active.leftLeg = item
                    OriginWorkshopGameAction.PLACE_RIGHT_LEG -> active.rightLeg = item
                    else -> active.workpiece = item
                }
                active.carried = null
                if (action == OriginWorkshopGameAction.PLACE_CUSHION_COVER) finishProduct(active)
            }
            OriginWorkshopGameAction.PICK_SAWN_BOARD,
            OriginWorkshopGameAction.PICK_DRILLED_BOARD,
            OriginWorkshopGameAction.PICK_PRESSED_COVER,
            OriginWorkshopGameAction.PICK_VISED_TABLETOP -> {
                active.carried = active.workpiece ?: return false
                active.workpiece = null
                carry(active, player)
            }
            OriginWorkshopGameAction.REPOSITION_SAW -> {
                val item = active.workpiece ?: return false
                val at = OriginWorkshopTablesModule.pointAt(tableId, target(active, OriginWorkshopGameStage.CARRY_RAW_TO_SAW)) ?: return false
                moveProp(item, at, boardRotation().rotateY(Math.PI.toFloat()), carrying = false)
            }
            OriginWorkshopGameAction.ALIGN_DRILL_CENTER,
            OriginWorkshopGameAction.ALIGN_DRILL_LAST -> {
                val item = active.workpiece ?: return false
                settle(active, item, interaction.target) ?: return false
            }
            OriginWorkshopGameAction.START_FINISH_PANEL,
            OriginWorkshopGameAction.ACTIVATE_SAW,
            OriginWorkshopGameAction.ACTIVATE_DRILL,
            OriginWorkshopGameAction.ACTIVATE_PRESS,
            OriginWorkshopGameAction.TIGHTEN_VISE,
            OriginWorkshopGameAction.ACTIVATE_ANVIL,
            OriginWorkshopGameAction.TIGHTEN_LEFT,
            OriginWorkshopGameAction.TIGHTEN_RIGHT -> Unit
            OriginWorkshopGameAction.DIP_FINISH_BRUSH -> {
                val at = OriginWorkshopTablesModule.pointAt(tableId, interaction.target) ?: return false
                active.brush = owner?.spawnItem(at, ItemStack(Material.BRUSH))?.apply {
                    isVisibleByDefault = false
                    showTo(player)
                    itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                    teleportDuration = 0
                    interpolationDuration = 0
                    transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(0.5f), Quaternionf())
                }?.also { active.parts += it } ?: return false
            }
            OriginWorkshopGameAction.COAT_PANEL_NEAR,
            OriginWorkshopGameAction.COAT_PANEL_CENTER,
            OriginWorkshopGameAction.COAT_PANEL_FAR -> {
                val at = OriginWorkshopTablesModule.pointAt(tableId, interaction.target) ?: return false
                // Three real successive bands make applied finish visible before the panel returns to the rack.
                val patch = spawnBlock(owner ?: return false, Material.STRIPPED_BIRCH_WOOD, at,
                    OriginWorkshopGamePartSize(0.18f, 0.012f, 0.22f))
                patch.isVisibleByDefault = false
                patch.showTo(player)
                active.parts += patch
                active.coating += patch
                if (action == OriginWorkshopGameAction.COAT_PANEL_FAR) {
                    active.brush?.remove()
                    active.brush = null
                    // The moving physical panel retains the finished material while the fixed bands are removed.
                    active.coating.forEach(PacketDisplay::remove)
                    active.coating.clear()
                    OriginWorkshopTablesModule.setCraftFinishedPanel(tableId, true)
                }
            }
        }
        return true
    }

    private fun step(active: Session, rules: OriginWorkshopGameRules) {
        if (active !== session) return
        if (!OriginFurnitureWorkshopModule.ownsPlayerTable(active.tableId, active.playerId)) {
            cancel(active, "station-reservation-lost", true)
            return
        }
        val player = Bukkit.getPlayer(active.playerId)
        val station = OriginWorkshopTablesModule.pointAt(tableId, start)
        val reason = originWorkshopCancelReason(
            online = player?.isOnline == true,
            sameWorld = station != null && station.world.uid == active.worldId && player?.world?.uid == active.worldId,
            sneaking = player?.isSneaking == true,
            distanceSquared = if (player != null && station != null && player.world.uid == station.world.uid) {
                player.location.distanceSquared(station)
            } else Double.POSITIVE_INFINITY,
            elapsedTicks = nowTick() - active.lastProgressAt,
            timeoutTicks = rules.timeout,
            maxDistanceSquared = SESSION_RANGE_SQUARED,
        )
        if (reason != null) {
            cancel(active, reason.name.lowercase(), reason != OriginWorkshopCancelReason.OFFLINE)
            return
        }
        val onlinePlayer = player ?: return
        advanceTimedStage(active, onlinePlayer, nowTick())
        animateMachine(active, nowTick())
        updateActiveHover(active, onlinePlayer)
        if (active.carried != null || active.brush != null) carry(active, onlinePlayer)
        refreshGuidance(active, onlinePlayer, nowTick())
        if (active.progress.stage == OriginWorkshopGameStage.REWARDING) reward(active, onlinePlayer)
    }

    private fun updateIdleHover() {
        if (session != null || owner == null) return
        reconcileIdleStation()
        if (idleTable == null) return
        val station = OriginWorkshopTablesModule.pointAt(tableId, start) ?: return
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
            OriginWorkshopTablesModule.highlightCraftControl(tableId, "start", hovered = next.isNotEmpty())
        }
        hoverSounds.entries.removeIf { Bukkit.getPlayer(it.key) == null }
    }

    private fun updateActiveHover(active: Session, player: Player) {
        val hovered = activeTargetDistance(active, player) != null
        if (active.hoveredControl == hovered) return
        active.hoveredControl = hovered
        OriginWorkshopTablesModule.highlightCraftControl(tableId, active.highlightedControl, hovered, viewer = player)
        currentPickableDisplay(active)?.glow(if (hovered) hoverGlow else woodGlow)
        active.targetMarker?.glow(if (hovered) hoverGlow else woodGlow)
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
        val operation = active.recipe.timedStages[before.stage] ?: return
        val after = originWorkshopAdvance(before, now, active.recipe)
        if (after == before) return
        animateMachine(active, before.stageStartedAt + operation.durationTicks)
        when (before.stage) {
            OriginWorkshopGameStage.SAWING,
            OriginWorkshopGameStage.SAWING_SECOND -> {
                active.workpiece?.remove()
                val second = before.stage == OriginWorkshopGameStage.SAWING_SECOND
                val boardSize = if (second) CUT_BOARD_SIZE else OriginWorkshopGamePartSize(0.87f, 0.08f, 0.22f)
                val output = target(active, OriginWorkshopGameStage.PICK_SAWN_BOARD)
                val pieces = if (second) originWorkshopBoardPieces(holes = 0) else listOf(OriginWorkshopWorkpiecePiece(
                    OriginWorkshopPoint(0.0, 0.0, 0.0), boardSize, Material.OAK_PLANKS))
                active.workpiece = spawnProp(active, player, pieces)
                settle(active, active.workpiece!!, output)
                val offcut = spawnProp(active, player, listOf(OriginWorkshopWorkpiecePiece(
                    OriginWorkshopPoint(0.0, 0.0, 0.0), OriginWorkshopGamePartSize(0.12f, 0.08f, 0.22f), Material.OAK_PLANKS)))
                settle(active, offcut, OriginWorkshopPoint(output.x - 0.60, output.y - 0.15, output.z + if (second) 0.58 else 0.30))
            }
            OriginWorkshopGameStage.DRILLING,
            OriginWorkshopGameStage.DRILLING_SECOND,
            OriginWorkshopGameStage.DRILLING_THIRD -> {
                active.workpiece?.remove()
                val holes = when (before.stage) {
                    OriginWorkshopGameStage.DRILLING -> 1
                    OriginWorkshopGameStage.DRILLING_SECOND -> 2
                    else -> 3
                }
                active.workpiece = spawnProp(active, player, originWorkshopBoardPieces(holes))
                val base = target(active, OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL)
                settle(active, active.workpiece!!, base.copy(x = base.x - 0.23 * (holes - 1)))
            }
            OriginWorkshopGameStage.UPHOLSTER_PRESSING -> {
                active.workpiece?.remove()
                // Padded cover with contrasting stitched borders, visibly different from a flat fabric sheet.
                val size = OriginWorkshopGamePartSize(0.64f, 0.12f, 0.46f)
                val pieces = mutableListOf(OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(0.0, 0.0, 0.0), size, Material.RED_WOOL))
                for (z in listOf(-0.20, 0.20)) pieces += OriginWorkshopWorkpiecePiece(
                    OriginWorkshopPoint(0.0, 0.064, z), OriginWorkshopGamePartSize(0.56f, 0.01f, 0.012f), Material.WHITE_WOOL)
                active.workpiece = spawnProp(active, player, pieces)
                settle(active, active.workpiece!!, target(active, OriginWorkshopGameStage.UPHOLSTER_PICK_COVER))
            }
            OriginWorkshopGameStage.CLAMPING_RIGHT,
            OriginWorkshopGameStage.ASSEMBLER_HAMMERING,
            OriginWorkshopGameStage.FINISHER_DRYING -> finishProduct(active)
            else -> Unit
        }
        active.progress = after
        active.lastProgressAt = now
        showStage(active, player, "STAGE")
    }

    private fun finishProduct(active: Session) {
        val chair = CustomStack.getInstance(active.recipe.productId)?.itemStack?.clone()
        if (chair == null) {
            ARC.instance.logger.warning("ORIGIN_WORKSHOP_GAME phase=RESULT_MISSING player=" + active.playerId + " session=" + active.id)
        } else {
            val player = Bukkit.getPlayer(active.playerId)
            active.workpiece?.remove()
            active.workpiece = null
            val at = OriginWorkshopTablesModule.pointAt(tableId, active.recipe.resultAnchor)
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
                    OriginWorkshopTablesModule.setCraftAssemblyFixtureVisible(active.tableId, false)
                }
            }
        }
        active.leftLeg?.remove()
        active.leftLeg = null
        active.rightLeg?.remove()
        active.rightLeg = null
    }

    private fun target(active: Session, stage: OriginWorkshopGameStage): OriginWorkshopPoint =
        active.recipe.interactions[stage]?.target ?: active.recipe.timedStages[stage]?.target ?: active.recipe.resultAnchor

    private fun control(active: Session, stage: OriginWorkshopGameStage): String? =
        active.recipe.interactions[stage]?.control ?: active.recipe.timedStages[stage]?.control

    private fun showStage(active: Session, player: Player, phase: String) {
        val stage = active.progress.stage
        active.targetMarker?.remove()
        active.targetMarker = null
        val action = active.recipe.interactions[stage]?.action
        val needsMarker = action in PLACEMENT_ACTIONS
        val nextControl = if (needsMarker || currentPickableDisplay(active) != null) null else control(active, stage)
        if (needsMarker) {
            val point = target(active, stage)
            val coating = action in COATING_ACTIONS
            val geometry = originWorkshopPlacementMarker(coating)
            active.targetMarker = spawnProp(active, player, geometry).also {
                settle(active, it, point)
                it.glow(woodGlow)
            }
        }
        if (active.highlightedControl != nextControl || active.hoveredControl) {
            OriginWorkshopTablesModule.highlightCraftControl(tableId, nextControl, hovered = false, viewer = player)
            active.highlightedControl = nextControl
            active.hoveredControl = false
        }
        active.workpiece?.glow(null)
        currentPickableDisplay(active)?.let { part ->
            part.glow(woodGlow)
        }
        refreshGuidance(active, player, nowTick(), force = true)
        log(active, phase, stage)
    }

    private fun currentPickableDisplay(active: Session): Prop? = when (active.progress.stage) {
        OriginWorkshopGameStage.SAW_REPOSITION,
        OriginWorkshopGameStage.DRILL_ALIGN_CENTER,
        OriginWorkshopGameStage.DRILL_ALIGN_LAST,
        OriginWorkshopGameStage.PICK_SAWN_BOARD,
        OriginWorkshopGameStage.PICK_DRILLED_BOARD,
        OriginWorkshopGameStage.UPHOLSTER_PICK_COVER,
        OriginWorkshopGameStage.ASSEMBLER_PICK_VISED -> active.workpiece
        else -> null
    }

    private fun refreshGuidance(active: Session, player: Player, now: Long, force: Boolean = false) {
        if (!force && active.lastGuideTick != Long.MIN_VALUE && now - active.lastGuideTick < GUIDANCE_REFRESH_TICKS) return
        active.lastGuideTick = now
        val stage = active.progress.stage
        val timed = timedProgress(active, now)
        val stepLabel = "Этап ${minOf(stage.step, active.recipe.totalSteps)}/${active.recipe.totalSteps} · ${stage.instruction}"
        val taskLabel = if (timed == null) {
            "$stepLabel · ${if (active.recipe.interactions[stage] == null) "Станок работает" else "ЛКМ"} · Shift — отмена"
        } else {
            "$stepLabel · ${floor(timed * 100.0).toInt()}% · Shift — отмена"
        }
        player.sendActionBar(Component.text(taskLabel))
        label?.takeIf(PacketTextDisplay::isValid)?.let { currentLabel ->
            val at = OriginWorkshopTablesModule.pointAt(tableId, labelAnchor(active, stage))
            if (at != null && at.world.uid == active.worldId) currentLabel.teleport(at)
            val shortLabel = buildString {
                append(minOf(stage.step, active.recipe.totalSteps)).append('/').append(active.recipe.totalSteps)
                append(" · ").append(stage.instruction)
                if (timed != null) append(" · ").append(floor(timed * 100.0).toInt()).append('%')
                else if (active.recipe.interactions[stage] != null) append(" · ЛКМ")
            }
            currentLabel.text(Component.text(shortLabel))
        }
    }

    private fun timedProgress(active: Session, now: Long): Double? {
        val duration = active.recipe.timedStages[active.progress.stage]?.durationTicks ?: return null
        return ((now - active.progress.stageStartedAt).coerceAtLeast(0L).toDouble() / duration).coerceIn(0.0, 1.0)
    }

    private fun animateMachine(active: Session, now: Long) {
        val operation = active.recipe.timedStages[active.progress.stage] ?: return
        val machine = operation.machine ?: return
        val progress = timedProgress(active, now) ?: return
        val machineProgress = when (active.progress.stage) {
            OriginWorkshopGameStage.FINISHER_WITHDRAWING -> progress * 0.25
            OriginWorkshopGameStage.FINISHER_DRYING -> 0.75 + progress * 0.25
            else -> progress
        }
        OriginWorkshopTablesModule.animateCraftMachine(tableId, machine, machineProgress)
        if (machine == "saw") animateSawWorkpiece(active, progress)
    }

    private fun animateSawWorkpiece(active: Session, progress: Double) {
        val board = active.workpiece ?: return
        val intake = OriginWorkshopTablesModule.pointAt(tableId, target(active, OriginWorkshopGameStage.CARRY_RAW_TO_SAW)) ?: return
        val outfeed = OriginWorkshopTablesModule.pointAt(tableId, target(active, OriginWorkshopGameStage.PICK_SAWN_BOARD)) ?: return
        if (intake.world.uid != active.worldId || outfeed.world.uid != active.worldId) return
        val center = intake.clone().add(outfeed.toVector().subtract(intake.toVector()).multiply(progress))
        val rotation = boardRotation()
        if (active.progress.stage == OriginWorkshopGameStage.SAWING_SECOND) rotation.rotateY(Math.PI.toFloat())
        moveProp(board, center, rotation, carrying = false)
    }

    private fun spawnProp(active: Session, player: Player, geometry: List<OriginWorkshopWorkpiecePiece>): Prop {
        val displays = checkNotNull(owner)
        return Prop(geometry.map { piece ->
            val display = spawnBlock(displays, piece.material, player.location, piece.size).apply {
                isVisibleByDefault = false
                showTo(player)
            }
            active.parts += display
            PropPiece(display, piece)
        })
    }

    private fun settle(active: Session, item: Prop, point: OriginWorkshopPoint): Location? {
        val center = OriginWorkshopTablesModule.pointAt(tableId, point) ?: return null
        moveProp(item, center, boardRotation(), carrying = false)
        item.glow(null)
        return center
    }

    private fun carry(active: Session, player: Player, location: Location = player.location) {
        val pose = originWorkshopShoulderPose(location.yaw.toDouble(), player.isSneaking)
        val center = location.clone().add(pose.center.x, pose.center.y, pose.center.z).apply { yaw = 0f; pitch = 0f }
        active.carried?.let { item ->
            moveProp(item, center, Quaternionf().rotationY(pose.rotationRadians.toFloat()), carrying = true)
            item.glow(null)
        }
        active.brush?.teleport(center)
    }

    private fun moveProp(item: Prop, center: Location, rotation: Quaternionf, carrying: Boolean) {
        item.pieces.forEach { piece ->
            val geometry = piece.geometry
            val offset = rotation.transform(Vector3f(geometry.center.x.toFloat(), geometry.center.y.toFloat(), geometry.center.z.toFloat()))
            val at = center.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()).apply { yaw = 0f; pitch = 0f }
            piece.display.interpolationDuration = if (carrying) 0 else 2
            piece.display.teleportDuration = if (carrying) 0 else 1
            piece.display.transformation = Transformation(Vector3f(), rotation, Vector3f(geometry.size.x, geometry.size.y, geometry.size.z), Quaternionf())
            moveBlockCenter(piece.display, at, geometry.size, rotation)
        }
    }

    private fun moveBlockCenter(item: PacketBlockDisplay, center: Location, size: OriginWorkshopGamePartSize, rotation: Quaternionf = boardRotation()) {
        val half = rotation.transform(Vector3f(size.x / 2f, size.y / 2f, size.z / 2f))
        item.teleport(center.clone().subtract(half.x.toDouble(), half.y.toDouble(), half.z.toDouble()).apply { yaw = 0f; pitch = 0f })
    }

    /** Named controls and pickable materials use the exact rendered cubes, not a nearby control anchor. */
    private fun activeTargetDistance(active: Session, player: Player): Double? {
        if (active.targetMarker != null) {
            val at = OriginWorkshopTablesModule.pointAt(tableId, target(active, active.progress.stage)) ?: return null
            return targetDistance(player, at)
        }
        val displays = currentPickableDisplay(active)?.pieces?.map { it.display }
            ?: active.highlightedControl?.let { OriginWorkshopTablesModule.craftControlDisplaysFor(tableId, it) }
            ?: return OriginWorkshopTablesModule.pointAt(tableId, target(active, active.progress.stage))?.let { targetDistance(player, it) }
        val eye = player.eyeLocation
        val direction = eye.direction.normalize()
        val reach = displays.filterIsInstance<PacketBlockDisplay>().mapNotNull { display ->
            val location = display.location
            if (location.world.uid != player.world.uid) return@mapNotNull null
            val t = display.transformation
            val matrix = Matrix4f().translation(location.x.toFloat(), location.y.toFloat(), location.z.toFloat())
                .rotateY(Math.toRadians(-location.yaw.toDouble()).toFloat())
                .rotateX(Math.toRadians(location.pitch.toDouble()).toFloat())
                .translate(t.translation).rotate(t.leftRotation).scale(t.scale).rotate(t.rightRotation)
            originWorkshopRayTransformedCube(OriginWorkshopVec3(eye.x, eye.y, eye.z),
                OriginWorkshopVec3(direction.x, direction.y, direction.z), matrix, REACH)
        }.minOrNull() ?: return null
        return reach.takeUnless {
            player.world.rayTraceBlocks(eye, direction, reach + BLOCK_EPSILON, FluidCollisionMode.NEVER, true) != null
        }
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
        val station = OriginWorkshopTablesModule.pointAt(tableId, start) ?: return null
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
        val dimensions = OriginWorkshopTablesModule.dimensionsFor(tableId) ?: return null
        val localBounds = originWorkshopStartInteractionAabb(dimensions)
        val localCorners = buildList {
            for (x in listOf(localBounds.minX, localBounds.maxX))
                for (y in listOf(localBounds.minY, localBounds.maxY))
                    for (z in listOf(localBounds.minZ, localBounds.maxZ)) {
                add(OriginWorkshopPoint(x, y, z))
            }
        }
        val corners = localCorners.map { OriginWorkshopTablesModule.pointAt(tableId, it) ?: return null }
        return OriginWorkshopAabb(
            minX = corners.minOf { it.x }, minY = corners.minOf { it.y }, minZ = corners.minOf { it.z },
            maxX = corners.maxOf { it.x }, maxY = corners.maxOf { it.y }, maxZ = corners.maxOf { it.z },
        )
    }

    private fun isWorkshopInteraction(player: Player): Boolean {
        if (startInteractionDistance(player) != null) return true
        val recipe = session?.recipe ?: return false
        val anchors = recipe.interactions.values.map { it.target } + recipe.timedStages.values.map { it.target }
        return anchors.any { anchor ->
            OriginWorkshopTablesModule.pointAt(tableId, anchor)?.let { targetDistance(player, it) } != null
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
        val origin = OriginWorkshopTablesModule.pointAt(tableId, OriginWorkshopPoint(0.0, 0.0, 0.0)) ?: return Quaternionf()
        val localX = OriginWorkshopTablesModule.pointAt(tableId, OriginWorkshopPoint(1.0, 0.0, 0.0)) ?: return Quaternionf()
        val dx = localX.x - origin.x
        val dz = localX.z - origin.z
        return Quaternionf().rotationY(atan2(-dz, dx).toFloat())
    }

    private fun finishSession(active: Session) {
        if (active !== session) return
        session = null
        OriginFurnitureWorkshopModule.releasePlayerTable(active.tableId, active.playerId)
        active.tasks.close()
        active.parts.forEach(PacketDisplay::remove)
        active.parts.clear()
        active.workpiece = null
        active.carried = null
        active.leftLeg = null
        active.rightLeg = null
        OriginWorkshopTablesModule.resetCraft(active.tableId)
        OriginWorkshopTablesModule.resetWork(active.tableId)
        showStart()
    }

    private fun cancel(active: Session, reason: String, notify: Boolean) {
        if (active !== session) return
        val player = Bukkit.getPlayer(active.playerId)
        log(active, "CANCEL", active.progress.stage, reason)
        session = null
        OriginFurnitureWorkshopModule.releasePlayerTable(active.tableId, active.playerId)
        active.progress = OriginWorkshopGameProgress(OriginWorkshopGameStage.CLOSED, nowTick())
        active.tasks.close()
        active.parts.forEach(PacketDisplay::remove)
        active.parts.clear()
        active.workpiece = null
        active.carried = null
        active.leftLeg = null
        active.rightLeg = null
        OriginWorkshopTablesModule.resetCraft(active.tableId)
        OriginWorkshopTablesModule.resetWork(active.tableId)
        showStart()
        if (notify && player?.isOnline == true) {
            player.sendActionBar(Component.text("Сборка прервана. Заготовки остались на складе."))
        }
    }

    private fun showStart() {
        idleTable?.let { OriginWorkshopTablesModule.resetCraft(it) }
        idleTable = null
        occupancyLabel?.remove()
        occupancyLabel = null
        label?.remove()
        label = null
        reconcileIdleStation()
    }

    private fun reconcileIdleStation() {
        if (session != null || owner == null) return
        val next = OriginFurnitureWorkshopModule.availablePlayerTable()
        if (next == idleTable) return
        idleTable?.let { OriginWorkshopTablesModule.resetCraft(it) }
        label?.remove()
        label = null
        idleHoverers.clear()
        idleTable = next
        if (next == null) return
        val product = OriginFurnitureWorkshopModule.productIdFor(next) ?: return
        val recipe = OriginWorkshopTablesModule.recipeFor(next, product, settings?.rules ?: return) ?: return
        OriginWorkshopTablesModule.highlightCraftControl(next, "start")
        label = OriginWorkshopTablesModule.pointAt(next, frontGuidanceAnchor(start))?.let {
            spawnLabel(it, recipe.title + " · ЛКМ", visibleByDefault = true)
        }
    }

    private fun frontGuidanceAnchor(target: OriginWorkshopPoint): OriginWorkshopPoint =
        OriginWorkshopPoint(target.x.coerceIn(-1.5, 1.5), 2.25, -1.5)

    private fun labelAnchor(active: Session, stage: OriginWorkshopGameStage): OriginWorkshopPoint {
        val target = target(active, stage)
        return if (active.recipe.interactions[stage]?.control == "stock")
            OriginWorkshopPoint(target.x, 1.45, target.z - 1.1)
        else frontGuidanceAnchor(target)
    }

    private fun setOccupancyLabel(player: Player) {
        occupancyLabel?.remove()
        val local = OriginWorkshopPoint(0.0, 2.65, -1.5)
        occupancyLabel = OriginWorkshopTablesModule.pointAt(tableId, local)?.let {
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
        val handler = active.rewardHandler
        val request = UUID.randomUUID()
        val generationAtRequest = generation
        val valid = {
            active === session && generationAtRequest == generation &&
                OriginFurnitureWorkshopModule.ownsPlayerTable(active.tableId, active.playerId) &&
                active.progress.stage == OriginWorkshopGameStage.REWARDING &&
                player.isOnline && player.world.uid == active.worldId &&
                (OriginWorkshopTablesModule.pointAt(tableId, start)?.takeIf { it.world.uid == active.worldId }
                    ?.let { player.location.distanceSquared(it) } ?: Double.POSITIVE_INFINITY) <= SESSION_RANGE_SQUARED
        }
        log(active, "REWARD_REQUEST", active.progress.stage)
        handler.complete(player, request, valid) { error ->
            if (active !== session || generationAtRequest != generation) return@complete
            if (error == null) {
                val next = if (player.hasPermission(WORKSHOP_COOLDOWN_BYPASS)) "Можно сразу начать новую сборку."
                    else "Новую сборку можно начать через 24 часа."
                player.sendMessage(
                    Component.text("\n  Мебель готова и добавлена в инвентарь.\n  $next\n"),
                )
                log(active, "REWARD_DELIVERED", active.progress.stage)
            } else {
                player.sendMessage(Component.text("\n  " + error + "\n  Если мебель не появилась, сообщи администрации.\n"))
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
        rewards.values.forEach { it.close() }
        rewards.clear()
        occupancyLabel?.remove()
        occupancyLabel = null
        label?.remove()
        label = null
        busyFeedbackNanos.clear()
        owner?.close()
        owner = null
        idleTable?.let { OriginWorkshopTablesModule.resetCraft(it) }
        idleTable = null
    }

    private fun log(active: Session, phase: String, stage: OriginWorkshopGameStage, detail: String = "") {
        ARC.instance.logger.info(
            "ORIGIN_WORKSHOP_GAME phase=" + phase + " player=" + active.playerId + " session=" + active.id +
                " table=" + active.tableId + " stage=" + stage + if (detail.isEmpty()) "" else " detail=" + detail,
        )
    }

    private fun nowTick() = System.nanoTime() / TICK_NANOS
}
