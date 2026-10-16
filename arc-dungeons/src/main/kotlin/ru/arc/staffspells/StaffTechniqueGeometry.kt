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
import kotlin.math.sqrt

internal const val STAFF_LUNAR_OUTBOUND_TICKS = 10
internal const val STAFF_LUNAR_TURN_TICKS = 2
internal const val STAFF_LUNAR_RETURN_TICKS = 14
internal const val STAFF_LUNAR_TOTAL_TICKS =
    STAFF_LUNAR_OUTBOUND_TICKS + STAFF_LUNAR_TURN_TICKS + STAFF_LUNAR_RETURN_TICKS
internal const val STAFF_LUNAR_OUTBOUND_BLOCKS = 18.0
internal const val STAFF_LUNAR_MAX_TRAVEL_BLOCKS = 38.0

internal data class StaffLunarBoomerangPose(
    val position: Vector3f,
    val facingYaw: Float,
    val complete: Boolean,
)

/** Predictable outbound arc, two-tick turn, and return lane used by the live controller and tests. */
internal fun staffLunarBoomerangPose(
    origin: Vector3f,
    forward: Vector3f,
    caster: Vector3f,
    ageTicks: Int,
): StaffLunarBoomerangPose {
    val axis = Vector3f(forward).apply { y = 0f }
    if (axis.lengthSquared() < 1.0e-6f || !axis.lengthSquared().isFinite()) axis.set(0f, 0f, 1f)
    axis.normalize()
    val side = Vector3f(axis.z, 0f, -axis.x)
    val age = ageTicks.coerceIn(0, STAFF_LUNAR_TOTAL_TICKS)
    val apex = Vector3f(origin).fma(STAFF_LUNAR_OUTBOUND_BLOCKS.toFloat(), axis)

    val position: Vector3f
    val facingYaw: Float
    when {
        age <= STAFF_LUNAR_OUTBOUND_TICKS -> {
            val progress = age.toFloat() / STAFF_LUNAR_OUTBOUND_TICKS
            val bow = sin(PI * progress).toFloat() * 0.42f
            position = Vector3f(origin).fma((STAFF_LUNAR_OUTBOUND_BLOCKS * progress).toFloat(), axis).fma(bow, side)
            val facing = Vector3f(axis).mul(STAFF_LUNAR_OUTBOUND_BLOCKS.toFloat())
                .fma(cos(PI * progress).toFloat() * 0.42f * PI.toFloat(), side)
            facingYaw = atan2(-facing.x, facing.z).toFloat()
        }
        age <= STAFF_LUNAR_OUTBOUND_TICKS + STAFF_LUNAR_TURN_TICKS -> {
            val p = (age - STAFF_LUNAR_OUTBOUND_TICKS).toFloat() / STAFF_LUNAR_TURN_TICKS
            val outbound = Vector3f(axis).mul(STAFF_LUNAR_OUTBOUND_BLOCKS.toFloat())
                .fma(-0.42f * PI.toFloat(), side)
            val outboundYaw = atan2(-outbound.x, outbound.z).toFloat()
            val returnYaw = atan2(axis.x, -axis.z).toFloat()
            facingYaw = outboundYaw + wrapTechniqueAngle(returnYaw - outboundYaw) * smoothTechnique(p)
            position = Vector3f(apex)
        }
        else -> {
            val progress = ((age - STAFF_LUNAR_OUTBOUND_TICKS - STAFF_LUNAR_TURN_TICKS).toFloat() /
                STAFF_LUNAR_RETURN_TICKS).coerceIn(0f, 1f)
            val from = Vector3f(apex)
            val to = Vector3f(caster)
            position = from.lerp(to, progress)
            val towardCaster = to.sub(from)
            facingYaw = if (towardCaster.x * towardCaster.x + towardCaster.z * towardCaster.z < 1.0e-6f)
                atan2(axis.x, -axis.z).toFloat()
            else atan2(-towardCaster.x, towardCaster.z).toFloat()
        }
    }
    return StaffLunarBoomerangPose(position, facingYaw, age >= STAFF_LUNAR_TOTAL_TICKS)
}

