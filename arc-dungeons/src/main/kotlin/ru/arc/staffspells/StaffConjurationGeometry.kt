package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Fixed local-axis geometry for staff Q powers; slots and identities stay stable across frames. */
internal fun staffFaultBarrierParts(ageTicks: Int, yawRadians: Double): List<StaffDisplayPart> {
    val parts = ArrayList<StaffDisplayPart>(18)

    val panes = listOf(
        Triple(-1.38f, Material.PURPLE_STAINED_GLASS, 0.08f),
        Triple(0.0f, Material.MAGENTA_STAINED_GLASS, -0.07f),
        Triple(1.38f, Material.PURPLE_STAINED_GLASS, 0.08f),
    )
    panes.forEachIndexed { index, (x, material, tilt) ->
        val sway = sin(ageTicks.coerceAtLeast(0) * 0.12 + index * 0.9).toFloat() * 0.035f
        parts += conjurationPart(
            "fault:q:barrier:pane:$index", material,
            x, 1.38f, 0f,
            1.28f, 2.52f, 0.13f,
            yawRadians, sway + tilt,
        )
    }

    val uprights = floatArrayOf(-2.08f, -0.70f, 0.70f, 2.08f)
    uprights.forEachIndexed { index, x ->
        parts += conjurationPart(
            "fault:q:barrier:upright:$index",
            if (index % 2 == 0) Material.AMETHYST_BLOCK else Material.CRYING_OBSIDIAN,
            x, 1.40f, -0.06f,
            0.18f, 2.82f, 0.24f,
            yawRadians, if (index % 2 == 0) 0.08f else -0.08f,
        )
    }

    repeat(5) { index ->
        val x = (index - 2) * 0.72f
        parts += conjurationPart(
            "fault:q:barrier:keystone:$index",
            if (index == 2) Material.SEA_LANTERN else Material.PURPLE_STAINED_GLASS,
            x, (0.45f + (index % 3) * 0.54f), 0.12f,
            0.22f, 0.30f, 0.16f,
            yawRadians, if (index % 2 == 0) 0.18f else -0.18f,
        )
    }

    repeat(6) { index ->
        val angle = index * PI / 3.0
        val x = cos(angle).toFloat() * 0.17f
        val y = 1.38f + sin(angle).toFloat() * 0.17f
        parts += conjurationPart(
            "fault:q:barrier:core:$index",
            if (index % 2 == 0) Material.AMETHYST_BLOCK else Material.MAGENTA_STAINED_GLASS,
            x, y, 0.22f,
            0.17f, 0.17f, 0.17f,
            yawRadians, angle.toFloat(),
        )
    }
    return parts
}

/** A compact, forward-moving release from the wall's fixed slots. */
internal fun staffFaultBarrierShatterParts(ageTicks: Int, yawRadians: Double): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val progress = smoothStaffConjuration(age / 10.0)
    val fade = (1.0 - progress * 0.88).toFloat()
    return List(12) { index ->
        val lane = index % 4
        val row = index / 4
        val side = lane - 1.5
        val launch = 0.48 + progress * 1.55
        val x = side * (0.82 + progress * 0.22)
        val y = 0.55 + row * 0.77 - progress * (0.20 + row * 0.08)
        val yaw = yawRadians + (side * 0.11).toFloat()
        conjurationPart(
            "fault:q:shatter:$index",
            when (index % 4) {
                0 -> Material.CRYING_OBSIDIAN
                1 -> Material.PURPLE_STAINED_GLASS
                2 -> Material.AMETHYST_BLOCK
                else -> Material.MAGENTA_STAINED_GLASS
            },
            x.toFloat(), y.toFloat(), launch.toFloat(),
            (0.34 + (index % 3) * 0.08).toFloat() * fade,
            (0.42 + (index % 2) * 0.16).toFloat() * fade,
            0.16f * fade,
            yaw, (side * 0.17 + progress * (if (index % 2 == 0) 0.38 else -0.38)).toFloat(),
        )
    }
}

