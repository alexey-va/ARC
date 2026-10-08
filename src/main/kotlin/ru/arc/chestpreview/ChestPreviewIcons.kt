package ru.arc.chestpreview

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.api.InspectionHologramAnchor
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Private, packet-only icon grids for chest inspection; closes its dedicated display owner on module shutdown. */
internal class ChestPreviewIcons(
    private val displays: PaperPacketDisplays,
    private val settings: ChestPreviewSettings,
) : AutoCloseable {
    private data class IconHandle(
        val display: PacketItemDisplay,
        var item: ItemStack,
        val label: PacketTextDisplay? = null,
        var count: Int = 0,
    )

    private data class ViewerState(
        var anchor: InspectionHologramAnchor,
        var sourceAnchor: InspectionHologramAnchor,
        var scale: Float,
        var itemCount: Int,
        val options: ChestPreviewSettings,
        val icons: MutableList<IconHandle> = mutableListOf(),
        var backdrop: PacketTextDisplay? = null,
    )

    private val viewers = mutableMapOf<UUID, ViewerState>()
    private var closed = false

    /** Renders the selected chest frame, or removes any old frame when selection moves elsewhere. */
    fun update(player: Player, frame: ChestPreviewFrame?, scale: Float, options: ChestPreviewSettings = settings) {
        if (closed) return
        val target = frame ?: run {
            clear(player)
            return
        }
        if (!options.enabled || target.anchor.worldId != player.world.uid) {
            clear(player)
            return
        }

        val selected = target.items.indices.filter { !target.items[it].type.isAir && target.items[it].amount > 0 }
            .take(options.maxItems)
        val items = selected.map { iconStack(target.items[it]) }
        val counts = selected.map { (target.counts.getOrNull(it) ?: target.items[it].amount).coerceAtLeast(1) }
        if (items.isEmpty()) {
            clear(player)
            return
        }

        try {
            val effectiveScale = ChestPreviewIconGeometry.normalizeScale(scale)
            val previous = viewers[player.uniqueId]?.takeIf {
                it.sourceAnchor == target.anchor && it.options == options && it.itemCount == items.size && it.scale == effectiveScale
            }?.anchor
            val anchor = ChestPreviewPlacement.place(player, target, items.size, effectiveScale, options, previous)
            if (anchor == null) clear(player)
            else render(player, anchor, target.anchor, items, counts, effectiveScale, options)
        } catch (failure: Exception) {
            try {
                clear(player)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun clear(player: Player) = clear(player.uniqueId)

    override fun close() {
        if (closed) return
        var failure: Exception? = null
        viewers.keys.toList().forEach { playerId ->
            try {
                clear(playerId)
            } catch (cleanupFailure: Exception) {
                failure = appendFailure(failure, cleanupFailure)
            }
        }
        try {
            displays.close()
        } catch (cleanupFailure: Exception) {
            failure = appendFailure(failure, cleanupFailure)
        }
        closed = failure == null
        failure?.let { throw it }
    }

    private fun render(
        player: Player,
        anchor: InspectionHologramAnchor,
        sourceAnchor: InspectionHologramAnchor,
        items: List<ItemStack>,
        counts: List<Int>,
        scale: Float,
        options: ChestPreviewSettings,
    ) {
        val playerId = player.uniqueId
        if (viewers[playerId]?.let { it.sourceAnchor != sourceAnchor || it.options != options } == true) {
            // A different target has no authored travel path between the two containers.
            clear(playerId)
        }
        val oldState = viewers[playerId]
        val created = oldState == null
        val state = if (created) {
            ViewerState(anchor = anchor, sourceAnchor = sourceAnchor, scale = scale, itemCount = 0, options = options)
                .also { viewers[playerId] = it }
        } else {
            requireNotNull(oldState)
        }

        val anchorChanged = state.anchor != anchor
        val layoutChanged = state.itemCount != items.size || state.scale != scale
        if (anchorChanged) {
            val location = anchor.toLocation(player)
            // Every part receives the same destination and native interpolation duration.
            // Unsafe paths snap rather than sweep the private panel through a solid block.
            val ticks = if (ChestPreviewPlacement.clearPath(player, state.anchor, anchor, items.size, scale, options)) options.teleportTicks else 0
            handles(state).forEach { it.teleportDuration = ticks; it.teleport(location) }
            state.anchor = anchor
        }

        while (state.icons.size > items.size) {
            val removed = mutableListOf<PacketDisplay>()
            while (state.icons.size > items.size) {
                val icon = state.icons.removeAt(state.icons.lastIndex)
                removed += icon.display
                icon.label?.let(removed::add)
            }
            removeAll(removed)
        }

        val offsets = ChestPreviewIconGeometry.offsets(items.size, scale, options)
        items.forEachIndexed { index, item ->
            val offset = offsets[index]
            val handle = state.icons.getOrNull(index)
            if (handle == null) {
                val display = displays.spawnItem(anchor.toLocation(player), item.clone())
                val label = if (options.showCounts) displays.spawnText(anchor.toLocation(player), countText(counts[index])) else null
                val newHandle = IconHandle(display, item.clone(), label, counts[index])
                state.icons += newHandle
                configureItemDisplay(display, player, item, offset, scale, options)
                label?.let { configureCount(it, player, offset, scale, options) }
            } else {
                val itemChanged = !handle.item.isSimilar(item)
                if (itemChanged) {
                    handle.item = item.clone()
                    handle.display.itemStack = item.clone()
                }
                if (layoutChanged || itemChanged) configureItemTransform(handle.display, item, offset, scale, options)
                if (handle.count != counts[index]) {
                    handle.label?.text(countText(counts[index]))
                    handle.count = counts[index]
                }
                if (layoutChanged) handle.label?.let { configureCountTransform(it, offset, scale, options) }
            }
        }

        if (options.backgroundOpacity > 0) {
            val backdrop = state.backdrop ?: run {
                // A single invisible space gives the native TextDisplay a measurable quad;
                // its transform scales that quad to the grid bounds without visible text.
                val display = displays.spawnText(anchor.toLocation(player), Component.text(" "))
                state.backdrop = display
                configureBackdrop(display, player, items.size, scale, options)
                display
            }
            if (state.backdrop === backdrop && !created && layoutChanged) {
                configureBackdropTransform(backdrop, items.size, scale, options)
            }
        }

        state.itemCount = items.size
        state.scale = scale
        state.sourceAnchor = sourceAnchor
    }

    private fun configureItemDisplay(
        display: PacketItemDisplay,
        player: Player,
        item: ItemStack,
        offset: ChestPreviewIconOffset,
        scale: Float,
        options: ChestPreviewSettings,
    ) {
        configureDisplay(display, options)
        display.itemDisplayTransform = options.itemTransform
        configureItemTransform(display, item, offset, scale, options)
        display.showTo(player)
    }

    private fun configureItemTransform(display: PacketItemDisplay, item: ItemStack, offset: ChestPreviewIconOffset, scale: Float, options: ChestPreviewSettings) {
        display.transformation = Transformation(
            Vector3f(offset.x, offset.y, ICON_DEPTH),
            Quaternionf(),
            Vector3f(options.iconScale * scale, options.iconScale * scale, options.iconScale * scale * options.depthScale),
            ChestPreviewIconGeometry.guiFacingRotation(
                options.itemTransform == ItemDisplay.ItemDisplayTransform.GUI && ChestPreviewIconGeometry.isBlockIcon(item),
                options.blockPitch, options.blockYaw,
            ),
        )
    }

    private fun configureBackdrop(
        display: PacketTextDisplay,
        player: Player,
        itemCount: Int,
        scale: Float,
        options: ChestPreviewSettings,
    ) {
        configureDisplay(display, options)
        display.textOpacity = 0.toByte()
        display.isShadowed = false
        display.isSeeThrough = false
        display.isDefaultBackground = false
        display.backgroundColor = backgroundColor(options.backgroundOpacity)
        display.alignment = TextDisplay.TextAlignment.CENTER
        display.lineWidth = TEXT_DISPLAY_LINE_WIDTH
        configureBackdropTransform(display, itemCount, scale, options)
        display.showTo(player)
    }

    private fun configureBackdropTransform(display: PacketTextDisplay, itemCount: Int, scale: Float, options: ChestPreviewSettings) {
        val bounds = ChestPreviewIconGeometry.panelBounds(itemCount, scale, options)
        val scaleX = bounds.width / (ChestPreviewIconGeometry.BACKGROUND_WIDTH_PIXELS * ChestPreviewIconGeometry.BACKGROUND_PIXEL_SIZE)
        val scaleY = bounds.height / (ChestPreviewIconGeometry.BACKGROUND_HEIGHT_PIXELS * ChestPreviewIconGeometry.BACKGROUND_PIXEL_SIZE)
        display.transformation = Transformation(
            Vector3f(
                -ChestPreviewIconGeometry.BACKGROUND_CENTER_X_PIXELS * ChestPreviewIconGeometry.BACKGROUND_PIXEL_SIZE * scaleX,
                // The native whitespace quad begins at the entity baseline, so y=0 makes its bottom edge align with the chest anchor.
                0f,
                -ChestPreviewIconGeometry.depth(scale, options),
            ),
            Quaternionf(),
            Vector3f(scaleX, scaleY, 1f),
            Quaternionf(),
        )
    }

    private fun configureDisplay(display: PacketDisplay, options: ChestPreviewSettings) {
        display.isVisibleByDefault = false
        display.billboard = Display.Billboard.CENTER
        // Upright culling boxes cannot enclose a camera-pitched grid.
        display.displayWidth = 0f
        display.displayHeight = 0f
        display.viewRange = DISPLAY_VIEW_RANGE
        display.shadowRadius = 0f
        display.shadowStrength = 0f
        display.brightness = Display.Brightness(options.brightness, options.brightness)
        display.teleportDuration = options.teleportTicks
    }

    private fun countText(count: Int): Component = Component.text("× $count")

    private fun configureCount(display: PacketTextDisplay, player: Player, offset: ChestPreviewIconOffset, scale: Float, options: ChestPreviewSettings) {
        configureDisplay(display, options)
        display.isSeeThrough = false
        display.isShadowed = true
        display.isDefaultBackground = false
        display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
        display.alignment = TextDisplay.TextAlignment.CENTER
        display.lineWidth = TEXT_DISPLAY_LINE_WIDTH
        configureCountTransform(display, offset, scale, options)
        display.showTo(player)
    }

    private fun configureCountTransform(display: PacketTextDisplay, offset: ChestPreviewIconOffset, scale: Float, options: ChestPreviewSettings) {
        display.transformation = Transformation(
            Vector3f(offset.x, offset.y + options.countOffsetY * scale, ChestPreviewIconGeometry.depth(scale, options) + 0.01f),
            Quaternionf(), Vector3f(options.countScale * scale / 0.25f), Quaternionf(),
        )
    }

    private fun handles(state: ViewerState): List<PacketDisplay> =
        state.icons.flatMap { listOfNotNull(it.display, it.label) } + listOfNotNull(state.backdrop)

    private fun InspectionHologramAnchor.toLocation(player: Player): Location =
        Location(player.world, x, y, z)

    private fun clear(playerId: UUID) {
        val state = viewers.remove(playerId) ?: return
        removeAll(handles(state))
    }

    private fun removeAll(displays: List<PacketDisplay>) {
        var failure: Exception? = null
        displays.forEach { display ->
            try {
                display.remove()
            } catch (cleanupFailure: Exception) {
                failure = appendFailure(failure, cleanupFailure)
            }
        }
        failure?.let { throw it }
    }

    private fun appendFailure(current: Exception?, next: Exception): Exception =
        current?.also { it.addSuppressed(next) } ?: next

    private fun iconStack(stack: ItemStack): ItemStack = stack.clone().apply { amount = 1 }

    private fun backgroundColor(opacityPercent: Int): Color {
        val alpha = (opacityPercent.coerceIn(0, 100) * 255 / 100.0).roundToInt()
        return Color.fromARGB(alpha, BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE)
    }

    private companion object {
        const val TEXT_DISPLAY_LINE_WIDTH = 16_384
        const val DISPLAY_VIEW_RANGE = 0.5f
        const val ICON_DEPTH = 0f
        const val BACKGROUND_RED = 15
        const val BACKGROUND_GREEN = 23
        const val BACKGROUND_BLUE = 30
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
