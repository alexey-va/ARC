package ru.arc.aurora

/** Pick a single definition per enum. The catalogue is private to the showcase. */
internal fun showcaseEffects(
    settings: AuroraSettings,
    defaults: AuroraSettings = settings,
): Map<AuroraShape, AuroraProfile> {
    fun AuroraSettings.definitions() = profiles + eventPresets.values.flatten()

    val profiles = settings.definitions() + if (defaults === settings) emptyList() else defaults.definitions()
    val effects = mutableMapOf<AuroraShape, AuroraProfile>()
    for (profile in profiles) {
        if (profile.skyScene) effects.putIfAbsent(profile.shape, profile)
    }
    for (profile in profiles) {
        if (profile.shape in effects) continue
        // Biome particles normally describe shapes only a block across. Sample the larger
        // sky contour often enough to see it, keeping the original material and typed payload.
        val point = profile.shape == AuroraShape.POINT
        effects[profile.shape] = profile.copy(
            skyScene = true,
            points = if (point) 1 else 48,
            skyTracers = if (point) 1 else 2,
            updateTicks = 1,
            skyRefreshTicks = if (point) 3 else 24,
            sceneDurationTicks = 100,
        )
    }
    // STAR and CRYSTAL share an outline renderer. STAR has no default ambient profile.
    if (AuroraShape.STAR !in effects) {
        effects[AuroraShape.CRYSTAL]?.let { outline ->
            effects[AuroraShape.STAR] = outline.copy(id = "showcase_star", shape = AuroraShape.STAR)
        }
    }
    return effects
}
