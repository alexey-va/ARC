package ru.arc.chestpreview

/** Icon count, reach, chest-top gap and background opacity, replaced on module reload. */
internal data class ChestPreviewSettings(
    val maxItems: Int = DEFAULT_MAX_ITEMS,
    val maxDistance: Double = DEFAULT_MAX_DISTANCE,
    val verticalGap: Double = DEFAULT_VERTICAL_GAP,
    val backgroundOpacity: Int = DEFAULT_BACKGROUND_OPACITY,
) {
    init {
        require(maxItems in 1..12) { "Chest preview max-items must be in 1..12" }
        require(maxDistance in 1.0..4.5 && maxDistance.isFinite()) {
            "Chest preview max-distance must be in 1.0..4.5"
        }
        require(verticalGap in 0.0..0.5 && verticalGap.isFinite()) {
            "Chest preview vertical-gap must be in 0.0..0.5"
        }
        require(backgroundOpacity in 0..100) { "Chest preview background-opacity must be in 0..100" }
    }

    companion object {
        const val MAX_DISTANCE = 4.5
        const val DEFAULT_MAX_ITEMS = 6
        const val DEFAULT_MAX_DISTANCE = 4.5
        const val DEFAULT_VERTICAL_GAP = 0.15
        const val DEFAULT_BACKGROUND_OPACITY = 40
    }
}
