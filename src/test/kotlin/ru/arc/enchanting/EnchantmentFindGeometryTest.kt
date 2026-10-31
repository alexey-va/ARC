package ru.arc.enchanting

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.block.BlockFace
import org.bukkit.Material
import org.joml.Vector3f
import java.nio.file.Files
import java.nio.file.Path

class EnchantmentFindGeometryTest : StringSpec({
    "both mining chests cross their supporting plane on every face" {
        for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            val normal = Vector3f(face.modX.toFloat(), face.modY.toFloat(), face.modZ.toFloat())
            val poses = EnchantmentFindGeometry.mining(face, EnchantmentFindReward.LOOTBOX)
            poses.size shouldBe 2
            for (pose in poses) {
                val depths = buildList {
                    // Closed vanilla chest body, after item-centre translation.
                    for (x in listOf(-0.4375f, 0.4375f)) for (y in listOf(-0.5f, 0.375f)) for (z in listOf(-0.4375f, 0.4375f)) {
                        add(Vector3f(x, y, z).mul(pose.scale).rotate(pose.rotation).add(pose.offset).dot(normal))
                    }
                }
                (depths.min() < 0.5f && depths.max() > 0.5f) shouldBe true
                (depths.max() < 0.8f) shouldBe true
            }
        }

        System.getenv("ENCHANTMENT_FIND_PREVIEW_OUTPUT")?.let { destination ->
            fun export(poses: List<EnchantmentFindPose>) = poses.map { pose ->
                val angles = pose.rotation.getEulerAnglesXYZ(Vector3f())
                mapOf(
                    "x" to pose.offset.x, "y" to pose.offset.y, "z" to pose.offset.z,
                    "scale" to pose.scale,
                    "rotationX" to Math.toDegrees(angles.x.toDouble()),
                    "rotationY" to Math.toDegrees(angles.y.toDouble()),
                    "rotationZ" to Math.toDegrees(angles.z.toDouble()),
                )
            }
            val output = Path.of(destination)
            Files.createDirectories(output.parent)
            Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "source" to "EnchantmentFindGeometry: actual packet-renderer poses; vanilla CHEST in NONE context",
                "mining" to export(EnchantmentFindGeometry.mining(BlockFace.NORTH, EnchantmentFindReward.LOOTBOX)),
                "farming" to export(EnchantmentFindGeometry.farming(EnchantmentFindGeometry.cropVisualTop(Material.WHEAT)!!, EnchantmentFindReward.LOOTBOX)),
                "shortCrop" to export(EnchantmentFindGeometry.farming(EnchantmentFindGeometry.cropVisualTop(Material.POTATOES)!!, EnchantmentFindReward.LOOTBOX)),
                "keyMining" to export(EnchantmentFindGeometry.mining(BlockFace.NORTH, EnchantmentFindReward.KEY)),
                "keyFarming" to export(EnchantmentFindGeometry.farming(EnchantmentFindGeometry.cropVisualTop(Material.WHEAT)!!, EnchantmentFindReward.KEY)),
                "keyFarmingWest" to export(EnchantmentFindGeometry.farming(EnchantmentFindGeometry.cropVisualTop(Material.WHEAT)!!, EnchantmentFindReward.KEY, BlockFace.WEST)),
            )))
        }
    }

    "crop props follow actual visible texture tops rather than collision heights" {
        val wheat = EnchantmentFindGeometry.cropVisualTop(Material.WHEAT)!!
        EnchantmentFindGeometry.cropVisualTop(Material.POTATOES)!! shouldBe wheat
        EnchantmentFindGeometry.cropVisualTop(Material.BEETROOTS)!! shouldBe 9f / 16f
        EnchantmentFindGeometry.cropVisualTop(Material.CARROTS)!! shouldBe 12f / 16f
        EnchantmentFindGeometry.cropVisualTop(Material.NETHER_WART)!! shouldBe 13f / 16f
        EnchantmentFindGeometry.cropVisualTop(Material.COCOA) shouldBe null
        EnchantmentFindGeometry.mining(BlockFace.NORTH, EnchantmentFindReward.KEY).size shouldBe 1
    }

    "the native daily key presents its broad side toward the exposed stone face" {
        for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            val normal = Vector3f(face.modX.toFloat(), face.modY.toFloat(), face.modZ.toFloat())
            val pose = EnchantmentFindGeometry.mining(face, EnchantmentFindReward.KEY).single()
            (Vector3f(1f, 0f, 0f).rotate(pose.rotation).dot(normal) > .9f) shouldBe true
        }
    }

    "crop finds keep their hanging height and face the harvested gap in all four directions" {
        for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            val normal = Vector3f(face.modX.toFloat(), 0f, face.modZ.toFloat())
            val pose = EnchantmentFindGeometry.farming(15f / 16f, EnchantmentFindReward.KEY, face).single()
            (Vector3f(1f, 0f, 0f).rotate(pose.rotation).dot(normal) > .9f) shouldBe true
            (pose.offset.dot(normal) > .19f) shouldBe true
            (kotlin.math.abs(pose.offset.y - .3075f) < .0001f) shouldBe true
        }
    }
})
