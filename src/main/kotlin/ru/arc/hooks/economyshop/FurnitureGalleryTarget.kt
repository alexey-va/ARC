package ru.arc.hooks.economyshop

import ru.arc.config.Config
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal const val FURNITURE_GALLERY_TARGET_RESOURCE = "furniture-gallery.yml"
internal const val FURNITURE_GALLERY_MAX_TARGETS = 256
internal const val FURNITURE_GALLERY_MAX_SEGMENTS = 32
internal const val FURNITURE_GALLERY_MAX_DIMENSION = 10.0
internal const val FURNITURE_GALLERY_ANCHOR_TOLERANCE = 0.05

internal data class FurnitureGalleryAnchor(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Double,
)

internal data class FurnitureGalleryBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    val widthX: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val widthZ: Double get() = maxZ - minZ
}

internal data class FurnitureGallerySegment(
    val index: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val width: Double,
    val height: Double,
    val chunkX: Int,
    val chunkZ: Int,
)

internal data class FurnitureGalleryTarget(
    val key: String,
    val furnitureId: String,
    val anchor: FurnitureGalleryAnchor,
    val bounds: FurnitureGalleryBounds,
    val anchorChunkX: Int,
    val anchorChunkZ: Int,
    val segments: List<FurnitureGallerySegment>,
) {
    val dependencyChunks: Set<Pair<Int, Int>>
        get() = segments.asSequence().map { it.chunkX to it.chunkZ }
            .plus(sequenceOf(anchorChunkX to anchorChunkZ))
            .toSet()

    fun touchesChunk(chunkX: Int, chunkZ: Int): Boolean = (chunkX to chunkZ) in dependencyChunks
}

internal object FurnitureGalleryTargetPlanner {
    /** Builds square XZ interaction boxes whose union exactly covers [bounds]. */
    fun create(
        key: String,
        furnitureId: String,
        anchor: FurnitureGalleryAnchor,
        bounds: FurnitureGalleryBounds,
    ): FurnitureGalleryTarget {
        require(key.isNotBlank() && key.length <= MAX_TARGET_KEY_LENGTH && key.none(Char::isISOControl)) {
            "Gallery target key is invalid"
        }
        require(furnitureId.matches(FURNITURE_ID_PATTERN)) { "Gallery furniture ID is invalid" }
        require(listOf(anchor.x, anchor.y, anchor.z, anchor.yaw).all(Double::isFinite)) {
            "Gallery anchor values must be finite"
        }
        require(listOf(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)
            .all(Double::isFinite)
        ) { "Gallery bounds must be finite" }

        val coordinates = listOf(anchor.x, anchor.z, bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ)
        require(coordinates.all { it in -MAX_HORIZONTAL_COORDINATE..MAX_HORIZONTAL_COORDINATE }) {
            "Gallery horizontal coordinates are outside the supported world range"
        }
        val elevations = listOf(anchor.y, bounds.minY, bounds.maxY)
        require(elevations.all { it in MIN_GALLERY_ELEVATION..MAX_GALLERY_ELEVATION }) {
            "Gallery elevations are outside the supported world range"
        }

        val dimensions = listOf(bounds.widthX, bounds.height, bounds.widthZ)
        require(dimensions.all { it > 0.0 && it <= FURNITURE_GALLERY_MAX_DIMENSION }) {
            "Gallery target dimensions must be positive and no larger than $FURNITURE_GALLERY_MAX_DIMENSION blocks"
        }

        val shortSide = min(bounds.widthX, bounds.widthZ)
        val longSide = max(bounds.widthX, bounds.widthZ)
        val segmentCount = ceil(longSide / shortSide).toInt()
        require(segmentCount in 1..FURNITURE_GALLERY_MAX_SEGMENTS) {
            "Gallery target must use between 1 and $FURNITURE_GALLERY_MAX_SEGMENTS interaction boxes"
        }

        val longIsX = bounds.widthX >= bounds.widthZ
        val longMin = if (longIsX) bounds.minX else bounds.minZ
        val longMax = if (longIsX) bounds.maxX else bounds.maxZ
        val spacing = if (segmentCount <= 1) 0.0 else (longSide - shortSide) / (segmentCount - 1)
        val segments = List(segmentCount) { index ->
            val along = if (segmentCount <= 1) (longMin + longMax) / 2.0
            else longMin + shortSide / 2.0 + spacing * index
            val x = if (longIsX) along else (bounds.minX + bounds.maxX) / 2.0
            val z = if (longIsX) (bounds.minZ + bounds.maxZ) / 2.0 else along
            FurnitureGallerySegment(
                index = index,
                x = x,
                y = bounds.minY,
                z = z,
                width = shortSide,
                height = bounds.height,
                chunkX = chunk(x),
                chunkZ = chunk(z),
            )
        }
        return FurnitureGalleryTarget(
            key = key,
            furnitureId = furnitureId,
            anchor = anchor,
            bounds = bounds,
            anchorChunkX = chunk(anchor.x),
            anchorChunkZ = chunk(anchor.z),
            segments = segments,
        )
    }

