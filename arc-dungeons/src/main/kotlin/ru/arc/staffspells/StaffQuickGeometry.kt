package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal enum class StaffQuickForm {
    CHAIN_FAN, CHAIN_SNAP, MARK_STRIKE, MARK_GLYPH, FROST_FAN, FROST_WARD,
    LANCE_CLEAVE, LANCE_PIN, EMBER_CRESCENT, EMBER_FLARE, ICICLE_CLAWS,
    ICICLE_CAGE, FAULT_FISSURE, FAULT_COLUMN,
}

internal enum class StaffQuickHitShape { SWEEP, CLOSE_BAND, POINT, WARD, FISSURE, CAGE, COLUMN }
internal enum class StaffQuickControl { NONE, PULL, SLOW }

/** One compact LMB action: its visible form, contact volume, timing and optional control effect. */
internal data class StaffQuickProfile(
    val form: StaffQuickForm,
    val hitShape: StaffQuickHitShape,
    val reach: Double,
    val radius: Double,
    val width: Double,
    val contactTick: Int = 6,
    val maxTargets: Int? = null,
    val control: StaffQuickControl = StaffQuickControl.NONE,
    val groundBased: Boolean = false,
)

internal fun staffQuickProfile(spell: StaffSpell, secondary: Boolean): StaffQuickProfile = when (spell) {
    StaffSpell.CHAIN -> if (secondary) StaffQuickProfile(StaffQuickForm.CHAIN_SNAP,
        StaffQuickHitShape.POINT, 7.0, 1.25, 1.25, maxTargets = 3)
    else StaffQuickProfile(StaffQuickForm.CHAIN_FAN, StaffQuickHitShape.SWEEP,
        5.2, 1.0, 2.15, maxTargets = 2)
    StaffSpell.MARK -> if (secondary) StaffQuickProfile(StaffQuickForm.MARK_GLYPH,
        StaffQuickHitShape.POINT, 7.0, 4.5, 4.5, control = StaffQuickControl.PULL, groundBased = true)
    else StaffQuickProfile(StaffQuickForm.MARK_STRIKE, StaffQuickHitShape.POINT,
        5.5, 1.35, 1.35)
    StaffSpell.FROST -> if (secondary) StaffQuickProfile(StaffQuickForm.FROST_WARD,
        StaffQuickHitShape.WARD, 3.5, 4.0, 2.4, contactTick = 4, control = StaffQuickControl.SLOW)
    else StaffQuickProfile(StaffQuickForm.FROST_FAN, StaffQuickHitShape.SWEEP,
        4.8, 1.0, 2.35, control = StaffQuickControl.SLOW)
    StaffSpell.LANCE -> if (secondary) StaffQuickProfile(StaffQuickForm.LANCE_PIN,
        StaffQuickHitShape.POINT, 7.0, 0.9, 0.9)
    else StaffQuickProfile(StaffQuickForm.LANCE_CLEAVE, StaffQuickHitShape.CLOSE_BAND,
        3.8, 0.9, 2.55, contactTick = 4)
    StaffSpell.EMBER -> if (secondary) StaffQuickProfile(StaffQuickForm.EMBER_FLARE,
        StaffQuickHitShape.POINT, 7.0, 2.8, 2.8)
    else StaffQuickProfile(StaffQuickForm.EMBER_CRESCENT, StaffQuickHitShape.CLOSE_BAND,
        4.0, 0.9, 2.65, contactTick = 4)
    StaffSpell.ICICLE -> if (secondary) StaffQuickProfile(StaffQuickForm.ICICLE_CAGE,
        StaffQuickHitShape.CAGE, 7.0, 1.7, 1.7, control = StaffQuickControl.SLOW, groundBased = true)
    else StaffQuickProfile(StaffQuickForm.ICICLE_CLAWS, StaffQuickHitShape.CLOSE_BAND,
        3.6, 0.9, 2.15, contactTick = 4, control = StaffQuickControl.SLOW)
    StaffSpell.FAULT -> if (secondary) StaffQuickProfile(StaffQuickForm.FAULT_COLUMN,
        StaffQuickHitShape.COLUMN, 8.0, 4.0, 1.9, groundBased = true)
    else StaffQuickProfile(StaffQuickForm.FAULT_FISSURE, StaffQuickHitShape.FISSURE,
        5.2, 0.8, 0.8, groundBased = true)
    else -> error("${spell.id} has no legacy quick form")
}

