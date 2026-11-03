package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.config.ConfigManager
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class GravesHudTest : FunSpec({
    test("public tombs stay stable without bossbars and release masks on snapshot world change and close") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHud"))
            val grave = fixture.grave(1, 0)
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(grave)
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(grave)
            fixture.spawned.size shouldBe 12
            fixture.spawned.forEach {
                verify(exactly = 1) { it.display.isVisibleByDefault = false }
                verify(exactly = 1) { it.display.showTo(fixture.player) }
                verify(exactly = 0) { it.display.isGlowing = true }
            }
            fixture.hud.refreshGlow(fixture.player, listOf(grave.copy(ownerId = fixture.owner)))
            fixture.removed.size shouldBe 0
            verify(exactly = 0) { fixture.player.showBossBar(any()) }
            verify(exactly = 0) { fixture.player.hideBossBar(any()) }

            fixture.hud.refreshGlow(fixture.player, emptyList()) shouldBe emptyList()
            fixture.removed.size shouldBe 12
            fixture.cleared.size shouldBe 1
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(grave)

            every { fixture.world.uid } returns UUID.randomUUID()
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(grave)
            fixture.removed.size shouldBe 24
            fixture.cleared.size shouldBe 2
            fixture.spawned.size shouldBe 36
            fixture.hud.closeAsync() shouldBe fixture.cleanup
            fixture.hud.closeAsync() shouldBe fixture.cleanup
            fixture.cleanup.isDone shouldBe false
            fixture.removed.size shouldBe 36
            fixture.cleared.size shouldBe 3
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe emptyList()
            verify(exactly = 1) { fixture.displays.closeAsync() }
            fixture.cleanup.complete(null)
        }
    }

    test("empty default epitaphs leave tomb models without text displays or bossbars") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudNoText"),
                GraveAppearanceSettings(mapOf("slate" to 1.0)))
            val grave = fixture.grave(1, 0, fixture.owner)
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(grave)
            fixture.spawned.size shouldBe GraveModels.slate.parts.size
            fixture.spawned.any { it.display is PacketTextDisplay } shouldBe false
            verify(exactly = 0) { fixture.displays.spawnText(any(), any()) }
            verify(exactly = 0) { fixture.player.showBossBar(any()) }
            fixture.hud.closeAsync()
            fixture.cleanup.complete(null)
        }
    }

    test("claim acknowledgement feedback is explicit local and stops with the runtime") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudClaim"))
            fixture.hud.refreshGlow(fixture.player, listOf(fixture.grave(1, 0)))
            verify(exactly = 0) { fixture.player.playSound(any<Location>(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .35f, 1.2f) }
            fixture.hud.claimSucceeded(fixture.player)
            verify(exactly = 1) { fixture.player.playSound(any<Location>(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .35f, 1.2f) }
            fixture.hud.closeAsync()
            fixture.hud.claimSucceeded(fixture.player)
            verify(exactly = 1) { fixture.player.playSound(any<Location>(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, .35f, 1.2f) }
            fixture.cleanup.complete(null)
        }
    }

    test("eight sent passable cells prefer owned graves then newest duplicates and nearby public graves") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudSelection"))
            val owned = fixture.grave(10, 12, fixture.owner)
            val ownedNewest = fixture.grave(21, 13, fixture.owner, 200)
            val publicNewest = fixture.grave(31, 1, createdAt = 100)
            val ordinary = (2..11).map { fixture.grave(100L + it, it) }
            val blocked = mockk<Block>()
            every { blocked.isPassable } returns false
            every { fixture.world.getBlockAt(3, 64, 0) } returns blocked
            every { fixture.world.isChunkLoaded(-1, 0) } returns false
            every { fixture.player.isChunkSent(Chunk.getChunkKey(1, 0)) } returns false
            val input = ordinary + listOf(
                fixture.grave(1, 0).copy(server = "spawn"),
                fixture.grave(2, 0).copy(world = "elsewhere"),
                fixture.grave(3, 0).copy(expiresAt = 1),
                fixture.grave(4, -16), fixture.grave(5, 90), fixture.grave(6, 16),
                fixture.grave(11, 12, createdAt = 500), owned,
                fixture.grave(20, 13, fixture.owner, 100), ownedNewest,
                fixture.grave(30, 1, createdAt = 50), publicNewest,
            )
            val expected = listOf(owned, ownedNewest, publicNewest) + ordinary.filter { it.x != 3 }.take(5)
            fixture.hud.refreshGlow(fixture.player, input) shouldBe expected
            fixture.spawned.count { it.display is PacketTextDisplay } shouldBe 8
            verify(exactly = 0) { fixture.world.getBlockAt(-16, any<Int>(), any<Int>()) }
            verify(exactly = 0) { fixture.world.getBlockAt(90, any<Int>(), any<Int>()) }
            verify(exactly = 0) { fixture.world.getBlockAt(16, any<Int>(), any<Int>()) }
            fixture.hud.clear(fixture.player)
            fixture.removed.size shouldBe fixture.spawned.size
            fixture.cleared.size shouldBe 1
            fixture.hud.closeAsync()
            fixture.cleanup.complete(null)
        }
    }

    test("failed scene allocation stays inactive and successful parts share the rotated front and literal text") {
        MockBukkitTestRuntime.open().use { paper ->
            val appearance = GraveAppearanceSettings(mapOf("obelisk" to 1.0), listOf("<red>Похуй..."))
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudRotation"), appearance)
            val grave = (1L..100L).map { fixture.grave(it, 0) }
                .first { GraveAppearances.select(it.id, appearance).yaw == 270f }
            fixture.failText = true
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe emptyList()
            fixture.removed.size shouldBe 8
            fixture.spawned.forEach { verify(exactly = 0) { it.display.showTo(any<Player>()) } }
            fixture.failText = false
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(grave)
            val current = fixture.spawned.drop(8)
            val text = current.last()
            text.location.yaw shouldBe -90f
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(text.text)) shouldBe "<red>Похуй..."
            val textDisplay = text.display as PacketTextDisplay
            verify { textDisplay.billboard = Display.Billboard.FIXED }
            verify { textDisplay.backgroundColor = Color.fromARGB(0) }
            verify { textDisplay.isSeeThrough = false }
            verify { textDisplay.isShadowed = false }

            val yaw = Quaternionf().rotationY((Math.PI * 1.5).toFloat())
            current.dropLast(1).zip(GraveModels.obelisk.parts).forEach { (spawn, part) ->
                val transform = fixture.transforms.getValue(spawn.display)
                val center = transform.leftRotation.transform(Vector3f(.5f).mul(transform.scale)).add(transform.translation)
                (center.length() < .00001f) shouldBe true
                val localCenter = yaw.transform(Vector3f(
                    (part.x + part.width / 2).toFloat(), (part.y + part.height / 2).toFloat(), (part.z + part.depth / 2).toFloat(),
                ))
                (spawn.location.distanceSquared(Location(fixture.world, .5 + localCenter.x, 64.0 + localCenter.y, .5 + localCenter.z)) < .0000001) shouldBe true
                val expectedRotation = Quaternionf(yaw).mul(Quaternionf().rotationXYZ(
                    Math.toRadians(part.rotationX.toDouble()).toFloat(), Math.toRadians(part.rotationY.toDouble()).toFloat(), Math.toRadians(part.rotationZ.toDouble()).toFloat(),
                ))
                (transform.leftRotation.transform(Vector3f(0f, 1f, 0f)).distance(expectedRotation.transform(Vector3f(0f, 1f, 0f))) < .00001f) shouldBe true
            }
            every { fixture.player.isDead } returns true
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe emptyList()
            fixture.removed.size shouldBe fixture.spawned.size
            fixture.hud.closeAsync()
            fixture.cleanup.complete(null)
        }
    }
})