/** A solid lunar blade leads a translucent wake; both turn together at the return apex. */
internal fun staffLunarBoomerangParts(facingYaw: Float, ageTicks: Int): List<StaffDisplayPart> {
    val blade = List(12) { index ->
        val angle = Math.toRadians(65.0 + index * (230.0 / 11.0))
        val taper = when (index) {
            0, 11 -> 0.58f
            1, 10 -> 0.76f
            2, 9 -> 0.90f
            else -> 1.0f + 0.12f * sin(index * PI / 11.0).toFloat()
        }
        val radius = 1.05
        val local = Vector3f((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat(), 0f)
        Quaternionf().rotateY(facingYaw).transform(local)
        val material = when {
            index == 0 || index == 11 -> Material.PURPUR_BLOCK
            index % 4 == 0 -> Material.BUDDING_AMETHYST
            else -> Material.AMETHYST_BLOCK
        }
        val spin = (angle + PI / 2.0).toFloat() +
            (if (index == 0 || index == 11) if (index == 0) -0.12f else 0.12f else 0f)
        StaffDisplayPart(
            material,
            local,
            Vector3f(0.48f * taper, 0.52f * taper, 0.34f),
            Quaternionf().rotateY(facingYaw).rotateZ(spin),
            identity = "lunar:boomerang:$index",
        )
    }
    val turn = ageTicks in STAFF_LUNAR_OUTBOUND_TICKS..(STAFF_LUNAR_OUTBOUND_TICKS + STAFF_LUNAR_TURN_TICKS + 2)
    val behind = Quaternionf().rotateY(facingYaw).transform(Vector3f(0f, 0f, if (turn) -1.05f else -.68f))
    return blade + blade.mapIndexed { index, part ->
        part.copy(material = if (index % 3 == 0) Material.MAGENTA_STAINED_GLASS else Material.PURPLE_STAINED_GLASS,
            center = Vector3f(part.center).mul(if (turn) 1.2f else 1.08f).add(behind),
            scale = Vector3f(part.scale).mul(1.08f, 1.08f, if (turn) 1.6f else 1.2f),
            identity = "lunar:boomerang:wake:$index")
    }
}

internal fun staffChainTechniqueParts(yaw: Float, pulse: Int): List<StaffDisplayPart> {
    val rotation = Quaternionf().rotateY(yaw)
    val radius = .48 + .05 * sin(pulse.coerceAtLeast(0) * .55)
    return buildList(9) {
        add(StaffDisplayPart(Material.SEA_LANTERN, Vector3f(), Vector3f(.30f), Quaternionf(rotation),
            identity = "chain:q:core"))
        repeat(2) { plane ->
            fun point(index: Int): Vector3f {
                val angle = index * PI / 2.0 + pulse * .13 + plane * PI / 4.0
                val a = (cos(angle) * radius).toFloat()
                val b = (sin(angle) * radius).toFloat()
                val kink = (sin(angle * 2.0) * .12).toFloat()
                return if (plane == 0) Vector3f(a, b, kink) else Vector3f(kink, b, a)
            }
            repeat(4) { index ->
                add(techniqueLink(if ((index + plane) % 3 == 0) Material.WHITE_CONCRETE else Material.LIGHT_BLUE_STAINED_GLASS,
                    point(index), point(index + 1), .105, .105, rotation, "chain:q:arc:$plane:$index"))
            }
        }
    }
}

internal const val STAFF_CHAIN_DETONATION_RADIUS = 4.25
internal const val STAFF_CHAIN_BURST_TICKS = 10

/** One instantaneous electrical fracture in volume, followed by a short thinning afterimage. */
internal fun staffChainBurstParts(ageTicks: Int): List<StaffDisplayPart> {
    if (ageTicks !in 0 until STAFF_CHAIN_BURST_TICKS) return emptyList()
    val fade = 1f - smoothTechnique(((ageTicks - 1) / 9f).coerceIn(0f, 1f))
    return buildList(25) {
        add(StaffDisplayPart(Material.SEA_LANTERN, Vector3f(), Vector3f(.58f * fade), Quaternionf(),
            identity = "chain:q:core"))
        repeat(12) { ray ->
            val direction = chainBurstDirection(ray)
            val bend = Vector3f(direction).mul((STAFF_CHAIN_DETONATION_RADIUS * .52).toFloat())
                .add((sin(ray * 2.1) * .22).toFloat(), (cos(ray * 1.7) * .22).toFloat(), 0f)
            val end = Vector3f(direction).mul(STAFF_CHAIN_DETONATION_RADIUS.toFloat())
            add(techniqueLink(if (ray % 3 == 0) Material.SEA_LANTERN else Material.LIGHT_BLUE_STAINED_GLASS,
                Vector3f(direction).mul(.28f), bend, .13 * fade, .13 * fade, Quaternionf(), "chain:q:burst:$ray:inner"))
            add(techniqueLink(if (ray % 2 == 0) Material.WHITE_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
                bend, end, .08 * fade, .08 * fade, Quaternionf(), "chain:q:burst:$ray:outer"))
        }
    }
}

internal fun staffChainBurstParticles(): List<StaffParticleSample> = List(24) { index ->
    val direction = chainBurstDirection(index / 2).mul((STAFF_CHAIN_DETONATION_RADIUS *
        if (index % 2 == 0) .52 else 1.0).toFloat())
    StaffParticleSample(Particle.ELECTRIC_SPARK,
        Vector(direction.x.toDouble(), direction.y.toDouble(), direction.z.toDouble()),
        count = 2, spread = Vector(.1, .1, .1), speed = .035)
}

private fun chainBurstDirection(index: Int): Vector3f {
    val y = 1.0 - 2.0 * (index + .5) / 12.0
    val radial = sqrt(1.0 - y * y)
    val angle = index * 2.399963
    return Vector3f((cos(angle) * radial).toFloat(), y.toFloat(), (sin(angle) * radial).toFloat())
}

internal const val STAFF_GALE_HURRICANE_RADIUS = 7.5
internal const val STAFF_GALE_HURRICANE_HEIGHT = 13.0
internal const val STAFF_GALE_HURRICANE_BUILD_TICKS = 12
internal const val STAFF_GALE_HURRICANE_ACTIVE_TICKS = 80
internal const val STAFF_GALE_HURRICANE_TICKS = 100
internal const val STAFF_GALE_HURRICANE_PULSE_TICKS = 10
internal const val STAFF_GALE_HURRICANE_MAX_TARGET_HITS = 8
internal const val STAFF_GALE_HURRICANE_PULSE_SCALE = .25

/** Three turbulent ribbons keep a broad crown and narrow to wind filaments across the caster's view. */
internal fun staffGaleHurricaneParts(ageTicks: Int): List<StaffDisplayPart> {
    if (ageTicks !in 0 until STAFF_GALE_HURRICANE_TICKS) return emptyList()
    val grow = smoothTechnique((ageTicks.toFloat() / STAFF_GALE_HURRICANE_BUILD_TICKS).coerceIn(0f, 1f))
    val fade = smoothTechnique(((STAFF_GALE_HURRICANE_TICKS - ageTicks) / 8f).coerceIn(0f, 1f))
    val funnel = staffTideGaleParts(StaffSpell.GALE, ageTicks + 12, STAFF_GALE_HURRICANE_TICKS + 14,
        STAFF_GALE_HURRICANE_RADIUS, impact = false, secondary = false, charge = 1.0)
    fun expand(point: Vector3f): Vector3f {
        val radius = kotlin.math.hypot(point.x.toDouble(), point.z.toDouble()).coerceAtLeast(.001)
        val expanded = (2.75 + radius * 1.05 * grow).coerceAtMost(STAFF_GALE_HURRICANE_RADIUS - .25)
        return Vector3f((point.x / radius * expanded).toFloat(), (.12 + point.y * 1.48 * grow).toFloat(),
            (point.z / radius * expanded).toFloat())
    }
    return buildList(48) {
        funnel.forEach { part ->
            val band = part.identity?.contains(":band:") == true
            val oldTangent = part.rotation.transform(Vector3f(0f, 0f, 1f))
            // The shared funnel adds 16% overlap and fade to its link length; recover its authored joints.
            val halfLength = part.scale.z / (2f * 1.16f * fade)
            val from = expand(Vector3f(part.center).fma(-halfLength, oldTangent))
            val to = expand(Vector3f(part.center).fma(halfLength, oldTangent))
            val tangent = Vector3f(to).sub(from)
            val length = tangent.length().coerceAtLeast(.001f)
            // A hollow center still looks through the surrounding sheets. Keep the eye-height
            // circulation thin, then restore the full storm ribbons above the combat silhouettes.
            val broad = smoothTechnique(((minOf(from.y, to.y) - 2.8f) / 2.4f).coerceIn(0f, 1f))
            val center = if (band) Vector3f(from).add(to).mul(.5f) else expand(part.center).apply {
                y = .12f + 3f * grow + (y - .12f) * .76f
            }
            add(part.copy(
                material = when (part.material) {
                    Material.WHITE_STAINED_GLASS -> Material.LIGHT_GRAY_STAINED_GLASS
                    Material.LIGHT_GRAY_STAINED_GLASS -> Material.LIGHT_BLUE_STAINED_GLASS
                    else -> part.material
                },
                center = center,
                scale = (if (band) Vector3f(part.scale.x * 1.28f * (.05f + .95f * broad),
                    part.scale.y * 1.48f * (.12f + .88f * broad), length * 1.16f * fade)
                    else Vector3f(part.scale).mul(1.28f, 1.48f, 1.28f)).mul(.12f + .88f * grow),
                rotation = if (band) Quaternionf().rotationTo(oldTangent, tangent.mul(1f / length)).mul(part.rotation)
                    else part.rotation,
                identity = part.identity?.replace("gale:tornado:", "gale:q:hurricane:"),
            ))
        }
        // Three low sweeping sheets mark the actual wide footprint without filling the player's eye line.
        repeat(3) { index ->
            val angle = index * PI * 2 / 3 - ageTicks * .18 + sin(ageTicks * .27 + index) * .12
            val radius = 6.55 + .25 * sin(ageTicks * .31 + index * 2.0)
            add(StaffDisplayPart(if (index == 1) Material.GRAY_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
                Vector3f((cos(angle) * radius).toFloat(), (.35 + .18 * sin(ageTicks * .3 + index)).toFloat(),
                    (sin(angle) * radius).toFloat()),
                Vector3f(.48f, .13f, 4.2f).mul((.15f + .85f * grow) * fade),
                Quaternionf().rotateY((-angle).toFloat()).rotateZ((.12 * sin(ageTicks * .17 + index)).toFloat()),
                identity = "gale:q:hurricane:ground:$index"))
        }
    }
}

/** Sparse authored accents follow the ribbon silhouette; no lingering smoke or explosion emitters. */
internal fun staffGaleHurricaneParticles(ageTicks: Int): List<StaffParticleSample> {
    if (ageTicks !in 0 until STAFF_GALE_HURRICANE_TICKS) return emptyList()
    val parts = staffGaleHurricaneParts(ageTicks)
    return parts.filterIndexed { index, _ -> index % 3 == (ageTicks / 2) % 3 }.mapIndexed { index, part ->
        StaffParticleSample(if (index % 4 == 0) Particle.CRIT else Particle.DUST_COLOR_TRANSITION,
            Vector(part.center.x.toDouble(), part.center.y.toDouble(), part.center.z.toDouble()), count = 1,
            spread = Vector(.035, .035, .035), speed = .012,
            data = if (index % 4 == 0) null else staffFade(0xA5CFD6, 0x475A69, .9f))
    }
}

internal const val STAFF_ICICLE_RADIAL_RADIUS = 4.5
internal const val STAFF_ICICLE_RADIAL_CONTACT_TICK = 6
internal const val STAFF_ICICLE_RADIAL_TICKS = 18

/** Twelve low crystal clusters burst away from a clear caster center, then break upward. */
internal fun staffIcicleRadialParts(ageTicks: Int): List<StaffDisplayPart> {
    if (ageTicks !in 0 until STAFF_ICICLE_RADIAL_TICKS) return emptyList()
    val expansion = smoothTechnique((ageTicks / 4f).coerceIn(0f, 1f))
    val fracture = smoothTechnique(((ageTicks - STAFF_ICICLE_RADIAL_CONTACT_TICK) / 6f).coerceIn(0f, 1f))
    val fade = 1f - smoothTechnique(((ageTicks - 8) / 8f).coerceIn(0f, 1f))
    if (fade <= 0f) return emptyList()
    return List(36) { index ->
        val cluster = index / 3
        val facet = index % 3
        val angle = cluster * PI / 6.0 + (facet - 1) * .11
        val direction = Vector3f(cos(angle).toFloat(), 0f, sin(angle).toFloat())
        val length = (if (facet == 0) 1.8f else 1.0f) * (.35f + .65f * expansion)
        val front = 1.4f + (STAFF_ICICLE_RADIAL_RADIUS.toFloat() - 1.4f) * expansion
        val center = Vector3f(direction).mul(front - length * .5f - facet * .14f)
            .add(0f, .22f + facet * .18f + fracture * (.14f + facet * .05f), 0f)
        StaffDisplayPart(
            when (facet) { 0 -> Material.PACKED_ICE; 1 -> Material.BLUE_ICE; else -> Material.LIGHT_BLUE_STAINED_GLASS },
            center, Vector3f(if (facet == 0) .50f else .34f, if (facet == 2) .58f else .32f, length).mul(fade),
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), direction)
                .rotateZ((facet - 1) * .38f).rotateX(fracture * (if (cluster % 2 == 0) .34f else -.28f)),
            identity = "icicle:q:burst:$cluster:$facet",
        )
    }
}

