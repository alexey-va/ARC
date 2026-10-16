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
    val end = finiteTetherEnd(targetOffset)
    val tension = (end.length() / 4.5f).coerceIn(0f, 1f)
    return buildList(24) {
        fun point(t: Float) = Vector3f(end).mul(t).add(
            (sin(ageTicks * .18 + t * PI * 2) * sin(t * PI) * .16 * (1f - tension)).toFloat(),
            -.6f + 1.2f * t + (cos(ageTicks * .14 + t * PI * 2) * sin(t * PI) * .12).toFloat(), 0f)
        repeat(8) { index ->
            add(conjurationLink("mark:q:tether:$index", if (index % 3 == 0) Material.AMETHYST_BLOCK
                else Material.PURPLE_STAINED_GLASS, point(index / 8f), point((index + 1) / 8f), .19f, .19f))
        }
        repeat(2) { cuff ->
            val anchor = if (cuff == 0) Vector3f(0f, -.6f, 0f) else Vector3f(end).add(0f, .6f, 0f)
            repeat(8) { index ->
                val angle = index * PI / 4 + (if (cuff == 0) 1 else -1) * ageTicks * .028
                val radius = .9 + .1 * tension
                add(conjurationPart("mark:q:ring:$cuff:$index", if (index % 4 == 0) Material.AMETHYST_BLOCK
                    else Material.CRYING_OBSIDIAN,
                    anchor.x + (cos(angle) * radius).toFloat(), anchor.y + .08f,
                    anchor.z + (sin(angle) * radius).toFloat(), .21f, .30f, .64f,
                    0.0, if (index % 2 == 0) .16f else -.16f, localYaw = -angle))
            }
        }
    }
}

/** The stretched link recoils into its two broken cuffs, exposing the punished target. */
internal fun staffMarkTetherBreakParts(ageTicks: Int, targetOffset: org.bukkit.util.Vector): List<StaffDisplayPart> {
    if (ageTicks !in 0 until 12) return emptyList()
    val progress = smoothStaffConjuration(ageTicks / 10.0).toFloat()
    val fade = (1f - progress).coerceAtLeast(.01f)
    val end = finiteTetherEnd(targetOffset)
    return staffMarkTetherParts(32, targetOffset).mapIndexed { index, part ->
        val home = if (index < 4 || index in 8..15) Vector3f() else Vector3f(end)
        val recoil = Vector3f(part.center).sub(home)
        if (index < 8) recoil.mul(1f - progress * .85f)
        else recoil.mul(1f + progress * 1.4f)
        part.copy(center = home.add(recoil).add(0f, progress * (.5f + index % 3 * .3f), 0f),
            scale = Vector3f(part.scale).mul(fade),
            rotation = Quaternionf(part.rotation).rotateX(progress * (if (index % 2 == 0) 1.4f else -1.1f)),
            identity = "mark:q:break:$index")
    }
}

private fun finiteTetherEnd(offset: org.bukkit.util.Vector): Vector3f {
    if (!offset.lengthSquared().isFinite()) return Vector3f()
    return Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).apply {
        if (lengthSquared() > 4.5f * 4.5f) normalize().mul(4.5f)
    }
}

/** Six faceted ice buds fracture outward; detached chips keep travelling while the cores dissolve. */
internal fun staffFrostThawParts(ageTicks: Int): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val form = smoothStaffConjuration(age / 4.0)
    val split = smoothStaffConjuration((age - 4) / 7.0)
    val fade = (1.0 - smoothStaffConjuration((age - 10) / 8.0)).coerceAtLeast(.01).toFloat()
    return List(36) { index ->
        val arm = index / 6
        val facet = index % 6
        val chip = facet >= 3
        val angle = arm * PI / 3 + (facet % 3 - 1) * .16
        val radius = .3 + form * .45 + split * (if (chip) 2.0 else 1.25)
        val lift = .7 + facet % 3 * .22 + if (chip) split * .65 else 0.0
        val lean = .18 + split * (if (chip) 1.05 else .65)
        StaffDisplayPart(when (facet % 3) { 0 -> Material.PACKED_ICE; 1 -> Material.BLUE_ICE
            else -> Material.WHITE_STAINED_GLASS },
            Vector3f((cos(angle) * radius).toFloat(), lift.toFloat(), (sin(angle) * radius).toFloat()),
            Vector3f(if (chip) .23f else .38f, if (chip) .8f else 1.8f, if (chip) .25f else .42f)
                .mul((.3 + .7 * form).toFloat() * fade),
            Quaternionf().rotateY((PI / 2 - angle).toFloat()).rotateX(lean.toFloat())
                .rotateZ(((facet % 3 - 1) * .2).toFloat()), identity = "frost:q:shard:$index")
    }
}

