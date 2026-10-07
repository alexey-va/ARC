package ru.arc.landsui

import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A six-action, viewer-only panel for the held claim block. */
internal class ClaimLandMenu(
    private val plugin: Plugin,
    private val title: (String) -> Component,
    private val labels: Map<LandsUiPanelAction, Component>,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "claim-land-menu"),
) : AutoCloseable {
    private data class Viewer(
        val playerId: UUID,
        val worldId: UUID,
        var body: Location,
        val eyeOffsetY: Double,
        var center: Location,
        var yaw: Float,
        var distance: Double,
        var shift: ClaimLandMenuShift,
        val heading: PacketTextDisplay,
        val buttons: Map<LandsUiPanelAction, PacketTextDisplay>,
        var landId: String,
        var headingComponent: Component,
        var hovered: LandsUiPanelAction? = null,
        var lookingAt: Boolean = false,
    ) {
        val allDisplays: List<PacketTextDisplay> get() = listOf(heading) + buttons.values
    }

    private val viewers = mutableMapOf<UUID, Viewer>()
    private var closed = false

    init {
        require(labels.keys == LandsUiPanelAction.entries.toSet()) {
            "Claim land menu needs a localized label for each Lands action"
        }
    }

    /** Called while the player holds the native claim block on an eligible land. */
    fun show(player: Player, landId: String, landName: String) {
        check(!closed) { "Claim land menu is closed" }
        val playerId = player.uniqueId
        val worldId = player.world.uid
        var viewer = viewers[playerId]
        if (viewer != null && viewer.worldId != worldId) {
            hide(playerId)
            viewer = null
        }

        if (viewer == null) {
            val yaw = ClaimLandMenuGeometry.panelYaw(player.eyeLocation.yaw)
            val placement = findPlacement(player, yaw, null, null, player.eyeLocation) ?: return
            viewer = createViewer(player, landId, title(landName), yaw, placement)
            viewers[playerId] = viewer
        } else {
            val body = bodyPosition(player.location)
            val eye = ClaimLandMenuGeometry.stableEye(body, viewer.eyeOffsetY)
            val moved = ClaimLandMenuGeometry.bodyMoved(viewer.body, body)
            val nextYaw = if (moved) ClaimLandMenuGeometry.panelYaw(player.location.yaw) else viewer.yaw
            if (moved || !placementSafe(player, viewer.center, viewer.yaw, eye)) {
                val placement = findPlacement(player, nextYaw, viewer.shift, viewer.distance, eye)
                if (placement == null) {
                    hide(playerId)
                    return
                }
                moveViewer(player, viewer, placement)
                viewer.yaw = nextYaw
                viewer.center = placement.center
                viewer.distance = placement.distance
                viewer.shift = placement.shift
            }
            if (moved) viewer.body = body
            viewer.landId = landId
            val newTitle = title(landName)
            if (viewer.headingComponent != newTitle) {
                viewer.headingComponent = newTitle
                viewer.heading.text(newTitle)
            }
            labels.forEach { (action, component) ->
                val display = viewer.buttons.getValue(action)
                if (display.text() != component) display.text(component)
            }
        }

        viewer.lookingAt = ClaimLandMenuGeometry.gazeIntersectsPanel(
            player.eyeLocation,
            player.eyeLocation.direction,
            viewer.center,
            viewer.yaw,
            if (viewer.lookingAt) GAZE_RESTORE_MARGIN_DEGREES else GAZE_ENTER_MARGIN_DEGREES,
        )
        val hover = targetAt(player, validatePlacement = false)
        if (viewer.hovered != hover) {
            viewer.buttons.forEach { (action, display) ->
                val highlighted = action == hover
                display.backgroundColor = if (highlighted) HOVER_BACKGROUND else PANEL_BACKGROUND
                display.isGlowing = highlighted
                display.glowColorOverride = if (highlighted) HOVER_GLOW else null
            }
            viewer.hovered = hover
        }
    }

    fun hide(playerId: UUID) {
        viewers.remove(playerId)?.allDisplays?.forEach(PacketTextDisplay::remove)
    }

    fun contains(playerId: UUID): Boolean = viewers[playerId] != null

    /** Builder-style whole-panel gaze state for suppressing the older claim guide. */
    fun isLookingAt(player: Player): Boolean = viewers[player.uniqueId]?.let {
        it.worldId == player.world.uid && it.lookingAt
    } ?: false

    /** The caller owns click gestures and should invoke this again at click time. */
    fun target(player: Player): LandsUiPanelAction? = targetAt(player, validatePlacement = true)

    private fun targetAt(player: Player, validatePlacement: Boolean): LandsUiPanelAction? {
        val viewer = viewers[player.uniqueId] ?: return null
        if (viewer.worldId != player.world.uid) return null
        if (validatePlacement && !placementSafe(player, viewer.center, viewer.yaw)) return null
        val eye = player.eyeLocation
        val hit = ClaimLandMenuGeometry.rayPlaneHit(eye, eye.direction, viewer.center, viewer.yaw) ?: return null
        val action = ClaimLandMenuGeometry.actionAt(hit.x, hit.y) ?: return null
        if (hit.distance > REACH || !rayClear(player, eye, pointOnRay(eye, eye.direction, hit.distance))) return null
        return action
    }

    fun landId(playerId: UUID): String? = viewers[playerId]?.landId

    override fun close() {
        if (closed) return
        viewers.keys.toList().forEach(::hide)
        displays.close()
        closed = true
    }

    private data class Placement(
        val center: Location,
        val distance: Double,
        val shift: ClaimLandMenuShift,
        val yaw: Float,
    )

    private fun findPlacement(
        player: Player,
        yaw: Float,
        previousShift: ClaimLandMenuShift?,
        previousDistance: Double?,
        eye: Location,
    ): Placement? {
        fun safeAt(distance: Double, shift: ClaimLandMenuShift, clearance: Double = 0.0): Boolean =
            placementSafe(player, ClaimLandMenuGeometry.center(eye, yaw, distance, shift), yaw, eye, clearance)
        fun at(distance: Double, shift: ClaimLandMenuShift) =
            ClaimLandMenuGeometry.center(eye, yaw, distance, shift).let { Placement(it, distance, shift, yaw) }

        val preferredDistance = previousDistance?.coerceAtMost(ClaimLandMenuGeometry.MAX_DISTANCE)
            ?: ClaimLandMenuGeometry.MAX_DISTANCE
        val localShift = ClaimLandMenuGeometry.chooseShift(previousShift) { candidate, clearance ->
            safeAt(preferredDistance, candidate, clearance)
        }
        if (localShift != null) {
            val distance = ClaimLandMenuGeometry.stableDistance(
                ClaimLandMenuGeometry.MAX_DISTANCE,
                previousDistance,
            ) { safeAt(it, localShift) }
            if (distance != null) return at(distance, localShift)
        }

        // Once the bounded local escape fails, pull back along the center ray without repeating that search.
        val direction = ClaimLandMenuGeometry.forward(yaw)
        val rayEnd = eye.clone().add(direction.clone().multiply(ClaimLandMenuGeometry.MAX_DISTANCE))
        if (!knownRayChunks(player, eye, rayEnd)) return null
        val ray = player.world.rayTraceBlocks(
            eye,
            direction,
            ClaimLandMenuGeometry.MAX_DISTANCE,
            FluidCollisionMode.NEVER,
            true,
        )
        val rayLimit = ray?.hitPosition?.distance(eye.toVector())
            ?.minus(RAY_CLEARANCE)
            ?.coerceAtLeast(ClaimLandMenuGeometry.MIN_DISTANCE)
            ?.coerceAtMost(ClaimLandMenuGeometry.MAX_DISTANCE)
            ?: ClaimLandMenuGeometry.MAX_DISTANCE
        val centered = ClaimLandMenuShift()
        val fallbackDistance = ClaimLandMenuGeometry.firstSafeCenterDistance(rayLimit) {
            safeAt(it, centered)
        } ?: return null
        return at(fallbackDistance, centered)
    }

    private fun createViewer(
        player: Player,
        landId: String,
        headingText: Component,
        yaw: Float,
        placement: Placement,
    ): Viewer {
        val made = mutableListOf<PacketTextDisplay>()
        try {
            val headingLocation = ClaimLandMenuGeometry.labelLocation(
                placement.center, yaw, ClaimLandMenuGeometry.headingRect,
            )
            val heading = newDisplay(player, headingLocation, yaw, headingText, title = true).also(made::add)
            val buttons = buildMap {
                LandsUiPanelAction.entries.forEach { action ->
                    val rect = ClaimLandMenuGeometry.rects.getValue(action)
                    val location = ClaimLandMenuGeometry.labelLocation(placement.center, yaw, rect)
                    put(action, newDisplay(player, location, yaw, labels.getValue(action), title = false).also(made::add))
                }
            }
            return Viewer(
                player.uniqueId,
                player.world.uid,
                bodyPosition(player.location),
                player.eyeLocation.y - player.location.y,
                placement.center.clone().apply { this.yaw = yaw; pitch = 0f },
                yaw,
                placement.distance,
                placement.shift,
                heading,
                buttons,
                landId,
                headingText,
            )
        } catch (failure: Throwable) {
            made.forEach { runCatching(it::remove) }
            throw failure
        }
    }

    private fun moveViewer(player: Player, viewer: Viewer, placement: Placement) {
        val nextCenter = placement.center.clone().apply { yaw = placement.yaw; pitch = 0f }
        val swept = ClaimLandMenuGeometry.sweptBounds(viewer.center, viewer.yaw, nextCenter, placement.yaw)
        val duration = if (spaceClear(player, swept)) INTERPOLATION_TICKS else 0
        viewer.allDisplays.forEach { display ->
            val rect = when (display) {
                viewer.heading -> ClaimLandMenuGeometry.headingRect
                else -> viewer.buttons.entries.first { it.value === display }.key.let(ClaimLandMenuGeometry.rects::getValue)
            }
            val at = ClaimLandMenuGeometry.labelLocation(nextCenter, placement.yaw, rect)
            display.teleportDuration = duration
            display.teleport(at)
        }
    }

    private fun newDisplay(
        player: Player,
        location: Location,
        yaw: Float,
        component: Component,
        title: Boolean,
    ): PacketTextDisplay = displays.spawnText(
        ClaimLandMenuGeometry.textLocation(location, yaw, 0.0, 0.0),
        component,
    ).apply {
        isVisibleByDefault = false
        billboard = Display.Billboard.FIXED
        brightness = Display.Brightness(15, 15)
        backgroundColor = PANEL_BACKGROUND
        isShadowed = true
        isSeeThrough = false
        isDefaultBackground = false
        alignment = TextDisplay.TextAlignment.CENTER
        lineWidth = if (title) ClaimLandMenuGeometry.TITLE_LINE_WIDTH else ClaimLandMenuGeometry.BUTTON_LINE_WIDTH
        displayWidth = if (title) ClaimLandMenuGeometry.TITLE_WIDTH.toFloat() else ClaimLandMenuGeometry.BUTTON_WIDTH.toFloat()
        displayHeight = if (title) ClaimLandMenuGeometry.TITLE_DISPLAY_HEIGHT else ClaimLandMenuGeometry.BUTTON_HEIGHT.toFloat()
        textOpacity = 255.toByte()
        viewRange = DISPLAY_VIEW_RANGE
        interpolationDelay = -1
        interpolationDuration = INTERPOLATION_TICKS
        teleportDuration = INTERPOLATION_TICKS
        transformation = Transformation(
            Vector3f(), Quaternionf(),
            Vector3f(if (title) ClaimLandMenuGeometry.TITLE_SCALE else ClaimLandMenuGeometry.BUTTON_SCALE),
            Quaternionf(),
        )
        showTo(player)
    }

    private fun placementSafe(player: Player, center: Location, yaw: Float): Boolean {
        val eye = player.eyeLocation
        return placementSafe(player, center, yaw, eye)
    }

    private fun placementSafe(
        player: Player,
        center: Location,
        yaw: Float,
        eye: Location,
        clearance: Double = 0.0,
    ): Boolean {
        if (!spaceClear(player, ClaimLandMenuGeometry.bounds(center, yaw, clearance)) { block ->
                ClaimLandMenuGeometry.overlapsPanel(center, yaw, block, clearance)
            }
        ) return false
        if (!ClaimLandMenuGeometry.allRectanglesReachable(eye, center, yaw, REACH)) return false
        return ClaimLandMenuGeometry.fullPanelRects.all { rect ->
            val halfWidth = rect.width / 2.0
            val halfHeight = rect.height / 2.0
            listOf(-halfWidth, halfWidth).all { x -> listOf(-halfHeight, halfHeight).all { y ->
                rayClear(
                    player,
                    eye,
                    ClaimLandMenuGeometry.textLocation(center, yaw, rect.centerX + x, rect.centerY + y),
                )
            } }
        }
    }

    private fun spaceClear(player: Player, bounds: ClaimLandMenuBounds): Boolean {
        return spaceClear(player, bounds, bounds::overlaps)
    }

    private fun spaceClear(
        player: Player,
        bounds: ClaimLandMenuBounds,
        overlaps: (ClaimLandMenuBounds) -> Boolean,
    ): Boolean {
        val world = player.world
        if (bounds.minY < world.minHeight || bounds.maxY >= world.maxHeight) return false
        val minBlockX = floor(bounds.minX + EPSILON).toInt()
        val maxBlockX = ceil(bounds.maxX - EPSILON).toInt() - 1
        val minBlockY = floor(bounds.minY + EPSILON).toInt()
        val maxBlockY = ceil(bounds.maxY - EPSILON).toInt() - 1
        val minBlockZ = floor(bounds.minZ + EPSILON).toInt()
        val maxBlockZ = ceil(bounds.maxZ - EPSILON).toInt() - 1
        val minChunkX = minBlockX shr 4
        val maxChunkX = maxBlockX shr 4
        val minChunkZ = minBlockZ shr 4
        val maxChunkZ = maxBlockZ shr 4
        for (chunkX in minChunkX..maxChunkX) for (chunkZ in minChunkZ..maxChunkZ) {
            if (!knownChunk(player, world, chunkX, chunkZ)) return false
        }
        for (x in minBlockX..maxBlockX) for (y in minBlockY..maxBlockY) for (z in minBlockZ..maxBlockZ) {
            val block = world.getBlockAt(x, y, z)
            if (blockOverlaps(block, x, y, z, overlaps)) return false
        }
        return true
    }

    private fun blockOverlaps(
        block: Block,
        x: Int,
        y: Int,
        z: Int,
        overlaps: (ClaimLandMenuBounds) -> Boolean,
    ): Boolean =
        block.collisionShape.boundingBoxes.any { shape ->
            overlaps(
                ClaimLandMenuBounds(
                    x + shape.minX, y + shape.minY, z + shape.minZ,
                    x + shape.maxX, y + shape.maxY, z + shape.maxZ,
                ),
            )
        }

    private fun rayClear(player: Player, from: Location, to: Location): Boolean {
        val world = from.world ?: return false
        if (world.uid != to.world?.uid || world.uid != player.world.uid) return false
        val delta = to.toVector().subtract(from.toVector())
        val distance = delta.length()
        if (!distance.isFinite() || distance <= EPSILON || distance > REACH + EPSILON) return false
        if (!knownRayChunks(player, from, to)) return false
        val hit = world.rayTraceBlocks(
            from,
            delta.normalize(),
            (distance - LOS_MARGIN).coerceAtLeast(0.0),
            FluidCollisionMode.NEVER,
            true,
        )
        return hit == null
    }

    private fun knownChunk(player: Player, world: World, x: Int, z: Int): Boolean =
        world.isChunkLoaded(x, z) && player.isChunkSent(Chunk.getChunkKey(x, z))

    private fun knownRayChunks(player: Player, from: Location, to: Location): Boolean {
        val world = from.world ?: return false
        for (x in (min(from.blockX, to.blockX) shr 4)..(max(from.blockX, to.blockX) shr 4)) {
            for (z in (min(from.blockZ, to.blockZ) shr 4)..(max(from.blockZ, to.blockZ) shr 4)) {
                if (!knownChunk(player, world, x, z)) return false
            }
        }
        return true
    }

    private fun bodyPosition(location: Location): Location = location.clone().apply { yaw = 0f; pitch = 0f }

    private fun pointOnRay(eye: Location, direction: Vector, distance: Double): Location =
        eye.clone().add(direction.clone().normalize().multiply(distance))

    companion object {
        private const val REACH = 4.0
        private const val INTERPOLATION_TICKS = 2
        private const val EPSILON = 1e-5
        private const val LOS_MARGIN = 0.025
        private const val DISPLAY_VIEW_RANGE = 0.15f
        private const val GAZE_ENTER_MARGIN_DEGREES = 8.0
        private const val GAZE_RESTORE_MARGIN_DEGREES = 13.0
        private const val RAY_CLEARANCE = 0.35
        private val PANEL_BACKGROUND = Color.fromARGB(190, 15, 23, 30)
        private val HOVER_BACKGROUND = Color.fromARGB(225, 76, 57, 24)
        private val HOVER_GLOW = Color.fromRGB(255, 204, 64)
    }
}