/** A funeral standard assembles for twelve ticks, then its six bone arrows fan toward the army. */
internal fun staffNecromancerCommandParts(ageTicks: Int): List<StaffDisplayPart> {
    if (ageTicks !in 0 until 32) return emptyList()
    val form = smoothTechnique((ageTicks / 12f).coerceIn(0f, 1f))
    val release = smoothTechnique(((ageTicks - 12) / 8f).coerceIn(0f, 1f))
    val fade = 1f - smoothTechnique(((ageTicks - 22) / 10f).coerceIn(0f, 1f))
    return buildList(27) {
        add(StaffDisplayPart(Material.BONE_BLOCK, Vector3f(0f, 2.15f + (1f - form), 0f),
            Vector3f(.18f, 2.2f, .18f).mul(fade), Quaternionf(), identity = "necromancer:q:standard:spine"))
        repeat(2) { side ->
            val sign = if (side == 0) -1f else 1f
            add(techniqueLink(Material.CALCITE, Vector3f(0f, 3.05f, 0f), Vector3f(sign * 1.28f * form, 2.72f, 0f),
                .2 * fade, .24 * fade, Quaternionf(), "necromancer:q:standard:wing:$side"))
        }
        repeat(6) { arm ->
            val angle = arm * PI / 3.0
            val axis = Vector3f(cos(angle).toFloat(), 0f, sin(angle).toFloat())
            val side = Vector3f(-axis.z, 0f, axis.x)
            val end = Vector3f(axis).mul(1.5f + release * 1.4f).add(0f, .16f + .2f * form, 0f)
            val tail = Vector3f(axis).mul(.42f + release * 1.4f).add(0f, .16f, 0f)
            add(techniqueLink(Material.BONE_BLOCK, tail, end, .17 * fade, .15 * fade, Quaternionf(),
                "necromancer:q:order:$arm:shaft"))
            repeat(2) { wing ->
                val tip = Vector3f(end).fma(-.55f, axis).fma(if (wing == 0) -.34f else .34f, side)
                add(techniqueLink(Material.CALCITE, end, tip, .17 * fade, .17 * fade, Quaternionf(),
                    "necromancer:q:order:$arm:barb:$wing"))
            }
            add(StaffDisplayPart(Material.CYAN_STAINED_GLASS,
                Vector3f(axis).mul(.8f + release * 1.5f).add(0f, 1.0f + form * .45f, 0f),
                Vector3f(.12f, 1.3f, .38f).mul(fade), Quaternionf().rotateY(-angle.toFloat()).rotateZ(.28f),
                identity = "necromancer:q:order:$arm:soul"))
        }
    }
}

