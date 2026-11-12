package ru.arc.chestpreview

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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

/** Chest content and a fixed world anchor stay local; Core owns keyed parts and packet lifecycle. */
internal class ChestPreviewIcons(
    private val displays: PaperPacketDisplays,
    private val settings: ChestPreviewSettings,
) : AutoCloseable {
    private class ViewerState(
        val anchor: InspectionHologramAnchor,
        val sourceAnchor: InspectionHologramAnchor,
        val scale: Float,
        val options: ChestPreviewSettings,
        val scene: PacketDisplayScene<String>,
        val audience: SceneAudience.Private,
        var items: List<ItemStack> = emptyList(),
        var counts: List<Int> = emptyList(),
        var visible: Boolean = false,
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
            val id = player.uniqueId
            if (viewers[id]?.let {
                it.sourceAnchor != target.anchor || it.options != options || it.scale != effectiveScale
            } == true) clear(id)
            val state = viewers.getOrPut(id) {
                ViewerState(
                    ChestPreviewPlacement.place(player.eyeLocation, target, effectiveScale, options),
                    target.anchor, effectiveScale, options, PacketDisplayScene(displays), SceneAudience.Private(id),
                )
            }
            if (!ChestPreviewPlacement.visible(player, state.anchor)) {
                // Keep the selected anchor while occluded; hiding must not start another placement search.
                state.scene.hide()
                state.visible = false
            } else render(player, state, items, counts)
        } catch (failure: Exception) {
            try { clear(player) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun clear(player: Player) = clear(player.uniqueId)

    private fun render(
        player: Player,
        state: ViewerState,
        items: List<ItemStack>,
        counts: List<Int>,
    ) {
        val anchor = state.anchor
        val scale = state.scale
        val options = state.options
        // Access and line of sight are still checked by every update. Only unchanged scene
        // construction is skipped; Core continues viewer/chunk/connection reconciliation.
        if (state.visible && state.items == items &&
            state.counts == counts && state.scene.isValid()) return
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
                    "count-" + index, ScenePose(at, countTransform), style,
                    Component.text(counts[index], NamedTextColor.WHITE), countStyle,
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
                    -bounds.height / 2f, -ChestPreviewIconGeometry.depth(scale, options),
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
        state.scene.apply(SceneFrame(parts, state.audience, 0))
        state.items = items
        state.counts = counts
        state.visible = true
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

/** Center-anchored, camera-facing offsets shared by rendering and offline visual previews. */
internal data class ChestPreviewIconOffset(val x: Float, val y: Float)

internal data class ChestPreviewPanelBounds(
    val width: Float,
    val height: Float,
)

/** One source of truth for icon geometry, the backdrop quad and offline preview exports. */
internal object ChestPreviewIconGeometry {
    const val COLUMNS = ChestPreviewSettings.DEFAULT_COLUMNS
    const val CELL_SPACING = ChestPreviewSettings.DEFAULT_CELL_SPACING
    const val ICON_SCALE = ChestPreviewSettings.DEFAULT_ICON_SCALE
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
        val columns = min(options.columns, itemCount)
        val bottomRowCenter = bottomRowCenter(options) * effectiveScale - panelBounds(itemCount, scale, options).height / 2f
        return List(itemCount) { index ->
            val row = index / options.columns
            val column = index % options.columns
            val x = ((column - (columns - 1) / 2.0) * options.cellSpacing * effectiveScale).toFloat()
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
