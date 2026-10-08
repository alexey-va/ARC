package ru.arc.chestpreview

import org.bukkit.entity.ItemDisplay

/** Private container preview settings, replaced on module reload. */
internal data class ChestPreviewSettings(
    val enabled: Boolean = DEFAULT_ENABLED,
    val scale: Float = DEFAULT_SCALE,
    val maxItems: Int = DEFAULT_MAX_ITEMS,
    val columns: Int = DEFAULT_COLUMNS,
    val cellSpacing: Float = DEFAULT_CELL_SPACING,
    val maxDistance: Double = DEFAULT_MAX_DISTANCE,
    val verticalGap: Double = DEFAULT_VERTICAL_GAP,
    val iconScale: Float = DEFAULT_ICON_SCALE,
    val depthScale: Float = DEFAULT_DEPTH_SCALE,
    val blockPitch: Float = DEFAULT_BLOCK_PITCH,
    val blockYaw: Float = DEFAULT_BLOCK_YAW,
    val showCounts: Boolean = DEFAULT_SHOW_COUNTS,
    val countScale: Float = DEFAULT_COUNT_SCALE,
    val countOffsetY: Float = DEFAULT_COUNT_OFFSET_Y,
    val teleportTicks: Int = DEFAULT_TELEPORT_TICKS,
    val stabilityThreshold: Double = DEFAULT_STABILITY_THRESHOLD,
    val brightness: Int = DEFAULT_BRIGHTNESS,
    val backgroundOpacity: Int = DEFAULT_BACKGROUND_OPACITY,
    val itemTransform: ItemDisplay.ItemDisplayTransform = DEFAULT_ITEM_TRANSFORM,
) {
    init {
        require(scale in 0.5f..2.0f && scale.isFinite()) { "Chest preview scale must be in 0.5..2.0" }
        require(maxItems in 1..12) { "Chest preview max-items must be in 1..12" }
        require(columns in 1..6) { "Chest preview columns must be in 1..6" }
        require(cellSpacing in 0.3f..1.0f && cellSpacing.isFinite()) { "Chest preview cell-spacing must be in 0.3..1.0" }
        require(maxDistance in 1.0..4.5 && maxDistance.isFinite()) {
            "Chest preview max-distance must be in 1.0..4.5"
        }
        require(verticalGap in 0.0..0.5 && verticalGap.isFinite()) {
            "Chest preview vertical-gap must be in 0.0..0.5"
        }
        require(iconScale in 0.2f..0.7f && iconScale.isFinite()) { "Chest preview icon-scale must be in 0.2..0.7" }
        require(depthScale in 0.05f..1.0f && depthScale.isFinite()) { "Chest preview depth-scale must be in 0.05..1.0" }
        require(blockPitch in 0.0f..45.0f && blockPitch.isFinite()) { "Chest preview block-pitch must be in 0..45" }
        require(blockYaw in 0.0f..60.0f && blockYaw.isFinite()) { "Chest preview block-yaw must be in 0..60" }
        require(countScale in 0.06f..0.25f && countScale.isFinite()) { "Chest preview count-scale must be in 0.06..0.25" }
        require(countOffsetY in -0.4f..0.1f && countOffsetY.isFinite()) { "Chest preview count-offset-y must be in -0.4..0.1" }
        require(teleportTicks in 0..10) { "Chest preview teleport-ticks must be in 0..10" }
        require(stabilityThreshold in 0.0..0.2 && stabilityThreshold.isFinite()) {
            "Chest preview stability-threshold must be in 0.0..0.2"
        }
        require(brightness in 0..15) { "Chest preview brightness must be in 0..15" }
        require(backgroundOpacity in 0..100) { "Chest preview background-opacity must be in 0..100" }
    }

    companion object {
        const val MAX_DISTANCE = 4.5
        const val DEFAULT_ENABLED = true
        const val DEFAULT_SCALE = 0.9f
        const val DEFAULT_MAX_ITEMS = 6
        const val DEFAULT_COLUMNS = 3
        const val DEFAULT_CELL_SPACING = 0.48f
        const val DEFAULT_MAX_DISTANCE = 4.5
        const val DEFAULT_VERTICAL_GAP = 0.15
        const val DEFAULT_ICON_SCALE = 0.40f
        const val DEFAULT_DEPTH_SCALE = 0.15f
        const val DEFAULT_BLOCK_PITCH = 12.0f
        const val DEFAULT_BLOCK_YAW = 20.0f
        const val DEFAULT_SHOW_COUNTS = true
        const val DEFAULT_COUNT_SCALE = 0.12f
        const val DEFAULT_COUNT_OFFSET_Y = -0.28f
        const val DEFAULT_TELEPORT_TICKS = 3
        const val DEFAULT_STABILITY_THRESHOLD = 0.04
        const val DEFAULT_BRIGHTNESS = 15
        const val DEFAULT_BACKGROUND_OPACITY = 40
        val DEFAULT_ITEM_TRANSFORM = ItemDisplay.ItemDisplayTransform.GUI
    }
}
