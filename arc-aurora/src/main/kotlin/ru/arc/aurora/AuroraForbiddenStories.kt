package ru.arc.aurora

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object AuroraForbiddenStories : AuroraStoryParts {
    private const val TURN = 2.0 * PI
    private const val TENDRIL_SEGMENTS = 7
    private const val EYE_LAYERS = 7
    private const val CLOTH_SEGMENTS = 4

    private fun p(x: Double, y: Double, z: Double = 0.0) = AuroraOffset(x, y, z)

    private fun beam(m: Material, a: AuroraOffset, b: AuroraOffset, width: Double, depth: Double): AuroraMiniaturePart {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val flat = hypot(dx, dy)
        return storyPart(m, (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2,
            hypot(flat, dz) + width * 0.18, width, depth, atan2(dy, dx), atan2(-dz, flat))
    }

    private fun place(part: AuroraMiniaturePart, facing: Quaternionf, at: AuroraOffset): AuroraMiniaturePart {
        val local = part.center
        val off = facing.transform(Vector3f(local.x.toFloat(), local.y.toFloat(), local.z.toFloat()))
        return part.copy(center = p(at.x + off.x, at.y + off.y, at.z + off.z),
            rotation = Quaternionf(facing).mul(part.rotation))
    }

    // Layered vanilla round item silhouettes. The caps of generated items in NONE are exactly 1/16 apart.
    private fun lens(mat: Material, x: Double, y: Double, z: Double, w: Double, h: Double, depth: Double) =
        storyPart(mat, x, y, z, w, h, depth * 16.0)

    private data class Tendril(val root: AuroraOffset, val first: AuroraOffset, val second: AuroraOffset,
        val tip: AuroraOffset, val girth: Double, val hide: Material)

    private fun curl(limb: Tendril, n: Int, k: Int, t: Double, extend: Double): AuroraMiniaturePart {
        fun joint(u: Double): AuroraOffset {
            val v = 1.0 - u
            val b = 3 * v * v * u
            val c = 3 * v * u * u
            val d = u * u * u
            fun axis(root: Double, first: Double, second: Double, tip: Double) =
                root + extend * (b * (first - root) + c * (second - root) + d * (tip - root))
            val wave = TURN * (t + n * 0.217 - u * 0.91)
            val swell = u * (0.42 + 0.58 * u) * extend
            return p(axis(limb.root.x, limb.first.x, limb.second.x, limb.tip.x) + 0.11 * swell * cos(wave),
                axis(limb.root.y, limb.first.y, limb.second.y, limb.tip.y) + 0.16 * swell * sin(wave),
                axis(limb.root.z, limb.first.z, limb.second.z, limb.tip.z) + 0.17 * swell * sin(wave + 1.2))
        }
        val width = limb.girth * (1.0 - 0.88 * (k + 0.35) / TENDRIL_SEGMENTS)
        return beam(limb.hide, joint(k.toDouble() / TENDRIL_SEGMENTS),
            joint((k + 1.0) / TENDRIL_SEGMENTS), width, width * 1.07)
    }

    private data class Orb(val at: AuroraOffset, val size: Double, val yaw: Double, val roll: Double,
        val membrane: Material, val iris: Material, val wake: Double)

    // The near and far lobes overlap around a heavy left-hand eye. They occupy three depth planes.
    private val orbs = listOf(
        Orb(p(-0.32, 0.19, 0.22), 1.16, -0.14, -0.13, Material.ENDER_PEARL, Material.MAGMA_CREAM, 0.24),
        Orb(p(0.40, -0.34, 0.16), 0.77, 0.26, 0.23, Material.ENDER_PEARL, Material.HEART_OF_THE_SEA, 0.10),
        Orb(p(0.48, 0.36, -0.01), 0.70, 0.32, -0.28, Material.CHORUS_FRUIT, Material.ENDER_EYE, 0.18),
        Orb(p(-0.54, -0.52, -0.02), 0.67, -0.30, 0.38, Material.HEART_OF_THE_SEA, Material.MAGMA_CREAM, 0.05),
        Orb(p(-0.04, 0.78, -0.18), 0.59, -0.08, 0.27, Material.NAUTILUS_SHELL, Material.HEART_OF_THE_SEA, 0.13),
        Orb(p(-0.99, 0.00, -0.14), 0.51, -0.44, -0.46, Material.ENDER_PEARL, Material.ENDER_EYE, 0.19),
        Orb(p(0.11, -0.83, 0.01), 0.42, 0.18, -0.38, Material.ENDER_PEARL, Material.MAGMA_CREAM, 0.23),
        Orb(p(0.97, 0.06, -0.22), 0.46, 0.54, 0.38, Material.CHORUS_FRUIT, Material.MAGMA_CREAM, 0.08),
        Orb(p(0.09, -0.27, 0.46), 0.34, 0.04, -0.18, Material.HEART_OF_THE_SEA, Material.ENDER_EYE, 0.30),
    )

    private val yogTendrils = listOf(
        Tendril(p(-0.60, 0.12, -0.20), p(-1.39, 0.02, -0.49), p(-1.62, 1.03, 0.10), p(-1.10, 0.99, 0.25), 0.19, Material.WARPED_HYPHAE),
        Tendril(p(-0.18, 0.48, -0.27), p(-0.93, 0.85, -0.41), p(-0.37, 1.48, -0.02), p(0.20, 1.21, 0.15), 0.18, Material.DARK_PRISMARINE),
        Tendril(p(0.49, 0.22, -0.28), p(1.19, 0.25, 0.01), p(0.65, 1.24, 0.12), p(1.29, 1.07, -0.03), 0.21, Material.WARPED_HYPHAE),
        Tendril(p(0.50, -0.28, -0.20), p(1.08, -1.08, -0.17), p(1.54, -0.88, 0.10), p(1.42, -0.15, 0.22), 0.23, Material.DARK_PRISMARINE),
        Tendril(p(-0.19, -0.50, -0.23), p(-0.10, -1.12, -0.35), p(0.80, -1.38, 0.13), p(0.88, -0.96, 0.30), 0.22, Material.WARPED_HYPHAE),
        Tendril(p(-0.65, -0.29, -0.09), p(-1.28, -0.27, -0.22), p(-1.45, -0.76, 0.04), p(-1.12, -1.02, 0.17), 0.17, Material.DARK_PRISMARINE),
    )

    private val vitreousCore = listOf(
        lens(Material.CHORUS_FRUIT, -0.12, -0.04, -0.27, 1.68, 1.39, 0.59),
        lens(Material.GLOW_INK_SAC, -0.49, -0.18, -0.20, 1.28, 0.98, 0.43),
        lens(Material.HEART_OF_THE_SEA, 0.40, 0.14, -0.19, 1.33, 1.04, 0.40),
        lens(Material.CHORUS_FRUIT, 0.20, -0.47, -0.10, 1.21, 0.85, 0.30),
        lens(Material.NAUTILUS_SHELL, -0.22, 0.43, -0.22, 1.04, 0.90, 0.28),
        lens(Material.CHORUS_FRUIT, 0.68, -0.13, -0.21, 0.90, 0.78, 0.33),
    )

    // Translucent-looking pale fibres thread *between* the lobes. Their endpoints remain attached.
    private data class Fibre(val a: AuroraOffset, val knee: AuroraOffset, val b: AuroraOffset,
        val width: Double, val material: Material)

    private val fibres = listOf(
        Fibre(p(-1.01, 0.085, -0.03), p(-0.75, 0.62, 0.06), p(-0.07, 0.77, -0.05), 0.110, Material.PRISMARINE),
        Fibre(p(-0.89, -0.14, 0.02), p(-0.94, -0.47, 0.13), p(-0.46, -0.53, 0.17), 0.105, Material.PURPUR_BLOCK),
        Fibre(p(-0.46, -0.53, 0.07), p(-0.12, -0.90, 0.10), p(0.31, -0.60, 0.20), 0.085, Material.PRISMARINE),
        Fibre(p(0.11, 0.75, -0.14), p(0.60, 0.65, -0.07), p(0.87, 0.10, -0.14), 0.100, Material.PURPUR_BLOCK),
        Fibre(p(0.30, -0.48, -0.01), p(0.94, -0.47, 0.04), p(0.93, 0.16, -0.10), 0.102, Material.PRISMARINE),
        Fibre(p(-0.35, -0.13, 0.13), p(0.025, 0.12, 0.30), p(0.48, 0.32, 0.16), 0.125, Material.PURPUR_BLOCK),
    )

    private fun fibre(n: Int, k: Int, t: Double): AuroraMiniaturePart {
        val f = fibres[n]
        fun knot(u: Double): AuroraOffset {
            val v = 1 - u
            val bend = 2 * u * v
            val flex = 0.058 * sin(TURN * t + n) * bend
            return p(v*v*f.a.x + bend*f.knee.x + u*u*f.b.x, v*v*f.a.y + bend*f.knee.y + u*u*f.b.y + flex,
                v*v*f.a.z + bend*f.knee.z + u*u*f.b.z + flex)
        }
        return beam(f.material, knot(k / 3.0), knot((k + 1) / 3.0), f.width, f.width * 0.80)
    }

    private fun aperture(o: Orb, t: Double): Double {
        val wake = storyBeat(t, o.wake, o.wake + if (o === orbs.first()) 0.19 else 0.14)
        val sleep = storyBeat(t, 0.76 + o.wake * 0.4, 0.91 + o.wake * 0.24)
        return 0.055 + 0.945 * wake * (1.0 - sleep)
    }

    private fun orbitPose(o: Orb, fixation: Double) = Quaternionf()
        .rotateY((o.yaw * (1.0 - 0.72 * fixation)).toFloat())
        .rotateX((0.25 + 0.08 * (1.0 - fixation)).toFloat()).rotateZ(o.roll.toFloat())

    private fun ocular(n: Int, layer: Int, t: Double, fixation: Double): AuroraMiniaturePart {
        val o = orbs[n]
        val d = o.size
        val aperture = aperture(o, t)
        val g = TURN * (t + 0.29 * n)
        val wobble = 1.0 - fixation
        val gazeX = d * (0.13 * cos(g) * wobble + 0.025 * fixation)
        val gazeY = d * (0.08 * sin(g) * wobble - 0.035 * fixation) * aperture
        val local = when (layer) {
            0 -> lens(o.membrane, 0.0, 0.0, -0.085 * d, 1.05 * d, d, 0.49 * d)
            1 -> lens(Material.CLAY_BALL, 0.0, 0.0, 0.13 * d, 0.92 * d, 0.86 * d, 0.22 * d)
            2 -> lens(o.membrane, 0.0, 0.0, 0.235 * d, 0.89 * d, 0.79 * d, 0.12 * d)
            3 -> lens(Material.SNOWBALL, 0.0, 0.0, 0.300 * d, 0.86 * d, 0.77 * d * aperture, 0.065 * d)
            4 -> lens(o.iris, gazeX, gazeY, 0.351 * d, 0.56 * d, 0.61 * d * aperture, 0.048 * d)
            5 -> storyPart(Material.BLACK_CONCRETE, gazeX, gazeY, 0.388 * d, 0.128 * d, 0.35 * d * aperture, 0.029 * d)
            else -> lens(Material.SNOWBALL, gazeX - 0.047 * d, gazeY + 0.11 * d * aperture, 0.408 * d,
                0.08 * d, 0.095 * d * aperture, 0.016 * d)
        }
        val pose = orbitPose(o, fixation)
        return place(local, pose, p(o.at.x, o.at.y + 0.025 * sin(g), o.at.z))
    }

    // The main eye has muscular opposing lids. They meet over its cornea and lift away in two arcs.
    private fun lid(k: Int, t: Double, fixation: Double): AuroraMiniaturePart {
        val o = orbs.first()
        val gap = aperture(o, t)
        val upper = k < 4
        val side = if (upper) 1.0 else -1.0
        fun corner(i: Int): AuroraOffset {
            val u = i / 4.0
            return p(o.size * (u - 0.5) * 0.76, o.size * side * (0.027 + 0.31 * sin(PI*u) * gap),
                o.size * (0.297 + 0.062 * sin(PI*u)))
        }
        val i = k % 4
        val lid = beam(if (upper) Material.PURPLE_TERRACOTTA else Material.PRISMARINE, corner(i), corner(i + 1),
            if (upper) 0.082 else 0.060, 0.072)
        return place(lid, orbitPose(o, fixation), p(o.at.x, o.at.y + 0.025 * sin(TURN*t), o.at.z))
    }

    private fun yogSothoth(slot: Int, t: Double): AuroraMiniaturePart {
        val fixation = storyBeat(t, 0.31, 0.56) * (1.0 - storyBeat(t, 0.73, 0.97))
        val eyeSlots = orbs.size * EYE_LAYERS
        val coreStart = eyeSlots + yogTendrils.size * TENDRIL_SEGMENTS
        val fibreStart = coreStart + vitreousCore.size
        val lidStart = fibreStart + fibres.size * 3
        val part = when {
            slot < eyeSlots -> ocular(slot / EYE_LAYERS, slot % EYE_LAYERS, t, fixation)
            slot < coreStart -> {
                val arm = (slot - eyeSlots) / TENDRIL_SEGMENTS
                curl(yogTendrils[arm], arm, (slot - eyeSlots) % TENDRIL_SEGMENTS, t, 0.77 + 0.23 * fixation)
            }
            slot < fibreStart -> vitreousCore[slot - coreStart]
            slot < lidStart -> fibre((slot - fibreStart) / 3, (slot - fibreStart) % 3, t)
            else -> lid(slot - lidStart, t, fixation)
        }
        val facing = Quaternionf().rotateY((-0.33 + 0.40 * fixation + 0.06 * sin(TURN * t)).toFloat())
            .rotateZ((-0.055 + 0.055 * sin(TURN * t)).toFloat())
        return place(part, facing, p(0.0, 0.032 * sin(TURN * t), 0.05 * fixation))
    }

    private val hood = buildList {
        // A slumped asymmetric arch surrounds one deep empty cavity. Nothing is inset as a face.
        add(storyPart(Material.BLACK_CONCRETE, -0.085, 0.83, -0.02, 0.44, 0.60, 0.24, -0.07, -0.11))
        add(beam(Material.BROWN_TERRACOTTA, p(-0.36, 0.50, -0.08), p(-0.24, 1.12, -0.18), 0.32, 0.39))
        add(beam(Material.YELLOW_TERRACOTTA, p(0.19, 0.51, -0.17), p(0.15, 1.12, -0.18), 0.27, 0.38))
        add(beam(Material.YELLOW_TERRACOTTA, p(-0.33, 0.56, 0.21), p(-0.33, 0.94, 0.26), 0.15, 0.22))
        add(beam(Material.YELLOW_WOOL, p(-0.33, 0.94, 0.26), p(-0.20, 1.22, 0.19), 0.14, 0.21))
        add(beam(Material.YELLOW_TERRACOTTA, p(-0.20, 1.22, 0.19), p(0.13, 1.025, 0.22), 0.20, 0.25))
        add(beam(Material.YELLOW_WOOL, p(0.13, 1.025, 0.22), p(0.20, 0.76, 0.24), 0.11, 0.20))
        add(beam(Material.STRIPPED_OAK_WOOD, p(0.20, 0.76, 0.24), p(0.12, 0.56, 0.17), 0.105, 0.16))
        add(beam(Material.YELLOW_TERRACOTTA, p(-0.30, 0.55, 0.00), p(-0.58, 0.45, -0.15), 0.25, 0.30))
        add(beam(Material.STRIPPED_OAK_WOOD, p(0.08, 0.56, 0.02), p(0.57, 0.44, -0.14), 0.24, 0.34))
        // Unequal flattened metal tines follow the incline of the hood rather than forming a hat.
        val roots = listOf(p(-0.31, 1.02, 0.045), p(-0.19, 1.16, 0.04), p(-0.03, 1.15, -0.10), p(0.13, 1.04, -0.025))
        val tips = listOf(p(-0.49, 1.28, -0.09), p(-0.27, 1.52, -0.10), p(0.06, 1.39, -0.24), p(0.34, 1.31, -0.16))
        for (i in roots.indices) {
            val a = roots[i]
            val b = tips[i]
            val joint = p(a.x * 0.44 + b.x * 0.56, a.y * 0.44 + b.y * 0.56, a.z * 0.44 + b.z * 0.56)
            add(beam(Material.RAW_GOLD_BLOCK, a, joint, 0.095, 0.091))
            add(beam(Material.STRIPPED_BIRCH_WOOD, joint, b, 0.044, 0.047))
        }
    }

    // The lower hem is cut to different lengths. Individual folds start at the same high mantle and
    // follow it in depth, so their undersides are visible to a viewer standing under the sky scene.
    private data class Fold(val shoulder: Double, val hem: Double, val y: Double, val z: Double,
        val width: Double, val sweep: Double, val cloth: Material)

    private val folds = listOf(
        Fold(-0.38, -0.43, -1.02, -0.32, 0.39, -0.26, Material.BROWN_TERRACOTTA),
        Fold(0.38, 0.49, -1.14, -0.36, 0.37, 0.24, Material.YELLOW_TERRACOTTA),
        Fold(-0.50, -0.67, -0.94, -0.17, 0.33, -0.47, Material.YELLOW_TERRACOTTA),
        Fold(0.48, 0.72, -0.60, -0.19, 0.32, 0.45, Material.STRIPPED_OAK_WOOD),
        Fold(-0.41, -0.45, -1.39, 0.00, 0.30, -0.48, Material.STRIPPED_OAK_WOOD),
        Fold(0.37, 0.49, -1.22, -0.03, 0.33, 0.54, Material.YELLOW_TERRACOTTA),
        Fold(-0.29, -0.24, -0.87, 0.19, 0.28, -0.47, Material.YELLOW_WOOL),
        Fold(0.22, 0.22, -0.93, 0.18, 0.28, 0.55, Material.YELLOW_WOOL),
        Fold(-0.15, -0.06, -1.07, 0.28, 0.19, -0.49, Material.YELLOW_TERRACOTTA),
        Fold(0.085, 0.09, -0.65, 0.28, 0.19, 0.48, Material.STRIPPED_OAK_WOOD),
    )

    private fun cloth(n: Int, k: Int, t: Double, open: Double): AuroraMiniaturePart {
        val f = folds[n]
        val side = if (f.shoulder < 0) -1.0 else 1.0
        fun seam(u: Double): AuroraOffset {
            val lower = u * u
            val gust = sin(TURN * (t - u * 0.22 + n * 0.045))
            return p(f.shoulder * (1 - u) + (f.hem + f.sweep * open) * u +
                    side * sin(PI * u) * (0.06 + 0.27 * open) + 0.064 * lower * gust,
                (0.57 - 0.14 * kotlin.math.abs(f.shoulder)) * (1 - u) + (f.y + open * 0.13) * u
                    + 0.054 * lower * gust,
                0.015 * (1 - u) + f.z * u + 0.18 * sin(PI * u) + lower * (0.12 * gust - 0.16 * open))
        }
        val u = k.toDouble() / CLOTH_SEGMENTS
        val width = f.width * (1.06 - if (k == CLOTH_SEGMENTS - 1) 0.39 else 0.10 * u)
        return beam(f.cloth, seam(u), seam((k + 1.0) / CLOTH_SEGMENTS), width, 0.12 + 0.025 * (n % 3))
    }

    private val rootsBelow = listOf(
        Tendril(p(-0.14, 0.31, 0.01), p(-0.23, -0.43, 0.20), p(-1.04, -0.59, 0.27), p(-1.03, -1.19, 0.17), 0.22, Material.PURPLE_TERRACOTTA),
        Tendril(p(0.16, 0.33, -0.01), p(0.71, -0.16, 0.18), p(0.62, -1.12, -0.11), p(1.03, -0.99, 0.17), 0.21, Material.POLISHED_BLACKSTONE),
        Tendril(p(-0.04, 0.20, 0.08), p(-0.54, -0.34, 0.19), p(-0.45, -1.58, 0.29), p(-0.03, -1.28, 0.21), 0.23, Material.CRIMSON_PLANKS),
        Tendril(p(0.10, 0.18, 0.12), p(0.26, -0.54, 0.34), p(0.07, -1.38, 0.25), p(0.62, -1.36, 0.41), 0.19, Material.CRIMSON_HYPHAE),
        Tendril(p(-0.04, 0.32, -0.14), p(0.43, -0.31, -0.14), p(-0.05, -0.84, 0.02), p(-0.64, -1.19, 0.46), 0.16, Material.POLISHED_BLACKSTONE),
    )

    private val bloodEyes = listOf(
        Orb(p(-0.03, 0.33, 0.13), 0.30, -0.08, -0.37, Material.SPIDER_EYE, Material.RED_DYE, 0.09),
        Orb(p(0.19, 0.04, 0.15), 0.34, 0.26, 0.45, Material.SPIDER_EYE, Material.RED_DYE, 0.19),
        Orb(p(-0.21, -0.14, 0.20), 0.25, -0.16, -0.39, Material.SPIDER_EYE, Material.RED_DYE, 0.12),
        Orb(p(0.01, -0.40, 0.22), 0.22, 0.12, -0.06, Material.SPIDER_EYE, Material.RED_DYE, 0.25),
    )

    private fun bloodEye(n: Int, layer: Int, t: Double, open: Double): AuroraMiniaturePart {
        val e = bloodEyes[n]
        val d = e.size
        val awake = 0.09 + 0.91 * storyBeat(open, e.wake, 0.65 + e.wake)
        val look = 0.045 * d * sin(TURN * t)
        val local = when (layer) {
            0 -> lens(e.membrane, 0.0, 0.0, 0.0, 1.28 * d, 0.99 * d, 0.12)
            1 -> lens(Material.SNOWBALL, 0.0, 0.0, 0.075, d, d * 0.68 * awake, 0.045)
            2 -> lens(e.iris, look, -0.025 * d, 0.101, 0.50 * d, 0.58 * d * awake, 0.025)
            else -> lens(Material.BLACK_DYE, look, -0.025 * d, 0.121, 0.21 * d, 0.48 * d * awake, 0.014)
        }
        return place(local, Quaternionf().rotateY(e.yaw.toFloat()).rotateX(0.24f).rotateZ(e.roll.toFloat()), e.at)
    }

    private fun claw(k: Int, t: Double, reach: Double): AuroraMiniaturePart {
        val shoulder = p(0.38, 0.45, -0.02)
        val elbow = p(0.57 + 0.38 * reach, 0.11 + 0.24 * reach, -0.10 + 0.14 * reach)
        val palm = p(0.46 + 0.79 * reach, -0.05 + 0.65 * reach, 0.13 + 0.65 * reach)
        if (k == 0) return beam(Material.YELLOW_TERRACOTTA, shoulder, elbow, 0.24, 0.25)
        if (k == 1) return beam(Material.CRIMSON_HYPHAE, elbow, palm, 0.15, 0.15)
        if (k == 2) return lens(Material.INK_SAC, palm.x, palm.y, palm.z, 0.26, 0.21, 0.15)
        val finger = (k - 3) / 2
        val pos = (k - 3) % 2
        val fan = finger - 1.5
        val bend = sin(TURN * t - 0.38 * finger)
        val root = p(palm.x + 0.024 * finger, palm.y + 0.038 * fan, palm.z)
        val knuckle = p(palm.x + 0.12 + (0.075 - 0.027 * fan) * reach, palm.y + fan * (0.05 + 0.092 * reach),
            palm.z + 0.14 + 0.04 * finger)
        val tip = p(knuckle.x + 0.13 - 0.029 * finger, knuckle.y - 0.135 + 0.035 * bend + 0.022 * fan,
            knuckle.z + 0.15 + 0.05 * reach)
        return if (pos == 0) beam(Material.CRIMSON_HYPHAE, root, knuckle, 0.075, 0.075)
        else beam(Material.DRIPSTONE_BLOCK, knuckle, tip, 0.043, 0.044)
    }

    private val mantleHeart = listOf(
        beam(Material.POLISHED_BLACKSTONE, p(-0.04, 0.53, -0.03), p(-0.22, 0.05, 0.05), 0.28, 0.24),
        beam(Material.PURPLE_TERRACOTTA, p(0.035, 0.49, -0.08), p(0.25, 0.01, 0.05), 0.23, 0.21),
        beam(Material.POLISHED_BLACKSTONE, p(-0.22, 0.09, 0.035), p(0.01, -0.44, 0.14), 0.19, 0.14),
        beam(Material.NETHERITE_BLOCK, p(0.22, 0.12, -0.06), p(-0.16, -0.27, 0.10), 0.18, 0.13),
    )

    // Strips split from the cloth at the hem; each narrows to a single tattered point.
    private fun tatters(n: Int, k: Int, t: Double, open: Double): AuroraMiniaturePart {
        val foldIndex = when (n) { 0 -> 2; 1 -> 4; else -> 5 }
        val fold = folds[foldIndex]
        val side = if (fold.hem < 0) -1.0 else 1.0
        val root = cloth(foldIndex, 2, t, open).center
        fun knot(u: Double): AuroraOffset {
            val ripple = sin(TURN * (t - u * 0.19 + 0.17 * n))
            return p(root.x + side * ((0.08 + 0.21 * open) * u + 0.15 * sin(PI * u)) + 0.081 * u * ripple,
                root.y - (0.60 + 0.065 * n) * u + 0.042 * u * ripple,
                root.z + (0.13 - n * 0.09) * u + 0.12 * u * ripple)
        }
        return beam(if (n == 1) Material.YELLOW_WOOL else Material.YELLOW_TERRACOTTA,
            knot(k / 3.0), knot((k + 1) / 3.0), 0.127 - 0.038 * k, 0.084 - 0.025 * k)
    }

    private fun hastur(slot: Int, t: Double): AuroraMiniaturePart {
        val open = storyBeat(t, 0.11, 0.49) * (1.0 - storyBeat(t, 0.73, 0.97))
        val reach = storyBeat(t, 0.32, 0.61) * (1.0 - storyBeat(t, 0.70, 0.91))
        val foldStart = hood.size
        val tendrilStart = foldStart + folds.size * CLOTH_SEGMENTS
        val eyeStart = tendrilStart + rootsBelow.size * TENDRIL_SEGMENTS
        val clawStart = eyeStart + bloodEyes.size * 4
        val heartStart = clawStart + 11
        val tatterStart = heartStart + mantleHeart.size
        val part = when {
            slot < foldStart -> hood[slot]
            slot < tendrilStart -> cloth((slot - foldStart) / CLOTH_SEGMENTS, (slot - foldStart) % CLOTH_SEGMENTS, t, open)
            slot < eyeStart -> {
                val i = (slot - tendrilStart) / TENDRIL_SEGMENTS
                curl(rootsBelow[i], i, (slot - tendrilStart) % TENDRIL_SEGMENTS, t, 0.67 + 0.33 * open)
            }
            slot < clawStart -> bloodEye((slot - eyeStart) / 4, (slot - eyeStart) % 4, t, open)
            slot < heartStart -> claw(slot - clawStart, t, reach)
            slot < tatterStart -> mantleHeart[slot - heartStart]
            else -> tatters((slot - tatterStart) / 3, (slot - tatterStart) % 3, t, open)
        }
        val facing = Quaternionf().rotateY((-0.31 + 0.15 * reach).toFloat())
            .rotateX((0.055 + 0.09 * reach).toFloat()).rotateZ((0.05 - 0.055 * reach).toFloat())
        return place(part, facing, p(-0.07 + 0.02 * reach, 0.034 * sin(TURN * t), 0.09 * reach))
    }

    override fun partCount(shape: AuroraShape): Int = when (shape) {
        AuroraShape.YOG_SOTHOTH -> orbs.size * EYE_LAYERS + yogTendrils.size * TENDRIL_SEGMENTS + vitreousCore.size + fibres.size * 3 + 8
        AuroraShape.HASTUR -> hood.size + folds.size * CLOTH_SEGMENTS + rootsBelow.size * TENDRIL_SEGMENTS + bloodEyes.size * 4 + 11 + mantleHeart.size + 9
        else -> error("Not a forbidden story: $shape")
    }

    override fun part(shape: AuroraShape, index: Int, t: Double): AuroraMiniaturePart = when (shape) {
        AuroraShape.YOG_SOTHOTH -> yogSothoth(index, t)
        AuroraShape.HASTUR -> hastur(index, t)
        else -> error("Not a forbidden story: $shape")
    }
}