internal data class ClaimLandMenuShift(val side: Double = 0.0, val up: Double = 0.0)

internal data class ClaimLandMenuRect(
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val action: LandsUiPanelAction? = null,
)

internal data class ClaimLandMenuPlaneHit(val x: Double, val y: Double, val distance: Double)

internal data class ClaimLandMenuBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    fun overlaps(other: ClaimLandMenuBounds): Boolean =
        minX < other.maxX && maxX > other.minX &&
            minY < other.maxY && maxY > other.minY &&
            minZ < other.maxZ && maxZ > other.minZ
}

/** Small panel-specific geometry; no entities or shared display framework are needed. */
internal object ClaimLandMenuGeometry {
    const val PANEL_DEPTH = 0.08
    const val MAX_DISTANCE = 2.4
    const val MIN_DISTANCE = 0.35
    const val BUTTON_WIDTH = 1.50
    const val BUTTON_HEIGHT = 0.23
    const val BUTTON_SCALE = 0.80f
    const val COLUMN_SPACING = 1.55
    const val ROW_SPACING = 0.26
    const val TITLE_WIDTH = 3.0
    const val TITLE_HEIGHT = 0.24
    const val TITLE_GAP = 0.04
    const val TITLE_SCALE = 0.48f
    const val TITLE_DISPLAY_HEIGHT = 0.55f
    const val TITLE_LINE_WIDTH = 240
    const val BUTTON_LINE_WIDTH = 190
    private const val LABEL_Y_OFFSET = 0.025
    private const val SEARCH_STEP = 0.25
    private const val FALLBACK_STEP = 0.1
    private const val RECENTER_STEP = 0.06
    private const val RECENTER_PROBE = 0.18
    private const val RECENTER_CLEARANCE = 0.12
    private const val MAX_SHIFT = 1.25
    private const val EPSILON = 1e-8
    private val rowYs = listOf(ROW_SPACING, 0.0, -ROW_SPACING)
    val rects: Map<LandsUiPanelAction, ClaimLandMenuRect> = mapOf(
        LandsUiPanelAction.ADD_MEMBER to ClaimLandMenuRect(-COLUMN_SPACING / 2.0, rowYs[0], BUTTON_WIDTH, BUTTON_HEIGHT, LandsUiPanelAction.ADD_MEMBER),
        LandsUiPanelAction.MEMBERS to ClaimLandMenuRect(COLUMN_SPACING / 2.0, rowYs[0], BUTTON_WIDTH, BUTTON_HEIGHT, LandsUiPanelAction.MEMBERS),
        LandsUiPanelAction.RULES to ClaimLandMenuRect(-COLUMN_SPACING / 2.0, rowYs[1], BUTTON_WIDTH, BUTTON_HEIGHT, LandsUiPanelAction.RULES),
        LandsUiPanelAction.TERRITORY to ClaimLandMenuRect(COLUMN_SPACING / 2.0, rowYs[1], BUTTON_WIDTH, BUTTON_HEIGHT, LandsUiPanelAction.TERRITORY),
        LandsUiPanelAction.SETTINGS to ClaimLandMenuRect(-COLUMN_SPACING / 2.0, rowYs[2], BUTTON_WIDTH, BUTTON_HEIGHT, LandsUiPanelAction.SETTINGS),
        LandsUiPanelAction.OVERVIEW to ClaimLandMenuRect(COLUMN_SPACING / 2.0, rowYs[2], BUTTON_WIDTH, BUTTON_HEIGHT, LandsUiPanelAction.OVERVIEW),
    )
    private val titleCenterY = rowYs.first() + BUTTON_HEIGHT / 2.0 + TITLE_GAP + TITLE_HEIGHT / 2.0
    val headingRect = ClaimLandMenuRect(0.0, titleCenterY, TITLE_WIDTH, TITLE_HEIGHT)
    val fullPanelRects = listOf(headingRect) + rects.values
    val PANEL_WIDTH = max(TITLE_WIDTH, rects.values.maxOf { abs(it.centerX) + it.width / 2.0 } * 2.0)
    private val panelMinY = fullPanelRects.minOf { it.centerY - it.height / 2.0 }
    private val panelMaxY = fullPanelRects.maxOf { it.centerY + it.height / 2.0 }
    val PANEL_HEIGHT = panelMaxY - panelMinY
    private val panelContentCenterY = (panelMinY + panelMaxY) / 2.0

