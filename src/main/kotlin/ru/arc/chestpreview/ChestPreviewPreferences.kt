package ru.arc.chestpreview

import org.bukkit.entity.ItemDisplay
import java.util.Locale

/** Sparse per-player overrides; null fields always inherit the current server configuration. */
internal data class ChestPreviewPreferences(
    val enabled: Boolean? = null,
    val scale: Float? = null,
    val maxItems: Int? = null,
    val columns: Int? = null,
    val cellSpacing: Float? = null,
    val maxDistance: Double? = null,
    val verticalGap: Double? = null,
    val iconScale: Float? = null,
    val depthScale: Float? = null,
    val blockPitch: Float? = null,
    val blockYaw: Float? = null,
    val showCounts: Boolean? = null,
    val countScale: Float? = null,
    val countOffsetY: Float? = null,
    val teleportTicks: Int? = null,
    val stabilityThreshold: Double? = null,
    val brightness: Int? = null,
    val backgroundOpacity: Int? = null,
    val itemTransform: ItemDisplay.ItemDisplayTransform? = null,
) {
    init {
        applyTo(ChestPreviewSettings())
    }

    fun applyTo(settings: ChestPreviewSettings): ChestPreviewSettings = settings.copy(
        enabled = enabled ?: settings.enabled,
        scale = scale ?: settings.scale,
        maxItems = maxItems ?: settings.maxItems,
        columns = columns ?: settings.columns,
        cellSpacing = cellSpacing ?: settings.cellSpacing,
        maxDistance = maxDistance ?: settings.maxDistance,
        verticalGap = verticalGap ?: settings.verticalGap,
        iconScale = iconScale ?: settings.iconScale,
        depthScale = depthScale ?: settings.depthScale,
        blockPitch = blockPitch ?: settings.blockPitch,
        blockYaw = blockYaw ?: settings.blockYaw,
        showCounts = showCounts ?: settings.showCounts,
        countScale = countScale ?: settings.countScale,
        countOffsetY = countOffsetY ?: settings.countOffsetY,
        teleportTicks = teleportTicks ?: settings.teleportTicks,
        stabilityThreshold = stabilityThreshold ?: settings.stabilityThreshold,
        brightness = brightness ?: settings.brightness,
        backgroundOpacity = backgroundOpacity ?: settings.backgroundOpacity,
        itemTransform = itemTransform ?: settings.itemTransform,
    )

    fun stored(): String = buildList {
        enabled?.let { add("enabled=$it") }
        scale?.let { add("scale=$it") }
        maxItems?.let { add("max-items=$it") }
        columns?.let { add("columns=$it") }
        cellSpacing?.let { add("cell-spacing=$it") }
        maxDistance?.let { add("max-distance=$it") }
        verticalGap?.let { add("vertical-gap=$it") }
        iconScale?.let { add("icon-scale=$it") }
        depthScale?.let { add("depth-scale=$it") }
        blockPitch?.let { add("block-pitch=$it") }
        blockYaw?.let { add("block-yaw=$it") }
        showCounts?.let { add("show-counts=$it") }
        countScale?.let { add("count-scale=$it") }
        countOffsetY?.let { add("count-offset-y=$it") }
        teleportTicks?.let { add("teleport-ticks=$it") }
        stabilityThreshold?.let { add("stability-threshold=$it") }
        brightness?.let { add("brightness=$it") }
        backgroundOpacity?.let { add("background-opacity=$it") }
        itemTransform?.let { add("item-transform=${it.name}") }
    }.joinToString(";").ifEmpty { DEFAULT_SENTINEL }

    companion object {
        const val META_KEY = "arc-chest-preview-preferences"

        fun fromStored(value: (String) -> String?): ChestPreviewPreferences {
            val raw = value(META_KEY)?.takeIf { it.length <= MAX_STORED_LENGTH } ?: return ChestPreviewPreferences()
            if (raw.trim().equals(DEFAULT_SENTINEL, ignoreCase = true)) return ChestPreviewPreferences()
            val fields = raw.split(';').mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) null else {
                    entry.substring(0, separator).trim().lowercase(Locale.ROOT) to entry.substring(separator + 1).trim()
                }
            }.toMap()
            fun boolean(key: String): Boolean? = fields[key]?.lowercase(Locale.ROOT)?.toBooleanStrictOrNull()
            fun integer(key: String, range: IntRange): Int? =
                fields[key]?.toIntOrNull()?.takeIf(range::contains)
            fun float(key: String, range: ClosedFloatingPointRange<Float>): Float? =
                fields[key]?.toFloatOrNull()?.takeIf { it.isFinite() && it in range }
            fun double(key: String, range: ClosedFloatingPointRange<Double>): Double? =
                fields[key]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in range }

            val transform = when (fields["item-transform"]?.uppercase(Locale.ROOT)) {
                "GUI" -> ItemDisplay.ItemDisplayTransform.GUI
                "FIXED" -> ItemDisplay.ItemDisplayTransform.FIXED
                "NONE" -> ItemDisplay.ItemDisplayTransform.NONE
                else -> null
            }
            return ChestPreviewPreferences(
                enabled = boolean("enabled"),
                scale = float("scale", 0.5f..2.0f),
                maxItems = integer("max-items", 1..12),
                columns = integer("columns", 1..6),
                cellSpacing = float("cell-spacing", 0.3f..1.0f),
                maxDistance = double("max-distance", 1.0..4.5),
                verticalGap = double("vertical-gap", 0.0..0.5),
                iconScale = float("icon-scale", 0.2f..0.7f),
                depthScale = float("depth-scale", 0.05f..1.0f),
                blockPitch = float("block-pitch", 0.0f..45.0f),
                blockYaw = float("block-yaw", 0.0f..60.0f),
                showCounts = boolean("show-counts"),
                countScale = float("count-scale", 0.06f..0.25f),
                countOffsetY = float("count-offset-y", -0.4f..0.1f),
                teleportTicks = integer("teleport-ticks", 0..10),
                stabilityThreshold = double("stability-threshold", 0.0..0.2),
                brightness = integer("brightness", 0..15),
                backgroundOpacity = integer("background-opacity", 0..100),
                itemTransform = transform,
            )
        }

        private const val DEFAULT_SENTINEL = "default"
        private const val MAX_STORED_LENGTH = 512
    }
}
