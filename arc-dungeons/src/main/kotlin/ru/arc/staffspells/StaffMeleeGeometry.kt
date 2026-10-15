package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
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
    val endTick = minOf(durationTicks, STAFF_MELEE_EFFECT_TICKS)
    val targetReach = reach.takeIf(Double::isFinite)?.coerceAtMost(MELEE_MAX_REACH) ?: return emptyList()
    if (ageTicks < 0 || durationTicks <= 0 || ageTicks >= endTick ||
        targetReach <= MELEE_START_DISTANCE + 0.1) return emptyList()

    val growth = smoothMelee(ageTicks.toDouble() / STAFF_MELEE_PEAK_TICK)
    val shape = meleeShape(spell, growth)
    if (shape.isEmpty() || shape.size > MAX_PARTS) return emptyList()

    val bounds = meleeBounds(shape)
    val depth = (bounds.maxZ - bounds.minZ).coerceAtLeast(0.001f)
    val availableDepth = targetReach - MELEE_START_DISTANCE
    // Retain a short compressed approach pose, then let the age-4 keyframe occupy full reach.
    val fit = minOf(1.0, ((availableDepth - 0.55) / depth).coerceAtLeast(0.001)).toFloat()
    if (!fit.isFinite() || fit <= 0f) return emptyList()
    val fittedDepth = depth * fit
    val contactAdvance = (availableDepth - fittedDepth).coerceAtLeast(0.0) * growth

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
        val scaleFactor = (fit * growth * fade).toFloat().coerceAtLeast(0.001f)
        part.copy(
            center = Vector3f(
                part.center.x * fit * growth.toFloat(),
                FLOOR_Y.toFloat() + (part.center.y - FLOOR_Y.toFloat()) * growth.toFloat(),
                (part.center.z - bounds.minZ) * fit * growth.toFloat() +
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
            surfaceSample(parts, "frost:shelf:0", Particle.BLOCK, 2, Material.PACKED_ICE.createBlockData(), Vector(0.08, 0.02, 0.07), 0.025),
            surfaceSample(parts, "frost:shelf:1", Particle.SNOWFLAKE, 1, spread = Vector(0.06, 0.04, 0.06)),
            surfaceSample(parts, "frost:shelf:3", Particle.BLOCK, 2, Material.BLUE_ICE.createBlockData(), Vector(0.08, 0.02, 0.07), 0.025),
            surfaceSample(parts, "frost:shard:0:1", Particle.SNOWFLAKE, 1, spread = Vector(0.05, 0.05, 0.05)),
            surfaceSample(parts, "frost:shard:1:1", Particle.BLOCK, 1, Material.BLUE_ICE.createBlockData(), Vector(0.04, 0.02, 0.04), 0.02),
            surfaceSample(parts, "frost:shard:2:1", Particle.SNOWFLAKE, 1, spread = Vector(0.05, 0.05, 0.05)),
            surfaceSample(parts, "frost:shard:3:1", Particle.BLOCK, 1, Material.PACKED_ICE.createBlockData(), Vector(0.04, 0.02, 0.04), 0.02),
        )

        StaffSpell.FAULT -> listOf(
            surfaceSample(parts, "fault:row:0:rock:0", Particle.BLOCK, 2, Material.BASALT.createBlockData(), Vector(0.07, 0.025, 0.06), 0.02),
            surfaceSample(parts, "fault:row:1:rock:1", Particle.CRIT, 1, spread = Vector(0.06, 0.06, 0.06), speed = 0.025),
            surfaceSample(parts, "fault:row:1:rock:4", Particle.BLOCK, 2, Material.POLISHED_BASALT.createBlockData(), Vector(0.07, 0.025, 0.06), 0.02),
            surfaceSample(parts, "fault:row:2:rock:2", Particle.CRIT, 1, spread = Vector(0.06, 0.06, 0.06), speed = 0.025),
            surfaceSample(parts, "fault:row:2:rock:3", Particle.BLOCK, 2, Material.OBSIDIAN.createBlockData(), Vector(0.07, 0.025, 0.06), 0.02),
            surfaceSample(parts, "fault:row:3:rock:5", Particle.CRIT, 1, spread = Vector(0.06, 0.06, 0.06), speed = 0.025),
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

private fun meleeShape(spell: StaffSpell, progress: Double): List<StaffDisplayPart> = buildList {
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
    ) = add(StaffDisplayPart(
        material,
        Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
        Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()),
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
            Vector3f(width.toFloat(), height.toFloat(), extendedLength),
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), direction),
            identity = identity,
        ))
    }

    when (spell) {
        StaffSpell.FROST -> {
            // Broken low shelves bind four separated, solid crystal clusters without filling the reticle.
            val shelves = listOf(
                doubleArrayOf(-3.05, 2.12, 1.42, 0.24, 1.04, -0.14),
                doubleArrayOf(-1.94, 3.84, 1.20, 0.20, 1.10, 0.11),
                doubleArrayOf(0.00, 4.82, 1.02, 0.17, 1.05, 0.03),
                doubleArrayOf(1.92, 5.56, 1.23, 0.22, 1.08, -0.09),
                doubleArrayOf(3.02, 6.12, 1.40, 0.24, 1.04, 0.15),
            )
            shelves.forEachIndexed { index, shelf ->
                piece(
                    "frost:shelf:$index",
                    if (index % 2 == 0) Material.PACKED_ICE else Material.BLUE_ICE,
                    shelf[0], FLOOR_Y + shelf[3] * 0.5, shelf[1], shelf[2], shelf[3], shelf[4], yaw = shelf[5],
                )
            }

            val clusters = listOf(-3.15 to 3.18, -1.80 to 4.58, 1.82 to 5.12, 3.18 to 4.02)
            val heights = listOf(
                listOf(1.25, 1.56, 1.02),
                listOf(1.44, 1.82, 1.30),
                listOf(1.37, 1.67, 1.17),
                listOf(1.18, 1.53, 1.36),
            )
            clusters.forEachIndexed { cluster, (centerX, centerZ) ->
                repeat(3) { tooth ->
                    val fan = tooth - 1
                    val root = Vector3f(
                        (centerX + fan * 0.17).toFloat(), -1.04f,
                        (centerZ + abs(fan) * 0.10).toFloat(),
                    )
                    val tip = Vector3f(
                        (root.x + fan * 0.30 + if (cluster % 2 == 0) -0.09 else 0.09).toFloat(),
                        (root.y + heights[cluster][tooth]).toFloat(),
                        (root.z + if (tooth == 1) 0.12 else -0.04 + fan * 0.08).toFloat(),
                    )
                    val material = if ((cluster + tooth) % 2 == 0) Material.BLUE_ICE else Material.PACKED_ICE
                    segment("frost:shard:$cluster:$tooth", material, root, tip,
                        width = 0.76 + (tooth % 2) * 0.08, height = 0.70)
                }
            }
        }

        StaffSpell.FAULT -> {
            val columns = listOf(-3.38, -2.04, -0.70, 0.70, 2.04, 3.38)
            val rows = listOf(1.85, 3.27, 4.69, 6.11)
            rows.forEachIndexed { row, z ->
                val rowProgress = smoothMelee((progress - row * 0.12) / (1.0 - row * 0.12))
                val rise = 0.68 * rowProgress
                columns.forEachIndexed { rock, x ->
                    val height = if (abs(x) < 0.8) 0.48 + (row % 2) * 0.05 else 0.84 + (rock % 2) * 0.12
                    val width = if (abs(x) < 0.8) 1.02 else 1.18 + (row + rock) % 2 * 0.20
                    val material = when ((row + rock) % 4) {
                        0 -> Material.BASALT
                        1 -> Material.POLISHED_BASALT
                        2 -> Material.BLACKSTONE
                        else -> Material.OBSIDIAN
                    }
                    piece(
                        "fault:row:$row:rock:$rock", material,
                        x + if (row % 2 == 0) (rock - 2.5) * 0.045 else (2.5 - rock) * 0.045,
                        FLOOR_Y + height * 0.5 + rise,
                        z + if (rock % 2 == 0) -0.08 else 0.08,
                        width, height, 0.88 + (rock % 3) * 0.09,
                        yaw = (if (rock % 2 == 0) -1 else 1) * 0.12,
                        pitch = (if ((row + rock) % 2 == 0) -1 else 1) * 0.10,
                        roll = (if (rock % 2 == 0) -1 else 1) * 0.07,
                    )
                }
            }
        }

        StaffSpell.LUNAR -> {
            // One heavy C-shaped sweep, its far center arched forward and its two horns deliberately tilted.
            val points = (0..12).map { index ->
                val t = index / 12.0
                val x = -4.02 + 8.04 * t
                val z = 1.96 + 4.42 * (1.0 - (x / 4.02) * (x / 4.02))
                val y = -0.98 + 0.35 * t + 0.06 * sin(t * PI)
                Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
            }
            repeat(12) { index ->
                val material = when (index % 3) {
                    0 -> Material.CALCITE
                    1 -> Material.QUARTZ_BLOCK
                    else -> Material.SMOOTH_QUARTZ
                }
                val taper = when (index) {
                    0, 11 -> 0.34
                    1, 10 -> 0.72
                    else -> 1.0
                }
                segment("lunar:arc:$index", material, points[index], points[index + 1],
                    width = 0.90 * taper, height = 0.66 * taper)
            }
        }

        StaffSpell.TIDE -> {
            // Five overlapping layers form a thick C-shaped breaker rather than isolated masonry.
            // The center stays below eye height while both shoulders rise into one continuous crest.
            val xs = listOf(-3.80, -2.715, -1.63, -0.545, 0.545, 1.63, 2.715, 3.80)
            val heights = listOf(1.50, 1.38, 1.08, 0.82, 0.82, 1.08, 1.38, 1.50)
            val depths = listOf(4.00, 4.45, 4.80, 5.00, 5.00, 4.80, 4.45, 4.00)
            val yaws = listOf(-0.22, -0.23, -0.15, -0.05, 0.05, 0.15, 0.23, 0.22)
            xs.forEachIndexed { index, x ->
                val height = heights[index]
                val z = depths[index]
                val yaw = yaws[index]
                val coreHeight = (height * 0.72).coerceAtLeast(0.58)
                val coreY = FLOOR_Y + coreHeight * 0.5
                val faceY = FLOOR_Y + height * 0.53
                val crestY = FLOOR_Y + height - 0.21

                piece("tide:core:$index", Material.BLUE_CONCRETE, x, coreY, z,
                    width = 1.54, height = coreHeight, depth = 1.32, yaw = yaw * 0.72,
                    roll = (if (index < 4) -1 else 1) * 0.035)
                piece("tide:backwash:$index", Material.BLUE_STAINED_GLASS, x, faceY, z + 0.31,
                    width = 2.40, height = height * 0.94, depth = 0.72, yaw = yaw,
                    pitch = 0.08)
                piece("tide:water:$index", if (index % 2 == 0) Material.CYAN_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
                    x, faceY, z - 0.20, width = 2.40, height = height, depth = 0.72, yaw = yaw,
                    pitch = -0.12)
                piece("tide:foam:$index", Material.WHITE_CONCRETE, x, crestY, z + 0.12,
                    width = 2.40, height = 0.42, depth = 0.78, yaw = yaw * 0.82,
                    pitch = -0.08)
                piece("tide:curl:$index", if (index % 2 == 0) Material.CYAN_STAINED_GLASS else Material.BLUE_STAINED_GLASS,
                    x, crestY - 0.07, z - 0.36, width = 2.40, height = 0.54, depth = 0.62,
                    yaw = yaw * 0.94, pitch = -0.40)
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