    /** Builder text faces the viewer with a 180-degree rotation from the camera yaw. */
    fun panelYaw(cameraYaw: Float): Float = ((cameraYaw + 180f) % 360f + 360f) % 360f

    fun stableEye(body: Location, eyeOffsetY: Double): Location = body.clone().apply { y += eyeOffsetY }

    fun forward(yaw: Float): Vector {
        val radians = Math.toRadians(yaw.toDouble())
        return Vector(sin(radians), 0.0, -cos(radians))
    }

    /** Yaw alone is intentionally ignored: a stationary head turn or crouch must not move the plane. */
    fun bodyMoved(previous: Location, current: Location, tolerance: Double = 0.03): Boolean {
        if (previous.world?.uid != current.world?.uid) return true
        val dx = previous.x - current.x
        val dy = previous.y - current.y
        val dz = previous.z - current.z
        return dx * dx + dy * dy + dz * dz > tolerance * tolerance
    }

    fun center(eye: Location, yaw: Float, distance: Double, shift: ClaimLandMenuShift): Location {
        val radians = Math.toRadians(yaw.toDouble())
        // `yaw` is the fixed display yaw (camera yaw + 180), matching Builder's basis.
        val forwardX = sin(radians)
        val forwardZ = -cos(radians)
        val rightX = cos(radians)
        val rightZ = sin(radians)
        return eye.clone().add(
            forwardX * distance + rightX * shift.side,
            -panelContentCenterY + shift.up,
            forwardZ * distance + rightZ * shift.side,
        ).apply { this.yaw = yaw; pitch = 0f }
    }

