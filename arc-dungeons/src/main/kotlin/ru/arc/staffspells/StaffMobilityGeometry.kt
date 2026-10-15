package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One authored mobility silhouette per available staff; local coordinates follow the travel heading. */
internal fun staffMobilityDisplayParts(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    direction: Vector,
): List<StaffDisplayPart> {
    val progress = (ageTicks.toDouble() / durationTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val phase = progress * PI
    val frame = MobilityParts(spell, direction, horizontalScale = 1.3 + 0.12 * sin(phase))
    when (spell) {
        StaffSpell.MARK -> {
            val radius = 0.55 + 0.18 * sin(phase)
            for (index in 0 until 7) {
                val angle = index * PI * 2.0 / 7.0 + phase * 0.3
                frame.cube("mark-gate-$index", sin(angle) * radius, 0.82 + cos(angle) * 0.25,
                    cos(angle) * radius, 0.16, 0.36, 0.16,
                    if (index % 2 == 0) Material.PURPLE_CONCRETE else Material.AMETHYST_BLOCK)
            }
            frame.bar("mark-key", 0.0, 0.55, 0.0, 0.0, 1.32 + 0.15 * sin(phase), 0.0,
                0.17, Material.CRYING_OBSIDIAN)
        }
        StaffSpell.CHAIN -> {
            for (index in 0 until 8) {
                val t = index / 7.0
                frame.cube("chain-link-$index", sin(t * PI * 2.0 + phase) * 0.23,
                    0.25 + t * 1.18, -0.95 + t * 1.25,
                    0.12, 0.25, 0.12, if (index % 2 == 0) Material.IRON_BLOCK else Material.IRON_CHAIN)
            }
            frame.cube("chain-anchor", 0.0, 1.45, 0.42, 0.34, 0.34, 0.34, Material.LIGHTNING_ROD)
        }
        StaffSpell.REQUIEM -> {
            frame.cube("requiem-coffin", 0.0, 0.8, 0.0, 0.46, 0.82, 0.24, Material.BLACKSTONE)
            for (index in 0 until 5) {
                val side = if (index % 2 == 0) -1.0 else 1.0
                frame.bar("requiem-shard-$index", side * (0.42 + index * 0.035), 0.2 + index * 0.23,
                    -0.18, side * (0.7 + index * 0.035), 0.34 + index * 0.23,
                    0.14 + index * 0.05, 0.12, if (index % 2 == 0) Material.CRYING_OBSIDIAN else Material.SOUL_SAND)
            }
            frame.cube("requiem-lantern", 0.0, 1.42, 0.12, 0.22, 0.22, 0.22, Material.SOUL_LANTERN)
        }
        StaffSpell.NECROMANCER -> {
            frame.bar("necro-spine", 0.0, 0.18, -0.25, 0.0, 1.48, -0.25, 0.18, Material.BONE_BLOCK)
            for (index in 0 until 5) {
                val y = 0.35 + index * 0.22
                val width = 0.34 + index * 0.035
                frame.bar("necro-rib-left-$index", -0.08, y, -0.25, -width, y + 0.17, -0.02,
                    0.12, Material.BONE_BLOCK)
                frame.bar("necro-rib-right-$index", 0.08, y, -0.25, width, y + 0.17, -0.02,
                    0.12, Material.BONE_BLOCK)
            }
        }
        StaffSpell.FROST -> {
            // Low twin runners and a raised nose make the slide read as a sled, not a speed ring.
            for (side in listOf(-1.0, 1.0)) {
                frame.bar("frost-runner-$side", side * 0.26, 0.08, -1.22,
                    side * 0.26, 0.08, 0.58, 0.12, Material.PACKED_ICE)
                frame.cube("frost-fin-$side", side * 0.3, 0.34 + 0.05 * sin(phase), 0.44,
                    0.14, 0.48, 0.15, Material.BLUE_ICE)
            }
            frame.cube("frost-nose", 0.0, 0.22, 0.73, 0.44, 0.2, 0.5, Material.SNOW_BLOCK)
            frame.cube("frost-keel", 0.0, 0.1, -0.58, 0.2, 0.12, 0.58, Material.ICE)
        }
        StaffSpell.TIDE -> {
            for (index in 0 until 7) {
                val t = index / 6.0
                val x = (t - 0.5) * 1.2
                val y = 0.28 + sin(t * PI) * (0.58 + 0.12 * sin(phase))
                frame.cube("tide-crest-$index", x, y, 0.3 + t * 0.46,
                    0.22, 0.18 + 0.12 * sin(t * PI), 0.22,
                    if (index % 2 == 0) Material.PRISMARINE else Material.CYAN_STAINED_GLASS)
            }
            frame.bar("tide-trough", -0.58, 0.12, -0.75, 0.58, 0.12, -0.75, 0.11, Material.DARK_PRISMARINE)
            frame.cube("tide-spray", 0.0, 0.7 + 0.12 * sin(phase), 0.68, 0.18, 0.18, 0.18, Material.SEA_LANTERN)
        }
        StaffSpell.FAULT -> {
            for (index in 0 until 6) {
                val sign = if (index % 2 == 0) -1.0 else 1.0
                val z = -1.05 + index * 0.34
                frame.bar("fault-fissure-$index", sign * 0.44, 0.08, z,
                    -sign * 0.16, 0.11, z + 0.34, 0.13, if (index % 2 == 0) Material.DEEPSLATE else Material.AMETHYST_BLOCK)
            }
            frame.cube("fault-keystone", 0.0, 0.26 + 0.08 * sin(phase), 0.28,
                0.4, 0.36, 0.42, Material.CRYING_OBSIDIAN)
            frame.cube("fault-chip-left", -0.55, 0.48, -0.12, 0.18, 0.3, 0.18, Material.BASALT)
            frame.cube("fault-chip-right", 0.55, 0.39, 0.1, 0.18, 0.24, 0.18, Material.POLISHED_BASALT)
        }
        StaffSpell.OSSUARY -> {
            // A low forward bone ram, distinct from NECROMANCER's upright spectral ribs.
            for (side in listOf(-1.0, 1.0)) {
                frame.bar("ossuary-tusk-base-$side", side * .36, .36, -.18,
                    side * .70, .62, .38, .20, Material.BONE_BLOCK)
                frame.bar("ossuary-tusk-curve-$side", side * .70, .62, .38,
                    side * .42, .85, .98, .16, Material.CALCITE)
                frame.bar("ossuary-tusk-tip-$side", side * .42, .85, .98,
                    side * .27, .92, 1.22, .10, Material.BONE_BLOCK)
            }
            repeat(3) { index ->
                frame.cube("ossuary-face-$index", (index - 1) * .21, .62, .18,
                    .20, if (index == 1) .50 else .36, .24, Material.BONE_BLOCK)
            }
            frame.bar("ossuary-jaw", -.48, .32, .04, .48, .32, .04, .14, Material.CALCITE)
        }
        StaffSpell.LANCE -> {
            frame.bar("lance-shaft", 0.0, 0.12, -.38, 0.0, .86, 1.18, 0.13, Material.IRON_BLOCK)
            frame.bar("lance-tip", 0.0, .86, 1.18, 0.0, 1.03, 1.55, 0.17, Material.QUARTZ_BLOCK)
            frame.bar("lance-wing-left", 0.0, .68, .78, -.35, .62, 1.0, 0.10, Material.GOLD_BLOCK)
            frame.bar("lance-wing-right", 0.0, .68, .78, .35, .62, 1.0, 0.10, Material.GOLD_BLOCK)
            frame.cube("lance-counterweight", 0.0, 0.18, -.25, 0.22, 0.22, 0.22, Material.COPPER_BLOCK)
        }
        StaffSpell.EMBER -> {
            for (index in 0 until 6) {
                val t = index / 5.0
                val spread = 0.12 + t * 0.48
                frame.bar("ember-jet-left-$index", -0.12, 0.48, -0.28 - t * 0.92,
                    -spread, 0.4 + t * 0.1, -0.42 - t * 1.05, 0.13,
                    if (index % 2 == 0) Material.ORANGE_CONCRETE else Material.MAGMA_BLOCK)
                frame.bar("ember-jet-right-$index", 0.12, 0.48, -0.28 - t * 0.92,
                    spread, 0.4 + t * 0.1, -0.42 - t * 1.05, 0.13,
                    if (index % 2 == 0) Material.ORANGE_CONCRETE else Material.MAGMA_BLOCK)
            }
            frame.cube("ember-nozzle", 0.0, 0.48, -0.22, 0.33, 0.33, 0.3, Material.BLACKSTONE)
        }
        StaffSpell.GRAVITY -> {
            for (index in 0 until 7) {
                val t = index / 6.0
                val angle = t * PI * 2.4 + phase * 0.4
                val radius = 0.18 + t * 0.55
                frame.cube("gravity-stone-$index", cos(angle) * radius, 0.2 + t * 1.42,
                    sin(angle) * radius, 0.17 + t * 0.05, 0.17 + t * 0.05, 0.17 + t * 0.05,
                    if (index % 2 == 0) Material.DEEPSLATE_BRICKS else Material.OBSIDIAN)
            }
            frame.cube("gravity-core", 0.0, 1.45 + 0.12 * sin(phase), 0.0, 0.28, 0.28, 0.28, Material.AMETHYST_BLOCK)
        }
        StaffSpell.GALE -> {
            for (index in 0 until 5) {
                val t = index / 4.0
                val x = 0.2 + t * 0.82
                val y = 0.92 + t * 0.32 + 0.05 * sin(phase + t)
                val z = -0.45 - t * 0.3
                frame.bar("gale-feather-left-$index", -0.12, y, z, -x, y + 0.1, z + 0.42,
                    0.12, if (index % 2 == 0) Material.WHITE_CONCRETE else Material.LIGHT_BLUE_STAINED_GLASS)
                frame.bar("gale-feather-right-$index", 0.12, y, z, x, y + 0.1, z + 0.42,
                    0.12, if (index % 2 == 0) Material.WHITE_CONCRETE else Material.LIGHT_BLUE_STAINED_GLASS)
            }
            frame.cube("gale-keel", 0.0, 0.84, 0.05, 0.18, 0.25, 0.58, Material.QUARTZ_BLOCK)
        }
        StaffSpell.NOVA -> {
            fun point(t: Double) = doubleArrayOf(.12 * sin(t * PI * 2.0 + phase * .7),
                .54 + t * .25 + .06 * sin(t * PI + phase), .24 + t * 1.65)
            repeat(7) { index ->
                val from = point(index / 7.0)
                val to = point((index + 1) / 7.0)
                frame.bar("nova-vine-$index", from[0], from[1], from[2], to[0], to[1], to[2],
                    .14, if (index % 3 == 0) Material.MOSS_BLOCK else Material.DARK_OAK_WOOD)
            }
            repeat(4) { index ->
                val point = point((index + 1) / 5.0)
                val side = if (index % 2 == 0) -1.0 else 1.0
                frame.bar("nova-thorn-$index", point[0], point[1], point[2],
                    point[0] + side * .24, point[1] + .14, point[2] - .15, .09, Material.MOSS_BLOCK)
            }
            for (side in listOf(-1.0, 1.0)) {
                frame.bar("nova-hook-$side", 0.0, .79, 1.89, side * .26, .94, 2.12,
                    .12, Material.FLOWERING_AZALEA_LEAVES)
            }
        }
        StaffSpell.LUNAR -> {
            for (index in 0 until 8) {
                val angle = PI * 0.25 + index * PI * 1.55 / 7.0
                val radius = 0.62 + 0.08 * sin(phase)
                frame.cube("lunar-crescent-$index", -0.38 + cos(angle) * radius,
                    0.9 + sin(angle) * radius, 0.1,
                    0.18, 0.24, 0.14,
                    if (index % 2 == 0) Material.QUARTZ_BLOCK else Material.LIGHT_BLUE_STAINED_GLASS)
            }
            frame.cube("lunar-core", 0.0, 0.86, 0.16, 0.28, 0.34, 0.22, Material.AMETHYST_BLOCK)
            frame.cube("lunar-tail", 0.42, 0.38, -0.22, 0.16, 0.34, 0.16, Material.CRYING_OBSIDIAN)
        }
        StaffSpell.PRISM -> {
            val facets = listOf(Material.RED_STAINED_GLASS, Material.ORANGE_STAINED_GLASS,
                Material.YELLOW_STAINED_GLASS, Material.LIME_STAINED_GLASS,
                Material.CYAN_STAINED_GLASS, Material.PURPLE_STAINED_GLASS)
            for (index in facets.indices) {
                val side = if (index % 2 == 0) -1.0 else 1.0
                frame.cube("prism-facet-$index", side * (0.26 + index * 0.09),
                    0.32 + (index % 3) * 0.42 + 0.06 * sin(phase + index),
                    -0.35 + index * 0.23, 0.28, 0.28, 0.18, facets[index])
            }
            frame.cube("prism-center", 0.0, 0.92, 0.18, 0.24, 0.34, 0.24, Material.AMETHYST_BLOCK)
            frame.bar("prism-tail", 0.0, 0.25, -0.82, 0.0, 0.25, -0.18, 0.11, Material.WHITE_STAINED_GLASS)
        }
        StaffSpell.ICICLE -> {
            for (index in 0 until 5) {
                val rise = index * 0.24
                val z = -0.92 + index * 0.38
                frame.cube("icicle-step-$index", if (index % 2 == 0) -0.22 else 0.22,
                    0.1 + rise + 0.05 * sin(phase + index), z, 0.34, 0.16, 0.38,
                    if (index % 2 == 0) Material.PACKED_ICE else Material.BLUE_ICE)
            }
            frame.bar("icicle-spike-left", -0.4, 0.35, 0.3, -0.58, 1.0, 0.58, 0.14, Material.ICE)
            frame.bar("icicle-spike-right", 0.4, 0.35, 0.3, 0.58, 1.0, 0.58, 0.14, Material.ICE)
        }
        StaffSpell.INFERNO -> return emptyList()
    }
    return frame.parts
}

internal const val STAFF_MOBILITY_AFTERIMAGE_TICKS = 8

/** The staff's own silhouette breaks outward near the feet at departure and braking. */
internal fun staffMobilityAfterimageParts(spell: StaffSpell, ageTicks: Int, direction: Vector): List<StaffDisplayPart> {
    if (ageTicks >= STAFF_MOBILITY_AFTERIMAGE_TICKS) return emptyList()
    val progress = (ageTicks.toDouble() / STAFF_MOBILITY_AFTERIMAGE_TICKS).coerceIn(0.0, 1.0)
    val spread = (0.8 + progress * 0.9).toFloat()
    val size = (0.8 - progress * 0.65).toFloat()
    return staffMobilityDisplayParts(spell, 0, STAFF_MOBILITY_AFTERIMAGE_TICKS, direction).map { part ->
        part.copy(center = Vector3f(part.center.x * spread, 0.06f + part.center.y * 0.2f, part.center.z * spread),
            scale = Vector3f(part.scale).mul(size), identity = "afterimage:${part.identity}")
    }
}

/** Quiet travel accents, with a wider material burst only at departure and arrival. */
internal fun staffMobilityParticleBurst(spell: StaffSpell, impulse: Boolean = false): List<StaffParticleSample> {
    val offsets = if (impulse) listOf(Vector(0.0, 0.18, -0.9), Vector(-0.85, 0.22, -0.3),
        Vector(0.85, 0.22, -0.3), Vector(-0.72, 0.2, 0.64), Vector(0.72, 0.2, 0.64),
        Vector(0.0, 0.26, 1.0))
    else listOf(Vector(0.0, 0.14, -0.2), Vector(-0.32, 0.18, 0.0), Vector(0.32, 0.18, 0.0))
    val particles = when (spell) {
        StaffSpell.MARK -> listOf(Particle.REVERSE_PORTAL, Particle.END_ROD, Particle.REVERSE_PORTAL)
        StaffSpell.CHAIN -> listOf(Particle.ELECTRIC_SPARK, Particle.END_ROD, Particle.ELECTRIC_SPARK)
        StaffSpell.REQUIEM -> listOf(Particle.SOUL, Particle.REVERSE_PORTAL, Particle.SOUL)
        StaffSpell.NECROMANCER -> listOf(Particle.SOUL, Particle.SOUL_FIRE_FLAME, Particle.SOUL)
        StaffSpell.FROST, StaffSpell.ICICLE -> listOf(Particle.SNOWFLAKE, Particle.SNOWFLAKE, Particle.SNOWFLAKE)
        StaffSpell.TIDE -> listOf(Particle.SPLASH, Particle.BUBBLE, Particle.SPLASH)
        StaffSpell.FAULT, StaffSpell.OSSUARY -> listOf(Particle.BLOCK, Particle.DUST, Particle.BLOCK)
        StaffSpell.LANCE -> listOf(Particle.END_ROD, Particle.CRIT, Particle.END_ROD)
        StaffSpell.EMBER -> listOf(Particle.FLAME, Particle.FLAME, Particle.LAVA)
        StaffSpell.GRAVITY -> listOf(Particle.REVERSE_PORTAL, Particle.DUST, Particle.REVERSE_PORTAL)
        StaffSpell.GALE -> listOf(Particle.CLOUD, Particle.END_ROD, Particle.CLOUD)
        StaffSpell.NOVA -> listOf(Particle.HAPPY_VILLAGER, Particle.DUST, Particle.HAPPY_VILLAGER)
        StaffSpell.PRISM -> listOf(Particle.DUST_COLOR_TRANSITION, Particle.END_ROD, Particle.DUST_COLOR_TRANSITION)
        StaffSpell.LUNAR -> listOf(Particle.END_ROD, Particle.REVERSE_PORTAL, Particle.END_ROD)
        StaffSpell.INFERNO -> return emptyList()
    }
    return offsets.mapIndexed { index, offset ->
        val particle = particles[index % particles.size]
        val data = when (particle) {
            Particle.DUST -> staffDust(when (spell) {
                StaffSpell.NOVA -> 0x8FD59A
                StaffSpell.OSSUARY -> 0xDED8C8
                else -> 0x7468C9
            }, 1.05f)
            Particle.DUST_COLOR_TRANSITION -> staffFade(0x8B63FF, 0xF1F6FF, 1.15f)
            Particle.BLOCK -> (if (spell == StaffSpell.OSSUARY) Material.BONE_BLOCK else Material.DEEPSLATE).createBlockData()
            else -> null
        }
        StaffParticleSample(particle, offset, count = if (impulse) 4 else 3,
            spread = if (impulse) Vector(0.25, 0.18, 0.25) else Vector(0.16, 0.12, 0.16), data = data)
    }
}

private class MobilityParts(private val spell: StaffSpell, direction: Vector, private val horizontalScale: Double) {
    val parts = mutableListOf<StaffDisplayPart>()
    private val forward = direction.clone().setY(0.0).let {
        if (!it.lengthSquared().isFinite() || it.lengthSquared() < 1.0e-9) Vector(0.0, 0.0, 1.0)
        else it.normalize()
    }
    private val right = Vector(forward.z, 0.0, -forward.x)
    private val facing = Quaternionf().rotationY(atan2(forward.x, forward.z).toFloat())

    fun cube(id: String, x: Double, y: Double, z: Double,
        sx: Double, sy: Double, sz: Double, material: Material) {
        val center = world(x, y, z)
        parts += StaffDisplayPart(material, vector(center),
            Vector3f((sx * horizontalScale).toFloat(), (sy * .82).toFloat(), (sz * horizontalScale).toFloat()),
            Quaternionf(facing), identity = "mobility:${spell.id}:$id")
    }

    fun bar(id: String, x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        width: Double, material: Material) {
        val start = world(x1, y1, z1)
        val end = world(x2, y2, z2)
        val delta = end.clone().subtract(start)
        val length = delta.length().coerceAtLeast(0.12)
        val midpoint = start.clone().add(end).multiply(0.5)
        val axis = Vector3f(delta.x.toFloat(), delta.y.toFloat(), delta.z.toFloat()).normalize()
        parts += StaffDisplayPart(material, vector(midpoint),
            Vector3f((width * 1.1).toFloat(), length.toFloat(), (width * 1.1).toFloat()),
            Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), axis),
            identity = "mobility:${spell.id}:$id")
    }

    private fun world(x: Double, y: Double, z: Double) = Vector(
        (right.x * x + forward.x * z) * horizontalScale,
        y * .82,
        (right.z * x + forward.z * z) * horizontalScale,
    )

    private fun vector(point: Vector) = Vector3f(point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
}
