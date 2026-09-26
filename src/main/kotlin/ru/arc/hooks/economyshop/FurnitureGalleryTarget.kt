package ru.arc.hooks.economyshop

import ru.arc.config.Config
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

internal const val FURNITURE_GALLERY_TARGET_RESOURCE = "furniture-gallery.yml"
internal const val FURNITURE_GALLERY_MAX_ROOTS = 512
internal const val FURNITURE_GALLERY_MAX_SEGMENTS = 32
internal const val FURNITURE_GALLERY_MAX_DIMENSION = 10.0
internal const val FURNITURE_GALLERY_MAX_PROFILES = 1_024
internal const val FURNITURE_GALLERY_MAX_VERTICES = 2_048
internal const val FURNITURE_GALLERY_MAX_TOTAL_VERTICES = 262_144

internal data class FurnitureGalleryVertex(val x: Double, val y: Double, val z: Double)

internal data class FurnitureGalleryProfile(
    val furnitureId: String,
    val vertices: List<FurnitureGalleryVertex>,
    /** Entity pitch already baked into these local vertices; configured as `pitch:`. */
    val referencePitch: Double = 0.0,
)

internal data class FurnitureGalleryAnchor(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Double,
    val pitch: Double = 0.0,
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
    val touchedChunks: Set<Pair<Int, Int>>,
)

/** Geometry for one currently loaded, exact ItemsAdder furniture root. */
internal data class FurnitureGalleryHitboxPlan(
    val rootId: UUID,
    val furnitureId: String,
    val anchor: FurnitureGalleryAnchor,
    val bounds: FurnitureGalleryBounds,
    val rootChunkX: Int,
    val rootChunkZ: Int,
    val segments: List<FurnitureGallerySegment>,
) {
    val targetKey: String get() = "native:$rootId"
    val dependencyChunks: Set<Pair<Int, Int>>
        get() = segments.asSequence().flatMap { it.touchedChunks.asSequence() }
            .plus(sequenceOf(rootChunkX to rootChunkZ))
            .toSet()
}

/**
 * Validates model-local grounded vertices and converts them to a bounded world
 * AABB for the live spawned root. Vertices already include IA/native item
 * transforms at zero yaw/pitch; runtime applies the live ItemDisplay pitch,
 * yaw and position.
 */
internal object FurnitureGalleryTargetPlanner {
    fun profile(
        furnitureId: String,
        vertices: List<FurnitureGalleryVertex>,
        referencePitch: Double = 0.0,
    ): FurnitureGalleryProfile {
        require(furnitureId.matches(FURNITURE_ID_PATTERN)) { "Gallery furniture ID is invalid" }
        require(vertices.isNotEmpty() && vertices.size <= FURNITURE_GALLERY_MAX_VERTICES) {
            "Gallery profile must contain between 1 and $FURNITURE_GALLERY_MAX_VERTICES vertices"
        }
        require(vertices.all { vertex ->
            listOf(vertex.x, vertex.y, vertex.z).all { it.isFinite() && it in -LOCAL_COORDINATE_LIMIT..LOCAL_COORDINATE_LIMIT }
        }) { "Gallery profile vertices must be finite and within the supported local coordinate range" }
        require(referencePitch.isFinite()) { "Gallery profile pitch must be finite" }
        val normalizedReferencePitch = normalizeDegrees(referencePitch)
        require(listOf(0.0, 90.0, 270.0).any {
            degreesApart(normalizedReferencePitch, it) <= ROTATION_TOLERANCE_DEGREES
        }) { "Gallery profile pitch must be 0, 90, or -90 degrees" }
        return FurnitureGalleryProfile(furnitureId, vertices.toList(), referencePitch)
    }

    fun plan(
        profile: FurnitureGalleryProfile,
        rootId: UUID,
        anchor: FurnitureGalleryAnchor,
    ): FurnitureGalleryHitboxPlan {
        val bounds = modelBounds(profile, anchor)

        val shortSide = min(bounds.widthX, bounds.widthZ)
        val longSide = max(bounds.widthX, bounds.widthZ)
        require(shortSide > MIN_DIMENSION) { "Gallery interaction footprints must have positive X and Z dimensions" }
        val segmentCount = ceil(longSide / shortSide).toInt()
        require(segmentCount in 1..FURNITURE_GALLERY_MAX_SEGMENTS) {
            "Gallery profile must use between 1 and $FURNITURE_GALLERY_MAX_SEGMENTS interaction boxes"
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
            val minChunkX = chunk(x - shortSide / 2.0)
            val maxChunkX = chunk(Math.nextDown(x + shortSide / 2.0))
            val minChunkZ = chunk(z - shortSide / 2.0)
            val maxChunkZ = chunk(Math.nextDown(z + shortSide / 2.0))
            val touchedChunks = buildSet {
                for (chunkX in minChunkX..maxChunkX) {
                    for (chunkZ in minChunkZ..maxChunkZ) add(chunkX to chunkZ)
                }
            }
            FurnitureGallerySegment(
                index = index,
                x = x,
                y = bounds.minY,
                z = z,
                width = shortSide,
                height = bounds.height,
                chunkX = chunk(x),
                chunkZ = chunk(z),
                touchedChunks = touchedChunks,
            )
        }
        return FurnitureGalleryHitboxPlan(
            rootId = rootId,
            furnitureId = profile.furnitureId,
            anchor = anchor,
            bounds = bounds,
            rootChunkX = chunk(anchor.x),
            rootChunkZ = chunk(anchor.z),
            segments = segments,
        )
    }