    /** Keep safe local escapes stable; move them inward only with measured clearance. */
    fun chooseShift(
        previous: ClaimLandMenuShift?,
        safe: (ClaimLandMenuShift, Double) -> Boolean,
    ): ClaimLandMenuShift? {
        previous?.takeIf(::validShift)?.takeIf { safe(it, 0.0) }?.let { retained ->
            val length = hypot(retained.side, retained.up)
            if (length <= EPSILON) return retained
            val next = towardsCenter(retained, RECENTER_STEP)
            val probe = towardsCenter(retained, RECENTER_PROBE)
            return if (safe(probe, RECENTER_CLEARANCE) && safe(next, 0.0)) next else retained
        }
        val center = ClaimLandMenuShift()
        if (safe(center, 0.0)) return center
        for (step in 1..(MAX_SHIFT / SEARCH_STEP).toInt()) {
            val amount = step * SEARCH_STEP
            for (candidate in listOf(
                ClaimLandMenuShift(up = amount),
                ClaimLandMenuShift(side = amount),
                ClaimLandMenuShift(side = -amount),
            )) {
                if (safe(candidate, 0.0)) return candidate
            }
        }
        return null
    }

    fun stableDistance(maximum: Double, previous: Double?, clear: (Double) -> Boolean): Double? {
        val retained = previous?.coerceAtMost(maximum)
        if (retained != null && clear(retained)) {
            val outward = min(maximum, retained + RECENTER_STEP)
            val probe = min(maximum, retained + RECENTER_PROBE)
            return if (outward > retained && clear(probe) && clear(outward)) outward else retained
        }
        return maximum.takeIf(clear)
    }

