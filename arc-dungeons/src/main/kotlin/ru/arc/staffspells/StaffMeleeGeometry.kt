package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal const val STAFF_MELEE_PEAK_TICK = 4
internal const val STAFF_MELEE_CONTACT_TICK = 6
internal const val STAFF_MELEE_FADE_START_TICK = 8
internal const val STAFF_MELEE_EFFECT_TICKS = 16
internal const val STAFF_MELEE_DISPLAY_ARRIVAL_TICK = STAFF_MELEE_CONTACT_TICK
internal const val STAFF_MELEE_FADE_TICK = STAFF_MELEE_EFFECT_TICKS

private const val MELEE_START_DISTANCE = 1.4
private const val MELEE_MAX_REACH = 7.0
private const val MELEE_PARTICLE_STOP_TICK = STAFF_MELEE_CONTACT_TICK
private const val FLOOR_Y = -1.15
private const val MAX_PARTS = 48

/**
 * Four large local frontal attacks. The renderer supplies an eyeY - 0.25 anchor and yaw-only
 * orientation, so positive local Z is forward and these shapes are laid out from that origin.
 */
internal fun staffMeleeDisplayParts(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    reach: Double,
): List<StaffDisplayPart> {
    if (spell !in STAFF_MELEE_SPELLS) return emptyList()
    val endTick = minOf(durationTicks, staffMeleeDuration(spell))
    val targetReach = reach.takeIf(Double::isFinite)?.coerceAtMost(MELEE_MAX_REACH) ?: return emptyList()
    if (ageTicks < 0 || durationTicks <= 0 || ageTicks >= endTick ||
        targetReach <= MELEE_START_DISTANCE + 0.1) return emptyList()

    val progress = smoothMelee(ageTicks.toDouble() / STAFF_MELEE_PEAK_TICK)
    val followThrough = smoothMelee((ageTicks - STAFF_MELEE_CONTACT_TICK).toDouble() / 2.0)
    val shape = meleeShape(spell, progress, followThrough)
    if (shape.isEmpty() || shape.size > MAX_PARTS) return emptyList()

    // Fit against the full contact footprint so every spell reaches the same authored range at age 4.
    val bounds = meleeBounds(meleeShape(spell, 1.0, 0.0))
    val depth = (bounds.maxZ - bounds.minZ).coerceAtLeast(0.001f)
    val availableDepth = targetReach - MELEE_START_DISTANCE
    // Retain a short compressed approach pose, then let the age-4 keyframe occupy full reach.
    val fit = minOf(1.0, ((availableDepth - 0.55) / depth).coerceAtLeast(0.001)).toFloat()
    if (!fit.isFinite() || fit <= 0f) return emptyList()
    val fittedDepth = depth * fit
    val contactAdvance = (availableDepth - fittedDepth).coerceAtLeast(0.0) * progress

    // With two-tick keyframes, age 14 is sent before removal at 16. Collapse that pose first.
    val lastKeyframe = (endTick - StaffSpellDisplayEffects.FRAME_TICKS).coerceAtLeast(0)
    val fade = when {
        ageTicks <= STAFF_MELEE_FADE_START_TICK -> 1.0
        lastKeyframe <= STAFF_MELEE_FADE_START_TICK -> 0.001
        else -> (1.0 - smoothMelee(
            (ageTicks - STAFF_MELEE_FADE_START_TICK).toDouble() /
                (lastKeyframe - STAFF_MELEE_FADE_START_TICK),
        )).coerceAtLeast(0.001)
    }

    return shape.map { part ->
        val scaleFactor = (fit * fade).toFloat().coerceAtLeast(0.001f)
        part.copy(
            center = Vector3f(
                part.center.x * fit,
                part.center.y,
                (part.center.z - bounds.minZ) * fit +
                    MELEE_START_DISTANCE.toFloat() + contactAdvance.toFloat(),
            ),
            scale = Vector3f(part.scale).mul(scaleFactor),
        )
    }
}

