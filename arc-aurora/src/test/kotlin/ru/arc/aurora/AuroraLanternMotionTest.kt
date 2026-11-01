package ru.arc.aurora

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.joml.Quaternionf
import org.joml.Vector3f
import java.io.File
import kotlin.math.abs
import kotlin.math.acos

class AuroraLanternMotionTest : FreeSpec({
    "one to six stable lanterns rise continuously with the same wind" {
        for (count in 1..6) {
            val frames = AuroraLanternMotion.frames(count)
            frames.size shouldBe 33
            frames.all { it.size == count } shouldBe true
            frames.first().all { it.opacity == 0.0 } shouldBe true
            frames.last().all { it.opacity == 0.0 } shouldBe true
            for (index in 0 until count) {
                val start = frames.first()[index].offset
                val end = frames.last()[index].offset
                (abs((end.y - start.y) * 4.0 - 9.0) < 1e-9) shouldBe true
                (abs((end.x - start.x) * 4.0 - 3.0) < 1e-9) shouldBe true
                (abs(end.z - start.z) < 1e-9) shouldBe true
                frames.zipWithNext().all { (a, b) -> b[index].offset.y >= a[index].offset.y } shouldBe true
                frames.all { it[index].opacity in 0.0..1.0 && it[index].paletteSlot == 0 } shouldBe true
            }
        }
        exportLanternPreview()
    }

    "the procession begins in sequence and keeps the model pivot at its flight center" {
        (AuroraLanternMotion.visibility(0, 0.04) > 0.0) shouldBe true
        AuroraLanternMotion.visibility(1, 0.04) shouldBe 0.0
        AuroraLanternMotion.visibility(2, 0.09) shouldBe 0.0
        val pivot = AuroraOffset(0.0, 0.53125, 0.5)
        for (index in 0..5) for (tick in 0..600 step 4) {
            val pose = AuroraLanternMotion.displayPose(index, tick / 600.0, 1.7, 1.0, pivot)
            val rotation = Quaternionf(pose.rotationX, pose.rotationY, pose.rotationZ, pose.rotationW)
            val center = rotation.transform(Vector3f(pivot.x.toFloat(), pivot.y.toFloat(), pivot.z.toFloat())).mul(pose.scale)
                .add(pose.translation.x.toFloat(), pose.translation.y.toFloat(), pose.translation.z.toFloat())
            (center.length() < 1e-6) shouldBe true
            val up = rotation.transform(Vector3f(0f, 1f, 0f))
            (acos(up.y.toDouble().coerceIn(-1.0, 1.0)) <= Math.toRadians(5.0)) shouldBe true
            (pose.scale in 0f..1f) shouldBe true
        }
        // The last pose actually sent at update-ticks=4 is already negligible before retirement.
        (AuroraLanternMotion.displayPose(0, 596.0 / 600.0, 0.0, 1.0, pivot).scale < 0.01f) shouldBe true
    }

    "invalid lantern requests fail before allocating motion" {
        listOf(0, 7).forEach { count -> shouldThrow<IllegalArgumentException> { AuroraLanternMotion.frames(count) } }
        shouldThrow<IllegalArgumentException> { AuroraLanternMotion.visibility(6, 0.5) }
        shouldThrow<IllegalArgumentException> { AuroraLanternMotion.visibility(0, Double.NaN) }
    }
})

/** Export exact sampled centers and renderer poses, without a second animation implementation. */
private fun exportLanternPreview() {
    val output = System.getenv("AURORA_LANTERN_PREVIEW_DIR") ?: return
    val directory = File(output).also { it.mkdirs() }
    val frames = AuroraLanternMotion.frames(3)
    val pivot = AuroraOffset(0.0, 0.53125, 0.5)
    val samples = (0..600 step 4).joinToString(prefix = "[", postfix = "]") { tick ->
        val progress = (tick / 599.0).coerceIn(0.0, 1.0)
        val parts = (0..2).joinToString(prefix = "[", postfix = "]") { index ->
            val center = AuroraCelestialShapes.pointAt(frames, progress, index).offset
            val pose = AuroraLanternMotion.displayPose(index, progress, 0.35, 1.0, pivot)
            "{\"index\":$index,\"center\":[${center.x * 4},${center.y * 4},${center.z * 4}]," +
                "\"translation\":[${pose.translation.x},${pose.translation.y},${pose.translation.z}]," +
                "\"rotation\":[${pose.rotationX},${pose.rotationY},${pose.rotationZ},${pose.rotationW}],\"scale\":${pose.scale}}"
        }
        "{\"tick\":$tick,\"parts\":$parts}"
    }
    File(directory, "lantern-motion.json").writeText("{\"durationTicks\":600,\"updateTicks\":4," +
        "\"model\":\"chinese_lamp_lantern\",\"pivot\":[0,0.53125,0.5],\"frames\":$samples}")
}