    fun firstSafeCenterDistance(maximum: Double, clear: (Double) -> Boolean): Double? {
        if (!maximum.isFinite()) return null
        var distance = maximum.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        while (true) {
            if (clear(distance)) return distance
            if (distance <= MIN_DISTANCE + EPSILON) return null
            distance = max(MIN_DISTANCE, distance - FALLBACK_STEP)
        }
    }

    private fun towardsCenter(shift: ClaimLandMenuShift, amount: Double): ClaimLandMenuShift {
        val length = hypot(shift.side, shift.up)
        if (length <= amount) return ClaimLandMenuShift()
        val factor = (length - amount) / length
        return ClaimLandMenuShift(shift.side * factor, shift.up * factor)
    }

    fun textLocation(center: Location, yaw: Float, offsetX: Double, offsetY: Double): Location {
        val radians = Math.toRadians(yaw.toDouble())
        return center.clone().add(cos(radians) * offsetX, offsetY, sin(radians) * offsetX)
            .apply { this.yaw = yaw; pitch = 0f }
    }

    /** TextDisplay's anchor is its bottom edge; match Builder's small baseline inset. */
    fun labelLocation(center: Location, yaw: Float, rect: ClaimLandMenuRect): Location =
        textLocation(center, yaw, rect.centerX, rect.centerY - rect.height / 2.0 + LABEL_Y_OFFSET)

