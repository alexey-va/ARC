package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

internal object AuroraMechanicusSigil : AuroraStoryParts {
    private const val TEETH = 12
    private const val GEAR_PARTS = TEETH * 2
    private const val PITCH = 2.0 * PI / TEETH
    private const val EYE_X = -0.315
    private const val EYE_Y = 0.060
    private const val RIM_WIDTH = 0.175
    private val rimLength = 2 * (1 + RIM_WIDTH / 2) * tan(PITCH / 2)

    private fun bar(material: Material, x1: Double, y1: Double, x2: Double, y2: Double,
        width: Double, depth: Double): AuroraMiniaturePart =
        storyPart(material, (x1 + x2) / 2, (y1 + y2) / 2, 0.0,
            hypot(x2 - x1, y2 - y1) + width * 0.24, width, depth, atan2(y2 - y1, x2 - x1))

    private fun MutableList<AuroraMiniaturePart>.dome(side: Double, crown: Material, temple: Material) {
        add(storyPart(crown, side * 0.153, 0.339, 0.0, 0.306, 0.865, 0.258))
        add(storyPart(temple, side * 0.462, 0.223, 0.0, 0.358, 0.604, 0.224))
        add(bar(crown, side * 0.249, 0.629, side * 0.557, 0.431, 0.241, 0.249))
    }

    private val bone = buildList {
        dome(1.0, Material.BONE_BLOCK, Material.BONE_BLOCK)
        add(bar(Material.BONE_BLOCK, 0.611, 0.056, 0.517, -0.213, 0.157, 0.250))
        add(bar(Material.BONE_BLOCK, 0.518, -0.135, 0.279, -0.260, 0.153, 0.241))
        add(storyPart(Material.BONE_BLOCK, 0.181, -0.204, 0.0, 0.362, 0.314, 0.255))
        add(storyPart(Material.SMOOTH_SANDSTONE, 0.199, -0.337, 0.0, 0.398, 0.126, 0.226))
        add(storyPart(Material.BLACK_CONCRETE, 0.333, 0.053, 0.0, 0.397, 0.208, 0.296, 0.21))
        add(storyPart(Material.BLACK_CONCRETE, 0.333, 0.053, 0.0, 0.307, 0.274, 0.306, 0.21))
        add(storyPart(Material.BLACK_CONCRETE, 0.059, -0.150, 0.0, 0.105, 0.229, 0.301, 0.29))
        for (i in 0 until 4) {
            val length = 0.224 - 0.009 * i * i
            add(storyPart(Material.SMOOTH_QUARTZ, 0.049 + 0.104 * i, -0.371 - length / 2 + 0.005 * i,
                0.0, 0.088 - 0.004 * i, length, 0.248 - 0.008 * i, -0.025 - 0.032 * i))
        }
    }

    private val machine = buildList {
        dome(-1.0, Material.GRAY_CONCRETE, Material.POLISHED_DEEPSLATE)
        add(bar(Material.POLISHED_ANDESITE, -0.568, 0.389, -0.304, 0.588, 0.063, 0.272))
        add(storyPart(Material.POLISHED_BASALT, -0.585, 0.012, 0.0, 0.185, 0.257, 0.293))
        add(bar(Material.POLISHED_DEEPSLATE, -0.495, -0.112, -0.273, -0.249, 0.179, 0.265))
        add(storyPart(Material.POLISHED_BLACKSTONE, -0.192, -0.277, 0.0, 0.384, 0.493, 0.258))
        for (i in 0 until 3) {
            add(storyPart(Material.POLISHED_ANDESITE, -0.052 - 0.115 * i, -0.427 + 0.026 * i,
                0.0, 0.059, 0.259 - 0.013 * i, 0.286, -0.04 - 0.047 * i))
        }
        // The return hoses run outside the jaw, with open sky between their bends.
        add(bar(Material.EXPOSED_COPPER, -0.608, -0.070, -0.705, -0.330, 0.086, 0.172))
        add(bar(Material.EXPOSED_COPPER, -0.705, -0.330, -0.568, -0.594, 0.086, 0.166))
        add(bar(Material.EXPOSED_COPPER, -0.568, -0.594, -0.297, -0.720, 0.086, 0.172))
        add(bar(Material.EXPOSED_COPPER, -0.297, -0.720, -0.142, -0.493, 0.086, 0.166))
        add(bar(Material.POLISHED_ANDESITE, -0.509, -0.129, -0.539, -0.359, 0.065, 0.203))
        add(bar(Material.POLISHED_ANDESITE, -0.539, -0.359, -0.378, -0.523, 0.065, 0.197))
        add(bar(Material.POLISHED_ANDESITE, -0.378, -0.523, -0.285, -0.422, 0.065, 0.203))

        // The native record sprites have round machined edges. Mount one bezel on each face.
        for (side in listOf(-1.0, 1.0)) {
            add(storyPart(Material.MUSIC_DISC_TEARS, EYE_X, EYE_Y, side * 0.166,
                0.505, 0.736, 0.88))
            add(storyPart(Material.MUSIC_DISC_STAL, EYE_X, EYE_Y, side * 0.188,
                0.439, 0.639, 0.88))
        }
    }

