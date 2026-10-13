package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val STAFF_MELEE_DISPLAY_ARRIVAL_TICK = 2
internal const val STAFF_MELEE_FADE_TICK = 10

private const val MELEE_START_DISTANCE = 1.3
private const val MELEE_PARTICLE_STOP_TICK = 8

/** Local, spell-themed close-attack silhouettes; the renderer supplies the eye anchor and yaw. */
internal fun staffMeleeDisplayParts(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    reach: Double,
): List<StaffDisplayPart> {
    val maxReach = when (spell) {
        StaffSpell.PRISM, StaffSpell.OSSUARY -> 5.0
        StaffSpell.MARK, StaffSpell.FROST, StaffSpell.NOVA, StaffSpell.LUNAR,
        StaffSpell.GALE, StaffSpell.TIDE -> 4.5
        else -> return emptyList()
    }
    val endTick = minOf(durationTicks, STAFF_MELEE_FADE_TICK)
    val targetReach = reach.takeIf(Double::isFinite)?.coerceAtMost(maxReach) ?: return emptyList()
    if (ageTicks < 0 || durationTicks <= STAFF_MELEE_DISPLAY_ARRIVAL_TICK || ageTicks >= endTick ||
        targetReach <= MELEE_START_DISTANCE) return emptyList()

    val progress = smoothMelee(ageTicks.toDouble() / STAFF_MELEE_DISPLAY_ARRIVAL_TICK)
    val shape = meleeShape(spell, progress)
    if (shape.isEmpty()) return emptyList()

    val rawBounds = meleeBounds(shape)
    val depth = (rawBounds.second - rawBounds.first).coerceAtLeast(0.001f)
    val fit = minOf(1.0, (targetReach - MELEE_START_DISTANCE) / depth).toFloat()
    if (!fit.isFinite() || fit <= 0f) return emptyList()

    val fittedDepth = depth * fit
    val front = MELEE_START_DISTANCE + fittedDepth +
        (targetReach - MELEE_START_DISTANCE - fittedDepth) * progress
    // Send the collapsed pose before expiry so the client's final interpolation can finish.
    val lastKeyframe = (endTick - StaffSpellDisplayEffects.FRAME_TICKS).coerceAtLeast(5)
    val fade = (1.0 - smoothMelee((ageTicks - 4.0) / (lastKeyframe - 4.0))).coerceAtLeast(0.001)
    return shape.map { part ->
        part.copy(
            center = Vector3f(
                part.center.x * fit,
                part.center.y * fit,
                (part.center.z - rawBounds.second) * fit + front.toFloat(),
            ),
            scale = Vector3f(part.scale).mul(fit * fade.toFloat()),
        )
    }
}

