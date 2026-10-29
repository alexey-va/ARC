package ru.arc.backwings

import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Pure world-axis pose for ItemDisplay NONE with entity yaw/pitch fixed at zero. The renderer
 * applies those Euler angles before this transformation, so they must not inherit the equip-time
 * gaze. The item renderer centers the JSON model at
 * (8, 8, 8) / 16 before this transform. Core attaches at feet + current native player height;
 * subtract that origin once. Body yaw rotates both anchor and mesh; camera pitch is irrelevant.
 */
internal fun backWingPose(
    style: BackWingStyle,
    bodyYawDegrees: Float,
    crouching: Boolean,
    passengerOriginY: Float,
    crouchOffset: WingVector,
    crouchPitchDegrees: Float,
): Transformation {
    require(bodyYawDegrees.isFinite() && passengerOriginY.isFinite()) { "Player pose must be finite" }
    val radians = (Math.PI / 180.0).toFloat()
    val bodyRotation = Quaternionf().rotationY(-bodyYawDegrees * radians)
    val anchor = Vector3f(style.anchor.x, style.anchor.y, style.anchor.z)
    if (crouching) anchor.add(crouchOffset.x, crouchOffset.y, crouchOffset.z)
    bodyRotation.transform(anchor)
    anchor.y -= passengerOriginY
    val rotation = Quaternionf(bodyRotation)
    if (crouching) rotation.rotateX(crouchPitchDegrees * radians)
    rotation.rotateXYZ(style.rotation.x * radians, style.rotation.y * radians, style.rotation.z * radians)
    return Transformation(anchor, rotation, Vector3f(style.scale), Quaternionf())
}
