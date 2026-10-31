package ru.arc.enchanting

import org.bukkit.block.BlockFace
import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f

internal enum class EnchantmentFindSource { MINING, FARMING }
internal enum class EnchantmentFindReward { LOOTBOX, KEY }

/** World-axis translation from the host's centre; rotation precedes the native item Y correction. */
internal data class EnchantmentFindPose(
    val offset: Vector3f,
    val rotation: Quaternionf,
    val scale: Float,
)

/** Shared by the packet renderer and the source-driven visual export. No animation or particles. */
internal object EnchantmentFindGeometry {
    fun mining(face: BlockFace, reward: EnchantmentFindReward): List<EnchantmentFindPose> {
        require(face in FACES) { "A mining find needs one exposed cardinal block face" }
        val normal = Vector3f(face.modX.toFloat(), face.modY.toFloat(), face.modZ.toFloat())
        // A vanilla chest's latch faces -Z; keep that face toward the newly opened space.
        val surface = Quaternionf().rotationTo(Vector3f(0f, 0f, -1f), normal)
        fun pose(x: Float, y: Float, depth: Float, scale: Float, pitch: Float, yaw: Float, roll: Float): EnchantmentFindPose {
            val offset = surface.transform(Vector3f(x, y, -depth))
            val rotation = Quaternionf(surface).mul(Quaternionf().rotationXYZ(radians(pitch), radians(yaw), radians(roll)))
            return EnchantmentFindPose(offset, rotation, scale)
        }
        return when (reward) {
            EnchantmentFindReward.LOOTBOX -> listOf(
                pose(-0.18f, 0.12f, 0.49f, 0.43f, -12f, 12f, -15f),
                pose(0.22f, -0.20f, 0.49f, 0.29f, 10f, -18f, 18f),
            )
            // The native daily key's broad silhouette lies in its YZ plane, not the chest's XY plane.
            EnchantmentFindReward.KEY -> listOf(pose(0f, 0f, 0.515f, 0.88f, 0f, -9f, -22f).also {
                it.rotation.rotateY(radians(90f))
            })
        }
    }

    /** 26.1.2 block/crop mesh top is 15/16; subtract each mature texture's transparent top rows. */
    fun cropVisualTop(material: Material): Float? = when (material) {
        Material.WHEAT, Material.POTATOES -> 15f / 16f
        Material.CARROTS -> 12f / 16f
        Material.BEETROOTS -> 9f / 16f
        Material.NETHER_WART -> 13f / 16f
        else -> null
    }

    /** Hang near the actual visible plant top, not the often shorter crop collision box. */
    fun farming(cropTop: Float, reward: EnchantmentFindReward, face: BlockFace = BlockFace.NORTH): List<EnchantmentFindPose> {
        require(cropTop.isFinite() && cropTop in 0f..1f)
        val yaw = when (face) {
            BlockFace.NORTH -> 0f
            BlockFace.EAST -> -90f
            BlockFace.SOUTH -> 180f
            BlockFace.WEST -> 90f
            else -> throw IllegalArgumentException("A crop find needs a horizontal exposed face")
        }
        val surface = Quaternionf().rotationY(radians(yaw))
        val key = reward == EnchantmentFindReward.KEY
        val scale = if (key) 0.68f else 0.34f
        val rotation = if (key) Quaternionf().rotationXYZ(radians(-8f), radians(18f), radians(-62f)).rotateY(radians(90f))
            else Quaternionf().rotationXYZ(radians(-8f), radians(24f), radians(-12f))
        return listOf(EnchantmentFindPose(
            surface.transform(Vector3f(0f, cropTop - 0.5f - 0.13f, if (key) -0.20f else -0.10f)),
            Quaternionf(surface).mul(rotation),
            scale,
        ))
    }

    private fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()
    private val FACES = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)
}