/** A fixed three-sided cutting prism; its open center is a deliberate crossing lane. */
internal fun staffPrismTripwireParts(yaw: Float, pulse: Int, cutAge: Int? = null): List<StaffDisplayPart> {
    val vertices = listOf(
        Vector3f(-2.05f, .12f, -1.55f),
        Vector3f(2.05f, .12f, -1.55f),
        Vector3f(0f, .12f, 2.25f),
    )
    val baseRotation = Quaternionf().rotateY(yaw)
    val swell = (1.0 + .055 * sin(pulse * .36)).toFloat()
    val cut = cutAge?.takeIf { it in 0..8 }?.let { sin(PI * (it + 1) / 10.0).toFloat() } ?: 0f
    return buildList(24) {
        repeat(3) { index ->
            val from = vertices[index]
            val to = vertices[(index + 1) % vertices.size]
            add(techniqueLink(Material.AMETHYST_BLOCK, from, to, .13 * swell, .16,
                baseRotation, "prism:q:edge:$index"))
            val peak = Vector3f((from.x + to.x) * .28f, 1.55f, (from.z + to.z) * .28f)
            add(techniqueLink(Material.PURPUR_BLOCK, from, peak, .105, .13,
                baseRotation, "prism:q:facet:$index:a"))
            add(techniqueLink(Material.BUDDING_AMETHYST, peak, to, .105, .13,
                baseRotation, "prism:q:facet:$index:b"))
            repeat(2) { prism ->
                val local = Vector3f(from).add(0f, .55f + prism * .75f, 0f)
                add(StaffDisplayPart(if (prism == 0) Material.AMETHYST_BLOCK else Material.BUDDING_AMETHYST,
                    baseRotation.transform(local), Vector3f(.34f, .76f, .34f),
                    Quaternionf(baseRotation).rotateY((PI / 4).toFloat()).rotateZ(if (prism == 0) .18f else -.18f),
                    identity = "prism:q:pylon:$index:$prism"))
            }
            repeat(3) { blade ->
                // Retain a broad cutting chord: collapsing to a tiny triangle hides the strike
                // inside its victim. The upper blade opens above the head while the eye line stays clear.
                val height = .38f + blade * .48f + when (blade) { 0 -> -.15f * cut; 2 -> .95f * cut; else -> 0f }
                val inset = .07f + cut * .38f
                val cutFrom = Vector3f(from).lerp(Vector3f(0f, from.y, 0f), inset).add(0f, height, 0f)
                val cutTo = Vector3f(to).lerp(Vector3f(0f, to.y, 0f), inset).add(0f, height, 0f)
                val material = if (cut > .15f) {
                    if (blade == 1) Material.WHITE_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS
                } else if (blade == 1) Material.MAGENTA_STAINED_GLASS else Material.PURPLE_STAINED_GLASS
                add(techniqueLink(material, cutFrom, cutTo, .035 + cut * .15, .07 + cut * .18, baseRotation,
                    "prism:q:cut:$index:$blade"))
            }
        }
    }
}

