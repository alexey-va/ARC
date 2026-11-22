package ru.arc.playerseat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.abs
import kotlin.math.tan
import kotlin.random.Random

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
            val pose = SeatDirectionPose(1.8f, SeatArrowStyle.COMPASS, SeatDirectionPalette.ROSE)
            val heading = seatHeadingRotation(yaw)
            val (shaft, left, right) = pose.arrow.filter { it.material == Material.NETHER_BRICKS }.map { it.transform.turned(heading) }
            val tail = shaft.point(.5f, .5f, 0f)
            val tip = left.point(1f, .5f, 1f)
            val shaftDirection = Vector3f(tip).sub(tail)
            shaftDirection.y = 0f
            shaftDirection.normalize().shouldMatch(forward)
            right.point(0f, .5f, 1f).shouldMatch(tip)
            left.point(1f, .5f, 0f).shouldMatch(right.point(0f, .5f, 0f))
            (left.point(0f, .5f, 0f).distance(right.point(1f, .5f, 0f)) > 1.5f) shouldBe true
            (tail.dot(forward) > 0f) shouldBe true

            val caption = pose.caption.turned(heading)
            val captionOffset = Vector3f(caption.translation)
            (captionOffset.y > tip.y) shouldBe true
            captionOffset.y = 0f
            captionOffset.normalize().shouldMatch(forward)
            caption.leftRotation.transform(Vector3f(0f, 0f, 1f)).shouldMatch(Vector3f(forward).negate())
        }
    }

    "the arrow points forward on screen for both players looking straight ahead at standard FOV" {
        val height = 1.8f
        val pose = SeatDirectionPose(height, SeatArrowStyle.COMPASS, SeatDirectionPalette.ROSE)
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

    "the three gestures cycle through distinct meshes using the same seven block handles" {
        val rotation = generateSequence(SeatArrowStyle.COMPASS) { it.next() }.take(4).toList()
        rotation shouldBe listOf(SeatArrowStyle.COMPASS, SeatArrowStyle.CHEVRONS, SeatArrowStyle.FLETCHED, SeatArrowStyle.COMPASS)
        val poses = rotation.map { SeatDirectionPose(1.8f, it, SeatDirectionPalette.ROSE) }
        poses.map { it.arrow.size }.distinct() shouldBe listOf(7)
        poses.map { it.arrow.map(SeatArrowPart::transform) }.distinct().size shouldBe 3
        poses.map(SeatDirectionPose::caption).distinct().size shouldBe 1
    }

    "all three silhouettes stay inside the vertical field of view and below their caption for both players" {
        val height = 1.8f
        val limit = tan(Math.toRadians(35.0)).toFloat()
        for (style in SeatArrowStyle.entries) {
            val pose = SeatDirectionPose(height, style, SeatDirectionPalette.ROSE)
            for (eye in listOf(1.62f, 3.38f)) {
                val caption = pose.caption.translation
                val captionBottom = (height + caption.y - eye) / caption.z
                for (part in pose.arrow) {
                    for (x in 0..1) for (y in 0..1) for (z in 0..1) {
                        val corner = part.transform.point(x.toFloat(), y.toFloat(), z.toFloat())
                        val screenY = (height + corner.y - eye) / corner.z
                        (screenY in -limit..(captionBottom - .005f)) shouldBe true
                    }
                }
            }
        }
    }

    "heading follows the short arc through the seam and completes a stationary half turn" {
        seatTurnYaw(179f, -179f) shouldBe 181f
        seatTurnYaw(-179f, 179f) shouldBe -181f
        val caption = SeatDirectionPose(1.8f, SeatArrowStyle.COMPASS, SeatDirectionPalette.ROSE).caption
        for ((start, target) in listOf(0f to 180f, 170f to -173f, -178f to 179f, 715f to 10f, -711f to -18f)) {
            var current = start
            repeat(6) {
                val next = seatTurnYaw(current, target)
                (abs(Location.normalizeYaw(next - current)) <= 30f) shouldBe true
                val a = seatHeadingRotation(current)
                val b = seatHeadingRotation(next)
                (a.dot(b) > .96f) shouldBe true
                // The native client interpolates local translation linearly between keyframes.
                val middle = Vector3f(caption.turned(a).translation).lerp(caption.turned(b).translation, .5f)
                (middle.length() > 5.6f) shouldBe true
                current = next
            }
            Location.normalizeYaw(target - current) shouldBe 0f
            seatTurnYaw(current, target) shouldBe current
        }
    }

    "any phrase and arrow shape can use each palette without changing its wording or geometry" {
        val phrases = listOf("Туда Нахуй!", "Пиздуй туда!", "Живее двигай!", "Газуй туда!", "Давай, гони!")
        for (phrase in phrases) {
            val leads = SeatDirectionPalette.entries.map { palette ->
                val caption = palette.caption(phrase)
                PlainTextComponentSerializer.plainText().serialize(caption) shouldBe phrase
                caption.decoration(TextDecoration.BOLD) shouldBe TextDecoration.State.TRUE
                (caption.children().single().color() != caption.color()) shouldBe true
                caption.color()
            }
            leads.distinct().size shouldBe SeatDirectionPalette.entries.size
        }
        for (style in SeatArrowStyle.entries) {
            val poses = SeatDirectionPalette.entries.map { palette ->
                SeatDirectionPose(1.8f, style, palette).also { pose ->
                    pose.arrow.map { it.material }.toSet() shouldBe setOf(palette.rim, palette.spine, palette.face, palette.shade)
                }
            }
            poses.map { pose -> pose.arrow.map { it.transform } }.distinct().size shouldBe 1
            poses.map { pose -> pose.arrow.map { it.material } }.distinct().size shouldBe poses.size
        }
    }

    "the first gesture can draw any palette and subsequent draws can use every palette except the previous one" {
        val random = Random(556)
        val palettes = SeatDirectionPalette.entries.toSet()
        List(160) { SeatDirectionPalette.pick(null, random) }.toSet() shouldBe palettes
        for (previous in palettes) {
            List(160) { SeatDirectionPalette.pick(previous, random) }.toSet() shouldBe palettes - previous
        }
    }

    "only an explicit gesture recolors the existing arrow and caption while turning and height changes preserve them" {
        val displays = mockk<PaperPacketDisplays>(relaxed = true)
        val blocks = mutableListOf<PacketBlockDisplay>()
        val captions = mutableListOf<Component>()
        val label = mockk<PacketTextDisplay>(relaxed = true)
        every { displays.spawnBlock(any(), any()) } answers { mockk<PacketBlockDisplay>(relaxed = true).also(blocks::add) }
        every { displays.spawnText(any(), capture(captions)) } returns label
        every { label.text(capture(captions)) } returns Unit
        val rider = mockk<Player> { every { uniqueId } returns UUID.randomUUID() }
        val world = mockk<World>()
        val carrier = mockk<Player> {
            every { uniqueId } returns UUID.randomUUID()
            every { location } returns Location(world, 0.0, 64.0, 0.0)
            every { height } returns 1.8
        }
        val writes = mutableListOf<Material>()
        mockkStatic(Bukkit::class) {
            every { Bukkit.createBlockData(capture(writes)) } returns mockk<BlockData>()
            SeatDirectionDisplay(displays).use { display ->
                display.show(carrier, rider, 0f, "Туда Нахуй!")
                writes.size shouldBe 7
                repeat(8) { display.turn(carrier, rider, 180f) }
                every { carrier.height } returns 1.5
                display.turn(carrier, rider, 180f)
                writes.size shouldBe 7
                captions.size shouldBe 1
                display.show(carrier, rider, 180f, "Туда Нахуй!")
                (writes.size > 7) shouldBe true
                captions.size shouldBe 2
                (captions[0].color() != captions[1].color()) shouldBe true
                val afterGesture = writes.size
                display.turn(carrier, rider, -179f)
                writes.size shouldBe afterGesture
                blocks.size shouldBe 7
                verify(exactly = 1) { displays.spawnText(any(), any()) }
                blocks.forEach { verify(exactly = 0) { it.remove() } }
            }
        }
    }
})

private fun Transformation.point(x: Float, y: Float, z: Float): Vector3f {
    val local = rightRotation.transform(Vector3f(x, y, z)).mul(scale)
    return leftRotation.transform(local).add(translation)
}

private fun Vector3f.shouldMatch(expected: Vector3f) {
    distance(expected).toDouble() shouldBeLessThanOrEqual .00001
}
