package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val STAFF_GESTURE_STEP_TICKS = 2
private const val MAX_GESTURE_SAMPLES = 192

internal enum class StaffGestureForm {
    CHAIN_FORKS, CHAIN_SNAP, MARK_GRASP, MARK_PULL, FROST_BREATH, FROST_WARD,
    LANCE_SWEEP, LANCE_PIN, EMBER_JET, EMBER_FLARE, ICICLE_SHOTGUN, ICICLE_CAGE,
    FAULT_GRAVEL, FAULT_COLUMN, LUNAR_CRESCENT, LUNAR_ECLIPSE, GALE_PALM, GALE_UPDRAFT,
    TIDE_WHIP, TIDE_GEYSER, REQUIEM_STREAM, REQUIEM_PULSE, PRISM_FAN, PRISM_FOCUS,
    NOVA_THORNS, NOVA_ROOTS, NECROMANCER_BONE, NECROMANCER_SOUL,
}

internal enum class StaffGestureContact { ADVANCE, GROUND, CONE, SLASH, POINT, WALL, COLUMN, UPDRAFT }
internal enum class StaffGestureControl { NONE, SLOW, PULL, UPDRAFT }
internal enum class StaffGestureAnchor { FORWARD, POINT, GROUND_FORWARD }

internal data class StaffGestureProfile(
    val form: StaffGestureForm,
    val contact: StaffGestureContact,
    val anchor: StaffGestureAnchor,
    val reach: Double,
    val width: Double,
    val radius: Double,
    val durationTicks: Int,
    val anticipationTicks: Int,
    val control: StaffGestureControl = StaffGestureControl.NONE,
)

internal fun staffGestureProfile(spell: StaffSpell, input: StaffCastInput): StaffGestureProfile {
    require(input.quick) { "staff gestures own quick inputs only" }
    val secondary = input.secondary
    return when (spell) {
        StaffSpell.CHAIN -> if (secondary) StaffGestureProfile(StaffGestureForm.CHAIN_SNAP,
            StaffGestureContact.POINT, StaffGestureAnchor.POINT, 8.0, 1.4, 1.4, 16, 4)
        else StaffGestureProfile(StaffGestureForm.CHAIN_FORKS, StaffGestureContact.ADVANCE,
            StaffGestureAnchor.FORWARD, 6.0, 1.9, 1.0, 18, 4)
        StaffSpell.MARK -> if (secondary) StaffGestureProfile(StaffGestureForm.MARK_PULL,
            StaffGestureContact.POINT, StaffGestureAnchor.POINT, 8.0, 3.2, 3.2, 18, 5,
            StaffGestureControl.PULL)
        else StaffGestureProfile(StaffGestureForm.MARK_GRASP, StaffGestureContact.POINT,
            StaffGestureAnchor.POINT, 6.0, 1.4, 1.4, 14, 4)
        StaffSpell.FROST -> if (secondary) StaffGestureProfile(StaffGestureForm.FROST_WARD,
            StaffGestureContact.WALL, StaffGestureAnchor.FORWARD, 4.5, 2.6, 2.7, 16, 4,
            StaffGestureControl.SLOW)
        else StaffGestureProfile(StaffGestureForm.FROST_BREATH, StaffGestureContact.CONE,
            StaffGestureAnchor.FORWARD, 7.0, 2.7, 1.2, 18, 4, StaffGestureControl.SLOW)
        StaffSpell.LANCE -> if (secondary) StaffGestureProfile(StaffGestureForm.LANCE_PIN,
            StaffGestureContact.COLUMN, StaffGestureAnchor.POINT, 8.0, 1.2, 1.2, 16, 5)
        else StaffGestureProfile(StaffGestureForm.LANCE_SWEEP, StaffGestureContact.SLASH,
            StaffGestureAnchor.FORWARD, 5.5, 2.7, 1.0, 14, 4)
        StaffSpell.EMBER -> if (secondary) StaffGestureProfile(StaffGestureForm.EMBER_FLARE,
            StaffGestureContact.POINT, StaffGestureAnchor.POINT, 8.0, 2.6, 2.6, 16, 4)
        else StaffGestureProfile(StaffGestureForm.EMBER_JET, StaffGestureContact.ADVANCE,
            StaffGestureAnchor.FORWARD, 5.5, 1.35, 0.8, 14, 3)
        StaffSpell.ICICLE -> if (secondary) StaffGestureProfile(StaffGestureForm.ICICLE_CAGE,
            StaffGestureContact.COLUMN, StaffGestureAnchor.POINT, 8.0, 1.65, 1.65, 18, 5,
            StaffGestureControl.SLOW)
        else StaffGestureProfile(StaffGestureForm.ICICLE_SHOTGUN, StaffGestureContact.CONE,
            StaffGestureAnchor.FORWARD, 6.5, 2.1, 0.8, 16, 4, StaffGestureControl.SLOW)
        StaffSpell.FAULT -> if (secondary) StaffGestureProfile(StaffGestureForm.FAULT_COLUMN,
            StaffGestureContact.COLUMN, StaffGestureAnchor.POINT, 8.0, 1.8, 1.8, 18, 5)
        else StaffGestureProfile(StaffGestureForm.FAULT_GRAVEL, StaffGestureContact.GROUND,
            StaffGestureAnchor.GROUND_FORWARD, 7.0, 1.55, 0.8, 18, 5)
        StaffSpell.LUNAR -> if (secondary) StaffGestureProfile(StaffGestureForm.LUNAR_ECLIPSE,
            StaffGestureContact.POINT, StaffGestureAnchor.POINT, 8.0, 2.2, 2.2, 16, 5)
        else StaffGestureProfile(StaffGestureForm.LUNAR_CRESCENT, StaffGestureContact.SLASH,
            StaffGestureAnchor.FORWARD, 6.0, 2.7, 0.9, 16, 5)
        StaffSpell.GALE -> if (secondary) StaffGestureProfile(StaffGestureForm.GALE_UPDRAFT,
            StaffGestureContact.UPDRAFT, StaffGestureAnchor.POINT, 8.0, 1.8, 1.8, 18, 5,
            StaffGestureControl.UPDRAFT)
        else StaffGestureProfile(StaffGestureForm.GALE_PALM, StaffGestureContact.CONE,
            StaffGestureAnchor.FORWARD, 5.5, 2.4, 1.0, 14, 3)
        StaffSpell.TIDE -> if (secondary) StaffGestureProfile(StaffGestureForm.TIDE_GEYSER,
            StaffGestureContact.COLUMN, StaffGestureAnchor.POINT, 8.0, 1.7, 1.7, 18, 5,
            StaffGestureControl.UPDRAFT)
        else StaffGestureProfile(StaffGestureForm.TIDE_WHIP, StaffGestureContact.SLASH,
            StaffGestureAnchor.FORWARD, 6.5, 2.2, 0.8, 16, 4)
        StaffSpell.REQUIEM -> if (secondary) StaffGestureProfile(StaffGestureForm.REQUIEM_PULSE,
            StaffGestureContact.POINT, StaffGestureAnchor.POINT, 8.0, 2.8, 2.8, 20, 6,
            StaffGestureControl.SLOW)
        else StaffGestureProfile(StaffGestureForm.REQUIEM_STREAM, StaffGestureContact.ADVANCE,
            StaffGestureAnchor.FORWARD, 7.0, 1.35, 0.8, 18, 5)
        StaffSpell.PRISM -> if (secondary) StaffGestureProfile(StaffGestureForm.PRISM_FOCUS,
            StaffGestureContact.POINT, StaffGestureAnchor.POINT, 12.0, 1.7, 1.7, 18, 5)
        else StaffGestureProfile(StaffGestureForm.PRISM_FAN, StaffGestureContact.CONE,
            StaffGestureAnchor.FORWARD, 8.0, 2.4, 0.8, 20, 6)
        StaffSpell.NOVA -> if (secondary) StaffGestureProfile(StaffGestureForm.NOVA_ROOTS,
            StaffGestureContact.POINT, StaffGestureAnchor.POINT, 8.0, 2.2, 2.2, 20, 6,
            StaffGestureControl.SLOW)
        else StaffGestureProfile(StaffGestureForm.NOVA_THORNS, StaffGestureContact.SLASH,
            StaffGestureAnchor.FORWARD, 6.5, 2.5, 0.9, 18, 5)
        StaffSpell.NECROMANCER -> if (secondary) StaffGestureProfile(StaffGestureForm.NECROMANCER_SOUL,
            StaffGestureContact.COLUMN, StaffGestureAnchor.POINT, 8.0, 1.7, 1.7, 20, 6,
            StaffGestureControl.SLOW)
        else StaffGestureProfile(StaffGestureForm.NECROMANCER_BONE, StaffGestureContact.ADVANCE,
            StaffGestureAnchor.FORWARD, 7.0, 1.6, 0.8, 18, 5)
    }
}