    val PART_COUNT = GEAR_PARTS + bone.size + machine.size + 3
    override fun partCount(shape: AuroraShape) = PART_COUNT

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart {
        val size = storyBeat(t, 0.0, 0.074) * (1 - storyBeat(t, 0.924, 1.0))
        if (index < GEAR_PARTS) return cog(index, t, size)

        val slot = index - GEAR_PARTS
        val boneSide = slot < bone.size
        val local = when {
            boneSide -> bone[slot]
            slot < bone.size + machine.size -> machine[slot - bone.size]
            else -> lens(slot - bone.size - machine.size, t)
        }
        val closed = storyBeat(t, 0.032, 0.225) * (1 - storyBeat(t, 0.902, 0.990))
        val split = (if (boneSide) 0.205 else -0.205) * (1 - closed) * size
        val faceSize = size * (0.77 + 0.23 * closed)
        val p = local.center
        return AuroraMiniaturePart(local.material, AuroraOffset(p.x * faceSize + split, p.y * faceSize, p.z * faceSize),
            Quaternionf(local.rotation), Vector3f(local.scale).mul(faceSize.toFloat()))
    }

    private fun cog(index: Int, t: Double, size: Double): AuroraMiniaturePart {
        val sector = index / 2
        val bright = sector < TEETH / 2
        val turn = PITCH * (-1.35 + 1.35 * storyBeat(t, 0.018, 0.287)
            + storyBeat(t, 0.455, 0.641) + storyBeat(t, 0.698, 0.878))
        val angle = PI / 2 + (sector + 0.5) * PITCH + turn
        val tooth = index % 2 == 1
        val radius = (if (tooth) 1.170 else 1.0) * size
        val material = if (bright) Material.IRON_BLOCK else Material.POLISHED_DEEPSLATE
        // Adjacent tangents overlap at the roots. Recess every other plate to keep the faces distinct.
        val depth = if (tooth) 0.214 else if (sector % 2 == 0) 0.177 else 0.152
        return storyPart(material, radius * cos(angle), radius * sin(angle), 0.0,
            (if (tooth) 0.254 else rimLength) * size, (if (tooth) 0.307 else RIM_WIDTH) * size,
            depth * size, angle + PI / 2)
    }

    private fun lens(index: Int, t: Double): AuroraMiniaturePart {
        val ignition = storyBeat(t, 0.266, 0.330) * (1 - storyBeat(t, 0.834, 0.905))
        val flicker = storyBeat(t, 0.351, 0.377) - storyBeat(t, 0.390, 0.442)
        val light = ignition * (1 - 0.73 * flicker)
        // These two stepped glass pieces protrude through both bezels, even as the aperture narrows.
        val depth = if (light > 0.0) 1.0 else 0.0
        return when (index) {
            0 -> storyPart(Material.REDSTONE_BLOCK, EYE_X, EYE_Y, 0.0, 0.267 * light, 0.170 * light, 0.449 * depth)
            1 -> storyPart(Material.REDSTONE_BLOCK, EYE_X, EYE_Y, 0.0, 0.185 * light, 0.243 * light, 0.460 * depth)
            else -> storyPart(Material.SHROOMLIGHT, EYE_X - 0.040 * light, EYE_Y + 0.047 * light,
                0.0, 0.052 * light, 0.043 * light, 0.472 * depth)
        }
    }
}
