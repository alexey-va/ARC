package ru.arc.origin

import org.bukkit.Material
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** ItemsAdder board models are the single visible render for each carpenter workpiece state. */
internal enum class OriginWorkshopBoardModel(val itemId: String) {
    RAW("arc_workshop:board_raw"),
    CUT_ONCE("arc_workshop:board_cut_once"),
    CUT("arc_workshop:board_cut"),
    DRILLED_1("arc_workshop:board_drilled_1"),
    DRILLED_2("arc_workshop:board_drilled_2"),
    DRILLED_3("arc_workshop:board_drilled_3"),
    OFFCUT("arc_workshop:board_offcut"),
}

/** ItemDisplay has a native Y+180 turn; cancel it once while preserving the board's world rotation. */
internal fun originWorkshopBoardItemDisplayRotation(modelRotation: Quaternionf): Quaternionf =
    Quaternionf(modelRotation).rotateY(Math.PI.toFloat())

/** Unit-cube transform for one pure analytical board piece at a centered world pose. */
internal fun originWorkshopWorkpieceHitboxMatrix(
    center: OriginWorkshopPoint,
    rotation: Quaternionf,
    piece: OriginWorkshopWorkpiecePiece,
): Matrix4f {
    val size = piece.size
    val offset = rotation.transform(Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat()))
    val half = rotation.transform(Vector3f(size.x / 2f, size.y / 2f, size.z / 2f))
    return Matrix4f().translation(
        center.x.toFloat() + offset.x - half.x,
        center.y.toFloat() + offset.y - half.y,
        center.z.toFloat() + offset.z - half.z,
    ).rotate(rotation).scale(size.x, size.y, size.z)
}

/** A board-local cuboid; center coordinates are relative to the centered workpiece. */
internal data class OriginWorkshopWorkpiecePiece(
    val center: OriginWorkshopPoint,
    val size: OriginWorkshopGamePartSize,
    val material: Material,
)

/** Center offset is relative to the player's feet; rotationRadians is a JOML rotationY angle. */
internal data class OriginWorkshopShoulderPose(
    val center: OriginWorkshopPoint,
    val rotationRadians: Double,
)

/** Returns the centered sawn board, with the requested progressive through-holes. */
internal fun originWorkshopBoardPieces(holes: Int): List<OriginWorkshopWorkpiecePiece> {
    require(holes in 0..DRILL_HOLE_X.size) { "workshop board supports 0..${DRILL_HOLE_X.size} drill holes" }
    if (holes == 0) return listOf(boardPiece(length = SAWN_LENGTH))

    val pieces = mutableListOf<OriginWorkshopWorkpiecePiece>()
    var cursorX = -SAWN_LENGTH / 2.0
    for (holeX in DRILL_HOLE_X.take(holes)) {
        val holeStart = holeX - HOLE_SIDE / 2.0
        val beforeLength = holeStart - cursorX
        if (beforeLength > 0.0) pieces += boardPiece((cursorX + holeStart) / 2.0, beforeLength)

        for (centerZ in listOf(-SIDE_RAIL_CENTER_Z, SIDE_RAIL_CENTER_Z)) {
            pieces += boardPiece(centerX = holeX, length = HOLE_SIDE, centerZ = centerZ, width = SIDE_RAIL_WIDTH)
        }
        cursorX = holeX + HOLE_SIDE / 2.0
    }

    val afterLength = SAWN_LENGTH / 2.0 - cursorX
    if (afterLength > 0.0) pieces += boardPiece(cursorX + afterLength / 2.0, afterLength)
    return pieces
}

/**
 * Pose for a board resting on the player's right shoulder. Board-local +X follows Bukkit's horizontal
 * forward direction (-sin(yaw), 0, cos(yaw)); apply rotationRadians with JOML rotationY.
 */
internal fun originWorkshopShoulderPose(yawDegrees: Double, sneaking: Boolean): OriginWorkshopShoulderPose {
    require(yawDegrees.isFinite()) { "player yaw must be finite" }
    val yaw = Math.toRadians(yawDegrees)
    val forwardX = -sin(yaw)
    val forwardZ = cos(yaw)
    val rightX = -cos(yaw)
    val rightZ = -sin(yaw)
    val forwardOffset = 0.12
    val rightOffset = 0.36
    return OriginWorkshopShoulderPose(
        center = OriginWorkshopPoint(
            forwardX * forwardOffset + rightX * rightOffset,
            if (sneaking) 1.18 else 1.43,
            forwardZ * forwardOffset + rightZ * rightOffset,
        ),
        rotationRadians = atan2(-forwardZ, forwardX),
    )
}

private fun boardPiece(
    centerX: Double = 0.0,
    length: Double,
    centerZ: Double = 0.0,
    width: Double = BOARD_WIDTH,
) = OriginWorkshopWorkpiecePiece(
    center = OriginWorkshopPoint(centerX, 0.0, centerZ),
    size = OriginWorkshopGamePartSize(length.toFloat(), BOARD_THICKNESS.toFloat(), width.toFloat()),
    material = Material.OAK_PLANKS,
)

private const val BOARD_THICKNESS = 0.08
private const val BOARD_WIDTH = 0.22
private const val SAWN_LENGTH = 0.72
private const val HOLE_SIDE = 0.075
private const val SIDE_RAIL_WIDTH = (BOARD_WIDTH - HOLE_SIDE) / 2.0
private const val SIDE_RAIL_CENTER_Z = HOLE_SIDE / 2.0 + SIDE_RAIL_WIDTH / 2.0
private val DRILL_HOLE_X = listOf(-0.23, 0.0, 0.23)

/** A thin, private outline centered exactly on the accepted loading/coating target. */
internal fun originWorkshopPlacementMarker(coating: Boolean): List<OriginWorkshopWorkpiecePiece> {
    val halfX = if (coating) 0.09f else 0.25f
    val halfZ = if (coating) 0.10f else 0.15f
    return buildList {
        for (x in listOf(-halfX, halfX)) add(OriginWorkshopWorkpiecePiece(
            OriginWorkshopPoint(x.toDouble(), 0.016, 0.0), OriginWorkshopGamePartSize(0.018f, 0.012f, halfZ * 2), Material.CUT_COPPER))
        for (z in listOf(-halfZ, halfZ)) add(OriginWorkshopWorkpiecePiece(
            OriginWorkshopPoint(0.0, 0.016, z.toDouble()), OriginWorkshopGamePartSize(halfX * 2, 0.012f, 0.018f), Material.CUT_COPPER))
    }
}