internal fun staffMarkTetherParts(ageTicks: Int, targetOffset: org.bukkit.util.Vector): List<StaffDisplayPart> {
    val offset = targetOffset.clone()
    if (!offset.lengthSquared().isFinite()) offset.zero()
    val length = offset.length().coerceIn(0.0, 4.5)
    val end = Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).let { value ->
        if (value.lengthSquared() > 4.5f * 4.5f) value.normalize().mul(4.5f) else value
    }
    val parts = ArrayList<StaffDisplayPart>(16)
    fun tetherPoint(t: Float): Vector3f = Vector3f(end).mul(t).add(
        (sin(ageTicks * 0.18 + t * PI * 2.0) * sin(t * PI) * 0.10).toFloat(),
        (cos(ageTicks * 0.14 + t * PI * 2.0) * sin(t * PI) * 0.07).toFloat(), 0f,
    )
    repeat(8) { index ->
        val from = tetherPoint(index / 8f)
        val to = tetherPoint((index + 1) / 8f)
        val delta = Vector3f(to).sub(from)
        parts += StaffDisplayPart(
            if (index % 3 == 0) Material.AMETHYST_BLOCK else Material.PURPLE_STAINED_GLASS,
            Vector3f(from).add(to).mul(0.5f), Vector3f(0.14f, 0.14f, delta.length().coerceAtLeast(0.08f) + 0.025f),
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), if (delta.lengthSquared() > 1e-5f) delta.normalize() else Vector3f(0f, 0f, 1f)),
            identity = "mark:q:tether:$index",
        )
    }
    repeat(8) { index ->
        val angle = index * PI / 4.0 + ageTicks * 0.035
        val radius = 0.72 + 0.05 * sin(ageTicks * 0.2 + index)
        val anchor = if (length > 0.05 && index % 2 == 1) Vector3f(end) else Vector3f()
        parts += conjurationPart(
            "mark:q:ring:$index",
            if (index % 4 == 0) Material.SEA_LANTERN else Material.CRYING_OBSIDIAN,
            anchor.x + (cos(angle) * radius).toFloat(), anchor.y + 0.08f,
            anchor.z + (sin(angle) * radius).toFloat(),
            0.14f, 0.18f, 0.44f, 0.0, (index % 2 * 0.12f), localYaw = -angle,
        )
    }
    return parts
}

internal fun staffFrostThawParts(ageTicks: Int): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val rise = smoothStaffConjuration(age / 7.0)
    val fade = (1.0 - smoothStaffConjuration((age - 10) / 6.0)).coerceAtLeast(0.08)
    return List(18) { index ->
        val angle = index * PI / 9.0 + age * 0.055
        val ring = 0.26 + rise * (0.9 + (index % 3) * 0.12)
        val height = 0.45 + rise * (0.65 + (index % 4) * 0.18) + sin(angle * 2.0) * 0.12
        conjurationPart(
            "frost:q:shard:$index",
            when (index % 4) {
                0 -> Material.PACKED_ICE
                1 -> Material.BLUE_ICE
                2 -> Material.WHITE_STAINED_GLASS
                else -> Material.FROSTED_ICE
            },
            (cos(angle) * ring * fade).toFloat(), (height * fade).toFloat(), (sin(angle) * ring * fade).toFloat(),
            (0.15 + (index % 3) * 0.04).toFloat() * fade.toFloat(),
            (0.42 + (index % 4) * 0.12).toFloat() * fade.toFloat(),
            0.16f * fade.toFloat(), 0.0, (sin(angle) * 0.16).toFloat(), localYaw = -angle,
        )
    }
}

internal fun staffNovaSeedParts(ageTicks: Int): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val armed = smoothStaffConjuration(age / 8.0)
    val pulse = 1.0 + sin(age * 0.22) * 0.06
    val parts = ArrayList<StaffDisplayPart>(20)
    repeat(12) { index ->
        val angle = index * PI / 6.0
        val radius = (1.34 + 0.08 * sin(age * 0.12 + index)) * armed
        val y = 0.10 + (index % 3) * 0.08
        parts += conjurationPart(
            "nova:q:seed:ring:$index",
            if (index % 3 == 0) Material.DARK_OAK_WOOD else Material.MOSS_BLOCK,
            (cos(angle) * radius).toFloat(), y.toFloat(), (sin(angle) * radius).toFloat(),
            0.18f, (0.14 + armed * 0.08).toFloat(), 0.48f,
            0.0, (index % 2 * 0.08f), localYaw = -angle,
        )
    }
    repeat(8) { index ->
        val angle = index * PI / 4.0 - age * 0.045
        val lift = 0.30 + (index % 3) * 0.16
        parts += conjurationPart(
            "nova:q:seed:petal:$index",
            if (index % 4 == 0) Material.VERDANT_FROGLIGHT
                else if (index % 2 == 0) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
            (cos(angle) * 0.34 * pulse).toFloat(), (lift * armed).toFloat(), (sin(angle) * 0.34 * pulse).toFloat(),
            (0.18 + armed * 0.10).toFloat(), (0.24 + armed * 0.12).toFloat(), 0.13f,
            0.0, (sin(angle) * 0.22).toFloat(), localYaw = PI / 2.0 - angle,
        )
    }
    return parts
}