internal fun staffNovaSeedParts(ageTicks: Int): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val armed = smoothStaffConjuration(age / 8.0)
    val pulse = 1.0 + sin(age * .22) * .045
    return buildList(20) {
        repeat(12) { index ->
            val angle = index * PI / 6.0
            val radius = 1.30 * armed
            add(conjurationPart("nova:q:seed:ring:$index", if (index % 3 == 0) Material.DARK_OAK_WOOD
                else Material.MOSS_BLOCK,
                (cos(angle) * radius).toFloat(), (.16 + index % 3 * .07).toFloat(), (sin(angle) * radius).toFloat(),
                .28f, (.20 + armed * .12).toFloat(), .62f,
                0.0, if (index % 2 == 0) .12f else -.12f, localYaw = -angle))
        }
        repeat(8) { index ->
            val angle = index * PI / 4 - age * .035
            add(StaffDisplayPart(if (index % 4 == 0) Material.VERDANT_FROGLIGHT
                else if (index % 2 == 0) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                Vector3f((cos(angle) * .42 * pulse).toFloat(), (.28 + armed * .30).toFloat(),
                    (sin(angle) * .42 * pulse).toFloat()),
                Vector3f(.32f, (.30 + armed * .65).toFloat(), .22f),
                Quaternionf().rotateY((PI / 2 - angle).toFloat()).rotateX(-.28f),
                identity = "nova:q:seed:petal:$index"))
        }
    }
}

/** Eight rooted shoots burst into a tall flower, opening above the captured victim. */
internal fun staffNovaSeedBloomParts(ageTicks: Int): List<StaffDisplayPart> {
    if (ageTicks !in 0 until 14) return emptyList()
    val bloom = smoothStaffConjuration(ageTicks / 5.0).toFloat()
    val fade = (1 - smoothStaffConjuration((ageTicks - 8) / 6.0)).toFloat().coerceAtLeast(.01f)
    return buildList(32) {
        repeat(8) { index ->
            val angle = index * PI / 4
            fun radial(radius: Float, height: Float) = Vector3f((cos(angle) * radius).toFloat(), height,
                (sin(angle) * radius).toFloat())
            val root = radial(1.32f, .12f)
            val bend = radial(.9f + bloom * .12f, .55f + bloom * .82f)
            val tip = radial(.38f + bloom * 1.22f, .65f + bloom * 1.68f)
            add(conjurationLink("nova:q:bloom:$index:root", Material.DARK_OAK_WOOD, root, bend, .23f * fade, .24f * fade))
            add(conjurationLink("nova:q:bloom:$index:shoot", Material.MOSS_BLOCK, bend, tip, .25f * fade, .28f * fade))
            add(StaffDisplayPart(Material.FLOWERING_AZALEA_LEAVES, Vector3f(tip).add(0f, -.08f, 0f),
                Vector3f(.65f, .34f, .76f).mul(fade), Quaternionf().rotateY(-angle.toFloat()).rotateZ(.3f),
                identity = "nova:q:bloom:$index:leaf"))
            add(StaffDisplayPart(if (index % 2 == 0) Material.VERDANT_FROGLIGHT else Material.PINK_STAINED_GLASS,
                Vector3f(tip).add(0f, .2f, 0f), Vector3f(.24f, .36f, .26f).mul(fade),
                Quaternionf().rotateY(angle.toFloat()).rotateZ(.45f), identity = "nova:q:bloom:$index:flower"))
        }
    }
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
    val form = smoothStaffConjuration(age / 6.0)
    val pulse = 1.0 + sin(age * 0.28) * 0.025
    val radius = .86 * pulse * (1.6 - .6 * form)
    val parts = ArrayList<StaffDisplayPart>(24)
    repeat(8) { index ->
        val angle = index * PI / 4.0
        val facing = Quaternionf().rotationY((PI / 2.0 - angle).toFloat())
        parts += StaffDisplayPart(
            if (index % 2 == 0) Material.CYAN_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
            Vector3f((cos(angle) * radius).toFloat(), (.24 + .91 * form).toFloat(), (sin(angle) * radius).toFloat()),
            Vector3f((0.71 * pulse).toFloat(), (.14 + 1.54 * form).toFloat(), 0.055f), Quaternionf(facing),
            identity = "tide:q:capsule:body:$index",
        )
        repeat(2) { cap ->
            parts += StaffDisplayPart(
                if (cap == 1 && index % 2 == 0) Material.WHITE_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
                Vector3f((cos(angle) * 0.71 * pulse).toFloat(), if (cap == 0) 0.20f else (.2 + 1.92 * form).toFloat(),
                    (sin(angle) * 0.71 * pulse).toFloat()),
                Vector3f((0.60 * pulse).toFloat(), 0.44f, 0.045f),
                Quaternionf(facing).rotateX(if (cap == 0) 0.65f else -0.65f),
                identity = "tide:q:capsule:cap:$index:$cap",
            )
        }
    }
    return parts
}