internal fun staffPrismTripwireContains(point: org.bukkit.Location, origin: org.bukkit.Location, yaw: Float): Boolean {
    if (point.world !== origin.world) return false
    val local = Quaternionf().rotateY(-yaw).transform(Vector3f(
        (point.x - origin.x).toFloat(), 0f, (point.z - origin.z).toFloat()))
    val a = Vector3f(-2.05f, 0f, -1.55f)
    val b = Vector3f(2.05f, 0f, -1.55f)
    val c = Vector3f(0f, 0f, 2.25f)
    fun side(first: Vector3f, second: Vector3f) =
        (local.x - second.x) * (first.z - second.z) - (first.x - second.x) * (local.z - second.z)
    val ab = side(a, b)
    val bc = side(b, c)
    val ca = side(c, a)
    return (ab >= 0f && bc >= 0f && ca >= 0f) || (ab <= 0f && bc <= 0f && ca <= 0f)
}

internal fun staffGravityPinParts(ageTicks: Int, thrown: Boolean, yaw: Float): List<StaffDisplayPart> {
    val rotation = Quaternionf().rotateY(yaw)
    if (thrown) return List(12) { index ->
        val angle = index * PI / 6.0
        val spread = .45 + .09 * ageTicks.coerceIn(0, 8)
        val offset = Vector3f((cos(angle) * spread).toFloat(), (sin(angle) * spread).toFloat(),
            -.8f - (index % 3) * .45f)
        StaffDisplayPart(if (index % 3 == 0) Material.CRYING_OBSIDIAN else Material.PURPLE_STAINED_GLASS,
            rotation.transform(offset), Vector3f(.22f, .26f, 1.55f),
            Quaternionf(rotation).rotateY((cos(angle) * -.22).toFloat()).rotateX((sin(angle) * .22).toFloat()),
            identity = "gravity:q:piece:$index")
    }
    val assemble = smoothTechnique((ageTicks.coerceAtLeast(0) / 6f).coerceIn(0f, 1f))
    val pulse = 1f + .035f * sin(ageTicks * .38f) + (1f - assemble) * .4f
    return buildList(24) {
        repeat(16) { index ->
            val corner = index / 2
            val x = if (corner % 2 == 0) -1f else 1f
            val y = if ((corner / 2) % 2 == 0) -1f else 1f
            val z = if (corner < 4) -.58f else .58f
            val vertical = index % 2 == 0
            val offset = Vector3f(x * (if (vertical) .96f else .72f) * pulse,
                y * (if (vertical) .78f else 1.03f) * pulse, z)
            add(StaffDisplayPart(if (vertical) Material.CRYING_OBSIDIAN else Material.AMETHYST_BLOCK,
                rotation.transform(offset), if (vertical) Vector3f(.23f, .69f, .26f) else Vector3f(.69f, .23f, .26f),
                Quaternionf(rotation), identity = "gravity:q:piece:$index"))
        }
        repeat(8) { index ->
            val angle = index * PI / 4 + ageTicks * .055
            add(StaffDisplayPart(Material.PURPLE_STAINED_GLASS,
                rotation.transform(Vector3f((cos(angle) * 1.3).toFloat(), if (index % 2 == 0) -1.36f else 1.36f,
                    (sin(angle) * .82).toFloat())), Vector3f(.13f, .38f, .68f),
                Quaternionf(rotation).rotateY(-angle.toFloat()).rotateZ(.4f), identity = "gravity:q:orbit:$index"))
        }
    }
}