    fun bounds(center: Location, yaw: Float, clearance: Double = 0.0): ClaimLandMenuBounds {
        require(clearance.isFinite() && clearance >= 0.0)
        val radians = Math.toRadians(yaw.toDouble())
        val halfWidth = PANEL_WIDTH / 2.0 + clearance
        val halfDepth = PANEL_DEPTH / 2.0 + clearance
        val xExtent = abs(cos(radians)) * halfWidth + abs(sin(radians)) * halfDepth
        val zExtent = abs(sin(radians)) * halfWidth + abs(cos(radians)) * halfDepth
        val panelCenterY = center.y + panelContentCenterY
        val halfHeight = PANEL_HEIGHT / 2.0 + clearance
        return ClaimLandMenuBounds(
            center.x - xExtent, panelCenterY - halfHeight, center.z - zExtent,
            center.x + xExtent, panelCenterY + halfHeight, center.z + zExtent,
        )
    }

    fun overlapsPanel(center: Location, yaw: Float, box: ClaimLandMenuBounds, clearance: Double = 0.0): Boolean {
        require(clearance.isFinite() && clearance >= 0.0)
        val radians = Math.toRadians(yaw.toDouble())
        val rightX = cos(radians)
        val rightZ = sin(radians)
        val normalX = sin(radians)
        val normalZ = -cos(radians)
        val dx = (box.minX + box.maxX) / 2.0 - center.x
        val dy = (box.minY + box.maxY) / 2.0 - (center.y + panelContentCenterY)
        val dz = (box.minZ + box.maxZ) / 2.0 - center.z
        val halfBoxX = (box.maxX - box.minX) / 2.0
        val halfBoxY = (box.maxY - box.minY) / 2.0
        val halfBoxZ = (box.maxZ - box.minZ) / 2.0
        fun separated(axisX: Double, axisY: Double, axisZ: Double): Boolean {
            val distance = abs(dx * axisX + dy * axisY + dz * axisZ)
            val panelRadius = (PANEL_WIDTH / 2.0 + clearance) * abs(rightX * axisX + rightZ * axisZ) +
                (PANEL_HEIGHT / 2.0 + clearance) * abs(axisY) +
                (PANEL_DEPTH / 2.0 + clearance) * abs(normalX * axisX + normalZ * axisZ)
            val boxRadius = halfBoxX * abs(axisX) + halfBoxY * abs(axisY) + halfBoxZ * abs(axisZ)
            return distance > panelRadius + boxRadius + EPSILON
        }
        // The panel and block shapes share the vertical axis, so these six face normals are complete.
        return !(separated(1.0, 0.0, 0.0) || separated(0.0, 1.0, 0.0) || separated(0.0, 0.0, 1.0) ||
            separated(rightX, 0.0, rightZ) || separated(0.0, 1.0, 0.0) ||
            separated(normalX, 0.0, normalZ))
    }