    /** Returns the bounded transformed model geometry without planning interaction segments. */
    fun modelBounds(profile: FurnitureGalleryProfile, anchor: FurnitureGalleryAnchor): FurnitureGalleryBounds {
        require(anchor.isFinite()) { "Furniture root location and rotation must be finite" }
        require(anchor.hasSupportedProfileRotation(profile)) {
            "Furniture profile bounds support floor pitch at any yaw and wall pitch with cardinal yaw, relative to the profile's baked pitch"
        }
        require(anchor.x in -MAX_HORIZONTAL_COORDINATE..MAX_HORIZONTAL_COORDINATE &&
            anchor.z in -MAX_HORIZONTAL_COORDINATE..MAX_HORIZONTAL_COORDINATE &&
            anchor.y in MIN_GALLERY_ELEVATION..MAX_GALLERY_ELEVATION
        ) { "Furniture root is outside the supported world range" }

        val bounds = transform(profile, anchor)
        val coordinates = listOf(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)
        require(coordinates.all(Double::isFinite)) { "Transformed furniture bounds must be finite" }
        require(listOf(bounds.minX, bounds.maxX, bounds.minZ, bounds.maxZ)
            .all { it in -MAX_HORIZONTAL_COORDINATE..MAX_HORIZONTAL_COORDINATE }
        ) { "Transformed furniture bounds are outside the supported world range" }
        require(listOf(bounds.minY, bounds.maxY).all { it in MIN_GALLERY_ELEVATION..MAX_GALLERY_ELEVATION }) {
            "Transformed furniture bounds are outside the supported world range"
        }
        val dimensions = listOf(bounds.widthX, bounds.height, bounds.widthZ)
        require(dimensions.all { it >= 0.0 && it <= FURNITURE_GALLERY_MAX_DIMENSION } &&
            dimensions.count { it > MIN_DIMENSION } >= 2
        ) {
            "Transformed furniture bounds must have at least two nonzero dimensions and be no larger than $FURNITURE_GALLERY_MAX_DIMENSION blocks"
        }
        return bounds
    }

    fun desiredSegments(
        plan: FurnitureGalleryHitboxPlan,
        rootChunkLoaded: Boolean,
        loadedChunks: Set<Pair<Int, Int>>,
    ): List<FurnitureGallerySegment> {
        if (!rootChunkLoaded) return emptyList()
        return plan.segments.filter { segment -> segment.touchedChunks.all(loadedChunks::contains) }
    }