internal fun staffGaleCorridorParts(ageTicks: Int, length: Double = 6.5, halfWidth: Double = 2.0,
    yawRadians: Double = 0.0): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val reach = length.takeIf(Double::isFinite)?.coerceIn(3.0, 8.0) ?: 6.5
    val width = halfWidth.takeIf(Double::isFinite)?.coerceIn(1.0, 2.5) ?: 2.0
    val facing = Quaternionf().rotationY(yawRadians.toFloat())
    val sweep = smoothStaffConjuration(age / 10.0)
    val fade = 1.0 - smoothStaffConjuration((age - 10.0) / 10.0)
    val parts = ArrayList<StaffDisplayPart>(24)
    repeat(3) { stream ->
        // A bounded wind packet crosses local +X, the same direction as the tick-7 impulse.
        // Its trailing and leading tips stay within the fixed corridor throughout the sweep.
        fun point(t: Double) = Vector3f(
            (-width + sweep * width * 1.20 + t * width * 0.80).toFloat(),
            (0.72 + stream * 0.40 + sin(t * PI) * 0.35 + sin(age * 0.22 + t * PI + stream) * 0.12).toFloat(),
            (reach * (0.28 + stream * 0.29) + cos(t * PI + stream * 0.30) * 0.55).toFloat(),
        )
        repeat(8) { index ->
            val from = point(index / 8.0)
            val to = point((index + 1) / 8.0)
            val delta = Vector3f(to).sub(from)
            val rotation = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(delta).normalize())
            parts += StaffDisplayPart(
                if (index == 7) Material.SEA_LANTERN else if (stream == 1) Material.WHITE_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
                facing.transform(Vector3f(from).add(to).mul(0.5f)),
                Vector3f(0.07f, (0.20 + sin((index + 0.5) * PI / 8.0) * 0.55).toFloat(), delta.length() + 0.025f)
                    .mul(fade.toFloat().coerceAtLeast(.001f)),
                Quaternionf(facing).mul(rotation),
                identity = "gale:q:corridor:$stream:$index",
            )
        }
    }
    return parts
}

internal fun staffTideCapsuleParts(ageTicks: Int): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val pulse = 1.0 + sin(age * 0.28) * 0.025
    val parts = ArrayList<StaffDisplayPart>(24)
    repeat(8) { index ->
        val angle = index * PI / 4.0
        val facing = Quaternionf().rotationY((PI / 2.0 - angle).toFloat())
        parts += StaffDisplayPart(
            if (index % 2 == 0) Material.CYAN_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
            Vector3f((cos(angle) * 0.86 * pulse).toFloat(), 1.15f, (sin(angle) * 0.86 * pulse).toFloat()),
            Vector3f((0.71 * pulse).toFloat(), 1.68f, 0.055f), Quaternionf(facing),
            identity = "tide:q:capsule:body:$index",
        )
        repeat(2) { cap ->
            parts += StaffDisplayPart(
                if (cap == 1 && index % 2 == 0) Material.WHITE_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
                Vector3f((cos(angle) * 0.71 * pulse).toFloat(), if (cap == 0) 0.20f else 2.12f,
                    (sin(angle) * 0.71 * pulse).toFloat()),
                Vector3f((0.60 * pulse).toFloat(), 0.44f, 0.045f),
                Quaternionf(facing).rotateX(if (cap == 0) 0.65f else -0.65f),
                identity = "tide:q:capsule:cap:$index:$cap",
            )
        }
    }
    return parts
}

internal fun staffRequiemBellParts(ageTicks: Int): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val swing = sin(age * PI / 24.0) * 0.25
    val hanging = Quaternionf().rotationX(swing.toFloat())
    fun hung(point: Vector3f) = hanging.transform(point.sub(0f, 2.8f, 0f)).add(0f, 2.8f, 0f)
    val parts = ArrayList<StaffDisplayPart>(9)
    parts += StaffDisplayPart(Material.IRON_CHAIN, hung(Vector3f(0f, 2.32f, 0f)), Vector3f(0.12f, 0.96f, 0.12f), Quaternionf(hanging), identity = "requiem:q:chain")
    parts += StaffDisplayPart(Material.AMETHYST_BLOCK, hung(Vector3f(0f, 1.77f, 0f)), Vector3f(0.48f, 0.18f, 0.44f), Quaternionf(hanging), identity = "requiem:q:shoulder")
    parts += StaffDisplayPart(Material.BELL, hung(Vector3f(0f, 1.23f, 0f)), Vector3f(1.28f, 1.18f, 1.12f), Quaternionf(hanging), identity = "requiem:q:bell")
    repeat(6) { index ->
        val angle = index * PI / 3.0
        val ring = Vector3f((cos(angle) * 0.66).toFloat(), 0.85f, (sin(angle) * 0.66).toFloat())
        parts += StaffDisplayPart(
            if (index % 2 == 0) Material.AMETHYST_BLOCK else Material.PURPLE_STAINED_GLASS,
            hung(ring), Vector3f(0.12f, 0.16f, 0.18f), Quaternionf(hanging).rotateY(angle.toFloat()),
            identity = "requiem:q:bell:mark:$index",
        )
    }
    return parts
}

