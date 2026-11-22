package ru.arc.aurora

import org.bukkit.Material
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object AuroraStormHerd : AuroraStoryParts {
    // Thirteen parts for each distant horse, fifteen for the lead, four clouds and a four-piece fork.
    const val PART_COUNT = 49
    private const val SKY = 41
    private const val TURN = 2.0 * PI

    private data class Lane(val start: Double, val travel: Double, val y: Double, val z: Double,
        val size: Double, val phase: Double, val takeoff: Double, val landing: Double, val height: Double)

    private val lanes = listOf(
        Lane(-0.99, 0.85, 0.015, -0.20, 0.67, 0.39, 0.445, 0.662, 0.25),
        Lane(-0.54, 1.01, -0.115, 0.0, 0.82, 0.04, 0.375, 0.666, 0.33),
        Lane(-0.06, 1.13, -0.28, 0.17, 1.04, 0.61, 0.305, 0.626, 0.42),
    )

    override fun partCount(shape: AuroraShape) = PART_COUNT

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        require(index in 0 until PART_COUNT)
        if (index >= SKY) return sky(index - SKY, t)
        val laneIndex = when (index) { in 0..12 -> 0; in 13..25 -> 1; else -> 2 }
        return horse(lanes[laneIndex], index - 13 * laneIndex, laneIndex == 2, t)
    }

    private fun pulse(t: Double, start: Double, bright: Double, fade: Double, end: Double) =
        storyBeat(t, start, bright) * (1.0 - storyBeat(t, fade, end))

    private fun horse(lane: Lane, slot: Int, lead: Boolean, t: Double): AuroraMiniaturePart {
        val visibility = maxOf(pulse(t, 0.075, 0.135, 0.225, 0.315), pulse(t, 0.365, 0.423, 0.705, 0.865))
        val f = ((t - lane.takeoff) / (lane.landing - lane.takeoff)).coerceIn(0.0, 1.0)
        val airborne = storyBeat(f, 0.0, 0.18) * (1.0 - storyBeat(f, 0.81, 1.0))
        // Four complete strides fit the 33 sampled keyframes without reversing the knee between them.
        val beat = TURN * (4.0 * t + lane.phase)
        val bank = 0.078 * cos(beat) * (1.0 - airborne) + 0.18 * cos(PI * f) * airborne
        val cb = cos(bank)
        val sb = sin(bank)
        val size = lane.size * visibility
        val rootX = lane.start + t * lane.travel
        val rootY = lane.y + lane.height * sin(PI * f) + 0.026 * lane.size * sin(beat) * (1.0 - airborne)
        fun piece(material: Material, x: Double, y: Double, z: Double, sx: Double, sy: Double, sz: Double,
            roll: Double = 0.0, yaw: Double = 0.0, pitch: Double = 0.0): AuroraMiniaturePart {
            // The near side is slightly aft in projection, keeping the far legs and the second ear visible.
            val across = x - 0.28 * z
            return storyPart(material, rootX + size * (across * cb - y * sb), rootY + size * (across * sb + y * cb),
                lane.z + size * (z + 0.16 * x), size * sx, size * sy, size * sz, bank + roll, yaw, pitch)
        }
        fun bone(material: Material, ax: Double, ay: Double, bx: Double, by: Double, z: Double,
            width: Double, depth: Double) =
            piece(material, (ax + bx) * 0.5, (ay + by) * 0.5, z,
                width, hypot(bx - ax, by - ay) + 0.013, depth, atan2(ax - bx, by - ay))

        // The poll moves forwards in the jump, independently of the muzzle's smaller downward nod.
        val reach = 0.285 + 0.10 * airborne + 0.016 * sin(beat) * (1.0 - airborne)
        val poll = 0.345 - 0.064 * airborne + 0.016 * cos(beat) * (1.0 - airborne)
        val nod = -0.22 - 0.10 * airborne + 0.06 * sin(beat)
        when (slot) {
            0 -> return piece(Material.QUARTZ_STAIRS, -0.02, 0.109, 0.0, 0.49, 0.126, 0.155)
            // The native iron-bars item already has three open ribs and transverse links on both sides.
            1 -> return piece(Material.IRON_BARS, 0.01, 0.007, 0.0, 0.37, 0.223, 1.23, -0.09, 0.12)
            2 -> return bone(Material.BONE_BLOCK, 0.158, 0.088, reach, poll, 0.0, 0.07, 0.085)
            // The half-block skull sits below its item origin. Undo ItemDisplay.NONE's Y turn to expose its sockets.
            3 -> return piece(Material.SKELETON_SKULL, reach + 0.041, poll + 0.050, 0.0,
                0.267, 0.219, 0.209, nod, PI + 0.28)
            4 -> return piece(Material.QUARTZ_STAIRS, reach + 0.128, poll - 0.034, 0.0,
                0.147, 0.075, 0.078, nod, 0.06)
            // The inverted fence has two separate posts above its rails, which are buried in the skull.
            5 -> return piece(Material.PALE_OAK_FENCE, reach - 0.006, poll + 0.066, 0.0,
                0.17, 0.126, 0.113, -0.12, 0.52, PI)
            6 -> return bone(Material.POLISHED_DIORITE, -0.226, 0.109, -0.402,
                0.035 + 0.045 * sin(beat - 0.6) + 0.040 * airborne, -0.007, 0.042, 0.044)
        }
        // Both near legs articulate on every horse. At the smaller scales the far legs each use one bone.
        val limb = slot - 7
        val leg = if (lead || limb < 4) limb / 2 else limb - 2
        val lower = limb % 2 == 1
        val fore = leg % 2 == 0
        val far = leg >= 2
        val gait = beat + (if (fore) 0.0 else 2.70) + (if (far) 0.91 else 0.0)
        val extending = storyBeat(f, 0.45, 0.98)
        val foreReach = if (fore) 1.17 - 0.57 * extending else -1.01 + 1.71 * extending
        val folded = if (fore) -1.04 + 1.83 * extending else -1.28 + 1.02 * extending
        val thigh = 0.86 * sin(gait) * (1.0 - airborne) + (foreReach - if (far) 0.23 else 0.0) * airborne
        val shin = (0.99 * sin(gait - 0.71) - 0.21) * (1.0 - airborne) +
            (folded + if (far) 0.26 else 0.0) * airborne
        val hx = if (fore) 0.159 else -0.171
        val hy = 0.037
        val kx = hx + 0.186 * sin(thigh)
        val ky = hy - 0.186 * cos(thigh)
        val fx = kx + 0.175 * sin(shin)
        val fy = ky - 0.175 * cos(shin)
        val z = if (far) -0.069 else 0.069
        val material = if (far) Material.POLISHED_DIORITE else Material.BONE_BLOCK
        return when {
            far && !lead -> bone(material, hx, hy, fx, fy, z, 0.037, 0.042)
            lower -> bone(material, kx, ky, fx, fy, z, 0.043, 0.048)
            else -> bone(material, hx, hy, kx, ky, z, 0.052, 0.055)
        }
    }

    private fun sky(slot: Int, t: Double): AuroraMiniaturePart {
        val scene = pulse(t, 0.0, 0.085, 0.89, 1.0)
        val drift = 0.023 * sin(TURN * t)
        when (slot) {
            // The wind-charge sprite has broken, silver-edged wisps. The darker powder forms the two storm cells.
            0 -> return storyPart(Material.WIND_CHARGE, -0.64 + drift, -0.59, -0.14,
                1.15 * scene, 0.24 * scene, 0.78 * scene, -0.035, -0.18)
            1 -> return storyPart(Material.WIND_CHARGE, 0.77 - drift, -0.59, -0.16,
                1.01 * scene, 0.21 * scene, 0.76 * scene, 0.018, PI - 0.23)
            2 -> return storyPart(Material.GUNPOWDER, -0.72 + drift, 0.72, -0.23,
                0.63 * scene, 0.19 * scene, 0.72 * scene, -0.025, PI + 0.11)
            3 -> return storyPart(Material.GUNPOWDER, 1.01 - drift, 0.79, -0.24,
                0.71 * scene, 0.20 * scene, 0.79 * scene, 0.035, 0.17)
        }
        // Move the dormant channel between anvils during the dark interval. The fork grows after the trunk.
        val branch = slot == 7
        val delay = if (branch) 0.013 else 0.0
        val flash = maxOf(pulse(t - delay, 0.084, 0.121, 0.157, 0.229),
            pulse(t - delay, 0.368, 0.409, 0.458, 0.548))
        val side = storyBeat(t, 0.246, 0.340)
        val root = -0.70 + 1.69 * side + drift * (1.0 - 2.0 * side)
        val bend = boltSegments[slot - 4]
        val dx = bend[2] - bend[0]
        val dy = bend[3] - bend[1]
        val thickness = (if (branch) 0.018 else 0.031) * flash
        return storyPart(Material.SEA_LANTERN, root + (bend[0] + bend[2]) * 0.5,
            (bend[1] + bend[3]) * 0.5 + 0.13 + 0.065 * side, -0.27,
            thickness, hypot(dx, dy) * flash, thickness, atan2(-dx, dy))
    }

    private val boltSegments = arrayOf(
        doubleArrayOf(0.0, 0.55, 0.105, 0.385),
        doubleArrayOf(0.105, 0.385, 0.017, 0.37),
        doubleArrayOf(0.017, 0.37, 0.204, 0.11),
        doubleArrayOf(0.105, 0.385, 0.245, 0.31),
    )
}