internal fun staffGestureFront(ageTicks: Int, reach: Double, profile: StaffGestureProfile): Double {
    val travel = (profile.durationTicks - profile.anticipationTicks - STAFF_GESTURE_STEP_TICKS).coerceAtLeast(1)
    val t = ((ageTicks - profile.anticipationTicks).coerceAtLeast(0) / travel.toDouble()).coerceIn(0.0, 1.0)
    val smooth = t * t * (3.0 - 2.0 * t)
    return (reach.takeIf(Double::isFinite)?.coerceIn(0.0, 12.0) ?: 0.0) * smooth
}

private fun gestureProgress(ageTicks: Int, profile: StaffGestureProfile): Double =
    gestureSmooth((ageTicks - profile.anticipationTicks).toDouble() /
        (profile.durationTicks - profile.anticipationTicks).coerceAtLeast(1))

private fun gestureStageProgress(ageTicks: Int, profile: StaffGestureProfile): Double =
    gestureSmooth((ageTicks - profile.anticipationTicks).toDouble() /
        (profile.durationTicks - 2 - profile.anticipationTicks).coerceAtLeast(1))

private fun lanceSweepPoint(ageTicks: Int, profile: StaffGestureProfile, along: Double): Vector {
    val progress = gestureStageProgress(ageTicks, profile)
    return Vector(-1.5 + progress * 3.0 + along,
        -0.85 + progress * 0.25 + along * 0.28,
        2.0 + progress * 3.0 + along * 0.42)
}

private fun lunarCrescentPoint(ageTicks: Int, profile: StaffGestureProfile, t: Double): Vector {
    val angle = PI * (0.07 + 0.86 * t)
    val travel = gestureStageProgress(ageTicks, profile)
    return Vector(cos(angle) * 1.5, -1.7 + sin(angle) * 2.0,
        2.1 + travel * 2.2 + cos(angle) * 0.18)
}