internal fun staffLancePinParts(length: Double, yaw: Float, held: Boolean, ageTicks: Int = 8): List<StaffDisplayPart> {
    val reach = length.takeIf(Double::isFinite)?.coerceIn(1.0, 4.0) ?: 2.0
    val rotation = Quaternionf().rotateY(yaw)
    val y = if (held) .18f else .10f
    val opening = .35f + .65f * smoothTechnique((ageTicks / 3f).coerceIn(0f, 1f))
    val flare = opening * (1f + .16f * sin(ageTicks.coerceAtMost(12) * PI / 12).toFloat())
    return buildList(19) {
        add(techniqueLink(Material.IRON_BLOCK, Vector3f(0f, y, -.35f), Vector3f(0f, y, reach.toFloat()),
            .21, .21, rotation, "lance:q:spear:shaft"))
        add(StaffDisplayPart(Material.GOLD_BLOCK, rotation.transform(Vector3f(0f, y, .02f)),
            Vector3f(.40f, .40f, .42f), Quaternionf(rotation).rotateZ((PI / 4.0).toFloat()),
            identity = "lance:q:spear:collar"))
        add(StaffDisplayPart(Material.QUARTZ_BLOCK, rotation.transform(Vector3f(0f, y, reach.toFloat() + .20f)),
            Vector3f(.28f, .28f, .68f), Quaternionf(rotation).rotateZ((PI / 4.0).toFloat()),
            identity = "lance:q:spear:tip"))
        repeat(4) { index ->
            val angle = index * PI / 2.0
            val rim = Vector3f((cos(angle) * 1.18 * flare).toFloat(), y + (sin(angle) * 1.18 * flare).toFloat(), -.22f)
            val tip = Vector3f((cos(angle) * .56 * flare).toFloat(), y + (sin(angle) * .56 * flare).toFloat(), -1.1f)
            add(techniqueLink(if (index % 2 == 0) Material.GOLD_BLOCK else Material.QUARTZ_BLOCK,
                Vector3f(0f, y, .5f), rim, .16, .16, rotation, "lance:q:spear:barb:$index"))
            add(techniqueLink(Material.GOLD_BLOCK, rim, tip, .12, .12, rotation, "lance:q:spear:feather:$index"))
            add(techniqueLink(Material.WHITE_STAINED_GLASS, Vector3f(0f, y, -.55f), rim,
                .09, .32, rotation, "lance:q:spear:light:$index"))
            add(StaffDisplayPart(Material.YELLOW_STAINED_GLASS, rotation.transform(Vector3f(rim).mul(1.15f)),
                Vector3f(.14f, .14f, 1.25f), Quaternionf(rotation), identity = "lance:q:spear:wake:$index"))
        }
    }
}