internal fun staffQuickFront(ageTicks: Int, reach: Double): Double {
    val t = ((ageTicks - QUICK_WINDUP_TICKS).coerceAtLeast(0) / QUICK_TRAVEL_TICKS.toDouble()).coerceIn(0.0, 1.0)
    val smooth = t * t * (3.0 - 2.0 * t)
    return max(0.0, reach.takeIf(Double::isFinite) ?: 0.0) * smooth
}

/** The same local contact volumes are used by the controller and the matching display animation. */
internal fun staffQuickContains(profile: StaffQuickProfile, ageTicks: Int,
    previousFront: Double, front: Double, local: Vector3f, reach: Double): Boolean {
    val x = local.x.toDouble()
    val y = local.y.toDouble()
    val z = local.z.toDouble()
    val r = reach.takeIf(Double::isFinite)?.coerceAtLeast(0.1) ?: profile.reach
    return when (profile.hitShape) {
        StaffQuickHitShape.SWEEP -> {
            val lateral = profile.width * (0.18 + 0.82 * (z / r).coerceIn(0.0, 1.0))
            z >= 1.35 && z <= front + QUICK_BODY_TOLERANCE &&
                z >= previousFront - QUICK_BODY_TOLERANCE && abs(x) <= lateral && abs(y) <= 1.65
        }
        StaffQuickHitShape.CLOSE_BAND -> ageTicks >= profile.contactTick &&
            ageTicks <= profile.contactTick + QUICK_STEP_TICKS &&
            z in (r * 0.48 - 0.55)..(r * 0.92 + 0.55) &&
            abs(x) <= profile.width + QUICK_BODY_TOLERANCE && abs(y) <= 1.6
        StaffQuickHitShape.POINT -> ageTicks >= profile.contactTick &&
            ageTicks <= profile.contactTick + QUICK_STEP_TICKS && local.lengthSquared() <= profile.radius.toFloat() * profile.radius.toFloat()
        StaffQuickHitShape.WARD -> ageTicks >= profile.contactTick &&
            ageTicks <= profile.contactTick + QUICK_STEP_TICKS && z in 1.65..3.7 &&
            abs(x) <= profile.width && abs(y) <= 1.5
        StaffQuickHitShape.FISSURE -> {
            val bodyFront = (front + QUICK_BODY_TOLERANCE).coerceAtLeast(1.6)
            z >= max(1.35, previousFront - QUICK_BODY_TOLERANCE) && z <= bodyFront &&
                abs(x) <= profile.width + QUICK_BODY_TOLERANCE && abs(y) <= 1.45
        }
        StaffQuickHitShape.CAGE -> ageTicks >= profile.contactTick &&
            ageTicks <= profile.contactTick + QUICK_STEP_TICKS && x * x + z * z <= profile.radius * profile.radius &&
            y in -0.55..3.2
        StaffQuickHitShape.COLUMN -> ageTicks >= profile.contactTick &&
            ageTicks <= profile.contactTick + QUICK_STEP_TICKS && x * x + z * z <= profile.width * profile.width &&
            abs(y) <= 2.6
    }
}