/** The pressure shell peels into eight falling water fans rather than disappearing in one tick. */
internal fun staffTideCapsuleReleaseParts(ageTicks: Int): List<StaffDisplayPart> {
    if (ageTicks !in 0 until 10) return emptyList()
    val progress = smoothStaffConjuration(ageTicks / 9.0).toFloat()
    val fade = (1f - progress).coerceAtLeast(.01f)
    return staffTideCapsuleParts(12).mapIndexed { index, part ->
        part.copy(center = Vector3f(part.center).mul(1f + progress * 1.6f, 1f - progress * .6f, 1f + progress * 1.6f),
            scale = Vector3f(part.scale).mul((.65f + .35f * fade), fade, fade),
            rotation = Quaternionf(part.rotation).rotateX(-progress * .7f),
            identity = "tide:q:release:$index")
    }
}

internal fun staffRequiemBellParts(ageTicks: Int): List<StaffDisplayPart> {
    // Reuse the authored bell shell: Material.BELL alone also depends on a block-entity renderer.
    // Q waits for the player's strike; do not inherit the primary's autonomous clang timeline.
    val shell = staffRequiemBellParts(ageTicks.coerceAtMost(8), 96, secondary = true, charge = 1.0).map { part ->
        part.copy(center = Vector3f(part.center).mul(1.12f, 1f, 1.12f).add(0f, .4f, 0f),
            scale = Vector3f(part.scale).mul(1.12f), identity = "requiem:q:${part.identity}")
    }
    return shell + StaffDisplayPart(Material.IRON_BLOCK, Vector3f(0f, 7.05f, 0f),
        Vector3f(.16f, 1.5f, .16f), Quaternionf(), identity = "requiem:q:chain")
}

internal fun staffRequiemImpactParts(ageTicks: Int, radius: Double = 4.0): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val reach = radius.takeIf(Double::isFinite)?.coerceIn(2.0, 5.0) ?: 4.0
    val expansion = smoothStaffConjuration(age / 7.0).toFloat()
    val fade = (1.0 - smoothStaffConjuration((age - 7) / 7.0)).coerceAtLeast(.01).toFloat()
    return buildList(32) {
        repeat(8) { ray ->
            val angle = ray * PI / 4
            fun point(level: Int): Vector3f {
                val arc = level * PI / 3
                val radiusAtLevel = (.65 + reach * .75 * expansion + sin(arc) * .7).toFloat()
                return Vector3f((cos(angle) * radiusAtLevel).toFloat(), .2f + level * .75f,
                    (sin(angle) * radiusAtLevel).toFloat())
            }
            repeat(3) { segment ->
                add(conjurationLink("requiem:q:strike:wave:$ray:$segment",
                    if (segment == 1) Material.YELLOW_STAINED_GLASS else Material.CYAN_STAINED_GLASS,
                    point(segment), point(segment + 1), .10f * fade, .30f * fade))
            }
            add(StaffDisplayPart(Material.GOLD_BLOCK,
                Vector3f((cos(angle) * expansion * 1.8).toFloat(), 3.2f - expansion * 1.6f,
                    (sin(angle) * expansion * 1.8).toFloat()), Vector3f(.25f, .35f, .25f).mul(fade),
                Quaternionf().rotateXYZ(age * .12f, angle.toFloat(), .5f), identity = "requiem:q:strike:clapper:$ray"))
        }
    }
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

private fun conjurationLink(identity: String, material: Material, from: Vector3f, to: Vector3f,
    width: Float, height: Float): StaffDisplayPart {
    val delta = Vector3f(to).sub(from)
    val length = delta.length().coerceAtLeast(.001f)
    return StaffDisplayPart(material, Vector3f(from).add(to).mul(.5f),
        Vector3f(width, height, length + .02f), Quaternionf().rotationTo(Vector3f(0f, 0f, 1f),
            if (delta.lengthSquared() > .00001f) delta.normalize() else Vector3f(0f, 0f, 1f)), identity = identity)
}

private fun smoothStaffConjuration(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}