/** Up to twelve persistent ground embers; fixed identities survive capture and sequential ignition. */
internal fun staffEmberTrailParts(points: List<Vector3f>, origin: Vector3f, burningThrough: Int,
    ageTicks: Int = 0): List<StaffDisplayPart> {
    val slots = points.take(STAFF_EMBER_TRAIL_MAX_POINTS)
    val bySlot = slots.mapIndexed { index, point -> index to point }
    val local = bySlot.flatMap { (index, point) ->
        val delta = Vector3f(point).sub(origin)
        val previous = Vector3f(slots.getOrElse(index - 1) { point }).sub(origin)
        val segment = Vector3f(delta).sub(previous)
        val lit = index <= burningThrough
        val flicker = 1f + .12f * sin(ageTicks * .7f + index * 2.1f)
        listOf(
            StaffDisplayPart(if (lit) Material.MAGMA_BLOCK else Material.COAL_BLOCK,
                Vector3f(delta).add(previous).mul(.5f).add(0f, .06f, 0f),
                Vector3f(if (lit) .66f else .42f, .12f, segment.length().coerceAtLeast(.4f)),
                if (segment.lengthSquared() > .001f) Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), segment.normalize())
                    else Quaternionf(), identity = "ember:q:ground:$index"),
            StaffDisplayPart(if (lit) Material.FIRE_CORAL_BLOCK else Material.NETHER_WART_BLOCK,
                Vector3f(delta.x - if (lit) .17f else .0f, delta.y + if (lit) .61f * flicker else .13f, delta.z),
                Vector3f(if (lit) .30f else .20f, if (lit) 1.12f * flicker else .17f, if (lit) .34f else .20f),
                Quaternionf().rotateXYZ(.12f, .17f * index, -.26f),
                identity = "ember:q:flame:$index"),
            StaffDisplayPart(if (lit) Material.ORANGE_STAINED_GLASS else Material.RED_STAINED_GLASS,
                Vector3f(delta.x + .22f, delta.y + if (lit) .76f * flicker else .11f, delta.z),
                Vector3f(if (lit) .27f else .12f, if (lit) 1.40f * flicker else .12f, if (lit) .35f else .12f),
                Quaternionf().rotateXYZ(-.17f, -.2f * index, .3f), identity = "ember:q:tongue:$index"),
        )
    }
    val padding = (slots.size until STAFF_EMBER_TRAIL_MAX_POINTS).flatMap { index ->
        val point = slots.lastOrNull() ?: origin
        val delta = Vector3f(point).sub(origin)
        listOf(
            hiddenTechniquePart("ember:q:ground:$index", delta, Material.MAGMA_BLOCK),
            hiddenTechniquePart("ember:q:flame:$index", delta, Material.NETHER_WART_BLOCK),
            hiddenTechniquePart("ember:q:tongue:$index", delta, Material.ORANGE_STAINED_GLASS),
        )
    }
    return local + padding
}