/** A compact material/particle layer emitted from surfaces of the same advancing parts. */
internal fun staffMeleeParticles(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    reach: Double,
): List<StaffParticleSample> {
    if (spell !in STAFF_MELEE_SPELLS || ageTicks !in listOf(2, 4, 6) ||
        ageTicks > MELEE_PARTICLE_STOP_TICK || durationTicks <= ageTicks) return emptyList()
    val parts = staffMeleeDisplayParts(spell, ageTicks, durationTicks, reach)
    if (parts.isEmpty()) return emptyList()
    val samples = when (spell) {
        StaffSpell.FROST -> listOf(
            surfaceSample(parts, "frost:jaw:0:0", Particle.BLOCK, 2, Material.PACKED_ICE.createBlockData(), Vector(0.08, 0.02, 0.07), 0.025),
            surfaceSample(parts, "frost:jaw:0:3", Particle.SNOWFLAKE, 1, spread = Vector(0.06, 0.04, 0.06)),
            surfaceSample(parts, "frost:jaw:1:3", Particle.BLOCK, 2, Material.BLUE_ICE.createBlockData(), Vector(0.08, 0.02, 0.07), 0.025),
            surfaceSample(parts, "frost:tooth:0:1", Particle.SNOWFLAKE, 1, spread = Vector(0.05, 0.05, 0.05)),
            surfaceSample(parts, "frost:tooth:1:1", Particle.BLOCK, 1, Material.BLUE_ICE.createBlockData(), Vector(0.04, 0.02, 0.04), 0.02),
            surfaceSample(parts, "frost:spall:0", Particle.SNOWFLAKE, 1, spread = Vector(0.05, 0.05, 0.05)),
            surfaceSample(parts, "frost:spall:3", Particle.BLOCK, 1, Material.PACKED_ICE.createBlockData(), Vector(0.04, 0.02, 0.04), 0.02),
        )

        StaffSpell.FAULT -> listOf(
            surfaceSample(parts, "fault:hammer:0", Particle.BLOCK, 2, Material.BASALT.createBlockData(), Vector(0.07, 0.025, 0.06), 0.02),
            surfaceSample(parts, "fault:hammer:2", Particle.CRIT, 1, spread = Vector(0.06, 0.06, 0.06), speed = 0.025),
            surfaceSample(parts, "fault:rift:0:0:0", Particle.BLOCK, 2, Material.POLISHED_BASALT.createBlockData(), Vector(0.07, 0.025, 0.06), 0.02),
            surfaceSample(parts, "fault:rift:1:0:2", Particle.CRIT, 1, spread = Vector(0.06, 0.06, 0.06), speed = 0.025),
            surfaceSample(parts, "fault:spall:2", Particle.BLOCK, 2, Material.OBSIDIAN.createBlockData(), Vector(0.07, 0.025, 0.06), 0.02),
            surfaceSample(parts, "fault:spall:7", Particle.CRIT, 1, spread = Vector(0.06, 0.06, 0.06), speed = 0.025),
        )

        StaffSpell.LUNAR -> listOf(
            surfaceSample(parts, "lunar:arc:1", Particle.END_ROD, 1, spread = Vector(0.035, 0.035, 0.035)),
            surfaceSample(parts, "lunar:arc:3", Particle.DUST_COLOR_TRANSITION, 1,
                staffFade(0xbac3ec, 0xfaf7e9, 0.8f), Vector(0.025, 0.025, 0.025)),
            surfaceSample(parts, "lunar:arc:5", Particle.END_ROD, 1, spread = Vector(0.035, 0.035, 0.035)),
            surfaceSample(parts, "lunar:arc:7", Particle.DUST_COLOR_TRANSITION, 1,
                staffFade(0xbac3ec, 0xfaf7e9, 0.8f), Vector(0.025, 0.025, 0.025)),
            surfaceSample(parts, "lunar:arc:9", Particle.END_ROD, 1, spread = Vector(0.035, 0.035, 0.035)),
            surfaceSample(parts, "lunar:arc:11", Particle.END_ROD, 1, spread = Vector(0.035, 0.035, 0.035)),
        )

        StaffSpell.TIDE -> listOf(
            surfaceSample(parts, "tide:water:0", Particle.SPLASH, 2, spread = Vector(0.08, 0.05, 0.08), speed = 0.035),
            surfaceSample(parts, "tide:water:1", Particle.BUBBLE_POP, 1, spread = Vector(0.05, 0.04, 0.05), speed = 0.02),
            surfaceSample(parts, "tide:water:2", Particle.SPLASH, 2, spread = Vector(0.08, 0.05, 0.08), speed = 0.035),
            surfaceSample(parts, "tide:foam:1", Particle.FALLING_WATER, 1, spread = Vector(0.05, 0.04, 0.05)),
            surfaceSample(parts, "tide:water:5", Particle.SPLASH, 2, spread = Vector(0.08, 0.05, 0.08), speed = 0.035),
            surfaceSample(parts, "tide:foam:5", Particle.FALLING_WATER, 1, spread = Vector(0.05, 0.04, 0.05)),
            surfaceSample(parts, "tide:curl:1", Particle.BUBBLE_POP, 1, spread = Vector(0.05, 0.04, 0.05), speed = 0.02),
            surfaceSample(parts, "tide:curl:2", Particle.SPLASH, 2, spread = Vector(0.08, 0.05, 0.08), speed = 0.035),
        )

        else -> emptyList()
    }
    return samples.filterNotNull()
}