    fun sweptBounds(from: Location, fromYaw: Float, to: Location, toYaw: Float): ClaimLandMenuBounds {
        val first = bounds(from, fromYaw)
        val last = bounds(to, toYaw)
        val horizontalRadius = sqrt(PANEL_WIDTH * PANEL_WIDTH + PANEL_DEPTH * PANEL_DEPTH) / 2.0
        val angle = Math.toRadians(yawDelta(fromYaw, toYaw).toDouble())
        val bow = horizontalRadius * (1.0 - cos(angle / 2.0))
        return ClaimLandMenuBounds(
            min(first.minX, last.minX) - bow,
            min(first.minY, last.minY),
            min(first.minZ, last.minZ) - bow,
            max(first.maxX, last.maxX) + bow,
            max(first.maxY, last.maxY),
            max(first.maxZ, last.maxZ) + bow,
        )
    }

    fun yawDelta(first: Float, second: Float): Float = abs(((second - first + 540f) % 360f) - 180f)

    fun rayPlaneHit(
        eye: Location,
        direction: Vector,
        center: Location,
        yaw: Float,
        margin: Double = 0.0,
    ): ClaimLandMenuPlaneHit? {
        if (!margin.isFinite() || margin < 0.0) return null
        if (eye.world?.uid != center.world?.uid || !listOf(eye.x, eye.y, eye.z, center.x, center.y, center.z).all(Double::isFinite)) return null
        val radians = Math.toRadians(yaw.toDouble())
        val normalX = sin(radians)
        val normalZ = -cos(radians)
        val length = direction.length()
        if (!length.isFinite() || length <= EPSILON) return null
        val rayX = direction.x / length
        val rayY = direction.y / length
        val rayZ = direction.z / length
        val denominator = rayX * normalX + rayZ * normalZ
        if (denominator <= EPSILON) return null
        val distance = ((center.x - eye.x) * normalX + (center.z - eye.z) * normalZ) / denominator
        if (!distance.isFinite() || distance <= 0.0) return null
        val offsetX = (eye.x + rayX * distance - center.x) * cos(radians) +
            (eye.z + rayZ * distance - center.z) * sin(radians)
        val offsetY = eye.y + rayY * distance - center.y
        if (abs(offsetX) > PANEL_WIDTH / 2.0 + margin + EPSILON ||
            offsetY < panelMinY - margin - EPSILON || offsetY > panelMaxY + margin + EPSILON
        ) return null
        return ClaimLandMenuPlaneHit(offsetX, offsetY, distance)
    }

