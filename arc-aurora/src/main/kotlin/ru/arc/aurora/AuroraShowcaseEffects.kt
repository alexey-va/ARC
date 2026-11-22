package ru.arc.aurora

import org.bukkit.Color
import org.bukkit.Particle
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

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
        if (profile.shape == AuroraShape.CIRCLE || profile.shape == AuroraShape.CUBE) {
            // Nearby biome dust disappears in a distant panel. Keep an authored sky profile above,
            // but give a converted ambience profile its own material and a stationary silhouette.
            val disc = profile.shape == AuroraShape.CIRCLE
            val spread = if (disc) 0.045 else 0.0
            effects[profile.shape] = profile.copy(
                skyScene = true, points = if (disc) 64 else 80, skyTracers = 4, updateTicks = 1,
                skyRefreshTicks = if (disc) 16 else 20, sceneDurationTicks = 200,
                particle = if (disc) Particle.FLAME else Particle.DUST,
                particleData = if (disc) null else Particle.DustOptions(Color.fromRGB(0xEF72FF), 2.35f),
                count = if (disc) 2 else 1, offsetX = spread, offsetY = spread, offsetZ = spread, speed = 0.0,
                skyOptions = profile.skyOptions.copy(heldDrift = 0.0, heldLift = 0.0, exitTicks = 12),
            )
            continue
        }
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
    // Audition new scenes privately before adding any of them to ambient or event rotation.
    effects[AuroraShape.FEATHER_GLIDER]?.let { template ->
        for (shape in AuroraShape.active.filter { it.isStory }) {
            effects.putIfAbsent(shape, template.copy(id = shape.name.lowercase(java.util.Locale.ROOT),
                shape = shape, points = AuroraSkyMiniatures.partCount(shape), updateTicks = 3,
                sceneDurationTicks = shape.storyDurationTicks))
        }
    }
    return effects
}

/** The rim, face and corners must be identifiable even while all nine copies share a packet budget. */
internal fun showcaseContour(shape: AuroraShape, size: Double, points: Int, slot: AuroraOffset): List<AuroraOffset>? {
    val contour = when (shape) {
        AuroraShape.CIRCLE -> {
            val rimPoints = (points + 1) / 2
            val goldenAngle = PI * (3.0 - sqrt(5.0))
            List(points) { index ->
                val sample = index / 2
                val rim = index % 2 == 0
                // Visit all quadrants first. The interior leaves a small round hole in the disc.
                val angle = if (rim) 2.0 * PI * ((sample % 4) / 4.0 + (sample / 4).toDouble() / rimPoints)
                    else sample * goldenAngle
                val radius = if (rim) size else size * sqrt(0.12 + 0.56 * (sample + 0.5) / (points / 2))
                AuroraOffset(cos(angle) * radius, sin(angle) * radius, 0.0)
            }
        }
        AuroraShape.CUBE -> {
            // The continuous sky stroke revisits three edges. A held wireframe needs equal density on all twelve.
            val walk = AuroraSkyShapes.offsets(shape, size, 16, 0L)
            val corners = walk.distinct()
            val edges = walk.zipWithNext().distinctBy { (a, b) -> setOf(a, b) }
            val fills = (points - corners.size).coerceAtLeast(0)
            corners.take(points) + List(fills) { index ->
                val edge = index % edges.size
                val (a, b) = edges[edge]
                val count = (fills + edges.lastIndex - edge) / edges.size
                val t = (index / edges.size + 1.0) / (count + 1)
                AuroraOffset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
            }
        }
        else -> return null
    }
    // Aim the face at the original eye for this slot, including the outside columns and high row.
    val yaw = atan2(-slot.x, slot.z)
    val pitch = atan2(slot.y, hypot(slot.x, slot.z))
    val cy = cos(yaw)
    val sy = sin(yaw)
    val cp = cos(pitch)
    val sp = sin(pitch)
    return contour.map { point ->
        val depth = point.y * sp + point.z * cp
        AuroraOffset(point.x * cy + depth * sy, point.y * cp - point.z * sp, -point.x * sy + depth * cy)
    }
}
