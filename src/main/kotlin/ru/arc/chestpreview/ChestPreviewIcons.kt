package ru.arc.chestpreview

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.api.InspectionHologramAnchor
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SceneTextStyle
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Chest content and lid placement stay local; Core owns keyed parts, motion and packet lifecycle. */
internal class ChestPreviewIcons(
    private val displays: PaperPacketDisplays,
    private val settings: ChestPreviewSettings,
) : AutoCloseable {
    private class ViewerState(
        var anchor: InspectionHologramAnchor,
        val sourceAnchor: InspectionHologramAnchor,
        var scale: Float,
        var itemCount: Int,
        val options: ChestPreviewSettings,
        val scene: PacketDisplayScene<String>,
        val audience: SceneAudience.Private,
    )

    private val viewers = mutableMapOf<UUID, ViewerState>()
    private var closed = false

    fun update(player: Player, frame: ChestPreviewFrame?, scale: Float, options: ChestPreviewSettings = settings) {
        if (closed) return
        val target = frame ?: run { clear(player); return }
        if (!options.enabled || target.anchor.worldId != player.world.uid) {
            clear(player)
            return
        }
        val selected = target.items.indices.filter { !target.items[it].type.isAir && target.items[it].amount > 0 }
            .take(options.maxItems)
        val items = selected.map { target.items[it].clone().apply { amount = 1 } }
        val counts = selected.map { (target.counts.getOrNull(it) ?: target.items[it].amount).coerceAtLeast(1) }
        if (items.isEmpty()) { clear(player); return }

        try {
            val effectiveScale = ChestPreviewIconGeometry.normalizeScale(scale)
            val previous = viewers[player.uniqueId]?.takeIf {
                it.sourceAnchor == target.anchor && it.options == options &&
                    it.itemCount == items.size && it.scale == effectiveScale
            }?.anchor
            val anchor = ChestPreviewPlacement.place(player, target, items.size, effectiveScale, options, previous)
            if (anchor == null) clear(player)
            else render(player, anchor, target.anchor, items, counts, effectiveScale, options)
        } catch (failure: Exception) {
            try { clear(player) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun clear(player: Player) = clear(player.uniqueId)

    private fun render(
        player: Player,
        anchor: InspectionHologramAnchor,
        sourceAnchor: InspectionHologramAnchor,
        items: List<ItemStack>,
        counts: List<Int>,
        scale: Float,
        options: ChestPreviewSettings,
    ) {
        val id = player.uniqueId
        if (viewers[id]?.let { it.sourceAnchor != sourceAnchor || it.options != options } == true) {
            // A different container has no authored travel path from the old one.
            clear(id)
        }
        val state = viewers.getOrPut(id) {
            ViewerState(anchor, sourceAnchor, scale, 0, options, PacketDisplayScene(displays), SceneAudience.Private(id))
        }
        val ticks = if (state.anchor != anchor &&
            !ChestPreviewPlacement.clearPath(player, state.anchor, anchor, items.size, scale, options)) 0
            else options.teleportTicks
        val at = Location(player.world, anchor.x, anchor.y, anchor.z)
        val style = SceneDisplayStyle(
            billboard = Display.Billboard.CENTER,
            brightness = Display.Brightness(options.brightness, options.brightness),
            viewRange = DISPLAY_VIEW_RANGE,
            // A camera-pitched assembly cannot use an upright culling box.
            displayWidth = 0f, displayHeight = 0f,
        )
        val countStyle = SceneTextStyle(
            lineWidth = TEXT_DISPLAY_LINE_WIDTH,
            background = Color.fromARGB(0, 0, 0, 0),
            shadowed = true,
        )
        val parts = mutableListOf<ScenePart<String>>()
        val offsets = ChestPreviewIconGeometry.offsets(items.size, scale, options)
        items.forEachIndexed { index, item ->
            val offset = offsets[index]
            val transform = Transformation(
                Vector3f(offset.x, offset.y, 0f), Quaternionf(),
                Vector3f(options.iconScale * scale, options.iconScale * scale, options.iconScale * scale * options.depthScale),
                ChestPreviewIconGeometry.guiFacingRotation(
                    options.itemTransform == ItemDisplay.ItemDisplayTransform.GUI && ChestPreviewIconGeometry.isBlockIcon(item),
                    options.blockPitch, options.blockYaw,
                ),
            )
            parts += ScenePart.Item("icon-" + index, ScenePose(at, transform), style, item, options.itemTransform)
            if (options.showCounts) {
                val countTransform = Transformation(
                    Vector3f(offset.x, offset.y + options.countOffsetY * scale, ChestPreviewIconGeometry.depth(scale, options) + .01f),
                    Quaternionf(), Vector3f(options.countScale * scale / .25f), Quaternionf(),
                )
                parts += ScenePart.Text(
                    "count-" + index, ScenePose(at, countTransform), style, Component.text("× " + counts[index]), countStyle,
                )
            }
        }
        if (options.backgroundOpacity > 0) {
            val bounds = ChestPreviewIconGeometry.panelBounds(items.size, scale, options)
            val scaleX = bounds.width / (ChestPreviewIconGeometry.BACKGROUND_WIDTH_PIXELS * ChestPreviewIconGeometry.BACKGROUND_PIXEL_SIZE)
            val scaleY = bounds.height / (ChestPreviewIconGeometry.BACKGROUND_HEIGHT_PIXELS * ChestPreviewIconGeometry.BACKGROUND_PIXEL_SIZE)
            val transform = Transformation(
                Vector3f(
                    -ChestPreviewIconGeometry.BACKGROUND_CENTER_X_PIXELS * ChestPreviewIconGeometry.BACKGROUND_PIXEL_SIZE * scaleX,
                    0f, -ChestPreviewIconGeometry.depth(scale, options),
                ),
                Quaternionf(), Vector3f(scaleX, scaleY, 1f), Quaternionf(),
            )
            parts += ScenePart.Text(
                "backdrop", ScenePose(at, transform), style, Component.text(" "),
                SceneTextStyle(
                    lineWidth = TEXT_DISPLAY_LINE_WIDTH,
                    background = Color.fromARGB((options.backgroundOpacity.coerceIn(0, 100) * 255 / 100.0).roundToInt(), 15, 23, 30),
                    opacity = 0.toByte(),
                ),
            )
        }
        state.scene.apply(SceneFrame(parts, state.audience, ticks))
        state.anchor = anchor
        state.itemCount = items.size
        state.scale = scale
    }

    private fun clear(playerId: UUID) {
        viewers.remove(playerId)?.scene?.close()
    }

    override fun close() {
        if (closed) return
        var failure: Exception? = null
        viewers.keys.toList().forEach {
            try { clear(it) } catch (cleanup: Exception) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        try { displays.close() } catch (cleanup: Exception) {
            if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
        }
        closed = failure == null
        failure?.let { throw it }
    }

    private companion object {
        const val TEXT_DISPLAY_LINE_WIDTH = 16_384
        const val DISPLAY_VIEW_RANGE = 0.5f
    }
}

/** Bottom-anchored, camera-facing offsets shared by rendering and offline visual previews. */
internal data class ChestPreviewIconOffset(val x: Float, val y: Float)

internal data class ChestPreviewPanelBounds(
    val width: Float,
    val height: Float,
)

/** One source of truth for icon geometry, the backdrop quad and offline preview exports. */
internal object ChestPreviewIconGeometry {
    const val COLUMNS = 3
    const val CELL_SPACING = 0.48f
    const val ICON_SCALE = 0.40f
    const val EDGE_PADDING = 0.08f
    // A small optical correction: authored block models read higher than flat item icons.
    const val ICON_VERTICAL_OFFSET = -0.04f
    const val BACKGROUND_WIDTH_PIXELS = 5f
    const val BACKGROUND_HEIGHT_PIXELS = 10f
    const val BACKGROUND_CENTER_X_PIXELS = 0.5f
    const val BACKGROUND_PIXEL_SIZE = 0.025f
    private const val MIN_SCALE = 0.50f
    private const val MAX_SCALE = 2.00f
    private const val DEFAULT_SCALE = 0.90f

    // A small correction for ordinary solid block models; preserve flat and custom item poses.
    fun isBlockIcon(item: ItemStack): Boolean = item.type.isBlock && item.type.isSolid &&
        item.itemMeta?.let { !it.hasItemModel() && !it.hasCustomModelData() } != false

    // Replace the vanilla block GUI pose with the chosen fixed panel-local angles.
    // Non-block items retain their authored GUI pose. Both cancel ItemDisplay's native Y half-turn.
    fun guiFacingRotation(blockIcon: Boolean, pitch: Float = 12f, yaw: Float = 20f): Quaternionf {
        val nativeCorrection = Quaternionf(0f, 1f, 0f, 0f)
        if (!blockIcon) return nativeCorrection
        val authored = Quaternionf().rotationXYZ(radians(30f), radians(225f), 0f)
        return Quaternionf().rotationXYZ(radians(pitch), radians(180f + yaw), 0f)
            .mul(authored.invert()).mul(nativeCorrection)
    }

    private fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

    fun offsets(itemCount: Int, scale: Float, options: ChestPreviewSettings = ChestPreviewSettings()): List<ChestPreviewIconOffset> {
        require(itemCount >= 0) { "Chest preview icon count cannot be negative" }
        if (itemCount == 0) return emptyList()

        val effectiveScale = normalizeScale(scale)
        val rows = ceil(itemCount / options.columns.toDouble()).toInt()
        val bottomRowCenter = bottomRowCenter(options) * effectiveScale
        return List(itemCount) { index ->
            val row = index / options.columns
            val columnsInRow = min(options.columns, itemCount - row * options.columns)
            val column = index % options.columns
            val x = ((column - (columnsInRow - 1) / 2.0) * options.cellSpacing * effectiveScale).toFloat()
            val y = bottomRowCenter + (rows - 1 - row) * options.cellSpacing * effectiveScale
            ChestPreviewIconOffset(x, y)
        }
    }

    fun panelBounds(itemCount: Int, scale: Float, options: ChestPreviewSettings = ChestPreviewSettings()): ChestPreviewPanelBounds {
        require(itemCount > 0) { "Chest preview panel needs at least one icon" }
        val effectiveScale = normalizeScale(scale)
        val columns = min(options.columns, itemCount)
        val rows = ceil(itemCount / options.columns.toDouble()).toInt()
        val contentWidth = maxOf(options.iconScale, if (options.showCounts) options.countScale * 3.5f else 0f)
        val width = ((columns - 1) * options.cellSpacing + contentWidth + EDGE_PADDING * 2) * effectiveScale
        val topExtent = maxOf(options.iconScale / 2f,
            if (options.showCounts) options.countOffsetY + options.countScale else 0f)
        val height = (bottomRowCenter(options) + (rows - 1) * options.cellSpacing + topExtent + EDGE_PADDING) * effectiveScale
        return ChestPreviewPanelBounds(width, height)
    }

    fun depth(scale: Float, options: ChestPreviewSettings): Float =
        0.9f * options.iconScale * scale * options.depthScale

    private fun bottomRowCenter(options: ChestPreviewSettings): Float = maxOf(
        EDGE_PADDING + options.iconScale / 2f + ICON_VERTICAL_OFFSET,
        if (options.showCounts) EDGE_PADDING - options.countOffsetY else 0f,
    )

    fun itemBounds(scale: Float): ChestPreviewPanelBounds {
        val extent = (ICON_SCALE + EDGE_PADDING * 2) * normalizeScale(scale)
        return ChestPreviewPanelBounds(extent, extent)
    }

    fun normalizeScale(scale: Float): Float =
        scale.takeIf(Float::isFinite)?.coerceIn(MIN_SCALE, MAX_SCALE) ?: DEFAULT_SCALE
}