/** Stable piece order, IDs and materials let the packet renderer interpolate without shape swaps. */
internal fun staffQuickDisplayParts(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    length: Double,
    radius: Double,
    impact: Boolean,
    secondary: Boolean,
): List<StaffDisplayPart> {
    val profile = staffQuickProfile(spell, secondary)
    val reach = length.takeIf { it.isFinite() && it > 0.2 }?.coerceIn(0.5, 12.0) ?: profile.reach
    val age = ageTicks.coerceAtLeast(0)
    val duration = durationTicks.coerceAtLeast(1)
    val progress = quickSmooth(age / profile.contactTick.toDouble())
    // Effects renders controller-owned scenes at two-tick keyframes, so reach a near-zero
    // terminal scale before the controller removes the scene at durationTicks.
    val fade = quickSmooth(((duration - 2 - age) / 2.0).coerceIn(0.0, 1.0)).coerceAtLeast(0.001)
    val size = 0.32 + 0.68 * progress
    val pointZ = if (profile.form == StaffQuickForm.MARK_STRIKE || profile.form == StaffQuickForm.MARK_GLYPH) 0.0
        else reach.coerceAtLeast(1.8)
    val parts = ArrayList<StaffDisplayPart>(36)

    when (profile.form) {
        StaffQuickForm.CHAIN_FAN -> repeat(3) { arc -> repeat(6) { index ->
            val t = index / 5.0
            val z = 1.85 + staffQuickFront(age, reach - 1.85) * t
            val x = (arc - 1) * 0.48 + sin(t * PI) * (arc - 1) * 0.42
            val y = (arc - 1) * 0.17 + sin(t * 2.0 * PI + arc) * 0.11
            parts += quickPart("chain:$arc:$index", if (index == 2) Material.SEA_LANTERN else Material.LIGHT_BLUE_STAINED_GLASS,
                x, y, z, 0.16 + 0.06 * size, 0.13 + 0.04 * size, 0.55 + 0.25 * size,
                yaw = (arc - 1) * 0.18, pitch = sin(t * PI) * 0.12)
        } }
        StaffQuickForm.CHAIN_SNAP -> repeat(12) { index ->
            val t = index / 11.0
            parts += quickPart("snap:$index", if (index % 3 == 0) Material.SEA_LANTERN else Material.LIGHT_BLUE_STAINED_GLASS,
                sin(t * PI * 2.0) * 0.18, cos(t * PI * 2.0) * 0.16,
                1.8 + (pointZ - 1.8) * t, 0.14, 0.14, 0.34 + 0.16 * size,
                yaw = t * PI / 3.0, pitch = 0.18)
        }
        StaffQuickForm.MARK_STRIKE, StaffQuickForm.MARK_GLYPH -> {
            val glyphRadius = (if (profile.form == StaffQuickForm.MARK_GLYPH) radius else 1.25) * (0.28 + 0.72 * progress)
            repeat(16) { index ->
                val angle = index * PI / 8.0
                parts += quickPart("mark:ring:$index", if (index % 4 == 0) Material.CRYING_OBSIDIAN else Material.PURPLE_STAINED_GLASS,
                    cos(angle) * glyphRadius, 0.12 + sin(angle * 2.0) * 0.08,
                    pointZ + sin(angle) * glyphRadius, 0.16, 0.12, 0.42 + 0.12 * size,
                    yaw = -angle, roll = angle * 0.2)
            }
            repeat(8) { index ->
                val angle = index * PI / 4.0
                parts += quickPart("mark:spoke:$index", if (index % 2 == 0) Material.AMETHYST_BLOCK else Material.CRYING_OBSIDIAN,
                    cos(angle) * glyphRadius * 0.44, 0.28, pointZ + sin(angle) * glyphRadius * 0.44,
                    0.11, 0.34, 0.56 * size, yaw = -angle)
            }
        }
        StaffQuickForm.FROST_FAN -> repeat(3) { arc -> repeat(8) { index ->
            val t = index / 7.0
            val z = 1.7 + staffQuickFront(age, reach - 1.7) * t
            val spread = (t * profile.width) * (arc - 1) * 0.6
            parts += quickPart("frost:fan:$arc:$index", when (index % 4) {
                0 -> Material.PACKED_ICE
                1 -> Material.WHITE_STAINED_GLASS
                else -> Material.BLUE_ICE
            }, spread + sin(t * PI) * (arc - 1) * 0.22, 0.18 + arc * 0.24 + sin(t * PI) * 0.3,
                z, 0.12 + 0.04 * size, 0.22 + 0.06 * size, 0.48 + 0.32 * size,
                yaw = (arc - 1) * 0.2, pitch = -0.18)
        } }
        StaffQuickForm.FROST_WARD -> repeat(8) { column -> repeat(3) { row ->
            val x = (column - 3.5) * 0.43
            parts += quickPart("frost:ward:$column:$row", if ((column + row) % 3 == 0) Material.PACKED_ICE else Material.BLUE_ICE,
                x, row * 0.52 + (1.0 - progress) * 0.4, 2.55 + sin(column * 0.8) * 0.08,
                0.26, 0.38 + 0.08 * size, 0.22, yaw = (column - 3.5) * 0.025)
        } }
        StaffQuickForm.LANCE_CLEAVE -> repeat(2) { slash -> repeat(12) { index ->
            val t = index / 11.0
            val direction = if (slash == 0) 1.0 else -1.0
            val x = direction * (t - 0.5) * 4.7
            val y = 0.22 + t * 1.0 + sin(t * PI) * 0.22
            parts += quickPart("lance:cross:$slash:$index", if (index % 4 == 0) Material.SEA_LANTERN else Material.GOLD_BLOCK,
                x, y, 2.2 + t * 1.2 + sin(t * PI) * 0.24,
                0.28 * size, 0.19 * size, 0.58 * size,
                yaw = direction * (PI / 4.0), roll = direction * 0.08)
        } }
        StaffQuickForm.LANCE_PIN -> repeat(20) { index ->
            val shaft = index < 12
            val partIndex = if (shaft) index else index - 12
            val x = if (shaft) 0.0 else cos(partIndex * PI / 4.0) * (0.48 + 0.05 * (partIndex % 3))
            val y = if (shaft) 3.3 - partIndex * 0.24 - (1.0 - progress) * 0.75 else 0.18 + (partIndex % 2) * 0.18
            parts += quickPart("lance:pin:$index", if (index % 4 == 0) Material.SEA_LANTERN else Material.GOLD_BLOCK,
                x, y, pointZ + if (shaft) 0.0 else sin(partIndex * PI / 4.0) * 0.48,
                if (shaft) 0.22 else 0.28, if (shaft) 0.34 else 0.18, if (shaft) 0.24 else 0.32,
                pitch = if (shaft) -0.18 else 0.0)
        }
        StaffQuickForm.EMBER_CRESCENT -> repeat(18) { index ->
            val t = index / 17.0
            val angle = PI * (0.12 + 0.76 * t)
            val x = cos(angle) * 2.45
            val z = 3.05 + sin(angle) * 0.34
            val y = 0.12 + sin(angle) * 0.62 + (1.0 - progress) * 0.16
            parts += quickPart("ember:crescent:$index", when (index % 4) {
                0 -> Material.YELLOW_STAINED_GLASS
                1 -> Material.MAGMA_BLOCK
                else -> Material.ORANGE_STAINED_GLASS
            }, x, y, z, 0.22 + 0.04 * size, 0.4 + 0.16 * size, 0.52 + 0.16 * size,
                yaw = -angle, pitch = -0.18)
        }
        StaffQuickForm.EMBER_FLARE -> repeat(24) { index ->
            val ring = index < 16
            val i = if (ring) index else index - 16
            val angle = i * (if (ring) PI / 8.0 else PI / 4.0)
            val spread = (radius.coerceIn(0.8, 4.5) * (0.2 + 0.8 * progress))
            parts += quickPart("ember:flare:$index", if (i % 4 == 0) Material.MAGMA_BLOCK else Material.ORANGE_STAINED_GLASS,
                cos(angle) * spread, if (ring) 0.12 + sin(angle * 2.0) * 0.11 else 0.25 + progress * 0.45,
                pointZ + sin(angle) * spread, if (ring) 0.16 else 0.32, if (ring) 0.14 else 0.42,
                if (ring) 0.32 + 0.08 * size else 0.54, yaw = -angle, pitch = -0.18)
        }
        StaffQuickForm.ICICLE_CLAWS -> repeat(3) { claw -> repeat(6) { index ->
            val t = index / 5.0
            val sign = if (claw == 1) 0.0 else if (claw == 0) -1.0 else 1.0
            val x = sign * (0.4 + t * 0.78) + sin(t * PI) * (if (claw == 0) -0.24 else 0.24)
            val z = 1.8 + t * 1.65
            val y = 0.08 + sin(t * PI) * (0.52 + claw * 0.08)
            parts += quickPart("icicle:claw:$claw:$index", if (index % 3 == 0) Material.PACKED_ICE else Material.BLUE_ICE,
                x, y, z, 0.18 * size, 0.2 * size, 0.58 * size,
                yaw = sign * 0.22, pitch = 0.12 + t * 0.22)
        } }
        StaffQuickForm.ICICLE_CAGE -> repeat(8) { crystal -> repeat(4) { index ->
            val angle = crystal * PI / 4.0
            val rise = index / 3.0
            val h = 0.28 + rise * (2.7 * progress)
            val r = radius.coerceIn(0.8, 2.3) * (0.68 + 0.18 * rise)
            parts += quickPart("icicle:cage:$crystal:$index", when (index) {
                0 -> Material.PACKED_ICE
                1 -> Material.SEA_LANTERN
                else -> Material.BLUE_ICE
            }, cos(angle) * r, h, pointZ + sin(angle) * r,
                0.20 + 0.04 * size, 0.28 + 0.05 * size, 0.8 + 0.22 * size,
                yaw = -angle, pitch = -0.12 - progress * 0.16)
        } }
        StaffQuickForm.FAULT_FISSURE -> repeat(28) { index ->
            val crack = index < 20
            val i = if (crack) index else index - 20
            val t = if (crack) i / 19.0 else (i + 2) / 9.0
            val front = staffQuickFront(age, reach - 1.4)
            val z = if (crack) 1.4 + front * t else 1.7 + front * t
            val x = if (crack) sin(i * 1.7) * 0.22 else (if (i % 2 == 0) -1 else 1) * (0.36 + (i % 3) * 0.13)
            val lift = if (crack) 0.05 + sin(t * PI) * 0.13 else 0.12 + progress * (0.58 + (i % 3) * 0.12)
            parts += quickPart("fault:fissure:$index", when (i % 5) {
                0 -> Material.AMETHYST_BLOCK
                1 -> Material.OBSIDIAN
                else -> Material.BASALT
            }, x, -1.48 + lift, z, if (crack) 0.2 else 0.34, if (crack) 0.12 else 0.36,
                if (crack) 0.48 else 0.56, yaw = sin(i * 1.5) * 0.2, pitch = -0.16)
        }
        StaffQuickForm.FAULT_COLUMN -> repeat(32) { index ->
            val wedge = index < 24
            val i = if (wedge) index / 3 else index - 24
            val layer = if (wedge) index % 3 else 0
            val angle = i * (if (wedge) PI / 4.0 else PI / 4.0) + if (wedge) 0.0 else PI / 8.0
            val rise = if (wedge) layer / 2.0 else 0.15
            val r = if (wedge) radius.coerceIn(1.4, 4.5) * (0.2 + rise * 0.16) else radius.coerceIn(1.4, 4.5) * 0.16
            val h = if (wedge) 0.55 + rise * 1.6 * progress else 0.18
            parts += quickPart("fault:column:$index", when (i % 5) {
                0 -> Material.POLISHED_BASALT
                1 -> Material.AMETHYST_BLOCK
                else -> Material.BLACKSTONE
            }, cos(angle) * r, h, pointZ + sin(angle) * r,
                if (wedge) 0.42 else 0.26, if (wedge) 0.68 + rise * 0.14 else 0.14,
                if (wedge) 0.46 else 0.48, yaw = angle, pitch = -0.12 - progress * 0.18)
        }
    }
    return parts.map { part -> part.copy(scale = Vector3f(part.scale).mul(fade.toFloat())) }
}

private const val QUICK_WINDUP_TICKS = 2
private const val QUICK_TRAVEL_TICKS = 4
internal const val STAFF_QUICK_STEP_TICKS = 2
internal const val STAFF_QUICK_DURATION_TICKS = 10
private const val QUICK_STEP_TICKS = STAFF_QUICK_STEP_TICKS
private const val QUICK_BODY_TOLERANCE = 0.45

private fun quickPart(identity: String, material: Material, x: Double, y: Double, z: Double,
    sx: Double, sy: Double, sz: Double, yaw: Double = 0.0, pitch: Double = 0.0,
    roll: Double = 0.0): StaffDisplayPart = StaffDisplayPart(material,
    Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
    Vector3f(max(0.03, sx).toFloat(), max(0.03, sy).toFloat(), max(0.03, sz).toFloat()),
    Quaternionf().rotateY(yaw.toFloat()).rotateX(pitch.toFloat()).rotateZ(roll.toFloat()),
    identity = "staff-quick:$identity")

private fun quickSmooth(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}