/** Contact is sampled from the same advancing front or authored field shown in staffGestureFrame. */
internal fun staffGestureContact(
    profile: StaffGestureProfile,
    ageTicks: Int,
    previousAgeTicks: Int,
    local: Vector,
    reach: Double,
): Boolean {
    if (!validGestureVector(local)) return false
    val safeReach = reach.takeIf(Double::isFinite)?.coerceIn(0.5, 12.0) ?: profile.reach
    val x = local.x
    val y = local.y
    val z = local.z
    val front = staffGestureFront(ageTicks, safeReach, profile)
    val prior = staffGestureFront(previousAgeTicks, safeReach, profile)
    val progress = gestureProgress(ageTicks, profile)
    return when (profile.contact) {
        StaffGestureContact.ADVANCE -> {
            val ratio = (z / safeReach).coerceIn(0.0, 1.0)
            val widthAt = when (profile.form) {
                StaffGestureForm.CHAIN_FORKS -> 0.28 + 1.4 * ratio
                StaffGestureForm.EMBER_JET -> 0.22 + 0.9 * ratio
                StaffGestureForm.REQUIEM_STREAM -> 0.52
                StaffGestureForm.NECROMANCER_BONE -> 0.32 + 0.5 * ratio
                else -> profile.width * (0.22 + 0.5 * ratio)
            }
            val withinHeight = when (profile.form) {
                StaffGestureForm.EMBER_JET -> y in -1.35..0.8
                StaffGestureForm.REQUIEM_STREAM -> y in -1.0..0.75
                StaffGestureForm.NECROMANCER_BONE -> y in -1.2..0.8
                else -> abs(y) <= 1.35
            }
            z >= max(1.35, prior - 0.55) && z <= front + 0.55 &&
                abs(x) <= widthAt && withinHeight
        }
        StaffGestureContact.GROUND -> {
            z >= max(1.35, prior - 0.55) && z <= front + 0.55 &&
                abs(x) <= 1.45 && y in -1.75..0.35
        }
        StaffGestureContact.CONE -> {
            when (profile.form) {
                StaffGestureForm.FROST_BREATH -> {
                    val widthAt = profile.width * (z / safeReach).coerceIn(0.0, 1.0)
                    z >= max(1.35, prior - 0.55) && z <= front + 0.55 &&
                        abs(x) <= widthAt + 0.2 && abs(y) <= widthAt * 0.62 + 0.25
                }
                StaffGestureForm.ICICLE_SHOTGUN -> {
                    val widthAt = 0.25 + profile.width * (0.12 + 0.43 * (z / safeReach).coerceIn(0.0, 1.0))
                    z >= max(1.5, prior - 0.55) && z <= front + 0.55 &&
                        abs(x) <= widthAt && y in -1.0..1.25
                }
                StaffGestureForm.GALE_PALM -> ageTicks >= profile.anticipationTicks &&
                    ageTicks <= profile.durationTicks - 2 && z in 1.8..3.05 &&
                    abs(x) <= 0.7 + progress * 0.55 && y in -1.1..0.8
                StaffGestureForm.PRISM_FAN -> {
                    val widthAt = 0.2 + 2.1 * (z / safeReach).coerceIn(0.0, 1.0)
                    z >= max(1.35, prior - 0.55) && z <= front + 0.55 &&
                        abs(x) <= widthAt && y in -1.0..0.8
                }
                else -> false
            }
        }
        StaffGestureContact.SLASH -> {
            val active = ageTicks >= profile.durationTicks - 6 && ageTicks <= profile.durationTicks - 2
            when (profile.form) {
                StaffGestureForm.LANCE_SWEEP -> {
                    val striking = ageTicks in profile.anticipationTicks..(profile.durationTicks - 2)
                    val head = lanceSweepPoint(ageTicks, profile, 0.0)
                    val along = (x - head.x).coerceIn(-0.85, 0.85)
                    val visiblePoint = lanceSweepPoint(ageTicks, profile, along)
                    striking && abs(x - head.x) <= 0.95 &&
                        abs(y - visiblePoint.y) <= 0.72 && abs(z - visiblePoint.z) <= 0.55
                }
                StaffGestureForm.LUNAR_CRESCENT -> active && (0..10).any { index ->
                    val point = lunarCrescentPoint(ageTicks, profile, index / 10.0)
                    abs(x - point.x) <= 0.5 && abs(y - point.y) <= 0.7 && abs(z - point.z) <= 0.55
                }
                StaffGestureForm.TIDE_WHIP -> {
                    if (!active || z !in 1.35..(front + 0.3)) false else {
                        val t = ((z - 1.5) / (front - 1.5).coerceAtLeast(0.1)).coerceIn(0.0, 1.0)
                        val centerX = sin(t * PI * 1.55 + progress) * profile.width * 0.58
                        val centerY = -0.25 + sin(t * PI) * 0.35
                        abs(x - centerX) <= 0.5 && abs(y - centerY) <= 1.1
                    }
                }
                StaffGestureForm.NOVA_THORNS -> active && (0..1).any { node ->
                    val t = node + progress
                    (0..5).any { vine ->
                        val centerX = (vine - 2.5) * (0.35 + t * 0.22) + sin(t * PI) * 0.28
                        val centerY = 0.08 + node * 0.35
                        val centerZ = 1.8 + t * 1.35
                        abs(x - centerX) <= 0.45 && abs(y - centerY) <= 1.15 && abs(z - centerZ) <= 0.65
                    }
                }
                else -> false
            }
        }
        StaffGestureContact.WALL -> ageTicks >= profile.durationTicks - 8 &&
            ageTicks <= profile.durationTicks - 2 && z in 2.5..3.5 &&
            abs(x) <= 2.15 && y in -1.1..1.8
        StaffGestureContact.POINT -> {
            if (ageTicks !in (profile.durationTicks - 4)..(profile.durationTicks - 2)) false else {
                val distance = local.length()
                val radius = when (profile.form) {
                    StaffGestureForm.MARK_GRASP -> 0.3 + 1.8 * (1.0 - progress) + 0.5
                    StaffGestureForm.MARK_PULL -> 3.0 * (1.0 - progress * 0.6) + 0.45
                    StaffGestureForm.EMBER_FLARE -> 0.35 + progress * profile.radius + 0.5
                    StaffGestureForm.LUNAR_ECLIPSE -> 2.0 * (1.0 - progress * 0.45) + 0.5
                    StaffGestureForm.REQUIEM_PULSE -> 2.7 * (1.0 - progress * 0.65) + 0.5
                    StaffGestureForm.PRISM_FOCUS -> 1.7 * (1.0 - progress * 0.5) + 0.5
                    StaffGestureForm.NOVA_ROOTS -> 0.4 + progress * 1.65 + 0.45
                    else -> profile.radius
                }
                distance <= radius
            }
        }
        StaffGestureContact.COLUMN -> {
            val firstImpactTick = if (profile.form == StaffGestureForm.ICICLE_CAGE) {
                profile.durationTicks - 10
            } else {
                profile.durationTicks - 4
            }
            if (ageTicks !in firstImpactTick..(profile.durationTicks - 2)) false else {
                val radius = when (profile.form) {
                    StaffGestureForm.LANCE_PIN -> 0.55
                    StaffGestureForm.FAULT_COLUMN -> 1.3
                    StaffGestureForm.ICICLE_CAGE -> 1.55
                    StaffGestureForm.TIDE_GEYSER -> 1.25
                    StaffGestureForm.NECROMANCER_SOUL -> 1.75
                    else -> profile.width
                }
                val yRange = when (profile.form) {
                    StaffGestureForm.LANCE_PIN -> y in -0.25..3.35
                    StaffGestureForm.FAULT_COLUMN -> y in -0.4..2.2
                    StaffGestureForm.TIDE_GEYSER -> y in -0.4..2.9
                    StaffGestureForm.NECROMANCER_SOUL -> y in -0.3..2.1
                    StaffGestureForm.ICICLE_CAGE -> abs(y - (0.25 + progress * 2.8)) <= 1.15
                    else -> y in -0.35..3.2
                }
                x * x + z * z <= radius * radius && yRange
            }
        }
        StaffGestureContact.UPDRAFT -> ageTicks >= profile.durationTicks - 4 &&
            ageTicks <= profile.durationTicks - 2 && x * x + z * z <= 1.35 * 1.35 &&
            y in -0.5..2.2
    }
}

