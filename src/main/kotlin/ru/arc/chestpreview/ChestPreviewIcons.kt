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
import kotlin.math.abs
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
    )

    private data class ViewerState(
        var anchor: InspectionHologramAnchor,
        var scale: Float,
        var itemCount: Int,
        val icons: MutableList<IconHandle> = mutableListOf(),
        var backdrop: PacketTextDisplay? = null,
    )

    private val viewers = mutableMapOf<UUID, ViewerState>()
    private var closed = false

    /** Renders the selected chest frame, or removes any old frame when selection moves elsewhere. */
    fun update(player: Player, frame: ChestPreviewFrame?, scale: Float) {
        if (closed) return
        val target = frame ?: run {
            clear(player)
            return
        }
        if (target.anchor.worldId != player.world.uid) {
            clear(player)
            return
        }

        val items = target.items.asSequence()
            .filterNot { it.type.isAir || it.amount <= 0 }
            .take(settings.maxItems)
            .map(::iconStack)
            .toList()
        if (items.isEmpty()) {
            clear(player)
            return
        }

        try {
            render(player, target.anchor, items, ChestPreviewIconGeometry.normalizeScale(scale))
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
        items: List<ItemStack>,
        scale: Float,
    ) {
        val playerId = player.uniqueId
        val oldState = viewers[playerId]
        if (oldState != null && oldState.anchor.worldId != anchor.worldId) {
            clear(playerId)
        }
        val created = oldState == null || oldState.anchor.worldId != anchor.worldId
        val state = if (created) {
            ViewerState(anchor = anchor, scale = scale, itemCount = 0).also { viewers[playerId] = it }
        } else {
            requireNotNull(oldState)
        }

        val anchorChanged = state.anchor != anchor
        val layoutChanged = state.itemCount != items.size || state.scale != scale
        if (anchorChanged) {
            val location = anchor.toLocation(player)
            state.icons.forEach { it.display.teleport(location) }
            state.backdrop?.teleport(location)
            state.anchor = anchor
        }

        while (state.icons.size > items.size) {
            val removed = mutableListOf<PacketDisplay>()
            while (state.icons.size > items.size) removed += state.icons.removeAt(state.icons.lastIndex).display
            removeAll(removed)
        }

        val offsets = ChestPreviewIconGeometry.offsets(items.size, scale)
        items.forEachIndexed { index, item ->
            val offset = offsets[index]
            val handle = state.icons.getOrNull(index)
            if (handle == null) {
                val display = displays.spawnItem(anchor.toLocation(player), item.clone())
                val newHandle = IconHandle(display, item.clone())
                state.icons += newHandle
                configureItemDisplay(display, player, offset, scale)
            } else {
                if (!handle.item.isSimilar(item)) {
                    handle.item = item.clone()
                    handle.display.itemStack = item.clone()
                }
                if (layoutChanged) configureItemTransform(handle.display, offset, scale)
            }
        }

        if (settings.backgroundOpacity > 0) {
            val backdrop = state.backdrop ?: run {
                // A single invisible space gives the native TextDisplay a measurable quad;
                // its transform scales that quad to the grid bounds without visible text.
                val display = displays.spawnText(anchor.toLocation(player), Component.text(" "))
                state.backdrop = display
                configureBackdrop(display, player, items.size, scale)
                display
            }
            if (state.backdrop === backdrop && !created && layoutChanged) {
                configureBackdropTransform(backdrop, items.size, scale)
                configureBackdropBounds(backdrop, items.size, scale)
            }
        }

        state.itemCount = items.size
        state.scale = scale
    }

    private fun configureItemDisplay(
        display: PacketItemDisplay,
        player: Player,
        offset: ChestPreviewIconOffset,
        scale: Float,
    ) {
        display.isVisibleByDefault = false
        display.billboard = Display.Billboard.CENTER
        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GUI
        display.viewRange = DISPLAY_VIEW_RANGE
        display.shadowRadius = 0f
        display.shadowStrength = 0f
        display.brightness = Display.Brightness(15, 15)
        configureItemTransform(display, offset, scale)
        display.showTo(player)
    }

    private fun configureItemTransform(display: PacketItemDisplay, offset: ChestPreviewIconOffset, scale: Float) {
        val itemBounds = ChestPreviewIconGeometry.itemBounds(scale)
        // All parts share the bottom anchor; include each local offset in native culling bounds.
        display.displayWidth = abs(offset.x) * 2f + itemBounds.width
        display.displayHeight = offset.y + itemBounds.height / 2f
        display.transformation = Transformation(
            Vector3f(offset.x, offset.y, ICON_DEPTH),
            Quaternionf(),
            Vector3f(ChestPreviewIconGeometry.ICON_SCALE * scale),
            Quaternionf(),
        )
    }

    private fun configureBackdrop(
        display: PacketTextDisplay,
        player: Player,
        itemCount: Int,
        scale: Float,
    ) {
        display.isVisibleByDefault = false
        display.billboard = Display.Billboard.CENTER
        display.viewRange = DISPLAY_VIEW_RANGE
        display.shadowRadius = 0f
        display.shadowStrength = 0f
        display.brightness = Display.Brightness(15, 15)
        display.textOpacity = 0.toByte()
        display.isShadowed = false
        display.isSeeThrough = false
        display.isDefaultBackground = false
        display.backgroundColor = backgroundColor(settings.backgroundOpacity)
        display.alignment = TextDisplay.TextAlignment.CENTER
        display.lineWidth = TEXT_DISPLAY_LINE_WIDTH
        configureBackdropTransform(display, itemCount, scale)
        configureBackdropBounds(display, itemCount, scale)
        display.showTo(player)
    }

    private fun configureBackdropTransform(display: PacketTextDisplay, itemCount: Int, scale: Float) {
        val bounds = ChestPreviewIconGeometry.panelBounds(itemCount, scale)
        val scaleX = bounds.width / (ChestPreviewIconGeometry.BACKGROUND_WIDTH_PIXELS * ChestPreviewIconGeometry.BACKGROUND_PIXEL_SIZE)
        val scaleY = bounds.height / (ChestPreviewIconGeometry.BACKGROUND_HEIGHT_PIXELS * ChestPreviewIconGeometry.BACKGROUND_PIXEL_SIZE)
        display.transformation = Transformation(
            Vector3f(
                -ChestPreviewIconGeometry.BACKGROUND_CENTER_X_PIXELS * ChestPreviewIconGeometry.BACKGROUND_PIXEL_SIZE * scaleX,
                // The native whitespace quad begins at the entity baseline, so y=0 makes its bottom edge align with the chest anchor.
                0f,
                BACKDROP_DEPTH * scale,
            ),
            Quaternionf(),
            Vector3f(scaleX, scaleY, 1f),
            Quaternionf(),
        )
    }

    private fun configureBackdropBounds(display: PacketTextDisplay, itemCount: Int, scale: Float) {
        val bounds = ChestPreviewIconGeometry.panelBounds(itemCount, scale)
        display.displayWidth = bounds.width
        display.displayHeight = bounds.height
    }

    private fun InspectionHologramAnchor.toLocation(player: Player): Location =
        Location(player.world, x, y, z)

    private fun clear(playerId: UUID) {
        val state = viewers.remove(playerId) ?: return
        val handles = state.icons.map(IconHandle::display) + listOfNotNull(state.backdrop)
        removeAll(handles)
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
        // GUI block icons have depth: keep the panel behind their rotated cube, not through it.
        const val BACKDROP_DEPTH = -0.24f
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
    const val BACKGROUND_WIDTH_PIXELS = 5f
    const val BACKGROUND_HEIGHT_PIXELS = 10f
    const val BACKGROUND_CENTER_X_PIXELS = 0.5f
    const val BACKGROUND_PIXEL_SIZE = 0.025f
    private const val MIN_SCALE = 0.50f
    private const val MAX_SCALE = 2.00f
    private const val DEFAULT_SCALE = 0.90f

    fun offsets(itemCount: Int, scale: Float): List<ChestPreviewIconOffset> {
        require(itemCount >= 0) { "Chest preview icon count cannot be negative" }
        if (itemCount == 0) return emptyList()

        val effectiveScale = normalizeScale(scale)
        val rows = ceil(itemCount / COLUMNS.toDouble()).toInt()
        val bottomRowCenter = (EDGE_PADDING + ICON_SCALE / 2f) * effectiveScale
        return List(itemCount) { index ->
            val row = index / COLUMNS
            val columnsInRow = min(COLUMNS, itemCount - row * COLUMNS)
            val column = index % COLUMNS
            val x = ((column - (columnsInRow - 1) / 2.0) * CELL_SPACING * effectiveScale).toFloat()
            val y = bottomRowCenter + (rows - 1 - row) * CELL_SPACING * effectiveScale
            ChestPreviewIconOffset(x, y)
        }
    }

    fun panelBounds(itemCount: Int, scale: Float): ChestPreviewPanelBounds {
        require(itemCount > 0) { "Chest preview panel needs at least one icon" }
        val effectiveScale = normalizeScale(scale)
        val columns = min(COLUMNS, itemCount)
        val rows = ceil(itemCount / COLUMNS.toDouble()).toInt()
        val width = ((columns - 1) * CELL_SPACING + ICON_SCALE + EDGE_PADDING * 2) * effectiveScale
        // The first icon's lower edge sits EDGE_PADDING above the chest-top anchor;
        // the last row gets the same clearance above it.
        val height = (rows * CELL_SPACING + EDGE_PADDING) * effectiveScale
        return ChestPreviewPanelBounds(width, height)
    }

    fun itemBounds(scale: Float): ChestPreviewPanelBounds {
        val extent = (ICON_SCALE + EDGE_PADDING * 2) * normalizeScale(scale)
        return ChestPreviewPanelBounds(extent, extent)
    }

    fun normalizeScale(scale: Float): Float =
        scale.takeIf(Float::isFinite)?.coerceIn(MIN_SCALE, MAX_SCALE) ?: DEFAULT_SCALE
}
