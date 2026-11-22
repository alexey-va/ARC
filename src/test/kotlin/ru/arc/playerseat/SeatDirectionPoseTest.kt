package ru.arc.playerseat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.util.Transformation
import org.joml.Vector3f
import kotlin.math.tan

class SeatDirectionPoseTest : StringSpec({
    val headings = mapOf(
        0f to Vector3f(0f, 0f, 1f),
        90f to Vector3f(-1f, 0f, 0f),
        180f to Vector3f(0f, 0f, -1f),
        -90f to Vector3f(1f, 0f, 0f),
        405f to Vector3f(-1f, 0f, 1f).normalize(),
    )
    for ((yaw, forward) in headings) {
        "the compass wings meet at one tip on Bukkit yaw $yaw and the caption faces the riders" {
            val pose = SeatDirectionPose(yaw, 1.8f)
            val (shaft, left, right) = pose.arrow.filter { it.material == Material.NETHER_BRICKS }.map { it.transform }
            val tail = shaft.point(.5f, .5f, 0f)
            val tip = left.point(1f, .5f, 1f)
            val shaftDirection = Vector3f(tip).sub(tail)
            shaftDirection.y = 0f
            shaftDirection.normalize().shouldMatch(forward)
            right.point(0f, .5f, 1f).shouldMatch(tip)
            left.point(1f, .5f, 0f).shouldMatch(right.point(0f, .5f, 0f))
            (left.point(0f, .5f, 0f).distance(right.point(1f, .5f, 0f)) > 1.5f) shouldBe true
            (tail.dot(forward) > 0f) shouldBe true

            val captionOffset = Vector3f(pose.caption.translation)
            (captionOffset.y > tip.y) shouldBe true
            captionOffset.y = 0f
            captionOffset.normalize().shouldMatch(forward)
            pose.caption.leftRotation.transform(Vector3f(0f, 0f, 1f)).shouldMatch(Vector3f(forward).negate())
        }
    }

    "the arrow points forward on screen for both players looking straight ahead at standard FOV" {
        val height = 1.8f
        val pose = SeatDirectionPose(0f, height)
        val (shaft, left) = pose.arrow.filter { it.material == Material.NETHER_BRICKS }.map { it.transform }
        val halfFov = tan(Math.toRadians(35.0)).toFloat()
        for (eyeHeight in listOf(1.62f, 3.38f)) {
            fun screenY(p: Vector3f) = (height + p.y - eyeHeight) / p.z
            val tail = screenY(shaft.point(.5f, .5f, 0f))
            val tip = screenY(left.point(1f, .5f, 1f))
            (tail in -halfFov..halfFov) shouldBe true
            (tip > tail) shouldBe true
            (screenY(pose.caption.translation) in tip..halfFov) shouldBe true
        }
    }

    "the five captions keep their exact words in bold, with a distinct lead and accent" {
        val phrases = listOf("Туда Нахуй!", "Пиздуй туда!", "Живее двигай!", "Газуй туда!", "Давай, шевелись!")
        val leads = phrases.map { phrase ->
            val caption = seatDirectionCaption(phrase)
            PlainTextComponentSerializer.plainText().serialize(caption) shouldBe phrase
            caption.decoration(TextDecoration.BOLD) shouldBe TextDecoration.State.TRUE
            (caption.children().single().color() != caption.color()) shouldBe true
            caption.color()
        }
        leads.distinct().size shouldBe phrases.size
    }
})

private fun Transformation.point(x: Float, y: Float, z: Float): Vector3f {
    val local = rightRotation.transform(Vector3f(x, y, z)).mul(scale)
    return leftRotation.transform(local).add(translation)
}

private fun Vector3f.shouldMatch(expected: Vector3f) {
    distance(expected).toDouble() shouldBeLessThanOrEqual .00001
}