/** Authored local particle frame, shared by native runtime and offline preview. */
internal fun staffGestureFrame(
    spell: StaffSpell,
    input: StaffCastInput,
    ageTicks: Int,
    reach: Double,
): List<StaffParticleSample> {
    val profile = staffGestureProfile(spell, input)
    val age = ageTicks.coerceIn(0, profile.durationTicks)
    val safeReach = reach.takeIf(Double::isFinite)?.coerceIn(0.5, 12.0) ?: profile.reach
    val front = staffGestureFront(age, safeReach, profile).coerceAtLeast(1.35)
    val progress = gestureProgress(age, profile)
    val out = ArrayList<StaffParticleSample>(24)
    val electric = staffFade(0x44CFFF, 0xE7FFFF, 1.15f)
    val ice = staffFade(0x63C8FF, 0xF1FFFF, 1.25f)
    val solar = staffFade(0xFFB52E, 0xFFF2A0, 1.25f)
    val ember = staffFade(0xF04A18, 0xFFD76A, 1.3f)
    val soul = staffFade(0x42266F, 0xB18BFF, 1.25f)
    val prism = staffFade(0x62E7FF, 0xFFD2F6, 1.15f)
    val thorn = staffFade(0x663ED0, 0xFF4E88, 1.25f)

    when (profile.form) {
        StaffGestureForm.CHAIN_FORKS -> repeat(3) { fork -> repeat(5) { node ->
            val t = (node + 1) / 5.0
            val z = 1.45 + (front - 1.45).coerceAtLeast(0.0) * t
            val zig = if ((node + fork) % 2 == 0) 0.22 else -0.22
            val side = (fork - 1) * (0.2 + 0.52 * t) + zig
            addGesture(out, Particle.ELECTRIC_SPARK, side, 0.16 * (fork - 1) + zig * 0.15, z, 3)
            if (node % 2 == 0) addGesture(out, Particle.DUST_COLOR_TRANSITION,
                side, 0.16 * (fork - 1) + zig * 0.15, z, 2, data = electric)
        } }
        StaffGestureForm.CHAIN_SNAP -> repeat(9) { index ->
            val t = index / 8.0
            val angle = t * PI * 4.0
            val radius = 0.18 + (1.0 - progress) * 0.35
            addGesture(out, Particle.ELECTRIC_SPARK, cos(angle) * radius, sin(angle) * radius, 0.0, 5)
        }
        StaffGestureForm.MARK_GRASP -> repeat(10) { index ->
            val t = index / 9.0
            val radius = 0.3 + (1.8 * (1.0 - progress))
            val angle = t * PI * 2.0 + progress * PI * 1.8
            addGesture(out, Particle.REVERSE_PORTAL, cos(angle) * radius, 0.15 + sin(angle * 2.0) * 0.2,
                sin(angle) * radius, 3)
            addGesture(out, Particle.DUST_COLOR_TRANSITION, cos(angle) * radius, 0.12,
                sin(angle) * radius, 2, data = soul)
        }
        StaffGestureForm.MARK_PULL -> repeat(8) { ray -> repeat(2) { node ->
            val t = node / 1.0
            val angle = ray * PI / 4.0 + progress * PI * 0.9
            val radius = 3.0 * (1.0 - progress * (0.6 + 0.35 * t))
            addGesture(out, Particle.SOUL, cos(angle) * radius, 0.1 + t * 0.5, sin(angle) * radius, 3)
        } }
        StaffGestureForm.FROST_BREATH -> {
            repeat(9) { index ->
                val angle = index * PI / 4.0
                val spread = (front / safeReach) * profile.width
                addGesture(out, Particle.SNOWFLAKE, cos(angle) * spread, sin(angle) * spread * 0.62,
                    front, 4, spread = Vector(0.06, 0.05, 0.06))
            }
            repeat(4) { index -> addGesture(out, Particle.CLOUD,
                (index - 1.5) * profile.width * progress * 0.28, 0.05, front * (0.55 + index * 0.1), 4,
                spread = Vector(0.13, 0.08, 0.08)) }
        }
        StaffGestureForm.FROST_WARD -> repeat(9) { column ->
            val x = (column - 4) * 0.48
            val y = (column % 3) * 0.62 + progress * 0.25
            addGesture(out, Particle.SNOWFLAKE, x, y, 3.0, 5, spread = Vector(0.04, 0.08, 0.04))
            if (column % 2 == 0) addGesture(out, Particle.DUST_COLOR_TRANSITION, x, y, 3.0, 2, data = ice)
        }
        StaffGestureForm.LANCE_SWEEP -> if (age >= profile.anticipationTicks) {
            repeat(2) { lane ->
                val side = if (lane == 0) -1.0 else 1.0
                repeat(20) { index ->
                    val t = index / 19.0
                    val along = (t - 0.5) * 1.7
                    val point = lanceSweepPoint(age, profile, along)
                    val crossing = side * (t - 0.5) * 0.16
                    addGesture(out, Particle.DUST_COLOR_TRANSITION, point.x + crossing,
                        point.y - side * 0.06, point.z, 1,
                        spread = Vector(0.005, 0.005, 0.005), data = solar)
                }
            }
            repeat(4) { spark ->
                val along = (spark / 3.0 - 0.5) * 1.35
                val point = lanceSweepPoint(age, profile, along)
                addGesture(out, Particle.END_ROD, point.x, point.y, point.z, 1,
                    spread = Vector(0.005, 0.005, 0.005))
            }
        }
        StaffGestureForm.LANCE_PIN -> repeat(8) { index ->
            val t = index / 7.0
            addGesture(out, Particle.END_ROD, sin(t * PI) * 0.3, 3.2 * (1.0 - progress * 0.8), 0.0, 4)
            if (index % 2 == 0) addGesture(out, Particle.DUST_COLOR_TRANSITION,
                sin(t * PI) * 0.3, 3.2 * (1.0 - progress * 0.8), 0.0, 2, data = solar)
        }
        StaffGestureForm.EMBER_JET -> repeat(8) { index ->
            val t = (index + 1) / 8.0
            val z = 1.35 + (front - 1.35).coerceAtLeast(0.0) * t
            val spread = 0.16 + t * 0.92
            addGesture(out, Particle.FLAME, sin(index * 1.8) * spread, cos(index * 1.2) * spread * 0.38, z, 4,
                spread = Vector(0.05, 0.04, 0.04))
            addGesture(out, Particle.SMOKE, sin(index * 1.8) * spread, 0.12, z - 0.15, 2, data = null)
        }
        StaffGestureForm.EMBER_FLARE -> repeat(12) { index ->
            val angle = index * PI / 6.0
            val radius = 0.35 + progress * profile.radius
            addGesture(out, Particle.FLAME, cos(angle) * radius, 0.2 + sin(angle * 2.0) * 0.22,
                sin(angle) * radius, 4)
            if (index % 2 == 0) addGesture(out, Particle.DUST_COLOR_TRANSITION,
                cos(angle) * radius, 0.25, sin(angle) * radius, 2, data = ember)
        }
        StaffGestureForm.ICICLE_SHOTGUN -> repeat(7) { fragment -> repeat(5) { node ->
            val t = node / 4.0
            val spread = (fragment - 3) * (0.12 + t * 0.25)
            val z = 1.55 + (front - 1.55).coerceAtLeast(0.0) * t
            val y = 0.14 + sin(t * PI) * (0.55 + abs(fragment - 3) * 0.05)
            addGesture(out, Particle.SNOWFLAKE, spread + sin(t * PI * 2 + fragment) * 0.16,
                y, z, 1, spread = Vector(0.025, 0.025, 0.04))
            if (node == 4) addGesture(out, Particle.END_ROD, spread, y, z, 1)
        } }
        StaffGestureForm.ICICLE_CAGE -> repeat(8) { shard ->
            val angle = shard * PI / 4.0
            val rise = progress * 2.8
            val radius = 0.9 + 0.3 * (shard % 3)
            addGesture(out, Particle.SNOWFLAKE, cos(angle) * radius, 0.25 + rise, sin(angle) * radius, 4)
            addGesture(out, Particle.DUST_COLOR_TRANSITION, cos(angle) * radius, 0.55 + rise * 0.65,
                sin(angle) * radius, 2, data = ice)
        }
        StaffGestureForm.FAULT_GRAVEL -> repeat(10) { index ->
            val t = (index + 1) / 10.0
            val z = 1.4 + (front - 1.4).coerceAtLeast(0.0) * t
            val side = if (index % 2 == 0) -1.0 else 1.0
            val y = -1.25 + sin(t * PI) * 0.22 + (index % 3) * 0.08
            addGesture(out, Particle.BLOCK, side * (0.18 + index % 4 * 0.34), y, z, 3,
                spread = Vector(0.08, 0.05, 0.08), data = Material.BASALT.createBlockData())
            addGesture(out, Particle.DUST, 0.0, y, z, 3, data = staffDust(0x887E8E, 1.4f))
        }
        StaffGestureForm.FAULT_COLUMN -> repeat(8) { side ->
            val angle = side * PI / 4.0
            val radius = 0.65 + (side % 3) * 0.18
            repeat(2) { layer -> addGesture(out, Particle.BLOCK, cos(angle) * radius,
                0.3 + layer * (1.1 + progress * 0.6), sin(angle) * radius, 3,
                data = Material.BASALT.createBlockData()) }
        }
        StaffGestureForm.LUNAR_CRESCENT -> if (age >= profile.anticipationTicks) {
            val lunar = staffFade(0x9A9FFF, 0xEEE8FF, 1.3f)
            repeat(56) { index ->
                val t = index / 55.0
                val point = lunarCrescentPoint(age, profile, t)
                addGesture(out, Particle.DUST_COLOR_TRANSITION, point.x, point.y, point.z, 1,
                    spread = Vector(0.005, 0.005, 0.005), data = lunar)
                if (index % 7 == 0) addGesture(out, Particle.END_ROD, point.x + 0.035,
                    point.y + 0.025, point.z + 0.025, 1, spread = Vector(0.005, 0.005, 0.005))
            }
        }
        StaffGestureForm.LUNAR_ECLIPSE -> repeat(10) { index ->
            val angle = index * PI / 5.0 + progress * PI * 0.75
            val radius = 2.0 * (1.0 - progress * 0.45)
            addGesture(out, Particle.REVERSE_PORTAL, cos(angle) * radius,
                0.15 + sin(angle * 2.0) * 0.22, sin(angle) * radius, 4)
            if (index % 2 == 0) addGesture(out, Particle.END_ROD, cos(angle) * radius, 0.2,
                sin(angle) * radius, 2)
        }
        StaffGestureForm.GALE_PALM -> repeat(5) { finger -> repeat(2) { node ->
            val spread = (finger - 2) * (0.45 + progress * 0.55)
            addGesture(out, Particle.CLOUD, spread, 0.15 + node * 0.32, 2.0 + node * 0.8, 5,
                spread = Vector(0.09, 0.08, 0.08))
            if (node == 1) addGesture(out, Particle.DUST_COLOR_TRANSITION, spread, 0.28, 2.8, 2,
                data = staffFade(0xA7F6FF, 0xFFFFFF, 1.1f))
        } }
        StaffGestureForm.GALE_UPDRAFT -> repeat(6) { stream -> repeat(2) { node ->
            val t = node + progress
            val angle = stream * PI / 3.0 + t * PI * 0.7
            val radius = 0.35 + t * 0.38
            addGesture(out, Particle.CLOUD, cos(angle) * radius, t * 1.0, sin(angle) * radius, 4,
                spread = Vector(0.06, 0.08, 0.06))
        } }
        StaffGestureForm.TIDE_WHIP -> {
            val waterCore = staffFade(0x4FBFEA, 0xE2FBFF, 1.2f)
            repeat(42) { index ->
                val t = index / 41.0
                val z = 1.5 + (front - 1.5).coerceAtLeast(0.0) * t
                val x = sin(t * PI * 1.55 + progress) * profile.width * 0.58
                val y = -0.25 + sin(t * PI) * 0.35
                addGesture(out, Particle.DUST_COLOR_TRANSITION, x, y, z, 1,
                    spread = Vector(0.005, 0.005, 0.005), data = waterCore)
                if (index % 3 == 0) addGesture(out, Particle.SPLASH, x, y, z, 1,
                    spread = Vector(0.02, 0.02, 0.02))
            }
        }
        StaffGestureForm.TIDE_GEYSER -> repeat(8) { stream ->
            val angle = stream * PI / 4.0
            val height = 0.3 + progress * (1.8 + (stream % 3) * 0.35)
            addGesture(out, Particle.SPLASH, cos(angle) * 0.45, height, sin(angle) * 0.45, 4)
            if (stream % 2 == 0) addGesture(out, Particle.SPLASH, cos(angle) * 0.9, 0.2,
                sin(angle) * 0.9, 3)
            if (stream % 2 == 0) addGesture(out, Particle.DUST_COLOR_TRANSITION,
                cos(angle) * 0.55, height * 0.75, sin(angle) * 0.55, 2,
                data = staffFade(0x4FBFEA, 0xE2FBFF, 1.15f))
        }
        StaffGestureForm.REQUIEM_STREAM -> repeat(8) { index ->
            val t = (index + 1) / 8.0
            val z = 1.4 + (front - 1.4).coerceAtLeast(0.0) * t
            addGesture(out, Particle.SOUL, sin(t * PI * 3.0) * 0.35, 0.1 + sin(t * PI) * 0.3, z, 5)
            if (index % 2 == 0) addGesture(out, Particle.DUST_COLOR_TRANSITION,
                sin(t * PI * 3.0) * 0.35, 0.1, z, 2, data = soul)
        }
        StaffGestureForm.REQUIEM_PULSE -> repeat(10) { index ->
            val angle = index * PI / 5.0
            val radius = 2.7 * (1.0 - progress * 0.65)
            addGesture(out, Particle.SOUL, cos(angle) * radius, 0.15 + sin(angle * 2.0) * 0.22,
                sin(angle) * radius, 4)
        }
        StaffGestureForm.PRISM_FAN -> repeat(5) { beam -> repeat(5) { node ->
            val t = (node + 1) / 5.0
            val side = (beam - 2) * (0.2 + 0.85 * t)
            val z = 1.45 + (front - 1.45).coerceAtLeast(0.0) * t
            addGesture(out, Particle.END_ROD, side, (beam - 2) * 0.08, z, 2)
            if (node % 2 == 0) addGesture(out, Particle.DUST_COLOR_TRANSITION, side, 0.04, z, 2, data = prism)
        } }
        StaffGestureForm.PRISM_FOCUS -> repeat(7) { ray -> repeat(2) { node ->
            val angle = ray * (2.0 * PI / 7.0)
            val radius = 1.7 * (1.0 - progress * (0.5 + node * 0.2))
            addGesture(out, Particle.END_ROD, cos(angle) * radius, 0.1 + node * 0.35,
                sin(angle) * radius, 4)
        } }
        StaffGestureForm.NOVA_THORNS -> repeat(6) { vine -> repeat(2) { node ->
            val t = node + progress
            val x = (vine - 2.5) * (0.35 + t * 0.22)
            val z = 1.8 + t * 1.35
            addGesture(out, Particle.DUST_COLOR_TRANSITION, x + sin(t * PI) * 0.28,
                0.08 + node * 0.35, z, 4, data = thorn)
            if (node == 1) addGesture(out, Particle.HAPPY_VILLAGER, x, 0.2, z, 2)
        } }
        StaffGestureForm.NOVA_ROOTS -> repeat(8) { root ->
            val angle = root * PI / 4.0
            val reachAt = 0.4 + progress * 1.65
            addGesture(out, Particle.DUST_COLOR_TRANSITION, cos(angle) * reachAt, -0.55,
                sin(angle) * reachAt, 4, data = thorn)
            addGesture(out, Particle.HAPPY_VILLAGER, cos(angle) * reachAt, -0.45,
                sin(angle) * reachAt, 2)
        }
        StaffGestureForm.NECROMANCER_BONE -> repeat(7) { fragment -> repeat(2) { node ->
            val t = (node + 1) / 2.0
            val x = (fragment - 3) * (0.11 + t * 0.12)
            val z = 1.5 + (front - 1.5).coerceAtLeast(0.0) * t
            addGesture(out, Particle.BLOCK, x, 0.12 + sin(t * PI) * 0.18, z, 2,
                data = Material.BONE_BLOCK.createBlockData())
            addGesture(out, Particle.SOUL, x, 0.1, z - 0.12, 2)
        } }
        StaffGestureForm.NECROMANCER_SOUL -> repeat(8) { shard ->
            val angle = shard * PI / 4.0
            val radius = 1.1 + progress * 0.3
            addGesture(out, Particle.BLOCK, cos(angle) * radius, 0.2 + progress * 1.4,
                sin(angle) * radius, 2, data = Material.BONE_BLOCK.createBlockData())
            addGesture(out, Particle.SOUL, cos(angle) * radius * 0.7, 0.25 + progress * 1.1,
                sin(angle) * radius * 0.7, 3)
        }
    }
    return out.take(MAX_GESTURE_SAMPLES)
}

