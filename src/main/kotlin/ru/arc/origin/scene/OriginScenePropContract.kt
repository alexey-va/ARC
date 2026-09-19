package ru.arc.origin.scene

import org.bukkit.Material
import org.bukkit.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Defines which point inside a display model is pinned to its world anchor. */
internal enum class OriginScenePropOrigin {
    BOTTOM_CENTER,
    CENTER,
}

internal data class OriginSceneVector(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "scene prop vectors must be finite" }
    }

    fun requirePositive(path: String): OriginSceneVector = apply {
        require(x > 0.0 && y > 0.0 && z > 0.0) { "$path values must be positive" }
    }

    fun requireBounded(path: String, maximum: Double): OriginSceneVector = apply {
        require(abs(x) <= maximum && abs(y) <= maximum && abs(z) <= maximum) {
            "$path values must be within +/-$maximum"
        }
    }

    companion object {
        val ZERO = OriginSceneVector(0.0, 0.0, 0.0)
    }
}

internal data class OriginSceneResolvedProp(
    val x: Double,
    val y: Double,
    val z: Double,
    val translationX: Float,
    val translationY: Float,
    val translationZ: Float,
)

internal data class OriginScenePropSurface(
    val near: OriginScenePoint,
    val materials: Set<Material>,
    val searchRadius: Int,
    val topOffset: Double,
    val lookTargetOffsetY: Double = -1.15,
) {
    init {
        require(materials.isNotEmpty() && materials.all(Material::isBlock)) { "scene prop surface requires block materials" }
        require(searchRadius in 0..4) { "scene prop surface search radius must be within 0..4" }
        require(topOffset.isFinite() && topOffset in 0.0..2.0) { "scene prop surface top offset must be within 0..2" }
        require(lookTargetOffsetY.isFinite() && lookTargetOffsetY in -4.0..2.0) {
            "scene prop surface look target offset must be within -4..2"
        }
    }

    fun resolve(world: World): OriginScenePoint? {
        val centerX = floor(near.x).toInt()
        val centerY = floor(near.y).toInt()
        val centerZ = floor(near.z).toInt()
        return buildList {
            for (x in centerX - searchRadius..centerX + searchRadius) {
                for (y in centerY - searchRadius..centerY + searchRadius) {
                    for (z in centerZ - searchRadius..centerZ + searchRadius) {
                        if (world.getBlockAt(x, y, z).type in materials) add(pointFor(x, y, z))
                    }
                }
            }
        }.minWithOrNull(compareBy<OriginScenePoint> { candidate ->
            val dx = candidate.x - near.x
            val dy = candidate.y - near.y
            val dz = candidate.z - near.z
            dx * dx + dy * dy + dz * dz
        }.thenBy(OriginScenePoint::x).thenBy(OriginScenePoint::y).thenBy(OriginScenePoint::z))
    }

    fun pointFor(blockX: Int, blockY: Int, blockZ: Int): OriginScenePoint =
        OriginScenePoint(blockX + 0.5, blockY + topOffset, blockZ + 0.5)

    fun lookTarget(point: OriginScenePoint): OriginScenePoint = point.copy(y = point.y + lookTargetOffsetY)
}

/**
 * Small scene-prop interface shared by config validation, execution and tests.
 * Anchors remain world-space facts; pivot math is owned here rather than copied
 * into individual scenes.
 */
internal object OriginScenePropContract {
    fun resolve(
        anchor: OriginScenePoint,
        origin: OriginScenePropOrigin,
        offset: OriginSceneVector,
        scale: OriginSceneVector,
        rotationYDegrees: Float = 0f,
    ): OriginSceneResolvedProp {
        scale.requirePositive("scene prop scale")
        offset.requireBounded("scene prop offset", MAX_OFFSET)
        require(rotationYDegrees.isFinite() && rotationYDegrees in -360f..360f) {
            "scene prop rotation-y-degrees must be within -360..360"
        }
        val radians = Math.toRadians(rotationYDegrees.toDouble())
        val halfX = scale.x / 2.0
        val halfZ = scale.z / 2.0
        val rotatedHalfX = cos(radians) * halfX + sin(radians) * halfZ
        val rotatedHalfZ = -sin(radians) * halfX + cos(radians) * halfZ
        val translationY = when (origin) {
            OriginScenePropOrigin.BOTTOM_CENTER -> 0f
            OriginScenePropOrigin.CENTER -> (-scale.y / 2.0).toFloat()
        }
        return OriginSceneResolvedProp(
            x = anchor.x + offset.x,
            y = anchor.y + offset.y,
            z = anchor.z + offset.z,
            translationX = (-rotatedHalfX).toFloat(),
            translationY = translationY,
            translationZ = (-rotatedHalfZ).toFloat(),
        )
    }

    fun validateLifecycle(steps: List<OriginSceneStep>, stepContext: (Int) -> String = { index -> "step[$index]" }) {
        val visible = mutableSetOf<String>()
        steps.forEachIndexed { index, step ->
            val context = stepContext(index)
            when (step) {
                is OriginSceneStep.BlockDisplay -> {
                    require(step.key.isNotBlank()) { "$context prop key must not be blank" }
                    visible += step.key
                }
                is OriginSceneStep.RemoveDisplay -> require(visible.remove(step.key)) {
                    "$context prop ${step.key} is removed before it is created"
                }
                else -> Unit
            }
        }
        require(visible.isEmpty()) {
            val context = if (steps.isEmpty()) "scene props" else "${stepContext(steps.lastIndex)} props"
            "$context must be explicitly removed: ${visible.sorted().joinToString(",")}"
        }
    }

    private const val MAX_OFFSET = 16.0
}
