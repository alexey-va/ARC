package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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

/** Twelve stable blocks form one broad crescent; the open side turns with the return. */
@Suppress("UNUSED_PARAMETER")
internal fun staffLunarBoomerangParts(facingYaw: Float, ageTicks: Int): List<StaffDisplayPart> {
    return List(12) { index ->
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
            Vector3f(0.42f * taper, 0.46f * taper, 0.26f),
            Quaternionf().rotateY(facingYaw).rotateZ(spin),
            identity = "lunar:boomerang:$index",
        )
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

internal fun staffIcicleRecallParts(ageTicks: Int, charge: Double, yaw: Float, pitch: Float): List<StaffDisplayPart> {
    val facing = Quaternionf().rotateY(yaw).rotateX(pitch)
    return staffIcicleDisplayParts(ageTicks, impact = false, charge = charge).map { part ->
        part.copy(center = facing.transform(Vector3f(part.center)),
            rotation = Quaternionf(facing).mul(part.rotation))
    }
}

/** A fixed three-sided cutting prism; its open center is a deliberate crossing lane. */
internal fun staffPrismTripwireParts(yaw: Float, pulse: Int): List<StaffDisplayPart> {
    val vertices = listOf(
        Vector3f(-2.05f, .12f, -1.55f),
        Vector3f(2.05f, .12f, -1.55f),
        Vector3f(0f, .12f, 2.25f),
    )
    val baseRotation = Quaternionf().rotateY(yaw)
    val swell = (1.0 + .055 * sin(pulse * .36)).toFloat()
    return buildList(9) {
        repeat(3) { index ->
            val from = vertices[index]
            val to = vertices[(index + 1) % vertices.size]
            add(techniqueLink(Material.AMETHYST_BLOCK, from, to, .13 * swell, .16,
                baseRotation, "prism:q:edge:$index"))
            val peak = Vector3f((from.x + to.x) * .28f, 1.35f, (from.z + to.z) * .28f)
            add(techniqueLink(Material.PURPUR_BLOCK, from, peak, .105, .13,
                baseRotation, "prism:q:facet:$index:a"))
            add(techniqueLink(Material.BUDDING_AMETHYST, peak, to, .105, .13,
                baseRotation, "prism:q:facet:$index:b"))
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
    if (thrown) return List(8) { index ->
        val angle = index * PI / 4.0
        val offset = Vector3f((cos(angle) * .25).toFloat(), (sin(angle) * .25).toFloat(),
            ((index % 3 - 1) * .19).toFloat())
        StaffDisplayPart(if (index % 2 == 0) Material.CRYING_OBSIDIAN else Material.AMETHYST_BLOCK,
            rotation.transform(offset), Vector3f(.22f, .22f, .72f),
            Quaternionf(rotation).rotateY(angle.toFloat()).rotateX(.72f),
            identity = "gravity:q:piece:$index")
    }
    val pulse = 1f + .04f * sin(ageTicks.coerceAtLeast(0) * .38f)
    return List(8) { index ->
        val corner = index / 2
        val x = if (corner % 2 == 0) -1f else 1f
        val y = if (corner < 2) -1f else 1f
        val vertical = index % 2 == 0
        val offset = Vector3f(x * (if (vertical) .68f else .49f) * pulse,
            y * (if (vertical) .49f else .68f) * pulse, if (vertical) -.12f else .12f)
        StaffDisplayPart(if (vertical) Material.CRYING_OBSIDIAN else Material.AMETHYST_BLOCK,
            rotation.transform(offset), if (vertical) Vector3f(.16f, .48f, .18f) else Vector3f(.48f, .16f, .18f),
            Quaternionf(rotation),
            identity = "gravity:q:piece:$index")
    }
}

internal fun staffLancePinParts(length: Double, yaw: Float, held: Boolean): List<StaffDisplayPart> {
    val reach = length.takeIf(Double::isFinite)?.coerceIn(1.0, 4.0) ?: 2.0
    val rotation = Quaternionf().rotateY(yaw)
    val y = if (held) .18f else .10f
    return buildList(7) {
        add(techniqueLink(Material.IRON_BLOCK, Vector3f(0f, y, -.35f), Vector3f(0f, y, reach.toFloat()),
            .12, .12, rotation, "lance:q:spear:shaft"))
        add(StaffDisplayPart(Material.GOLD_BLOCK, rotation.transform(Vector3f(0f, y, .02f)),
            Vector3f(.24f, .24f, .26f), Quaternionf(rotation).rotateZ((PI / 4.0).toFloat()),
            identity = "lance:q:spear:collar"))
        add(StaffDisplayPart(Material.QUARTZ_BLOCK, rotation.transform(Vector3f(0f, y, reach.toFloat() + .20f)),
            Vector3f(.16f, .16f, .44f), Quaternionf(rotation).rotateZ((PI / 4.0).toFloat()),
            identity = "lance:q:spear:tip"))
        repeat(4) { index ->
            val angle = index * PI / 2.0
            add(techniqueLink(if (index % 2 == 0) Material.GOLD_BLOCK else Material.QUARTZ_BLOCK,
                Vector3f(0f, y, .32f), Vector3f((cos(angle) * .32).toFloat(), y + (sin(angle) * .32).toFloat(), -.24f),
                .10, .10, rotation, "lance:q:spear:barb:$index"))
        }
    }
}

/** Up to twelve persistent ground embers; fixed identities survive capture and sequential ignition. */
internal fun staffEmberTrailParts(points: List<Vector3f>, origin: Vector3f, burningThrough: Int): List<StaffDisplayPart> {
    val slots = points.take(STAFF_EMBER_TRAIL_MAX_POINTS)
    val bySlot = slots.mapIndexed { index, point -> index to point }
    val local = bySlot.flatMap { (index, point) ->
        val delta = Vector3f(point).sub(origin)
        val previous = Vector3f(slots.getOrElse(index - 1) { point }).sub(origin)
        val segment = Vector3f(delta).sub(previous)
        val lit = index <= burningThrough
        val scale = if (lit) 1.0f else .55f
        listOf(
            StaffDisplayPart(if (lit) Material.MAGMA_BLOCK else Material.COAL_BLOCK,
                Vector3f(delta).add(previous).mul(.5f).add(0f, .06f, 0f),
                Vector3f(.22f, .09f, segment.length().coerceAtLeast(.28f)),
                if (segment.lengthSquared() > .001f) Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), segment.normalize())
                    else Quaternionf(), identity = "ember:q:ground:$index"),
            StaffDisplayPart(if (lit) Material.FIRE_CORAL_BLOCK else Material.NETHER_WART_BLOCK,
                Vector3f(delta.x, delta.y + if (lit) .29f else .13f, delta.z),
                Vector3f(.16f * scale, if (lit) .50f else .13f, .16f * scale),
                Quaternionf().rotateXYZ(.25f * index, .17f * index, .31f * index),
                identity = "ember:q:flame:$index"),
        )
    }
    val padding = (slots.size until STAFF_EMBER_TRAIL_MAX_POINTS).flatMap { index ->
        val point = slots.lastOrNull() ?: origin
        val delta = Vector3f(point).sub(origin)
        listOf(
            hiddenTechniquePart("ember:q:ground:$index", delta, Material.MAGMA_BLOCK),
            hiddenTechniquePart("ember:q:flame:$index", delta, Material.NETHER_WART_BLOCK),
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