internal fun staffRequiemImpactParts(ageTicks: Int, radius: Double = 4.0): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val reach = radius.takeIf(Double::isFinite)?.coerceIn(2.0, 5.0) ?: 4.0
    val expansion = smoothStaffConjuration(age / 7.0)
    val fade = (1.0 - smoothStaffConjuration((age - 7) / 7.0)).coerceAtLeast(0.10)
    val parts = ArrayList<StaffDisplayPart>(24)
    repeat(16) { index ->
        val angle = index * PI / 8.0 + age * 0.035
        val r = (0.45 + reach * 0.88 * expansion) * fade
        val y = (0.20 + sin(angle * 2.0 + age * 0.12) * 0.12) * fade
        parts += conjurationPart(
            "requiem:q:strike:ring:$index",
            if (index % 4 == 0) Material.SEA_LANTERN else Material.AMETHYST_BLOCK,
            (cos(angle) * r).toFloat(), y.toFloat(), (sin(angle) * r).toFloat(),
            0.18f * fade.toFloat(), 0.16f * fade.toFloat(), 0.48f * fade.toFloat(),
            0.0, (if (index % 2 == 0) 0.12 else -0.12).toFloat(), localYaw = -angle,
        )
    }
    repeat(8) { index ->
        val angle = index * PI / 4.0
        val r = reach * 0.36 * expansion * fade
        parts += conjurationPart(
            "requiem:q:strike:note:$index", Material.PURPLE_STAINED_GLASS,
            (cos(angle) * r).toFloat(), (0.45 + expansion * 0.28).toFloat(), (sin(angle) * r).toFloat(),
            0.36f * fade.toFloat(), 0.42f * fade.toFloat(), 0.16f * fade.toFloat(),
            0.0, (angle * 0.15).toFloat(), localYaw = PI / 2.0 - angle,
        )
    }
    return parts
}

internal fun staffOssuaryJawParts(ageTicks: Int, closing: Boolean = false): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val angle = 0.18 + if (closing) smoothStaffConjuration(age / 5.0) * 1.17 else 0.0
    val parts = ArrayList<StaffDisplayPart>(26)
    repeat(2) { jaw ->
        val side = if (jaw == 0) -1.0 else 1.0
        val hinge = Quaternionf().rotationZ((side * angle).toFloat())
        repeat(8) { index ->
            val t = (index + 0.5) * PI / 8.0
            val center = Vector3f((side * sin(t) * 2.05).toFloat(), 0f, (-cos(t) * 1.85).toFloat())
            val tangent = Vector3f((side * cos(t) * 2.05).toFloat(), 0f, (sin(t) * 1.85).toFloat()).normalize()
            parts += StaffDisplayPart(
                if (index % 3 == 0) Material.BONE_BLOCK else Material.CALCITE,
                hinge.transform(center).add(0f, 0.18f, 0f), Vector3f(0.24f, 0.24f, 0.79f),
                Quaternionf(hinge).mul(Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), tangent)),
                identity = "ossuary:q:jaw:$jaw:bone:$index",
            )
        }
        repeat(5) { tooth ->
            val t = (tooth + 1) * PI / 6.0
            val center = Vector3f((side * sin(t) * 1.83).toFloat(), 0.32f, (-cos(t) * 1.72).toFloat())
            parts += StaffDisplayPart(
                Material.BONE_BLOCK, hinge.transform(center).add(0f, 0.18f, 0f),
                Vector3f(0.19f, 0.54f, 0.21f), Quaternionf(hinge).rotateZ((-side * 0.14).toFloat()),
                identity = "ossuary:q:jaw:$jaw:tooth:$tooth",
            )
        }
    }
    return parts
}

internal fun staffOssuaryJawClosingParts(ageTicks: Int): List<StaffDisplayPart> =
    staffOssuaryJawParts(ageTicks.coerceIn(0, 7), closing = true)

private fun conjurationPart(
    identity: String,
    material: Material,
    x: Float,
    y: Float,
    z: Float,
    sx: Float,
    sy: Float,
    sz: Float,
    yaw: Double,
    roll: Float,
    localYaw: Double = 0.0,
): StaffDisplayPart {
    val orientation = Quaternionf().rotationY(yaw.toFloat())
    val center = orientation.transform(Vector3f(x, y, z))
    val rotation = Quaternionf(orientation).rotateY(localYaw.toFloat()).rotateZ(roll)
    return StaffDisplayPart(material, center, Vector3f(sx, sy, sz), rotation, identity = identity)
}

private fun smoothStaffConjuration(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}