    private fun transform(profile: FurnitureGalleryProfile, anchor: FurnitureGalleryAnchor): FurnitureGalleryBounds {
        val pitch = Math.toRadians(profile.referencePitch - anchor.pitch)
        val pitchCosine = kotlin.math.cos(pitch)
        val pitchSine = kotlin.math.sin(pitch)
        val radians = Math.toRadians(-anchor.yaw)
        val cosine = kotlin.math.cos(radians)
        val sine = kotlin.math.sin(radians)
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY

        profile.vertices.forEach { vertex ->
            // ItemsAdder applies the live ItemDisplay pitch around X before the existing yaw transform.
            val pitchedY = vertex.y * pitchCosine - vertex.z * pitchSine
            val pitchedZ = vertex.y * pitchSine + vertex.z * pitchCosine
            val x = anchor.x + vertex.x * cosine + pitchedZ * sine
            val y = anchor.y + pitchedY
            val z = anchor.z - vertex.x * sine + pitchedZ * cosine
            minX = min(minX, x)
            minY = min(minY, y)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxZ = max(maxZ, z)
        }
        return FurnitureGalleryBounds(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun FurnitureGalleryAnchor.isFinite(): Boolean =
        listOf(x, y, z, yaw, pitch).all(Double::isFinite)

    private fun FurnitureGalleryAnchor.hasSupportedProfileRotation(profile: FurnitureGalleryProfile): Boolean {
        val normalizedPitch = normalizeDegrees(pitch)
        val referencePitch = normalizeDegrees(profile.referencePitch)
        val pitchDelta = normalizeDegrees(pitch - profile.referencePitch)
        val liveFloorPitch = degreesApart(normalizedPitch, 0.0) <= ROTATION_TOLERANCE_DEGREES
        if (liveFloorPitch && degreesApart(referencePitch, 0.0) <= ROTATION_TOLERANCE_DEGREES) return true

        val liveWallPitch = degreesApart(normalizedPitch, 90.0) <= ROTATION_TOLERANCE_DEGREES ||
            degreesApart(normalizedPitch, 270.0) <= ROTATION_TOLERANCE_DEGREES
        if (!liveWallPitch) return false
        val quarterTurns = normalizeDegrees(yaw) / 90.0
        val cardinalYaw = abs(quarterTurns - round(quarterTurns)) <= ROTATION_TOLERANCE_TURNS
        if (!cardinalYaw) return false

        val profilePitchAlreadyBaked = degreesApart(normalizedPitch, referencePitch) <= ROTATION_TOLERANCE_DEGREES
        val supportedWallPitchDelta = degreesApart(pitchDelta, 90.0) <= ROTATION_TOLERANCE_DEGREES ||
            degreesApart(pitchDelta, 270.0) <= ROTATION_TOLERANCE_DEGREES
        return profilePitchAlreadyBaked || supportedWallPitchDelta
    }

    private fun normalizeDegrees(angle: Double): Double = ((angle % FULL_ROTATION) + FULL_ROTATION) % FULL_ROTATION

    private fun degreesApart(first: Double, second: Double): Double = abs(first - second)

    private fun chunk(coordinate: Double): Int = floor(coordinate).toInt() shr 4

    private const val MIN_DIMENSION = 1.0e-6
    private const val FULL_ROTATION = 360.0
    private const val ROTATION_TOLERANCE_DEGREES = 0.05
    private const val ROTATION_TOLERANCE_TURNS = ROTATION_TOLERANCE_DEGREES / 90.0
    private const val LOCAL_COORDINATE_LIMIT = 10.0
    private const val MAX_HORIZONTAL_COORDINATE = 30_000_000.0
    private const val MIN_GALLERY_ELEVATION = -2_048.0
    private const val MAX_GALLERY_ELEVATION = 2_048.0
    private val FURNITURE_ID_PATTERN = Regex("[a-z0-9._-]+:[a-z0-9._/-]+")
}

/** Parses only exact IA-ID geometry profiles; legacy authored room targets are ignored. */
internal class FurnitureGalleryTargetConfig(private val source: Config) {
    fun snapshot(onInvalidProfile: (String) -> Unit = {}): Map<String, FurnitureGalleryProfile> {
        val rawProfiles = source.map<Any?>("profiles", emptyMap())
        require(rawProfiles.size <= FURNITURE_GALLERY_MAX_PROFILES) {
            "Furniture gallery profile count exceeds $FURNITURE_GALLERY_MAX_PROFILES"
        }
        val result = LinkedHashMap<String, FurnitureGalleryProfile>()
        var totalVertices = 0
        rawProfiles.entries.sortedBy { it.key }.forEach { (id, raw) ->
            runCatching {
                val config = stringMap(raw, "profiles.$id")
                val vertices = vertexList(config["vertices"], "profiles.$id.vertices")
                val referencePitch = config["pitch"]?.let { number(it, "profiles.$id.pitch") } ?: 0.0
                totalVertices += vertices.size
                require(totalVertices <= FURNITURE_GALLERY_MAX_TOTAL_VERTICES) {
                    "Gallery profile vertex count exceeds $FURNITURE_GALLERY_MAX_TOTAL_VERTICES"
                }
                FurnitureGalleryTargetPlanner.profile(id, vertices, referencePitch)
            }.onSuccess { profile -> result[id] = profile }
                .onFailure { onInvalidProfile(id) }
        }
        return result
    }

    private fun stringMap(raw: Any?, path: String): Map<String, Any?> {
        val map = raw as? Map<*, *> ?: error("$path must be a mapping")
        require(map.keys.all { it is String }) { "$path keys must be strings" }
        return map.entries.associate { (key, value) -> (key as String) to value }
    }

    private fun vertexList(raw: Any?, path: String): List<FurnitureGalleryVertex> {
        val list = raw as? List<*> ?: error("$path must be a list of [x, y, z] vertices")
        require(list.size in 1..FURNITURE_GALLERY_MAX_VERTICES) {
            "$path must contain between 1 and $FURNITURE_GALLERY_MAX_VERTICES vertices"
        }
        return list.mapIndexed { index, rawVertex ->
            val vertex = rawVertex as? List<*> ?: error("$path[$index] must be a three-number list")
            require(vertex.size == 3) { "$path[$index] must have exactly three numbers" }
            FurnitureGalleryVertex(
                number(vertex[0], "$path[$index][0]"),
                number(vertex[1], "$path[$index][1]"),
                number(vertex[2], "$path[$index][2]"),
            )
        }
    }

    private fun number(raw: Any?, path: String): Double =
        (raw as? Number)?.toDouble()?.takeIf(Double::isFinite)
            ?: error("$path must be a finite number")
}
