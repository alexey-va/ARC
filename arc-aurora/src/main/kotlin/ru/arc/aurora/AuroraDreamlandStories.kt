package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal object AuroraDreamlandStories : AuroraStoryParts {
    private const val PHARAOH_PARTS = 135
    private const val GOAT_PARTS = 138
    private const val TURN = 2 * PI

    override fun partCount(shape: AuroraShape) = when (shape) {
        AuroraShape.NYARLATHOTEP -> PHARAOH_PARTS
        AuroraShape.SHUB_NIGGURATH -> GOAT_PARTS
        else -> error("Not a Dreamland deity: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double) = when (shape) {
        AuroraShape.NYARLATHOTEP -> pharaoh(index, t)
        AuroraShape.SHUB_NIGGURATH -> goat(index, t)
        else -> error("Not a Dreamland deity: $shape")
    }

    private fun point(x: Double, y: Double, z: Double = 0.0) = AuroraOffset(x, y, z)

    private fun beam(material: Material, a: AuroraOffset, b: AuroraOffset, width: Double, depth: Double = width): AuroraMiniaturePart {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val flat = hypot(dx, dy)
        return storyPart(material, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            width, hypot(flat, dz) + width * 0.15, depth, atan2(-dx, dy), pitch = atan2(dz, flat))
    }

    private fun moved(p: AuroraOffset, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) =
        point(p.x + x, p.y + y, p.z + z)

    private fun posed(part: AuroraMiniaturePart, y: Double, roll: Double, yaw: Double, pitch: Double): AuroraMiniaturePart {
        val q = Quaternionf().rotateZ(roll.toFloat()).rotateY(yaw.toFloat()).rotateX(pitch.toFloat())
        val c = part.center
        val p = q.transform(Vector3f(c.x.toFloat(), c.y.toFloat(), c.z.toFloat()))
        return AuroraMiniaturePart(part.material, point(p.x.toDouble(), p.y + y, p.z.toDouble()), q.mul(part.rotation), part.scale)
    }

    private fun swell(t: Double, start: Double, peak: Double, release: Double, end: Double) =
        storyBeat(t, start, peak) * (1.0 - storyBeat(t, release, end))

    // Catmull–Rom sampling keeps the joints coincident while a horn or tentacle bends in depth.
    private fun curve(knots: Array<AuroraOffset>, u: Double): AuroraOffset {
        val at = u.coerceIn(0.0, 1.0) * knots.lastIndex
        val i = minOf(at.toInt(), knots.lastIndex - 1)
        val f = at - i
        val a = knots[maxOf(0, i - 1)]
        val b = knots[i]
        val c = knots[i + 1]
        val d = knots[minOf(i + 2, knots.lastIndex)]
        fun sample(a: Double, b: Double, c: Double, d: Double) =
            b + 0.5 * f * (c - a + f * (2 * a - 5 * b + 4 * c - d + f * (3 * (b - c) + d - a)))
        return point(sample(a.x, b.x, c.x, d.x), sample(a.y, b.y, c.y, d.y), sample(a.z, b.z, c.z, d.z))
    }

    private data class Eye(val x: Double, val y: Double, val z: Double, val width: Double, val slant: Double)

    private fun eye(e: Eye, layer: Int, opening: Double, gaze: Double, vertical: Double, golden: Boolean): AuroraMiniaturePart {
        val width = e.width * storyBeat(opening, 0.0, 0.20)
        val height = width * (0.035 + 0.54 * opening)
        if (layer == 0) {
            // A diamond seen at a tilt has pointed lids. Its near and far corners sit above and below the iris.
            val side = width / sqrt(2.0)
            val m = if (golden) Material.OCHRE_FROGLIGHT else Material.PEARLESCENT_FROGLIGHT
            val q = Quaternionf().rotateZ(e.slant.toFloat()).rotateX(acos((0.035 + 0.54 * opening).coerceIn(0.0, 1.0)).toFloat())
                .rotateZ((PI / 4).toFloat())
            return AuroraMiniaturePart(m, point(e.x, e.y, e.z), q, Vector3f(side.toFloat(), side.toFloat(), (width * .035).toFloat()))
        }
        val x = e.x + width * .19 * gaze
        val y = e.y + height * .13 * vertical
        val z = e.z + width * .27
        return if (layer == 1) {
            storyPart(if (golden) Material.ORANGE_CONCRETE else Material.REDSTONE_BLOCK,
                x, y, z, width * .35, height * .83, width * .055, e.slant)
        } else {
            storyPart(Material.BLACK_CONCRETE, x, y, z + width * .045, width * .095, height * .83, width * .035, e.slant)
        }
    }

    private val lashKnots = arrayOf(
        point(-.13, .40, .04), point(-.28, .64, .07), point(-.39, .93, .11), point(-.31, 1.19, .08),
        point(-.07, 1.40, .015), point(.26, 1.46, -.07), point(.62, 1.41, -.10),
        point(.89, 1.22, -.045), point(.98, 1.00, .075), point(.90, .83, .19), point(.72, .81, .27),
    )

    private val chestEyes = arrayOf(
        Eye(-.045, .115, .325, .225, -.09), Eye(-.192, .232, .32, .130, -.31),
        Eye(.108, .245, .32, .145, .23), Eye(-.161, -.002, .325, .126, .28),
        Eye(.086, -.023, .33, .151, -.21), Eye(.167, .107, .322, .116, .32),
        Eye(-.029, -.117, .32, .107, -.08),
    )

    private fun lash(slot: Int, t: Double, grown: Double): AuroraMiniaturePart {
        val ridge = slot >= 20
        val i = if (ridge) slot - 20 else slot
        val n = if (ridge) 14 else 20
        val start = (i + if (ridge) .09 else 0.0) / n
        val end = (i + if (ridge) .91 else 1.0) / n
        val material = if (ridge) Material.RED_CONCRETE else if (i < 5 || i in 9..13) Material.NETHER_WART_BLOCK else Material.RED_NETHER_BRICKS
        val throwHead = swell(t, .49, .605, .67, .84)
        fun diameter(u: Double) = (.028 + .263 * (1.0 - u)) * (.13 + .87 * storyBeat(grown - u, 0.0, .042 + .11 * (1.0 - grown)))
        fun sample(u: Double): AuroraOffset {
            val p = curve(lashKnots, u)
            val bend = u * u
            val w = diameter(u)
            return moved(p, u * .072 * sin(TURN * t - 4 * u) + .46 * bend * throwHead - if (ridge) .13 * w else 0.0,
                .07 * u * sin(2 * TURN * t - 3 * u) - .62 * bend * throwHead, .24 * bend * throwHead + if (ridge) .49 * w else 0.0)
        }
        if (start >= grown) return storyPart(material, -.13, .40, .04, 0.0, 0.0, 0.0)
        val endVisible = minOf(end, grown)
        val u = (start + endVisible) / 2
        val width = diameter(u) * if (ridge) .24 else 1.0
        return beam(material, sample(start), sample(endVisible), width * storyBeat(endVisible - start, 0.0, .018),
            width * if (ridge) .60 else .88)
    }

    private fun pharaohCore(i: Int, opened: Double): AuroraMiniaturePart {
        val s = if (i % 2 == 0) -1.0 else 1.0
        return when (i) {
            0 -> beam(Material.POLISHED_BASALT, point(.15, -.39, -.005), point(-.13, .40, .025), .203, .20)
            1 -> storyPart(Material.RED_NETHER_BRICKS, -.02, .075, .098, .445, .49, .195, .13)
            2, 3 -> beam(Material.POLISHED_BLACKSTONE, point(-.063 + s * (.128 + opened * .170), .306, .187),
                point(.022 + s * (.076 + opened * .147), -.160, .205), .205 - .123 * opened, .067)
            4, 5 -> beam(Material.POLISHED_BASALT, point(-.065 + s * .087, .326, .012),
                point(-.073 + s * .296, .328 + s * .034, .035), .143, .184)
            6 -> storyPart(Material.POLISHED_BLACKSTONE, .13, -.322, .005, .288, .14, .213, .08)
            7 -> storyPart(Material.GILDED_BLACKSTONE, .124, -.505, .075, .134, .37, .158, -.19)
            8, 9 -> beam(Material.POLISHED_BLACKSTONE, point(.13 + s * .089, -.367, .03),
                point(.129 + s * .174, -.696 + s * .046, .057), .128, .153)
            10 -> beam(Material.STRIPPED_DARK_OAK_WOOD, point(.075, -.396, .144), point(.083, -.717, .15), .048, .043)
            else -> beam(Material.POLISHED_BASALT, point(-.13, .326, .01), point(-.16, .517, .043), .112)
        }
    }

    private fun headdress(i: Int, opened: Double): AuroraMiniaturePart {
        val s = if (i % 2 == 0) -1.0 else 1.0
        val remain = 1.0 - opened
        val faceX = -.155 + s * (.057 + opened * .177)
        val y = .611 - opened * .208
        val outer = -.155 + s * (.145 + opened * .113)
        return when (i) {
            0, 1 -> storyPart(Material.POLISHED_BLACKSTONE, faceX, y, .116, .113 * remain, .181 * remain, .107 * remain, s * (.03 + .64 * opened))
            2, 3 -> beam(Material.LAPIS_BLOCK, point(outer - s * .038, y + .149, .053),
                point(outer + s * (.054 + .042 * opened), y - .111, .08), .112 - .063 * opened, .117 - .037 * opened)
            6, 7 -> storyPart(Material.GOLD_BLOCK, faceX, y + .010, .177, .073 * remain, .026 * remain,
                .019 * remain, s * .21)
            4, 5, 8, 9 -> {
                val row = (i - 4) / 2
                val x = outer + s * (-.019 + .029 * row)
                storyPart(Material.GOLD_BLOCK, x, y + .09 - .078 * row, .122, .111 - .057 * opened,
                    .026, .029, s * (.16 + .10 * opened))
            }
            10 -> storyPart(Material.GOLD_BLOCK, -.155, .761 - .189 * opened, .095, .267 * remain, .054 * remain, .14 * remain)
            else -> storyPart(Material.LAPIS_BLOCK, -.149, y - .118, .171, .046 * remain, .119 * remain, .038 * remain, .05)
        }
    }

    private fun ribs(i: Int, open: Double): AuroraMiniaturePart {
        val s = if (i % 2 == 0) -1.0 else 1.0
        val row = i / 2
        if (row < 2) return beam(if (row == 0) Material.GOLD_BLOCK else Material.RAW_GOLD_BLOCK,
            point(-.08 + s * (.247 + open * .035), .350 - row * .039, .182),
            point(-.068 + s * (.020 + open * (.097 + .021 * row)), .275 - row * .063 + .023 * open, .237),
            if (row == 0) .040 else .025, .047)
        val j = row - 2
        val x = -.042 + .028 * j
        return beam(Material.GOLD_BLOCK, point(x + s * (.275 - .029 * j + open * .057), .232 - j * .1, .201),
            point(x + s * (.117 - .007 * j + open * .096), .122 - j * .10 + .027 * open, .245),
            .032 - .002 * j, .032)
    }

    private fun arm(i: Int, s: Double, t: Double): AuroraMiniaturePart {
        val delay = if (s < 0) .0 else .14
        val reach = swell(t, .32 + delay, .52 + delay, .66 + delay, .83 + delay)
        val drag = sin(TURN * t + s)
        val shoulder = point(-.073 + s * .29, .328 + s * .034, .052)
        val elbow = point(-.084 + s * (.465 + reach * .225), .085 - s * .079 + .105 * reach, .025 + .11 * reach)
        val wrist = point(-.061 + s * (.485 + reach * .48), -.237 - s * .08 + .182 * reach + .042 * drag, .095 + .19 * reach)
        val palm = moved(wrist, s * (.062 + .05 * reach), -.086 + reach * .05, .039)
        return when (i) {
            0 -> beam(Material.POLISHED_BLACKSTONE, shoulder, elbow, .128, .137)
            1 -> beam(Material.POLISHED_BASALT, elbow, wrist, .083, .089)
            2 -> beam(Material.GILDED_BLACKSTONE, moved(elbow, s * .039, -.016, .016), moved(wrist, s * .016, .033, .016), .038)
            3 -> beam(Material.POLISHED_BLACKSTONE, wrist, palm, .117, .080)
            else -> {
                val digit = (i - 4) / 3
                val joint = (i - 4) % 3
                val curl = .5 + .5 * sin(TURN * t - digit * .64 + s * 1.6)
                val root = moved(palm, s * (.022 - .016 * digit), .051 - digit * .032, .013)
                val knuckle = moved(root, s * (.063 + .014 * digit), .037 - .025 * digit, .019 + digit * .005)
                val hook = moved(knuckle, s * (.072 + .010 * (3 - digit) + .042 * reach), .052 - .048 * digit, .04)
                val tip = moved(hook, s * (.026 + .044 * (1 - curl)), -.058 - .031 * curl, .041)
                when (joint) {
                    0 -> beam(Material.POLISHED_BASALT, root, knuckle, .036)
                    1 -> beam(Material.POLISHED_BASALT, knuckle, hook, .031)
                    else -> beam(Material.POLISHED_ANDESITE, hook, tip, .023)
                }
            }
        }
    }

    private fun leg(i: Int, s: Double, t: Double): AuroraMiniaturePart {
        val pace = sin(TURN * t + s * .9)
        val hip = point(.15 + s * .092, -.358, -.024)
        val knee = point(.181 + s * .174 + .037 * pace, -.686 + .045 * s, .012 + .074 * s)
        val hock = point(.126 + s * .223 + .041 * pace, -.934 - .028 * s, -.079)
        val ankle = point(.195 + s * .235 - .022 * pace, -1.173 + .041 * s, .015)
        val foot = moved(ankle, s * .035, -.048, .058)
        return when (i) {
            0 -> beam(Material.POLISHED_BLACKSTONE, hip, knee, .143, .133)
            1 -> beam(Material.POLISHED_BASALT, knee, hock, .107, .085)
            2 -> beam(Material.POLISHED_BLACKSTONE, hock, ankle, .083, .074)
            3 -> beam(Material.POLISHED_BASALT, ankle, foot, .091)
            else -> beam(Material.POLISHED_ANDESITE, moved(foot, s * (i - 4) * .036), moved(foot, s * (.136 - (i - 4) * .093), -.051, .085), .039)
        }
    }

    private fun pharaoh(index: Int, t: Double): AuroraMiniaturePart {
        val change = swell(t, .17, .405, .79, .976)
        val open = swell(t, .255, .478, .78, .939)
        val local = when (index) {
            in 0..11 -> pharaohCore(index, open)
            in 12..23 -> headdress(index - 12, change)
            in 24..35 -> ribs(index - 24, open)
            in 36..69 -> lash(index - 36, t, swell(t, .184, .446, .77, .965))
            in 70..101 -> arm((index - 70) % 16, if (index < 86) -1.0 else 1.0, t)
            in 102..113 -> leg((index - 102) % 6, if (index < 108) -1.0 else 1.0, t)
            else -> {
                val id = (index - 114) / 3
                val e = chestEyes[id]
                val delay = id * .016
                val awakened = swell(t, .324 + delay, .408 + delay, .769 - delay * .27, .878 - delay * .24)
                val blink = swell(t, .58 + delay, .595 + delay, .602 + delay, .633 + delay)
                eye(e, (index - 114) % 3, awakened * (1.0 - .88 * blink),
                    sin(TURN * (2 * t - .018 * id)) * .86, sin(TURN * t + id * .73), false)
            }
        }
        return posed(local, .045 * sin(TURN * t), -.038 + .035 * sin(TURN * t - .4),
            .105 * sin(TURN * t) - .06 * open, .036 * cos(TURN * t))
    }

    private val hornKnots = arrayOf(
        point(.226, .326, -.103), point(.32, .55, -.116), point(.53, .772, -.16),
        point(.77, .94, -.146), point(1.015, .986, -.105), point(1.244, .888, -.03),
        point(1.35, .699, .048), point(1.305, .499, .118), point(1.145, .378, .146),
        point(.964, .382, .164), point(.859, .498, .185), point(.888, .632, .189), point(1.04, .71, .201),
    )
    private val tentacleKnots = arrayOf(
        arrayOf(point(-.37,-.16,-.06), point(-.57,-.35,-.08), point(-.60,-.55,.01), point(-.45,-.75,.08),
            point(-.45,-.95,.075), point(-.66,-1.13,.12)),
        arrayOf(point(.37,-.17,-.06), point(.55,-.38,-.075), point(.47,-.55,-.005), point(.25,-.75,-.035),
            point(.34,-.96,.055), point(.55,-1.17,.09)),
        arrayOf(point(-.14,-.20,.012), point(.106,-.395,.115), point(.333,-.571,.14), point(.246,-.751,.15),
            point(-.015,-.924,.093), point(-.083,-1.102,.10), point(.134,-1.31,.16)),
        arrayOf(point(.15,-.21,.07), point(-.116,-.426,.095), point(-.343,-.583,.175), point(-.29,-.768,.219),
            point(-.06,-.905,.174), point(.144,-.971,.216), point(.209,-1.095,.246), point(.151,-1.167,.26)),
    )
    private val amberEyes = arrayOf(
        Eye(-.016, .295, .405, .276, -.08), Eye(-.227, .387, .333, .149, -.39),
        Eye(.248, .345, .35, .176, .27), Eye(-.409, .123, .318, .173, -.27),
        Eye(.415, .052, .331, .203, .29), Eye(-.198, -.032, .378, .132, .34),
    )

    private fun horn(slot: Int, s: Double, t: Double): AuroraMiniaturePart {
        fun at(u: Double): AuroraOffset {
            val p = curve(hornKnots, u)
            val flex = sin(TURN * t + s * .43)
            return point(s * (p.x + u * (.045 * s + .048 * flex)), p.y + (.07 * s + flex * .066) * u,
                p.z + .10 * s * u + .042 * u * cos(TURN * t + s))
        }
        val u = slot / 16.0
        val width = .037 + .196 * (1.0 - u) * (1.0 - u)
        val material = when (slot) {
            0, 3, 7, 11 -> Material.SMOOTH_SANDSTONE
            1, 5 -> Material.STRIPPED_BIRCH_WOOD
            else -> Material.BONE_BLOCK
        }
        return beam(material, at(u), at((slot + 1) / 16.0), width, width * .88)
    }

    private fun tentacle(which: Int, u: Double, t: Double): AuroraOffset {
        val p = curve(tentacleKnots[which], u)
        val phase = TURN * t
        val stomp = when (which) {
            0 -> swell(t, .12, .283, .301, .40)
            1 -> swell(t, .52, .661, .693, .79)
            else -> 0.0
        }
        return moved(p, u * (.087 * sin(phase - 3.1 * u + which * 1.6) + .041 * sin(2 * phase + which - 2 * u)),
            u * (.081 * sin(phase + 4 * u + which) + .191 * u * stomp),
            u * .07 * cos(phase - 4 * u + which * 1.3))
    }

    private fun bellyLimb(i: Int, t: Double): AuroraMiniaturePart {
        val which = i / 9
        val slot = i % 9
        val u = slot / 9.0
        val endWidth = if (which < 2) .091 else .027
        val width = endWidth + (.224 - endWidth) * (1.0 - u)
        val material = when (which) {
            0 -> if (slot in 2..6) Material.SOUL_SOIL else Material.POLISHED_BASALT
            1 -> if (slot in 3..6) Material.POLISHED_BASALT else Material.POLISHED_BLACKSTONE
            2 -> if (slot < 7) Material.CRIMSON_HYPHAE else Material.RED_NETHER_BRICKS
            else -> if (slot in 2..7) Material.POLISHED_BASALT else Material.COBBLED_DEEPSLATE
        }
        return beam(material, tentacle(which, u, t), tentacle(which, (slot + 1) / 9.0, t), width, width * .93)
    }

    private fun hoof(index: Int, t: Double): AuroraMiniaturePart {
        val which = index / 3
        val p = tentacle(which, 1.0, t)
        val s = if (which == 0) -1.0 else 1.0
        val tilt = s * .29 + .115 * sin(TURN * t + which)
        if (index % 3 == 0) return storyPart(Material.POLISHED_BASALT, p.x + s * .021, p.y - .035, p.z,
            .169, .141, .153, tilt)
        val toe = if (index % 3 == 1) -1.0 else 1.0
        return storyPart(Material.POLISHED_BLACKSTONE, p.x + s * .054 + toe * .073, p.y - .120 + toe * tilt * .063,
            p.z + .041 + toe * .014, .097, .163, .193, tilt + toe * .078, toe * .11)
    }

    private fun shag(i: Int, t: Double): AuroraMiniaturePart {
        val s = if (i % 2 == 0) -1.0 else 1.0
        val row = i / 2
        val x = s * (.109 + row * .103)
        val sway = sin(TURN * t + .76 * i)
        val centerY = -.034 + (row % 2) * .055 + s * .018
        // ItemDisplay.NONE keeps the flint sprite's angular outline and its actual extruded edges.
        return storyPart(if (i == 3 || i == 6) Material.INK_SAC else Material.FLINT, x,
            centerY + sway * .021, .278 - .021 * row, .189 + .053 * (row % 2),
            .432 + .094 * (i % 3), 1.44, s * (.124 + row * .073) + sway * .063, .13 * s)
    }

    private fun goatCore(i: Int, t: Double): AuroraMiniaturePart {
        return when (i) {
            0 -> storyPart(Material.POLISHED_BLACKSTONE, -.04, .117, -.057, .775, .646, .478, -.105)
            1 -> storyPart(Material.SOUL_SOIL, -.313, .177, .021, .351, .447, .402, .282)
            2 -> storyPart(Material.COBBLED_DEEPSLATE, .333, .105, -.006, .432, .529, .419, -.263)
            3 -> storyPart(Material.POLISHED_BASALT, .049, -.146, .046, .593, .374, .336, .143)
            4 -> storyPart(Material.CRYING_OBSIDIAN, -.077, .283, .110, .429, .278, .348, -.077)
            5 -> beam(Material.SOUL_SOIL, point(-.36, .339, .025), point(-.075, .445, .045), .16, .263)
            6 -> beam(Material.POLISHED_BLACKSTONE, point(.26, .367, .022), point(.442, .191, .084), .184, .247)
            7 -> storyPart(Material.BLACK_CONCRETE, .033, .008, .339, .132, .269, .039, -.14)
            8 -> beam(Material.RED_NETHER_BRICKS, point(.117, .138, .356), point(.076, -.119, .356), .029, .036)
            else -> shag(i - 9, t)
        }
    }

    private fun feeder(i: Int, s: Double, t: Double): AuroraMiniaturePart {
        val delay = if (s < 0) .0 else .071
        val reach = swell(t, .298 + delay, .555 + delay, .686 + delay, .917 + delay)
        val wave = sin(TURN * t + s)
        val root = point(s * .415, -.145 + s * .041, .055)
        fun branch(x: Double, y: Double, z: Double): AuroraOffset {
            val k = .37 + .63 * reach
            return moved(root, s * x * k, y * k - .31 * x * (1.0 - reach) + wave * .042 * x,
                z * k + .05 * x * cos(TURN * t + s))
        }
        val fork = branch(.379, -.104, .08)
        if (i < 4) {
            fun stalk(u: Double) = branch(.379 * u, -.104 * u - (.089 + .025 * wave) * sin(PI * u),
                .08 * u + .066 * sin(PI * u))
            return beam(if (i < 2) Material.SOUL_SOIL else Material.STRIPPED_CRIMSON_HYPHAE,
                stalk(i / 4.0), stalk((i + 1) / 4.0), .142 - .016 * i)
        }
        val low = i in 6..7 || i in 10..11 || i == 13
        val sign = if (low) -1.0 else 1.0
        val throat = branch(if (low) .639 else .686, if (low) -.359 else .052, if (low) .131 else .04)
        if (i in 4..7) {
            val bend = branch(if (low) .465 else .489, if (low) -.266 else .012, .096)
            return if (i % 2 == 0) beam(Material.STRIPPED_CRIMSON_HYPHAE, fork, bend, .091)
            else beam(Material.RED_NETHER_BRICKS, bend, throat, .070)
        }
        val open = .021 + reach * (.067 + .029 * sin(2 * TURN * t + s * 1.9 + sign))
        val upper = i % 2 == 0 || i >= 12
        val jawSide = if (upper) 1.0 else -1.0
        val tip = moved(throat, s * (.083 + .076 * reach), sign * .043 + jawSide * open, .055)
        return if (i >= 12) {
            val bite = moved(tip, -s * .037, -.066, .012)
            beam(Material.BONE_BLOCK, tip, bite, .033 * (.65 + .35 * reach))
        } else {
            beam(if (upper) Material.NETHER_WART_BLOCK else Material.RED_NETHER_BRICKS,
                moved(throat, y = jawSide * .018), tip, .055 + reach * .016, .062)
        }
    }

    private fun goat(index: Int, t: Double): AuroraMiniaturePart {
        val local = when (index) {
            in 0..14 -> goatCore(index, t)
            in 15..46 -> horn((index - 15) % 16, if (index < 31) -1.0 else 1.0, t)
            in 47..82 -> bellyLimb(index - 47, t)
            in 83..88 -> hoof(index - 83, t)
            in 89..112 -> feeder((index - 89) % 12, if (index < 101) -1.0 else 1.0, t)
            in 113..130 -> {
                val id = (index - 113) / 3
                val opening = if (id == 0) .68 + .32 * swell(t, .09, .22, .71, .89)
                    else swell(t, .115 + id * .043, .202 + id * .043, .764 - id * .016, .88 + id * .012)
                val blink = swell(t, .53 + id * .027, .55 + id * .027, .555 + id * .027, .594 + id * .027)
                eye(amberEyes[id], (index - 113) % 3, opening * (1.0 - .77 * blink),
                    .82 * sin(TURN * t - id * .29), sin(2 * TURN * t + id * .62), true)
            }
            in 131..134 -> feeder(12 + (index - 131) % 2, if (index < 133) -1.0 else 1.0, t)
            else -> shag(6 + index - 135, t)
        }
        return posed(local, .046 * sin(TURN * t - .82), -.036 + .043 * sin(TURN * t),
            -.010 + .138 * sin(TURN * t - .3), -.031 + .046 * sin(TURN * t + .7))
    }
}