internal const val STAFF_EMBER_TRAIL_MAX_POINTS = 12

private fun techniqueLink(material: Material, start: Vector3f, end: Vector3f, width: Double, height: Double,
    baseRotation: Quaternionf, identity: String): StaffDisplayPart {
    val delta = Vector3f(end).sub(start)
    val length = delta.length().coerceAtLeast(.001f)
    val midpoint = Vector3f(start).add(end).mul(.5f)
    val rotation = if (length > .001f) Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(delta).normalize())
        else Quaternionf()
    return StaffDisplayPart(material, baseRotation.transform(midpoint), Vector3f(width.toFloat(), height.toFloat(), length),
        Quaternionf(baseRotation).mul(rotation), identity = identity)
}

private fun hiddenTechniquePart(identity: String, center: Vector3f, material: Material) = StaffDisplayPart(
    material, Vector3f(center), Vector3f(.001f), Quaternionf(), visible = false, identity = identity,
)

private fun smoothTechnique(value: Float): Float = value * value * (3f - 2f * value)

private fun wrapTechniqueAngle(value: Float): Float {
    var angle = value
    val pi = PI.toFloat()
    while (angle > pi) angle -= (2.0 * PI).toFloat()
    while (angle < -pi) angle += (2.0 * PI).toFloat()
    return angle
}
