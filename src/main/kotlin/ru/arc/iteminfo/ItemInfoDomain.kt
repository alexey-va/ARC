package ru.arc.iteminfo

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.util.Vector
import ru.arc.onboarding.claimGuideLabelLocation
import java.util.Locale

enum class ItemInfoMode(val id: String) {
    HOLOGRAM("hologram"),
    BOSSBAR("bossbar"),
    OFF("off");

    companion object {
        const val META_KEY = "arc-item-info-mode"

        fun fromStored(raw: String?): ItemInfoMode =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase(Locale.ROOT) } ?: HOLOGRAM
    }
}

data class ItemInfoTarget(
    val name: Component,
    val namespacedId: String,
)

data class ItemInfoPreferences(
    val mode: ItemInfoMode = ItemInfoMode.HOLOGRAM,
    val showNamespacedId: Boolean = false,
    val hologramScale: Float = DEFAULT_SCALE,
    val verticalOffset: Double = DEFAULT_VERTICAL_OFFSET,
    val horizontalOffset: Double = DEFAULT_HORIZONTAL_OFFSET,
) {
    fun storedLayout(): String = listOf(hologramScale, verticalOffset, horizontalOffset)
        .joinToString(",") { value -> "%.2f".format(Locale.ROOT, value) }

    companion object {
        const val SHOW_ID_META_KEY = "arc-item-info-show-id"
        const val LAYOUT_META_KEY = "arc-item-info-hologram-layout"
        const val DEFAULT_SCALE = 0.90f
        const val DEFAULT_VERTICAL_OFFSET = 0.50
        const val DEFAULT_HORIZONTAL_OFFSET = 0.0
        const val MIN_SCALE = 0.50f
        const val MAX_SCALE = 2.00f
        const val MIN_VERTICAL_OFFSET = -1.50
        const val MAX_VERTICAL_OFFSET = 1.50
        const val MIN_HORIZONTAL_OFFSET = -2.00
        const val MAX_HORIZONTAL_OFFSET = 2.00
        val DEFAULT = ItemInfoPreferences()

        fun fromStored(value: (String) -> String?): ItemInfoPreferences {
            val mode = ItemInfoMode.fromStored(value(ItemInfoMode.META_KEY))
            val showId = value(SHOW_ID_META_KEY)?.trim()?.lowercase(Locale.ROOT) in setOf("true", "1", "yes", "on")
            val layout = value(LAYOUT_META_KEY)?.split(',')?.takeIf { it.size == 3 }
                ?.map { it.trim().toDoubleOrNull() }
                ?.takeIf { values -> values.all { it != null && it.isFinite() } }
                ?.map { requireNotNull(it) }
            val scale = layout?.get(0)?.takeIf { it in MIN_SCALE.toDouble()..MAX_SCALE.toDouble() }?.toFloat() ?: DEFAULT_SCALE
            val vertical = layout?.get(1)?.takeIf { it in MIN_VERTICAL_OFFSET..MAX_VERTICAL_OFFSET }
                ?: DEFAULT_VERTICAL_OFFSET
            val horizontal = layout?.get(2)?.takeIf { it in MIN_HORIZONTAL_OFFSET..MAX_HORIZONTAL_OFFSET }
                ?: DEFAULT_HORIZONTAL_OFFSET
            return ItemInfoPreferences(mode, showId, scale, vertical, horizontal)
        }
    }
}

internal fun itemInfoHologramLocation(
    eye: Location,
    verticalOffset: Double = 0.0,
    horizontalOffset: Double = 0.0,
): Location {
    val location = claimGuideLabelLocation(eye, verticalOffset)
    if (horizontalOffset == 0.0) return location
    val yaw = Math.toRadians(eye.yaw.toDouble())
    return location.add(Vector(-kotlin.math.cos(yaw), 0.0, -kotlin.math.sin(yaw)).multiply(horizontalOffset))
}