    fun desiredSegments(
        target: FurnitureGalleryTarget,
        anchorChunkLoaded: Boolean,
        exactRootLoaded: Boolean,
        loadedChunks: Set<Pair<Int, Int>>,
    ): List<FurnitureGallerySegment> {
        if (!anchorChunkLoaded || !exactRootLoaded) return emptyList()
        return target.segments.filter { (it.chunkX to it.chunkZ) in loadedChunks }
    }

    fun matchesAnchor(
        expected: FurnitureGalleryAnchor,
        observed: FurnitureGalleryAnchor,
        tolerance: Double = FURNITURE_GALLERY_ANCHOR_TOLERANCE,
    ): Boolean =
        listOf(
            expected.x to observed.x,
            expected.y to observed.y,
            expected.z to observed.z,
        ).all { (left, right) -> kotlin.math.abs(left - right) <= tolerance } &&
            yawDistance(expected.yaw, observed.yaw) <= tolerance

    fun matchesFurnitureRoot(
        target: FurnitureGalleryTarget,
        observedFurnitureId: String?,
        observedAnchor: FurnitureGalleryAnchor,
    ): Boolean =
        observedFurnitureId == target.furnitureId && matchesAnchor(target.anchor, observedAnchor)

    private fun yawDistance(left: Double, right: Double): Double {
        val wrapped = ((left - right + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return kotlin.math.abs(wrapped)
    }

    private fun chunk(coordinate: Double): Int = floor(coordinate).toInt() shr 4

    private const val MAX_TARGET_KEY_LENGTH = 192
    private const val MAX_HORIZONTAL_COORDINATE = 30_000_000.0
    private const val MIN_GALLERY_ELEVATION = -2_048.0
    private const val MAX_GALLERY_ELEVATION = 2_048.0
    private val FURNITURE_ID_PATTERN = Regex("[a-z0-9._-]+:[a-z0-9._/-]+")
}

/** Parses the environment-owned target map. Invalid individual entries fail closed. */
internal class FurnitureGalleryTargetConfig(private val source: Config) {
    fun snapshot(onInvalidTarget: (String) -> Unit = {}): List<FurnitureGalleryTarget> {
        val rawTargets = source.map<Any?>("targets", emptyMap())
        require(rawTargets.size <= FURNITURE_GALLERY_MAX_TARGETS) {
            "Furniture gallery target count exceeds $FURNITURE_GALLERY_MAX_TARGETS"
        }
        return rawTargets.entries.sortedBy { it.key }.mapNotNull { (key, raw) ->
            runCatching { parse(key, raw) }.getOrElse {
                onInvalidTarget(key)
                null
            }
        }
    }

    private fun parse(key: String, raw: Any?): FurnitureGalleryTarget {
        val target = stringMap(raw, "targets.$key")
        val furnitureId = target["furniture-id"] as? String
            ?: error("targets.$key.furniture-id is required")
        val anchor = stringMap(target["anchor"], "targets.$key.anchor")
        val bounds = stringMap(target["bounds"], "targets.$key.bounds")
        return FurnitureGalleryTargetPlanner.create(
            key = key,
            furnitureId = furnitureId,
            anchor = FurnitureGalleryAnchor(
                x = number(anchor["x"], "targets.$key.anchor.x"),
                y = number(anchor["y"], "targets.$key.anchor.y"),
                z = number(anchor["z"], "targets.$key.anchor.z"),
                yaw = number(anchor["yaw"], "targets.$key.anchor.yaw"),
            ),
            bounds = FurnitureGalleryBounds(
                minX = numberList(bounds["min"], "targets.$key.bounds.min")[0],
                minY = numberList(bounds["min"], "targets.$key.bounds.min")[1],
                minZ = numberList(bounds["min"], "targets.$key.bounds.min")[2],
                maxX = numberList(bounds["max"], "targets.$key.bounds.max")[0],
                maxY = numberList(bounds["max"], "targets.$key.bounds.max")[1],
                maxZ = numberList(bounds["max"], "targets.$key.bounds.max")[2],
            ),
        )
    }

    private fun stringMap(raw: Any?, path: String): Map<String, Any?> {
        val map = raw as? Map<*, *> ?: error("$path must be a mapping")
        require(map.keys.all { it is String }) { "$path keys must be strings" }
        return map.entries.associate { (key, value) -> (key as String) to value }
    }

    private fun number(raw: Any?, path: String): Double =
        (raw as? Number)?.toDouble()?.takeIf(Double::isFinite)
            ?: error("$path must be a finite number")

    private fun numberList(raw: Any?, path: String): List<Double> {
        val list = raw as? List<*> ?: error("$path must be a three-number list")
        require(list.size == 3) { "$path must have exactly three numbers" }
        return list.mapIndexed { index, value -> number(value, "$path[$index]") }
    }
}