    fun gazeIntersectsPanel(
        eye: Location,
        direction: Vector,
        center: Location,
        yaw: Float,
        marginDegrees: Double,
    ): Boolean {
        if (!marginDegrees.isFinite() || marginDegrees < 0.0 || marginDegrees >= 90.0) return false
        val visualCenter = textLocation(center, yaw, 0.0, panelContentCenterY)
        val centerDistance = eye.distance(visualCenter)
        if (!centerDistance.isFinite() || centerDistance <= EPSILON) return false
        val angularPadding = centerDistance * tan(Math.toRadians(marginDegrees))
        val hit = rayPlaneHit(eye, direction, center, yaw, angularPadding) ?: return false
        val halfWidth = PANEL_WIDTH / 2.0 + angularPadding
        val halfHeight = PANEL_HEIGHT / 2.0 + angularPadding
        val rayLimit = centerDistance + sqrt(halfWidth * halfWidth + halfHeight * halfHeight) + EPSILON
        return hit.distance <= rayLimit
    }

    fun actionAt(x: Double, y: Double): LandsUiPanelAction? = rects.values.firstOrNull {
        abs(x - it.centerX) <= it.width / 2.0 && abs(y - it.centerY) <= it.height / 2.0
    }?.action

    fun allRectanglesReachable(eye: Location, center: Location, yaw: Float, reach: Double): Boolean =
        fullPanelRects.all { rect ->
            val halfWidth = rect.width / 2.0
            val halfHeight = rect.height / 2.0
            listOf(-halfWidth, halfWidth).all { x -> listOf(-halfHeight, halfHeight).all { y ->
                textLocation(center, yaw, rect.centerX + x, rect.centerY + y).distance(eye) <= reach + EPSILON
            } }
        }

    private fun validShift(shift: ClaimLandMenuShift): Boolean =
        shift.side.isFinite() && shift.up.isFinite() && abs(shift.side) <= MAX_SHIFT && shift.up in 0.0..MAX_SHIFT
}
