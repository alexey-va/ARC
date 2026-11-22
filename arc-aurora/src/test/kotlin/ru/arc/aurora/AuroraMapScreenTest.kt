package ru.arc.aurora

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.api.VisualPacketAdmission

class AuroraMapScreenTest : StringSpec({
    "image traversal preserves signed palette bytes and trims transparent tile margins" {
        val image = ByteArray(AuroraMapAsset.WIDTH * AuroraMapAsset.HEIGHT)
        image[126 * AuroraMapAsset.WIDTH + 127] = -3
        image[127 * AuroraMapAsset.WIDTH + 126] = 57
        image[128 * AuroraMapAsset.WIDTH + 128] = 19
        image[image.lastIndex] = 4

        val tiles = AuroraMapAsset.read(image.inputStream())
        tiles.map { it.column to it.row } shouldBe listOf(0 to 0, 1 to 1, 7 to 11)
        tiles[0].apply {
            listOf(left, top, width, height) shouldBe listOf(126, 126, 2, 2)
            colors.toList() shouldBe listOf<Byte>(0, -3, 57, 0)
        }
        tiles[1].apply {
            listOf(left, top, width, height) shouldBe listOf(0, 0, 1, 1)
            colors.single() shouldBe 19.toByte()
        }
        tiles.last().apply {
            listOf(left, top, width, height) shouldBe listOf(127, 127, 1, 1)
            colors.single() shouldBe 4.toByte()
        }
    }

    "asset reader requires exactly one nonempty panel" {
        val count = AuroraMapAsset.WIDTH * AuroraMapAsset.HEIGHT
        for (length in listOf(count - 1, count, count + 1)) {
            shouldThrow<IllegalArgumentException> { AuroraMapAsset.read(ByteArray(length).inputStream()) }
        }
        val actual = requireNotNull(AuroraMapAsset::class.java.getResourceAsStream("/maps/sakura-idol.bin"))
            .use(AuroraMapAsset::read)
        (actual.size in 1..96) shouldBe true
    }

    "every mounting face puts increasing columns on the viewer's right and top rows above bottom rows" {
        val south = AuroraMapPlacement.inFrontOf(0.0, 64.0, 0.0, 0f)
        val west = AuroraMapPlacement.inFrontOf(0.0, 64.0, 0.0, 90f)
        val north = AuroraMapPlacement.inFrontOf(0.0, 64.0, 0.0, 180f)
        val east = AuroraMapPlacement.inFrontOf(0.0, 64.0, 0.0, -90f)

        south.face shouldBe AuroraMapFace.NORTH
        south.position(0, 0) shouldBe AuroraMapPosition(4, 84, 32)
        south.position(7, 11) shouldBe AuroraMapPosition(-3, 73, 32)
        west.face shouldBe AuroraMapFace.EAST
        west.position(0, 0) shouldBe AuroraMapPosition(-33, 84, 4)
        west.position(7, 11) shouldBe AuroraMapPosition(-33, 73, -3)
        north.face shouldBe AuroraMapFace.SOUTH
        north.position(0, 0) shouldBe AuroraMapPosition(-4, 84, -33)
        north.position(7, 11) shouldBe AuroraMapPosition(3, 73, -33)
        east.face shouldBe AuroraMapFace.WEST
        east.position(0, 0) shouldBe AuroraMapPosition(32, 84, -4)
        east.position(7, 11) shouldBe AuroraMapPosition(32, 73, 3)

        north.above(100, -64, 320)?.position(7, 11) shouldBe AuroraMapPosition(3, 102, -33)
        north.above(308, -64, 320) shouldBe null
    }

    "rate deferral retries only the unsent tile and a large panel yields between batches" {
        val fixture = MapDeliveryFixture(9)
        fixture.tileAdmissions.addAll(listOf(VisualPacketAdmission.ALLOWED, VisualPacketAdmission.VIEWER_RATE))
        fixture.delivery.start()
        fixture.step()

        fixture.attempted shouldBe listOf(0, 1)
        fixture.loop.size shouldBe 1
        fixture.step()
        fixture.attempted shouldBe listOf(0, 1, 1, 2, 3, 4)
        fixture.step()
        fixture.attempted shouldBe listOf(0, 1, 1, 2, 3, 4, 5, 6, 7, 8)
        fixture.flushed shouldBe 3
        fixture.loop.size shouldBe 0
    }

    "close during an admitted write destroys only the submitted prefix and awaits cleanup backpressure" {
        val fixture = MapDeliveryFixture(96)
        fixture.onSend = { index -> if (index == 1) fixture.delivery.retire() }
        fixture.cleanupAdmissions.add(VisualPacketAdmission.CHANNEL_BACKPRESSURE)
        fixture.delivery.start()
        fixture.step()

        val completed = fixture.delivery.closeAsync()
        fixture.attempted shouldBe listOf(0, 1)
        fixture.step()
        fixture.destroyed shouldBe listOf(2)
        completed.isDone shouldBe false
        fixture.step()
        fixture.destroyed shouldBe listOf(2, 2)
        completed.isDone shouldBe true
        completed.isCompletedExceptionally shouldBe false
        fixture.loop.size shouldBe 0
    }

    "partial send failure also destroys the uncertain frame before reporting the failure" {
        val failure = IllegalStateException("partly written")
        val fixture = MapDeliveryFixture(4)
        fixture.onSend = { index -> if (index == 1) throw failure }
        fixture.delivery.start()
        fixture.step()
        fixture.step()

        fixture.attempted shouldBe listOf(0, 1)
        fixture.destroyed shouldBe listOf(2)
        fixture.failures shouldBe listOf(failure)
        fixture.delivery.closeAsync().isCompletedExceptionally shouldBe true
        fixture.loop.size shouldBe 0
    }

    "disconnect retires pending delivery without sending to a replaced channel" {
        val fixture = MapDeliveryFixture(96)
        fixture.delivery.start()
        fixture.step()
        fixture.open = false
        val closed = fixture.delivery.closeAsync()
        fixture.step()

        fixture.attempted shouldBe listOf(0, 1, 2, 3)
        fixture.destroyed shouldBe emptyList()
        closed.isDone shouldBe true
        fixture.loop.size shouldBe 0
    }
})

private class MapDeliveryFixture(tileCount: Int) {
    val loop = ArrayDeque<() -> Unit>()
    val attempted = mutableListOf<Int>()
    val destroyed = mutableListOf<Int>()
    val failures = mutableListOf<Exception>()
    val tileAdmissions = ArrayDeque<VisualPacketAdmission>()
    val cleanupAdmissions = ArrayDeque<VisualPacketAdmission>()
    var onSend: (Int) -> Unit = {}
    var open = true
    var flushed = 0

    val delivery = AuroraMapDelivery(tileCount,
        sendTile = {
            attempted += it
            onSend(it)
            tileAdmissions.removeFirstOrNull() ?: VisualPacketAdmission.ALLOWED
        },
        destroy = {
            destroyed += it
            cleanupAdmissions.removeFirstOrNull() ?: VisualPacketAdmission.ALLOWED
        },
        enqueue = loop::addLast,
        retry = loop::addLast,
        flush = { flushed++ },
        isOpen = { open },
        reportFailure = { failures += it },
    )

    fun step() = loop.removeFirst().invoke()
}