private val STAFF_MELEE_SPELLS = setOf(StaffSpell.FROST, StaffSpell.FAULT, StaffSpell.LUNAR, StaffSpell.TIDE)

private fun surfaceSample(
    parts: List<StaffDisplayPart>,
    identity: String,
    particle: Particle,
    count: Int,
    data: Any? = null,
    spread: Vector = Vector(0.025, 0.025, 0.025),
    speed: Double = 0.0,
): StaffParticleSample? {
    val part = parts.firstOrNull { it.identity == identity } ?: return null
    val surface = Vector3f(0f, part.scale.y * 0.5f, 0f)
    part.rotation.transform(surface).add(part.center)
    return StaffParticleSample(
        particle = particle,
        offset = Vector(surface.x.toDouble(), surface.y.toDouble(), surface.z.toDouble()),
        count = count,
        spread = spread,
        speed = speed,
        data = data,
    )
}

private fun meleeShape(spell: StaffSpell, progress: Double, followThrough: Double): List<StaffDisplayPart> = buildList {
    val p = progress.coerceIn(0.0, 1.0)
    val follow = followThrough.coerceIn(0.0, 1.0)

    fun piece(
        identity: String,
        material: Material,
        x: Double,
        y: Double,
        z: Double,
        width: Double,
        height: Double,
        depth: Double,
        yaw: Double = 0.0,
        pitch: Double = 0.0,
        roll: Double = 0.0,
        growth: Double = p,
    ) = add(StaffDisplayPart(
        material,
        Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
        Vector3f((width * growth).coerceAtLeast(0.001).toFloat(),
            (height * growth).coerceAtLeast(0.001).toFloat(),
            (depth * growth).coerceAtLeast(0.001).toFloat()),
        Quaternionf().rotationY(yaw.toFloat()).rotateZ(roll.toFloat()).rotateX(pitch.toFloat()),
        identity = identity,
    ))

    fun segment(
        identity: String,
        material: Material,
        from: Vector3f,
        to: Vector3f,
        width: Double,
        height: Double,
        growth: Double = p,
    ) {
        val direction = Vector3f(to).sub(from)
        val length = direction.length()
        if (length <= 0.001f) return
        direction.div(length)
        val start = Vector3f(from).fma(-0.035f, direction)
        val end = Vector3f(to).fma(0.035f, direction)
        val extendedLength = Vector3f(end).sub(start).length()
        add(StaffDisplayPart(
            material,
            Vector3f(start).add(end).mul(0.5f),
            Vector3f((width * growth).coerceAtLeast(0.001).toFloat(),
                (height * growth).coerceAtLeast(0.001).toFloat(),
                (extendedLength * growth).coerceAtLeast(0.001).toFloat()),
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), direction),
            identity = identity,
        ))
    }

    when (spell) {
        StaffSpell.FROST -> {
            // Two solid jaws surge along the ground, then close around the reticle at impact.
            repeat(2) { jaw ->
                val side = if (jaw == 0) -1.0 else 1.0
                fun point(t: Double): Vector3f {
                    val x = side * (4.35 - 3.25 * t - p * 1.10 * (1.0 - t))
                    val y = FLOOR_Y + 0.34 + 0.20 * sin(t * PI) + (jaw * 0.035)
                    val z = 1.72 + 4.58 * t
                    return Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
                }
                repeat(4) { joint ->
                    val t0 = joint / 4.0
                    val t1 = (joint + 1) / 4.0
                    val start = point(t0)
                    val end = point(t1)
                    segment("frost:jaw:$jaw:$joint",
                        if ((joint + jaw) % 2 == 0) Material.PACKED_ICE else Material.BLUE_ICE,
                        start, end, width = 0.96, height = 0.82, growth = p)
                }
            }

            // Tilted teeth fold inward; unequal height and overlap keep the clap from becoming a fence.
            repeat(2) { sideIndex ->
                val side = if (sideIndex == 0) -1.0 else 1.0
                repeat(3) { tooth ->
                    val root = Vector3f(
                        (side * (3.30 - tooth * 0.65 - p * (0.75 + tooth * 0.12))).toFloat(),
                        (FLOOR_Y + 0.12).toFloat(),
                        (3.14 + tooth * 0.88 + if (sideIndex == 0) 0.08 else -0.04).toFloat(),
                    )
                    val tip = Vector3f(
                        (side * (2.08 - tooth * 0.68 - p * (0.78 + tooth * 0.05))).toFloat(),
                        (root.y + (0.62 + ((tooth + sideIndex) % 3) * 0.12)).toFloat(),
                        (root.z + 0.48 + (tooth % 2) * 0.18).toFloat(),
                    )
                    segment("frost:tooth:$sideIndex:$tooth",
                        if ((tooth + sideIndex) % 2 == 0) Material.BLUE_ICE else Material.PACKED_ICE,
                        root, tip, width = 0.72 + (tooth % 2) * 0.10, height = 0.66, growth = p)
                }
            }

            // Small spalls break outward only after the jaws meet; they share stable slots with the jaws.
            repeat(6) { index ->
                val side = if (index % 2 == 0) -1.0 else 1.0
                val scatter = follow * (0.38 + (index % 3) * 0.12)
                piece("frost:spall:$index",
                    if (index % 2 == 0) Material.PACKED_ICE else Material.BLUE_ICE,
                    side * (0.82 + index / 2 * 0.58 + scatter),
                    FLOOR_Y + 0.54 + follow * (0.18 + (index % 3) * 0.08),
                    4.92 + index % 3 * 0.42 - follow * 0.18,
                    0.62, 0.48, 0.54,
                    yaw = side * (0.18 + index * 0.025),
                    pitch = side * (0.08 + follow * 0.12),
                    roll = side * (0.13 + follow * 0.10),
                    growth = p * (0.05 + follow * 0.55),
                )
            }
        }

        StaffSpell.FAULT -> {
            // A heavy leading fracture hammers forward; branching seams open sideways in sequence.
            repeat(4) { row ->
                val beat = smoothMelee((p - row * 0.13) / (1.0 - row * 0.13))
                val height = 0.74 + (row % 2) * 0.12
                val settle = follow * (0.14 + row * 0.025)
                piece("fault:hammer:$row",
                    when (row % 3) { 0 -> Material.OBSIDIAN; 1 -> Material.BASALT; else -> Material.BLACKSTONE },
                    (if (row % 2 == 0) -0.08 else 0.12) * p,
                    FLOOR_Y + height * 0.5 + 0.50 * beat - settle,
                    1.82 + row * 1.38,
                    1.54 + (row % 2) * 0.18, height, 1.18 + (row % 3) * 0.12,
                    yaw = (if (row % 2 == 0) -1 else 1) * (0.12 + beat * 0.12),
                    roll = (if (row % 2 == 0) -1 else 1) * 0.08,
                    growth = beat,
                )
            }

            repeat(2) { sideIndex ->
                val side = if (sideIndex == 0) -1.0 else 1.0
                repeat(2) { branch ->
                    val baseZ = 2.02 + branch * 2.12 + if (sideIndex == 0) 0.0 else 0.16
                    fun riftPoint(t: Double): Vector3f {
                        val x = side * (0.28 + 4.02 * t * p)
                        val y = FLOOR_Y + 0.26 + (0.62 + branch * 0.16) * t * p - follow * .30
                        val z = baseZ + 1.08 * t + sin(t * PI * 2 + branch) * 0.16 * p
                        return Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
                    }
                    repeat(3) { segmentIndex ->
                        val t0 = segmentIndex / 3.0
                        val t1 = (segmentIndex + 1) / 3.0
                        val start = riftPoint(t0)
                        val end = riftPoint(t1)
                        val birthAt = (t0 * 0.18 + branch * 0.06).coerceAtMost(0.42)
                        val birth = smoothMelee((p - birthAt) / (1.0 - birthAt))
                        segment("fault:rift:$sideIndex:$branch:$segmentIndex",
                            if ((branch + segmentIndex) % 2 == 0) Material.POLISHED_BASALT else Material.BLACKSTONE,
                            start, end,
                            width = 0.64 + (segmentIndex % 2) * 0.10,
                            height = 0.52 + branch * 0.12,
                            growth = birth,
                        )
                    }
                }
            }

            repeat(8) { index ->
                val side = if (index % 2 == 0) -1.0 else 1.0
                val branch = index / 4
                val slot = index % 4
                val birth = smoothMelee((p - 0.30 - slot * 0.04) / 0.62)
                piece("fault:spall:$index",
                    when (index % 3) { 0 -> Material.OBSIDIAN; 1 -> Material.BLACKSTONE; else -> Material.BASALT },
                    side * (2.30 + slot * 0.48 + follow * 0.28),
                    FLOOR_Y + 0.62 + branch * 0.18 + birth * 0.32 + follow * 0.34,
                    3.10 + branch * 2.10 + slot * 0.22 - follow * 0.12,
                    0.82 + (slot % 2) * 0.20, 1.18 + (index % 3) * 0.18, 0.74,
                    yaw = side * (0.20 + slot * 0.10),
                    pitch = side * (0.10 + follow * 0.10),
                    roll = side * (0.16 + slot * 0.03),
                    growth = birth * (0.78 + follow * 0.22),
                )
            }
        }

        StaffSpell.LUNAR -> {
            // A face-on, open crescent is recognizable from the caster, with tapered horns.
            // Its tilted blade sweeps across the contact area; the hollow center stays readable.
            fun point(t: Double, echo: Boolean): Vector3f {
                val angle = Math.toRadians(52.0 + 256.0 * t)
                val roll = -.48 + p * .64
                val after = if (echo) follow else 0.0
                val rawX = cos(angle) * 2.90
                val rawY = sin(angle) * 1.24
                val x = rawX * cos(roll) - rawY * sin(roll) + (p - .5) * 1.45 + after * .25
                val y = rawX * sin(roll) + rawY * cos(roll) - .12 - after * .20
                val z = 3.20 + 1.25 * sin(t * PI) + rawY * .18 - after * .40
                return Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
            }
            repeat(12) { index ->
                val t0 = index / 12.0
                val t1 = (index + 1) / 12.0
                val material = when (index % 3) {
                    0 -> Material.CALCITE
                    1 -> Material.QUARTZ_BLOCK
                    else -> Material.SMOOTH_QUARTZ
                }
                val taper = when (index) {
                    0, 11 -> 0.18
                    1, 10 -> 0.55
                    else -> 1.0
                }
                val birth = smoothMelee((p - t0 * 0.50) / (1.0 - t0 * 0.50))
                segment("lunar:arc:$index", material, point(t0, false), point(t1, false),
                    width = (0.62 + (index % 3) * 0.045) * taper * (1.0 - 0.45 * follow),
                    height = 0.34 * taper * (1.0 - 0.38 * follow),
                    growth = birth)
                val echoBirth = 0.001 + follow * 0.42
                segment("lunar:echo:$index", Material.LIGHT_BLUE_STAINED_GLASS,
                    point(t0, true), point(t1, true),
                    width = 0.52 * taper, height = 0.30 * taper, growth = echoBirth)
            }
        }

        StaffSpell.TIDE -> {
            // The body advances as one overlapping water wall, rises into a curled crest, then
            // folds down across its own footprint after contact instead of moving past the target.
            val xs = listOf(-3.80, -2.715, -1.63, -0.545, 0.545, 1.63, 2.715, 3.80)
            val heights = listOf(1.50, 1.38, 1.08, 0.82, 0.82, 1.08, 1.38, 1.50)
            val depths = listOf(4.00, 4.45, 4.80, 5.00, 5.00, 4.80, 4.45, 4.00)
            val yaws = listOf(-0.22, -0.23, -0.15, -0.05, 0.05, 0.15, 0.23, 0.22)
            xs.forEachIndexed { index, x ->
                val height = heights[index]
                val z = depths[index]
                val yaw = yaws[index]
                val foldedHeight = height * (1.0 - 0.42 * follow)
                val coreHeight = (height * 0.72).coerceAtLeast(0.58)
                val coreY = FLOOR_Y + coreHeight * 0.5
                val faceY = FLOOR_Y + foldedHeight * 0.53 - follow * 0.12
                val crestY = FLOOR_Y + foldedHeight - 0.21 - follow * 0.34

                piece("tide:core:$index", Material.BLUE_CONCRETE, x, coreY, z,
                    width = 1.54, height = coreHeight, depth = 1.32, yaw = yaw * 0.72,
                    roll = (if (index < 4) -1 else 1) * 0.035,
                    growth = p)
                piece("tide:backwash:$index", Material.BLUE_STAINED_GLASS, x, faceY, z + 0.31 - follow * 0.01,
                    width = 2.40, height = foldedHeight * 0.94, depth = 0.72, yaw = yaw,
                    pitch = 0.08 + follow * 0.12, growth = p)
                piece("tide:water:$index", if (index % 2 == 0) Material.CYAN_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
                    x, faceY, z - 0.20, width = 2.40, height = foldedHeight, depth = 0.72, yaw = yaw,
                    pitch = -0.12 - follow * 0.26, growth = p)
                piece("tide:foam:$index", Material.WHITE_CONCRETE, x, crestY, z + 0.12,
                    width = 2.40, height = 0.42, depth = 0.78, yaw = yaw * 0.82,
                    pitch = -0.08 - follow * 0.22, growth = p)
                piece("tide:curl:$index", if (index % 2 == 0) Material.CYAN_STAINED_GLASS else Material.BLUE_STAINED_GLASS,
                    x, crestY - 0.07 + follow * 0.18, z - 0.36, width = 2.40, height = 0.54, depth = 0.62,
                    yaw = yaw * 0.94, pitch = -0.40 - follow * 0.34, growth = p)
            }
        }

        else -> Unit
    }
}

private data class MeleeBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
)

private fun meleeBounds(parts: List<StaffDisplayPart>): MeleeBounds {
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    for (part in parts) {
        for (x in listOf(-0.5f, 0.5f)) for (y in listOf(-0.5f, 0.5f)) for (z in listOf(-0.5f, 0.5f)) {
            val corner = Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z)
            part.rotation.transform(corner).add(part.center)
            minX = minOf(minX, corner.x)
            maxX = maxOf(maxX, corner.x)
            minY = minOf(minY, corner.y)
            maxY = maxOf(maxY, corner.y)
            minZ = minOf(minZ, corner.z)
            maxZ = maxOf(maxZ, corner.z)
        }
    }
    return MeleeBounds(minX, maxX, minY, maxY, minZ, maxZ)
}

private fun smoothMelee(value: Double): Double = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)
    ?.let { it * it * (3.0 - 2.0 * it) } ?: 0.0
