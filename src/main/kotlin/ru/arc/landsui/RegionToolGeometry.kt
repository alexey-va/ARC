package ru.arc.landsui

import kotlin.math.max
import kotlin.math.min

internal data class RegionPoint(val x: Int, val z: Int)

/** Inclusive block bounds; regions cover the configured world's full height. */
internal data class RegionBox(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {
    val width: Long get() = maxX.toLong() - minX + 1
    val depth: Long get() = maxZ.toLong() - minZ + 1
    val chunkCount: Long get() = ((maxX shr 4).toLong() - (minX shr 4) + 1) * ((maxZ shr 4).toLong() - (minZ shr 4) + 1)
    fun chunks(): Sequence<Pair<Int, Int>> = sequence {
        for (x in (minX shr 4)..(maxX shr 4)) for (z in (minZ shr 4)..(maxZ shr 4)) yield(x to z)
    }

    companion object {
        fun between(first: RegionPoint, second: RegionPoint) = RegionBox(
            min(first.x, second.x), min(first.z, second.z), max(first.x, second.x), max(first.z, second.z),
        )
    }
}

/** A single safe command argument; Lands still validates its own naming rules. */
internal fun regionToolName(input: String): String = input.trim().also {
    require(Regex("[\\p{L}\\p{N}_-]{1,24}").matches(it)) { "Invalid region name" }
}

internal data class RegionLine(val x: Double, val z: Double, val length: Double, val alongX: Boolean)

internal enum class RegionFrameAxis { X, Y, Z }

/** One edge of the visible, eye-height slice of a full-height region preview. */
internal data class RegionFrameEdge(
    val x: Double,
    val z: Double,
    val yOffset: Double,
    val length: Double,
    val axis: RegionFrameAxis,
)

/** Clip actual edges, never add a false border at the preview's distance cutoff. */
internal fun regionToolLines(box: RegionBox, viewer: RegionPoint, radius: Double = 48.0): List<RegionLine> = buildList {
    val left = max(box.minX.toDouble(), viewer.x - radius)
    val right = min(box.maxX.toDouble() + 1, viewer.x + radius)
    val north = max(box.minZ.toDouble(), viewer.z - radius)
    val south = min(box.maxZ.toDouble() + 1, viewer.z + radius)
    if (right > left) for (z in listOf(box.minZ.toDouble(), box.maxZ.toDouble() + 1)) {
        if (z in viewer.z - radius..viewer.z + radius) add(RegionLine(left, z, right - left, true))
    }
    if (south > north) for (x in listOf(box.minX.toDouble(), box.maxX.toDouble() + 1)) {
        if (x in viewer.x - radius..viewer.x + radius) add(RegionLine(x, north, south - north, false))
    }
}

/**
 * Builds an ArcBuilder-style cage for the portion visible around the viewer.
 *
 * The horizontal edges retain [regionToolLines]' clipping semantics. Vertical
 * edges are emitted only at actual region corners that are inside the same
 * bounded view, so a distance cutoff never creates a misleading fake corner.
 */
internal fun regionToolFrame(box: RegionBox, viewer: RegionPoint, radius: Double = 48.0): List<RegionFrameEdge> {
    require(radius >= 0.0) { "radius must be non-negative" }
    val halfHeight = 1.2
    val edges = buildList {
        for (line in regionToolLines(box, viewer, radius)) {
            val axis = if (line.alongX) RegionFrameAxis.X else RegionFrameAxis.Z
            add(RegionFrameEdge(line.x, line.z, -halfHeight, line.length, axis))
            add(RegionFrameEdge(line.x, line.z, halfHeight, line.length, axis))
        }

        val minX = box.minX.toDouble()
        val maxX = box.maxX.toDouble() + 1.0
        val minZ = box.minZ.toDouble()
        val maxZ = box.maxZ.toDouble() + 1.0
        val xRange = viewer.x - radius..viewer.x + radius
        val zRange = viewer.z - radius..viewer.z + radius
        for (x in listOf(minX, maxX)) for (z in listOf(minZ, maxZ)) {
            if (x in xRange && z in zRange) {
                add(RegionFrameEdge(x, z, -halfHeight, halfHeight * 2.0, RegionFrameAxis.Y))
            }
        }
    }
    return edges
}