/** Small native accents sampled from the same moving display parts. */
internal fun staffMeleeParticles(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    reach: Double,
): List<StaffParticleSample> {
    if (ageTicks < 0 || ageTicks >= MELEE_PARTICLE_STOP_TICK || durationTicks <= ageTicks) return emptyList()
    val parts = staffMeleeDisplayParts(spell, ageTicks, durationTicks, reach)
    if (parts.isEmpty()) return emptyList()
    val transition = when (spell) {
        StaffSpell.MARK -> staffFade(0x321056, 0x9c49db, 0.8f)
        StaffSpell.FROST -> staffFade(0x8fdcff, 0xe8fbff, 0.9f)
        StaffSpell.NOVA -> staffFade(0x326a37, 0x9ddd68, 0.9f)
        StaffSpell.LUNAR -> staffFade(0x9ca5d9, 0xf4f2ff, 0.8f)
        StaffSpell.GALE -> staffFade(0x82949b, 0xf3fbff, 0.75f)
        StaffSpell.TIDE -> staffFade(0x167fb5, 0x88f0ff, 0.9f)
        StaffSpell.PRISM -> staffFade(0x623aaf, 0xe4bdff, 0.9f)
        StaffSpell.OSSUARY -> staffFade(0x9f8d72, 0xfff0d2, 0.8f)
        else -> return emptyList()
    }
    val accents = when (spell) {
        StaffSpell.MARK -> listOf("mark:claw:1:tip" to Particle.PORTAL, "mark:claw:0:root" to Particle.SOUL)
        StaffSpell.FROST -> listOf("frost:fan:3" to Particle.SNOWFLAKE, "frost:fan:1" to Particle.DUST_COLOR_TRANSITION)
        StaffSpell.NOVA -> listOf("nova:whip:0:2" to Particle.COMPOSTER, "nova:thorn:1" to Particle.HAPPY_VILLAGER)
        StaffSpell.LUNAR -> listOf("lunar:blade:3" to Particle.END_ROD, "lunar:hilt" to Particle.DUST_COLOR_TRANSITION)
        StaffSpell.GALE -> listOf("gale:blade:0:2" to Particle.SWEEP_ATTACK, "gale:blade:1:3" to Particle.DUST_COLOR_TRANSITION)
        StaffSpell.TIDE -> listOf("tide:water:3" to Particle.SPLASH, "tide:foam:3" to Particle.FALLING_WATER)
        StaffSpell.PRISM -> listOf("prism:core" to Particle.END_ROD, "prism:facet:0" to Particle.DUST_COLOR_TRANSITION)
        StaffSpell.OSSUARY -> listOf("ossuary:shaft:1" to Particle.CRIT, "ossuary:tip:2" to Particle.DUST_COLOR_TRANSITION)
        else -> return emptyList()
    }
    val byIdentity = parts.associateBy(StaffDisplayPart::identity)
    return accents.mapNotNull { (identity, particle) ->
        val center = byIdentity[identity]?.center ?: return@mapNotNull null
        StaffParticleSample(
            particle = particle,
            offset = Vector(center.x.toDouble(), center.y.toDouble(), center.z.toDouble()),
            spread = Vector(0.012, 0.012, 0.012),
            data = if (particle == Particle.DUST_COLOR_TRANSITION) transition else null,
        )
    }
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
    ) = add(StaffDisplayPart(material, Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
        Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()), Quaternionf(), identity = identity))

    fun segment(
        identity: String,
        material: Material,
        from: Vector3f,
        to: Vector3f,
        width: Double,
        height: Double,
    ) {
        val delta = Vector3f(to).sub(from)
        val length = delta.length()
        if (length <= 0.001f) return
        add(StaffDisplayPart(
            material,
            Vector3f(from).add(to).mul(0.5f),
            Vector3f(width.toFloat(), height.toFloat(), length),
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.div(length)),
            identity = identity,
        ))
    }

    when (spell) {
        StaffSpell.MARK -> repeat(3) { claw ->
            val side = claw - 1
            val back = Vector3f(side * 0.55f, -0.08f, -0.98f)
            val middle = Vector3f(side * 0.28f, 0.02f, -0.50f)
            val tip = Vector3f(side * 0.035f, 0.13f, -0.06f)
            segment("mark:claw:$claw:root", Material.CRYING_OBSIDIAN, back, middle, 0.30, 0.26)
            segment("mark:claw:$claw:tip", Material.PURPLE_STAINED_GLASS, middle, tip, 0.22, 0.20)
        }

        StaffSpell.FROST -> repeat(7) { shard ->
            val spread = (shard - 3) / 3f
            val fan = 0.50f + 1.05f * progress.toFloat()
            val back = Vector3f(spread * fan * 0.72f, -0.33f + kotlin.math.abs(spread) * 0.06f, -0.94f)
            val tip = Vector3f(spread * fan, 0.28f - kotlin.math.abs(spread) * 0.08f, -0.08f)
            segment("frost:fan:$shard", if (shard % 2 == 0) Material.BLUE_ICE else Material.PACKED_ICE,
                back, tip, 0.24, 0.34)
        }

        StaffSpell.NOVA -> {
            val inward = 0.18f + (1.0f - progress.toFloat()) * 0.60f
            fun whipX(side: Float, t: Float) = if (side < 0f) {
                -1.55f + (1.55f + inward) * t
            } else {
                1.55f - (1.55f + inward) * t
            }
            repeat(2) { whip ->
                val side = if (whip == 0) -1f else 1f
                repeat(4) { joint ->
                    val t0 = joint / 4f
                    val t1 = (joint + 1) / 4f
                val curl = 0.12f + 0.12f * progress.toFloat()
                    fun point(t: Float) = Vector3f(
                        whipX(side, t),
                        0.02f + sin(t * PI).toFloat() * 0.20f,
                    -0.98f + t * 0.88f + sin(t * PI).toFloat() * curl,
                    )
                    segment("nova:whip:$whip:$joint", if (joint % 2 == 0) Material.MOSS_BLOCK else Material.MOSSY_COBBLESTONE,
                        point(t0), point(t1), 0.34, 0.25)
                }
            }
            repeat(4) { thorn ->
                val whip = thorn / 2
                val side = if (whip == 0) -1f else 1f
                val t = if (thorn % 2 == 0) 0.28f else 0.72f
                val root = Vector3f(whipX(side, t), 0.16f,
                    -0.98f + t * 0.88f + sin(t * PI).toFloat() * (0.12f + 0.12f * progress.toFloat()))
                val tip = Vector3f(root).add(side * 0.24f, 0.20f, 0.11f)
                segment("nova:thorn:$thorn", Material.FLOWERING_AZALEA_LEAVES, root, tip, 0.18, 0.24)
            }
        }

        StaffSpell.LUNAR -> {
            val halfSpan = 1.42 + progress * 0.43
            val sweep = (-0.52 + progress * 0.95)
            fun swing(point: Vector3f) = Vector3f(
                (point.x * cos(sweep) - point.y * sin(sweep)).toFloat(),
                (point.x * sin(sweep) + point.y * cos(sweep)).toFloat(),
                point.z,
            )
            repeat(7) { joint ->
                val t0 = joint / 7.0
                val t1 = (joint + 1) / 7.0
                fun point(t: Double) = swing(Vector3f(
                    (-halfSpan + 2.0 * halfSpan * t).toFloat(),
                    (-0.56 + 1.12 * t + 0.27 * sin(t * PI)).toFloat(),
                    (-0.72 + 0.20 * sin(t * PI)).toFloat(),
                ))
                segment("lunar:blade:$joint", if (joint % 2 == 0) Material.WHITE_STAINED_GLASS else Material.SEA_LANTERN,
                    point(t0), point(t1), 0.40, 0.22)
            }
            segment("lunar:hilt", Material.IRON_BLOCK,
                swing(Vector3f((-halfSpan - 0.14).toFloat(), -0.64f, -0.82f)),
                swing(Vector3f((-halfSpan + 0.32).toFloat(), -0.40f, -0.77f)), 0.20, 0.18)
        }

        StaffSpell.GALE -> repeat(2) { blade ->
            repeat(5) { joint ->
                val t0 = joint / 5.0
                val t1 = (joint + 1) / 5.0
                val halfWidth = 0.82 + 0.93 * progress
                val curl = 0.20 + 0.34 * progress
                fun point(t: Double) = Vector3f(
                    (-halfWidth + t * 2.0 * halfWidth).toFloat(),
                    (blade * 0.30 + 0.08 * sin(t * PI)).toFloat(),
                    (-0.72 + curl * sin(t * PI)).toFloat(),
                )
                segment("gale:blade:$blade:$joint",
                    if (joint % 2 == 0) Material.WHITE_STAINED_GLASS else Material.LIGHT_GRAY_STAINED_GLASS,
                    point(t0), point(t1), 0.46, 0.18)
            }
        }

        StaffSpell.TIDE -> {
            val halfWidth = 0.70 + 0.80 * progress
            val curlHeight = 0.42 + 0.30 * progress
            val curlDepth = 0.10 + 0.14 * progress
            repeat(7) { joint ->
                val t0 = joint / 7.0
                val t1 = (joint + 1) / 7.0
                fun point(t: Double, foam: Boolean = false) = Vector3f(
                    (-halfWidth + t * 2.0 * halfWidth).toFloat(),
                    (-0.40 + curlHeight * sin(t * PI) + if (foam) 0.22 else 0.0).toFloat(),
                    (-0.84 + 0.52 * t + curlDepth * sin(t * PI) + if (foam) 0.05 else 0.0).toFloat(),
                )
                segment("tide:water:$joint", when (joint % 3) {
                    0 -> Material.BLUE_STAINED_GLASS
                    1 -> Material.CYAN_STAINED_GLASS
                    else -> Material.LIGHT_BLUE_STAINED_GLASS
                }, point(t0), point(t1), 0.76, 0.40)
                segment("tide:foam:$joint", if (joint % 2 == 0) Material.WHITE_STAINED_GLASS else Material.SEA_LANTERN,
                    point(t0, true), point(t1, true), 0.12, 0.10)
            }
        }

        StaffSpell.PRISM -> {
            piece("prism:core", Material.AMETHYST_BLOCK, 0.0, 0.0, -0.54, 0.48, 0.46, 0.88)
            piece("prism:tip", Material.AMETHYST_BLOCK, 0.0, 0.0, -0.08, 0.30, 0.30, 0.22)
            piece("prism:light", Material.PURPLE_STAINED_GLASS, 0.0, 0.0, -0.50, 0.16, 0.30, 0.68)
            repeat(2) { facet ->
                val side = if (facet == 0) -1.0 else 1.0
                piece("prism:facet:$facet", Material.BUDDING_AMETHYST, side * 0.24, 0.0, -0.52,
                    0.12, 0.28, 0.62)
            }
        }

        StaffSpell.OSSUARY -> repeat(3) { spike ->
            val side = spike - 1
            val base = Vector3f(side * 0.24f, if (side == 0) -0.20f else -0.29f, -0.98f)
            val shoulder = Vector3f(side * 0.27f, if (side == 0) 0.18f else 0.10f, -0.34f)
            val tip = Vector3f(side * 0.28f, if (side == 0) 0.33f else 0.24f, -0.07f)
            segment("ossuary:shaft:$spike", Material.BONE_BLOCK, base, shoulder, 0.19, 0.23)
            segment("ossuary:tip:$spike", Material.CALCITE, shoulder, tip, 0.16, 0.18)
        }

        else -> Unit
    }
}

private fun meleeBounds(parts: List<StaffDisplayPart>): Pair<Float, Float> {
    var minZ = Float.POSITIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    for (part in parts) {
        for (x in listOf(-0.5f, 0.5f)) for (y in listOf(-0.5f, 0.5f)) for (z in listOf(-0.5f, 0.5f)) {
            val corner = Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z)
            part.rotation.transform(corner).add(part.center)
            minZ = minOf(minZ, corner.z)
            maxZ = maxOf(maxZ, corner.z)
        }
    }
    return minZ to maxZ
}

private fun smoothMelee(value: Double): Double = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)
    ?.let { it * it * (3.0 - 2.0 * it) } ?: 0.0
