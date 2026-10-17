package ru.arc.landsui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.VoxelShape
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class ClaimLandMenuTest : FunSpec({
    aroundTest { (test, execute) ->
        MockBukkitTestRuntime.open().use { execute(test) }
    }
    test("six localized controls keep the Lands footprint and interpolation contract") {
        val labels = LandsUiPanelAction.entries.associateWith { Component.text(it.name) }
        val layout = claimLandMenuLayout(Component.text("Дом"), labels)
        layout.buttons.map { it.action } shouldBe LandsUiPanelAction.entries.toList()
        layout.buttons.forEach {
            it.text shouldBe labels.getValue(it.action)
            it.rect.width shouldBe 1.5
            kotlin.math.abs(it.rect.height - 0.23) shouldBeLessThanOrEqual 1e-8
            it.style.scale shouldBe 0.8f
            it.style.lineWidth shouldBe 190
            it.style.baselineInset shouldBe 0.025
            it.style.interpolationDelay shouldBe -1
            it.style.interpolationDuration shouldBe 2
        }
        layout.buttons.first().rect.center.x shouldBe -0.775
        layout.buttons.last().rect.center.y shouldBe -0.26
        layout.labels.single().let {
            kotlin.math.abs(it.rect.center.y - 0.535) shouldBeLessThanOrEqual 1e-8
            it.rect.width shouldBe 3.0
            it.rect.height shouldBe 0.24
            it.style.scale shouldBe 0.48f
            it.style.displayHeight shouldBe 0.55f
            it.style.lineWidth shouldBe 240
            it.style.baselineInset shouldBe 0.025
        }
        claimLandMenuLayout(Component.empty(), labels).labels.single().visible shouldBe true
    }

    test("shared runtime keeps identities through head turns, tiny body movement and title changes") {
        val fixture = ClaimMenuFixture()
        with(fixture) {
            menu.show(player, "home", "Дом")
            made.size shouldBe 7
            val initial = positions.map { it.clone() }
            eye = eye.clone().apply { yaw = 130f }
            body = body.clone().add(0.02, 0.0, 0.0)
            menu.show(player, "home", "Новый дом")
            made.size shouldBe 7
            positions shouldBe initial
            texts.last() shouldBe Component.text("Новый дом")
            val viewerId = player.uniqueId
            made.forEach { display ->
                verify { display.isVisibleByDefault = false }
                verify { display.showTo(viewerId) }
            }
            menu.contains(player.uniqueId) shouldBe true
            menu.landId(player.uniqueId) shouldBe "home"
            menu.close()
            made.forEach { verify(exactly = 1) { it.remove() } }
            verify(exactly = 1) { packetOwner.close() }
        }
    }

    test("whole-panel gaze keeps its entry and restoration margins while clicks use the actual eye") {
        val fixture = ClaimMenuFixture()
        with(fixture) {
            menu.show(player, "home", "Дом")
            menu.isLookingAt(player) shouldBe true
            eye = eye.clone().apply { yaw = -40f }
            menu.show(player, "home", "Дом")
            menu.isLookingAt(player) shouldBe true
            eye = eye.clone().apply { yaw = -45f }
            menu.show(player, "home", "Дом")
            menu.isLookingAt(player) shouldBe false
            eye = eye.clone().apply { yaw = -40f }
            menu.show(player, "home", "Дом")
            menu.isLookingAt(player) shouldBe false
            val button = positions.first().clone().add(0.0, 0.23 / 2.0 - 0.025, 0.0)
            eye = eye.clone().setDirection(button.toVector().subtract(eye.toVector()))
            menu.target(player) shouldBe LandsUiPanelAction.ADD_MEMBER
            menu.landId(player.uniqueId) shouldBe "home"
            menu.hide(player.uniqueId)
            menu.target(player) shouldBe null
            menu.landId(player.uniqueId) shouldBe null
            menu.isLookingAt(player) shouldBe false
            menu.close()
        }
    }

    test("an unloaded or unsent placement produces no panel and never loads chunks") {
        val fixture = ClaimMenuFixture()
        with(fixture) {
            every { player.isChunkSent(any<Long>()) } returns false
            menu.show(player, "home", "Дом")
            made.size shouldBe 0
            menu.contains(player.uniqueId) shouldBe false
            menu.landId(player.uniqueId) shouldBe null
            verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>()) }
            menu.close()
        }
    }
})

private class ClaimMenuFixture {
    val world = mockk<World>(relaxed = true)
    val player = mockk<Player>(relaxed = true)
    val plugin = mockk<Plugin>(relaxed = true)
    val packetOwner = mockk<PaperPacketDisplays>(relaxed = true)
    val made = mutableListOf<PacketTextDisplay>()
    val positions = mutableListOf<Location>()
    val texts = mutableListOf<Component>()
    var body = Location(world, 0.0, 64.0, 0.0)
    var eye = Location(world, 0.0, 65.62, 0.0)
    val menu: ClaimLandMenu

    init {
        val playerId = UUID.randomUUID()
        every { world.uid } returns UUID.randomUUID()
        every { world.minHeight } returns 0
        every { world.maxHeight } returns 256
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        val shape = mockk<VoxelShape>()
        every { shape.boundingBoxes } returns emptyList()
        val block = mockk<Block>(relaxed = true)
        every { block.collisionShape } returns shape
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns block
        every { world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } returns null
        every { player.uniqueId } returns playerId
        every { player.world } returns world
        every { player.location } answers { body.clone() }
        every { player.eyeLocation } answers { eye.clone() }
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.isChunkSent(any<Long>()) } returns true
        every { plugin.server.getPlayer(playerId) } returns player
        every { packetOwner.spawnText(any<Location>(), any<Component>()) } answers {
            val index = made.size
            positions += firstArg<Location>().clone()
            texts += secondArg<Component>()
            mockk<PacketTextDisplay>(relaxed = true).also { display ->
                made += display
                every { display.isValid } returns true
                every { display.location } answers { positions[index].clone() }
                every { display.text() } answers { texts[index] }
                every { display.text(any<Component>()) } answers { texts[index] = firstArg(); Unit }
                every { display.teleport(any<Location>()) } answers { positions[index] = firstArg<Location>().clone(); Unit }
            }
        }
        menu = ClaimLandMenu(plugin, Component::text,
            LandsUiPanelAction.entries.associateWith { Component.text(it.name) }, packetOwner)
    }
}