private class GravesHudFixture(
    plugin: Plugin,
    appearance: GraveAppearanceSettings = GraveAppearanceSettings(mapOf("slate" to 1.0), listOf("Да не ссы, ничего не будет")),
) {
    data class Spawn(val display: PacketDisplay, val location: Location, val text: Component? = null)
    val owner: UUID = UUID.randomUUID()
    val world = mockk<World>(relaxed = true)
    val player = mockk<Player>(relaxed = true)
    val displays = mockk<PaperPacketDisplays>()
    val spawned = mutableListOf<Spawn>()
    val removed = mutableSetOf<PacketDisplay>()
    val transforms = mutableMapOf<PacketDisplay, Transformation>()
    val cleared = mutableListOf<Player>()
    val cleanup = CompletableFuture<Void>()
    var failText = false
    val hud: GravesHud

    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.name } returns "world"
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        val air = mockk<Block>()
        every { air.isPassable } returns true
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns air
        every { player.uniqueId } returns owner
        every { player.world } returns world
        every { player.location } returns Location(world, .5, 64.0, 2.0)
        every { player.isOnline } returns true
        every { player.isChunkSent(any<Long>()) } returns true
        every { displays.spawnBlock(any(), any()) } answers {
            val display = mockk<PacketBlockDisplay>(relaxed = true)
            track(display, firstArg())
            display
        }
        every { displays.spawnText(any(), any()) } answers {
            if (failText) throw IllegalStateException("Injected text failure")
            val display = mockk<PacketTextDisplay>(relaxed = true)
            track(display, firstArg(), secondArg())
            display
        }
        every { displays.closeAsync() } returns cleanup
        hud = GravesHud(plugin, "survival", GravesConfig(ConfigManager.empty()).snapshot().copy(appearance = appearance), displays) { cleared += it }
    }

    private fun track(display: PacketDisplay, location: Location, text: Component? = null) {
        spawned += Spawn(display, location.clone(), text)
        every { display.isValid } answers { display !in removed }
        every { display.remove() } answers { removed += display; Unit }
        every { display.transformation = any() } answers { transforms[display] = firstArg(); Unit }
    }

    fun grave(id: Long, x: Int, graveOwner: UUID = UUID(9, 9), createdAt: Long = id) =
        GraveView(UUID(0, id), graveOwner, "survival", "world", x, 64, 0, createdAt, -1, 20, emptyList())
}
