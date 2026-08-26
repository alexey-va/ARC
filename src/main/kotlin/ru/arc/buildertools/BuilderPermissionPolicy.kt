package ru.arc.buildertools

/** Migration-aware permission contract shared by every builder-tools route. */
internal enum class BuilderFeature(
    val compactPermission: String,
    val dottedPermission: String,
    val denizenPermission: String,
) {
    FILL("arc.buildertools.fill", "arc.builder.tools.fill", "arc.deconstruction"),
    COPY("arc.buildertools.copy", "arc.builder.tools.copy", "arc.deconstruction"),
    PASTE("arc.buildertools.paste", "arc.builder.tools.paste", "arc.deconstruction"),
    DECONSTRUCT("arc.buildertools.deconstruct", "arc.builder.tools.deconstruct", "arc.deconstruction"),
    CROWN("arc.buildertools.crown", "arc.builder.tools.crown", "arc.crown"),
}

internal object BuilderPermissionPolicy {
    private val umbrellaPermissions = setOf("arc.buildertools.use", "arc.builder.tools.use")
    private val sizeTiers = listOf(100, 80, 60, 40, 20)
    private val hourlyTiers = listOf(200_000, 150_000, 100_000, 50_000, 20_000)

    fun canUseAny(hasPermission: (String) -> Boolean): Boolean =
        umbrellaPermissions.any(hasPermission) ||
            BuilderFeature.entries.any { feature -> canUse(feature, hasPermission) }

    fun canUse(feature: BuilderFeature, hasPermission: (String) -> Boolean): Boolean =
        umbrellaPermissions.any(hasPermission) ||
            hasPermission(feature.compactPermission) ||
            hasPermission(feature.dottedPermission) ||
            hasPermission(feature.denizenPermission)

    fun maximumAxis(hasPermission: (String) -> Boolean, absoluteMaximum: Int): Int {
        val tier = sizeTiers.firstOrNull { size ->
            hasPermission("arc.buildertools.selection.size.$size") ||
                hasPermission("arc.builder.tools.selection.size.$size") ||
                hasPermission("arc.deconstruction.size.$size")
        } ?: 20
        return minOf(tier, absoluteMaximum)
    }

    fun hourlyChanges(hasPermission: (String) -> Boolean, baseLimit: Int): Int =
        hourlyTiers.firstOrNull { limit ->
            hasPermission("arc.buildertools.hourly.$limit") ||
                hasPermission("arc.builder.tools.hourly.$limit") ||
                hasPermission("arc.deconstruction.hourly.$limit") ||
                hasPermission("arc.deconstruction.limit.$limit")
        } ?: baseLimit
}