/** Owns every LMB gesture independently of the cooldown clock. */
internal class StaffSpellGestures(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
) : AutoCloseable {
    private data class Basis(val right: Vector, val up: Vector, val forward: Vector) {
        fun transform(local: Vector) = right.clone().multiply(local.x)
            .add(up.clone().multiply(local.y)).add(forward.clone().multiply(local.z))
        fun transformSpread(local: Vector) = Vector(
            sqrt((right.x * local.x) * (right.x * local.x) + (up.x * local.y) * (up.x * local.y) + (forward.x * local.z) * (forward.x * local.z)),
            sqrt((right.y * local.x) * (right.y * local.x) + (up.y * local.y) * (up.y * local.y) + (forward.y * local.z) * (forward.y * local.z)),
            sqrt((right.z * local.x) * (right.z * local.x) + (up.z * local.y) * (up.z * local.y) + (forward.z * local.z) * (forward.z * local.z)),
        )
        fun local(world: Vector) = Vector(world.dot(right), world.dot(up), world.dot(forward))
    }

    private data class Gesture(
        val casterId: UUID,
        val spell: StaffSpell,
        val input: StaffCastInput,
        val profile: StaffGestureProfile,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val anchor: Location,
        val basis: Basis,
        val reach: Double,
        val aimPoint: Location,
        val visited: MutableSet<UUID> = mutableSetOf(),
        var age: Int = 0,
        var contactCuePlayed: Boolean = false,
    )

    private val tasks = LifecycleTaskScope()
    private val gestures = mutableListOf<Gesture>()
    private var started = false
    private var closed = false

    fun start() {
        if (started || closed) return
        started = true
        tasks.runTimer(STAFF_GESTURE_STEP_TICKS.toLong(), STAFF_GESTURE_STEP_TICKS.toLong(), ::tick)
    }

    fun cast(player: Player, spell: StaffSpell, cast: StaffSpellCast,
        tuning: StaffSpellTuning, input: StaffCastInput) {
        if (closed || !started || !input.quick || !ready(player) ||
            cast.casterId != player.uniqueId || !damage.isCurrent(player, cast)) return
        val profile = staffGestureProfile(spell, input)
        val eye = player.eyeLocation
        val configuredRange = settings.range.takeIf(Double::isFinite) ?: profile.reach
        val aimRange = minOf(configuredRange, profile.reach, MAX_GESTURE_REACH)
        if (aimRange < GESTURE_CAMERA_CLEARANCE) return
        val selected = if (profile.anchor == StaffGestureAnchor.POINT || profile.anchor == StaffGestureAnchor.GROUND_FORWARD)
            targeting.aimed(player, aimRange, settings.aimDegrees, cast).firstOrNull() else null
        val rawPoint = selected?.let { if (profile.anchor == StaffGestureAnchor.GROUND_FORWARD) it.location.clone() else center(it) }
            ?: targeting.rayEnd(eye, eye.direction, aimRange)
        val point = when {
            profile.anchor == StaffGestureAnchor.GROUND_FORWARD -> targeting.groundBelow(rawPoint.clone().add(0.0, 0.15, 0.0), 8.0) ?: rawPoint
            profile.form in setOf(StaffGestureForm.FAULT_COLUMN, StaffGestureForm.ICICLE_CAGE,
                StaffGestureForm.TIDE_GEYSER, StaffGestureForm.NECROMANCER_SOUL) ->
                targeting.groundBelow(rawPoint.clone().add(0.0, 0.45, 0.0), 8.0) ?: rawPoint
            else -> rawPoint
        }
        if (!visible(eye, point)) return

        val horizontal = point.toVector().subtract(eye.toVector()).apply { y = 0.0 }
        val horizontalDistance = horizontal.length()
        val direction = when {
            profile.anchor == StaffGestureAnchor.POINT && horizontalDistance > 0.001 -> horizontal.clone().normalize()
            profile.anchor == StaffGestureAnchor.GROUND_FORWARD && horizontalDistance > 0.001 -> horizontal.clone().normalize()
            else -> eye.direction.normalize()
        }
        val anchor: Location
        val forward: Vector
        val reach: Double
        when (profile.anchor) {
            StaffGestureAnchor.POINT -> {
                anchor = point.clone()
                forward = direction
                reach = aimRange
            }
            StaffGestureAnchor.GROUND_FORWARD -> {
                if (horizontalDistance < GESTURE_CAMERA_CLEARANCE) return
                anchor = eye.clone()
                forward = direction
                reach = horizontalDistance.coerceAtMost(aimRange)
            }
            StaffGestureAnchor.FORWARD -> {
                anchor = eye.clone()
                forward = eye.direction.normalize()
                val end = targeting.rayEnd(anchor, forward, aimRange)
                val visibleReach = anchor.distance(end)
                if (visibleReach < GESTURE_CAMERA_CLEARANCE) return
                reach = visibleReach
            }
        }
        val basis = basis(forward)
        val gesture = Gesture(player.uniqueId, spell, input, profile, cast, tuning,
            anchor.clone(), basis, reach, point.clone())
        while (gestures.count { it.casterId == player.uniqueId } >= MAX_GESTURES_PER_CASTER) {
            gestures.firstOrNull { it.casterId == player.uniqueId }?.let(gestures::remove) ?: break
        }
        while (gestures.size >= MAX_GESTURES) gestures.removeAt(0)
        gestures += gesture
        visuals.withOwner(player.uniqueId) {
            visuals.cue(eye, spell.gestureSound(), volume = 0.25f, pitch = 1.5f)
        }
    }

    fun cancel(casterId: UUID) {
        gestures.removeAll { it.casterId == casterId }
    }

    private fun tick() {
        gestures.toList().forEach { gesture ->
            if (gesture !in gestures) return@forEach
            val player = Bukkit.getPlayer(gesture.casterId)
            if (player == null || !ready(player) || player.world != gesture.anchor.world ||
                !damage.isCurrent(player, gesture.cast)) {
                gestures.remove(gesture)
                return@forEach
            }
            val previousAge = gesture.age
            gesture.age += STAFF_GESTURE_STEP_TICKS
            if (gesture.age >= gesture.profile.durationTicks) {
                gestures.remove(gesture)
                return@forEach
            }
            val frame = staffGestureFrame(gesture.spell, gesture.input, gesture.age, gesture.reach)
                .map { sample -> sample.copy(offset = gesture.basis.transform(sample.offset), spread = gesture.basis.transformSpread(sample.spread)) }
            if (frame.isNotEmpty()) visuals.withOwner(gesture.casterId) {
                visuals.particles(gesture.anchor, frame, clearance = GESTURE_CAMERA_CLEARANCE)
            }
            val pointCentered = gesture.profile.anchor == StaffGestureAnchor.POINT
            val candidates = targeting.nearby(player, gesture.anchor,
                (if (pointCentered) gesture.profile.radius else gesture.reach + gesture.profile.width) + 1.4,
                gesture.cast).sortedBy { center(it).distanceSquared(gesture.anchor) }
                .take(MAX_GESTURE_CANDIDATES)
            for (target in candidates) {
                if (gesture !in gestures || !ready(player) || !damage.isCurrent(player, gesture.cast)) break
                if (gesture.visited.size >= settings.maxAreaTargets.coerceAtLeast(1)) break
                if (target.uniqueId in gesture.visited || !damage.eligible(player, target, gesture.cast)) continue
                val targetPoint = center(target)
                val local = gesture.basis.local(targetPoint.toVector().subtract(gesture.anchor.toVector()))
                if (!staffGestureContact(gesture.profile, gesture.age, previousAge, local, gesture.reach)) continue
                if (!visible(player.eyeLocation, targetPoint) || !visible(gesture.anchor, targetPoint)) continue
                gesture.visited += target.uniqueId
                if (!gesture.contactCuePlayed) {
                    gesture.contactCuePlayed = true
                    visuals.withOwner(gesture.casterId) {
                        visuals.cue(targetPoint, gesture.spell.gestureSound(),
                            volume = 0.5f, pitch = if (gesture.input.secondary) 0.82f else 1.08f)
                    }
                }
                val hit = damage.hit(player, target, gesture.cast, gesture.tuning.power, gesture.tuning.vanillaDamage)
                if (gesture !in gestures || !damage.isCurrent(player, gesture.cast)) break
                if (!hit) continue
                if (damage.eligible(player, target, gesture.cast)) applyControl(gesture, target)
            }
        }
    }

    private fun applyControl(gesture: Gesture, target: LivingEntity) {
        when (gesture.profile.control) {
            StaffGestureControl.NONE -> Unit
            StaffGestureControl.SLOW -> target.addPotionEffect(
                PotionEffect(PotionEffectType.SLOWNESS, settings.frostSlowTicks.coerceIn(1, 200), 1))
            StaffGestureControl.PULL -> if (target !is Player) {
                val pull = gesture.aimPoint.toVector().subtract(center(target).toVector())
                if (pull.lengthSquared() > 0.04) target.velocity = pull.normalize().multiply(0.42)
                    .apply { y = y.coerceIn(-0.12, 0.18) }
            }
            StaffGestureControl.UPDRAFT -> if (target !is Player) {
                target.velocity = target.velocity.clone().add(Vector(0.0, 0.42, 0.0))
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        gestures.clear()
        tasks.close()
    }

    private fun ready(player: Player) = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR && player.gameMode != GameMode.CREATIVE

    private fun basis(direction: Vector): Basis {
        val forward = direction.clone().normalize()
        val right = Vector(0.0, 1.0, 0.0).crossProduct(forward)
        if (right.lengthSquared() < 0.0001) right.setX(1.0).setY(0.0).setZ(0.0) else right.normalize()
        val up = forward.clone().crossProduct(right).normalize()
        return Basis(right, up, forward)
    }

    private fun StaffSpell.gestureSound() = when (this) {
        StaffSpell.CHAIN -> Sound.ENTITY_LIGHTNING_BOLT_IMPACT
        StaffSpell.MARK, StaffSpell.LUNAR -> Sound.BLOCK_AMETHYST_BLOCK_CHIME
        StaffSpell.FROST, StaffSpell.ICICLE -> Sound.BLOCK_SNOW_BREAK
        StaffSpell.LANCE, StaffSpell.GALE -> Sound.ENTITY_PLAYER_ATTACK_SWEEP
        StaffSpell.EMBER -> Sound.ITEM_FIRECHARGE_USE
        StaffSpell.FAULT -> Sound.BLOCK_STONE_BREAK
        StaffSpell.TIDE -> Sound.ITEM_TRIDENT_THROW
        StaffSpell.REQUIEM, StaffSpell.NECROMANCER -> Sound.ENTITY_PHANTOM_FLAP
        StaffSpell.PRISM -> Sound.BLOCK_BEACON_POWER_SELECT
        StaffSpell.NOVA -> Sound.BLOCK_ROOTED_DIRT_BREAK
    }

    private companion object {
        const val GESTURE_CAMERA_CLEARANCE = 1.35
        const val MAX_GESTURE_REACH = 12.0
        const val MAX_GESTURES_PER_CASTER = 2
        const val MAX_GESTURES = 32
        const val MAX_GESTURE_CANDIDATES = 32
    }
}

private fun addGesture(out: MutableList<StaffParticleSample>, particle: Particle,
    x: Double, y: Double, z: Double, count: Int,
    spread: Vector = Vector(0.025, 0.025, 0.025), data: Any? = null) {
    if (x.isFinite() && y.isFinite() && z.isFinite()) out += StaffParticleSample(
        particle = particle, offset = Vector(x, y, z), count = count.coerceIn(1, 12),
        spread = spread.clone(), speed = 0.0, data = data)
}

private fun validGestureVector(vector: Vector) = vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()

private fun gestureSmooth(value: Double): Double {
    val t = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    return t * t * (3.0 - 2.0 * t)
}
