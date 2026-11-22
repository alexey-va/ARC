package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal object AuroraImperialFleet : AuroraStoryParts {
    private const val TURN = 2.0 * PI
    private const val DISH_LATITUDE = 0.535
    private const val DISH_RADIUS = 0.413
    private const val APERTURE_DEPTH = 0.992
    private val dishMount = Quaternionf().rotationX((-DISH_LATITUDE).toFloat())

    private fun mounted(piece: AuroraMiniaturePart, turn: Quaternionf,
        dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0): AuroraMiniaturePart {
        val p = piece.center
        val point = turn.transform(Vector3f(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
        return AuroraMiniaturePart(piece.material, AuroraOffset(dx + point.x, dy + point.y, dz + point.z),
            Quaternionf(turn).mul(piece.rotation), Vector3f(piece.scale))
    }

    private fun segment(material: Material, a: AuroraOffset, b: AuroraOffset, height: Double, width: Double): AuroraMiniaturePart {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val xy = hypot(dx, dy)
        return storyPart(material, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            hypot(xy, dz), height, width, atan2(dy, dx), atan2(-dz, xy))
    }

    /** Stretch a local-X beam from its fixed starting face, including at zero length. */
    private fun extend(beam: AuroraMiniaturePart, length: Double, thickness: Double): AuroraMiniaturePart {
        val shift = beam.rotation.transform(Vector3f((beam.scale.x * (length - 1) / 2).toFloat(), 0f, 0f))
        val p = beam.center
        return beam.copy(center = AuroraOffset(p.x + shift.x, p.y + shift.y, p.z + shift.z),
            scale = Vector3f(beam.scale).mul(length.toFloat(), thickness.toFloat(), thickness.toFloat()))
    }

    // Four intersecting bars inscribe a solid octagon with a face on each of the eight meridians.
    private val station = buildList {
        val heights = listOf(0.050, 0.244, 0.459, 0.658, 0.826, 0.953, 1.019)
        val facets = listOf(4, 4, 4, 3, 2, 1)
        for (side in listOf(-1.0, 1.0)) {
            for ((band, interval) in heights.zipWithNext().withIndex()) {
                val (low, high) = interval
                val y = (low + high) / 2
                val r = sqrt(1.036 - y * y)
                val bars = if (side < 0 && band == 4) 1 else facets[band]
                for (i in 0 until bars) {
                    val rotation = if (bars == 2) i * PI / 4 else i * PI / bars
                    val apothem = r * if (bars > 2) cos(PI / (bars * 2)) else 0.73
                    val width = if (bars > 2) 2 * apothem * tan(PI / (bars * 2)) else 2 * apothem
                    // Cut into the forward bar above the trench, so the dish can sink into the hull.
                    val notch = if (i == 0 && side > 0 && bars > 2)
                        (0.315 - 1.14 * (y - 0.43) * (y - 0.43)).coerceAtLeast(0.0) else 0.0
                    val shell = when {
                        band == 0 && i == 2 || band == 3 && i == 1 -> Material.POLISHED_ANDESITE
                        (band + i) % 3 == 0 -> Material.ANDESITE
                        else -> Material.SMOOTH_STONE
                    }
                    add(storyPart(shell, 0.0, side * y, -notch / 2, width, high - low + 0.0015 * i,
                        2 * apothem - notch, yaw = rotation))
                }
            }
        }
        repeat(4) { i ->
            add(storyPart(Material.POLISHED_DEEPSLATE, 0.0, 0.0, 0.0, 1.811, 0.106 - 0.002 * i,
                1.811 * tan(PI / 8), yaw = i * PI / 4))
        }
        // The radial panels rise from a sunken centre to the rim. Their shared outer edge is the beam aperture.
        for (i in 0 until 8) {
            val angle = i * TURN / 8
            val spoke = Quaternionf(dishMount).rotateZ(angle.toFloat())
            val dish = storyPart(Material.POLISHED_TUFF,
                0.0, DISH_RADIUS / 2, 0.832, 0.353, hypot(DISH_RADIUS, 0.206), 0.058, pitch = atan2(0.206, DISH_RADIUS))
            add(mounted(dish, spoke))
            add(mounted(storyPart(Material.SMOOTH_STONE, 0.0, DISH_RADIUS, 0.944,
                0.363, 0.051, 0.097), spoke))
        }
        add(mounted(storyPart(Material.BLACK_CONCRETE, 0.0, 0.0, 0.772, 0.144, 0.144, 0.059, PI / 4), dishMount))
    }

    private val focus = AuroraOffset(0.37, -0.035, 1.49)
    private val tributaries = List(6) { i ->
        val angle = i * TURN / 6
        val face = round(angle / (PI / 4)) * PI / 4
        val radius = DISH_RADIUS / cos(angle - face)
        val emitter = AuroraOffset(-radius * sin(angle), radius * cos(angle), APERTURE_DEPTH)
        mounted(segment(Material.LIME_CONCRETE_POWDER, emitter, focus, 0.031, 0.031), dishMount)
    }
    // Continue the line from the middle of the emitting ring through the point where its six rays meet.
    private fun dischargeBeam(material: Material, length: Double, thickness: Double): AuroraMiniaturePart {
        val dz = focus.z - APERTURE_DEPTH
        val distance = length / hypot(hypot(focus.x, focus.y), dz)
        val tip = AuroraOffset(focus.x + distance * focus.x, focus.y + distance * focus.y, focus.z + distance * dz)
        return mounted(segment(material, focus, tip, thickness, thickness), dishMount)
    }

    private val discharge = dischargeBeam(Material.LIME_STAINED_GLASS, 7.31, 0.163)
    private val dischargeCore = dischargeBeam(Material.VERDANT_FROGLIGHT, 7.50, 0.057)

    private fun stationPulse(beam: AuroraMiniaturePart, t: Double, delay: Double): AuroraMiniaturePart {
        // Once emission stops, its trailing edge travels away from the dish. Reset both ends in darkness.
        val reset = 1 - storyBeat(t, 0.90, 0.982)
        val head = storyBeat(t, 0.435 + delay, 0.48 + delay) * reset
        val tail = storyBeat(t, 0.804 + delay, 0.865 + delay) * reset
        val span = head - tail
        val shift = beam.rotation.transform(Vector3f((beam.scale.x * (head + tail - 1) / 2).toFloat(), 0f, 0f))
        val p = beam.center
        val width = minOf(1.0, 4 * span).toFloat()
        return beam.copy(center = AuroraOffset(p.x + shift.x, p.y + shift.y, p.z + shift.z),
            scale = Vector3f(beam.scale).mul(span.toFloat(), width, width))
    }

    private val destroyer = buildList {
        val stern = -1.16
        val bow = 1.46
        val width = 0.87
        val slices = 8
        val step = (bow - stern) / slices
        for (i in 0 until slices) {
            val x = stern + (i + 0.5) * step
            val span = width * (1.0 - (i + 0.77) / slices)
            for (side in listOf(-1.0, 1.0)) for (deck in listOf(-1.0, 1.0)) {
                val rise = span * if (deck > 0) 0.25 else 0.31
                val material = if (deck > 0) Material.SMOOTH_STONE else Material.POLISHED_ANDESITE
                add(storyPart(material, x, deck * rise / 2, side * span / 2,
                    step + 0.014, 0.043 + 0.036 * span, hypot(span, rise) + 0.026,
                    pitch = side * deck * atan2(rise, span)))
            }
        }
        // The swept armour rails join the forward ends of the transverse plates all the way to the needle bow.
        for (side in listOf(-1.0, 1.0)) {
            add(segment(Material.POLISHED_DEEPSLATE, AuroraOffset(stern, -0.013, side * (width - 0.042)),
                AuroraOffset(bow + 0.021, -0.002, 0.0), 0.085, 0.117))
            add(segment(Material.SMOOTH_STONE, AuroraOffset(stern, 0.040, side * (width - 0.054)),
                AuroraOffset(bow - 0.015, 0.025, 0.0), 0.031, 0.148))
        }
        add(storyPart(Material.POLISHED_ANDESITE, stern, -0.004, 0.0, 0.121, 0.180, 1.58))
        add(storyPart(Material.STONE, stern, -0.020, 0.0, 0.131, 0.308, 0.95))
        // The ventral docking bay sits between the raised sides of the keel and faces the ground during the bank.
        add(storyPart(Material.BLACK_CONCRETE, -0.181, -0.205, 0.0, 0.59, 0.043, 0.212, 0.102))
        for (side in listOf(-1.0, 1.0)) {
            add(storyPart(Material.LIGHT_GRAY_CONCRETE, -0.185, -0.207, side * 0.127, 0.655, 0.079, 0.061, 0.102))
        }
        add(storyPart(Material.POLISHED_ANDESITE, -0.54, 0.20, 0.0, 0.96, 0.17, 0.63))
        add(storyPart(Material.SMOOTH_STONE, -0.60, 0.328, 0.0, 0.76, 0.13, 0.51))
        add(storyPart(Material.POLISHED_ANDESITE, -0.66, 0.446, 0.0, 0.45, 0.13, 0.41))
        add(storyPart(Material.POLISHED_ANDESITE, -0.74, 0.567, 0.0, 0.144, 0.21, 0.29, 0.13))
        add(storyPart(Material.POLISHED_DEEPSLATE, -0.765, 0.674, 0.0, 0.221, 0.087, 0.655))
        add(storyPart(Material.SMOOTH_STONE, -0.765, 0.722, 0.0, 0.232, 0.047, 0.682))
        for (side in listOf(-1.0, 1.0)) {
            add(storyPart(Material.POLISHED_ANDESITE, -0.771, 0.758, side * 0.241, 0.153, 0.057, 0.153, yaw = PI / 4))
            add(storyPart(Material.SMOOTH_STONE, -0.771, 0.807, side * 0.241, 0.137, 0.125, 0.137, 0.42, PI / 4, 0.49))
        }
        add(storyPart(Material.POLISHED_ANDESITE, -0.79, 0.822, 0.0, 0.041, 0.170, 0.040))
        for (engine in -1..1) {
            val diameter = if (engine == 0) 0.335 else 0.279
            add(storyPart(Material.LODESTONE, -1.211, -0.025, engine * 0.478,
                diameter, 0.199, diameter, PI / 2, PI / 4))
            add(storyPart(Material.BLUE_ICE, -1.315, -0.025, engine * 0.478,
                0.054, diameter * 0.79, diameter * 0.79, pitch = PI / 4))
        }
    }

    private val engineCores = List(3) { index ->
        val z = (index - 1) * 0.478
        val width = if (index == 1) 0.147 else 0.122
        segment(Material.SEA_LANTERN, AuroraOffset(-1.334, -0.025, z), AuroraOffset(-1.621, -0.025, z), width, width)
    }
    private val exhaust = List(3) { index ->
        val z = (index - 1) * 0.478
        val width = if (index == 1) 0.177 else 0.149
        segment(Material.LIGHT_BLUE_STAINED_GLASS, AuroraOffset(-1.34, -0.025, z), AuroraOffset(-1.929, -0.025, z), width, width)
    }

    override fun partCount(shape: AuroraShape) = when (shape) {
        AuroraShape.DEATH_STAR -> station.size + tributaries.size + 2
        AuroraShape.IMPERIAL_STAR_DESTROYER -> destroyer.size + engineCores.size + exhaust.size
        else -> error("Unknown Imperial scene: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double) = when (shape) {
        AuroraShape.DEATH_STAR -> stationPart(index, t)
        AuroraShape.IMPERIAL_STAR_DESTROYER -> destroyerPart(index, t)
        else -> error("Unknown Imperial scene: $shape")
    }

    private fun stationPart(index: Int, t: Double): AuroraMiniaturePart {
        val aim = storyBeat(t, 0.025, 0.34) * (1 - storyBeat(t, 0.885, 0.995))
        val recoil = storyBeat(t, 0.435, 0.476) * (1 - storyBeat(t, 0.50, 0.59))
        val angle = 0.32 + 0.75 * aim + 0.035 * sin(TURN * t) * (1 - aim)
        val attitude = Quaternionf().rotationZ(-0.035f).rotateX(0.06f).rotateY(angle.toFloat())
        val beam = index - station.size
        val piece = when {
            beam < 0 -> station[index]
            beam < tributaries.size -> {
                val delay = (beam % 3) * 0.022
                val length = storyBeat(t, 0.14 + delay, 0.335 + delay) * (1 - storyBeat(t, 0.801, 0.873))
                extend(tributaries[beam], length, sqrt(length))
            }
            beam == tributaries.size -> stationPulse(discharge, t, 0.005)
            else -> stationPulse(dischargeCore, t, 0.0)
        }
        return mounted(piece, attitude, -1.30 - 0.75 * aim - 0.061 * recoil, -0.21 - 0.16 * aim - 0.029 * recoil,
            -0.85 - 0.21 * aim)
    }

    private fun destroyerPart(index: Int, t: Double): AuroraMiniaturePart {
        val bank = 0.70 * cos(TURN * (t - 0.10))
        val attitude = Quaternionf().rotationZ((0.13 + 0.058 * sin(TURN * t)).toFloat())
            .rotateY((0.67 + 0.17 * sin(TURN * (t - 0.18))).toFloat()).rotateX(bank.toFloat())
        val thrust = storyBeat(t, 0.486, 0.589) * (1 - storyBeat(t, 0.67, 0.828))
        val jet = index - destroyer.size
        val piece = when {
            jet < 0 -> destroyer[index]
            jet < engineCores.size -> extend(engineCores[jet], 0.10 + 0.90 * thrust, 0.95 + 0.05 * thrust)
            else -> {
                val length = 0.034 + thrust * (0.86 + 0.10 * sin(3 * TURN * t))
                extend(exhaust[jet - engineCores.size], length, 0.75 + 0.25 * thrust)
            }
        }
        return mounted(piece, attitude, 0.18 * sin(TURN * t) + 0.20 * thrust, -0.092 + 0.078 * cos(TURN * t))
    }
}
