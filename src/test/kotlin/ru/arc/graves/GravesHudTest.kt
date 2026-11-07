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
import org.bukkit.event.server.PluginDisableEvent
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
    test("decorative scenes use the same backing and saved direction without recovery markers or forced signatures") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudDecorative"))
            val decor = fixture.grave(1, 0, fixture.owner).copy(decorative = true, cosmeticYaw = 90f, ownerName = "",
                appearance = GraveAppearanceChoice("slate", false, "Мои слова"))
            fixture.hud.refreshGlow(fixture.player, listOf(decor)) shouldBe emptyList()
            fixture.spawned shouldBe emptyList()
            fixture.solidDecorations += decor.id
            fixture.hud.refreshGlow(fixture.player, listOf(decor)) shouldBe emptyList()
            fixture.spawned.size shouldBe GraveModels.slate.parts.size + 1
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(fixture.spawned.last().text)) shouldBe "«Мои слова»"
            fixture.spawned.last().location.yaw shouldBe 90f
            // Even a much newer decoration owned by this viewer cannot hide another player's recoverable grave.
            val recovery = fixture.grave(2, 0, createdAt = 0)
            fixture.hud.refreshGlow(fixture.player, listOf(decor.copy(createdAt = 999), recovery)) shouldBe listOf(GraveMarker(recovery, 1))
            // An obstructed recovery scene cannot suppress an actually installed monument.
            fixture.passable = false
            val before = fixture.spawned.size
            fixture.hud.refreshGlow(fixture.player, listOf(decor, recovery)) shouldBe emptyList()
            fixture.spawned.size - before shouldBe GraveModels.slate.parts.size + 1
            val pending = fixture.hud.closeAsync()
            fixture.cleanup.complete(null)
            pending.join()
        }
    }

    test("the bounded scene budget admits all recoverable graves before even nearby owned decorations") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudRecoveryPriority"))
            val decoration = fixture.grave(1, 0, fixture.owner).copy(decorative = true)
            fixture.solidDecorations += decoration.id
            val recoveries = (2L..9L).map { fixture.grave(it, it.toInt()) }
            fixture.hud.refreshGlow(fixture.player, listOf(decoration) + recoveries).map { it.grave } shouldBe recoveries
            fixture.spawned.count { it.display is PacketTextDisplay } shouldBe 8
            fixture.hud.closeAsync()
            fixture.cleanup.complete(null)
        }
    }

    test("public tombs stay stable without bossbars and release masks on snapshot world change and close") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHud"))
            val grave = fixture.grave(1, 0)
            val displayCount = GraveAppearances.select(grave.id, fixture.appearance, engravingsAvailable = false).model.parts.size + 1
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            fixture.spawned.size shouldBe displayCount
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
            fixture.removed.size shouldBe displayCount
            fixture.cleared.size shouldBe 1
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))

            every { fixture.world.uid } returns UUID.randomUUID()
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            fixture.removed.size shouldBe displayCount * 2
            fixture.cleared.size shouldBe 2
            fixture.spawned.size shouldBe displayCount * 3
            val closing = fixture.hud.closeAsync()
            (fixture.hud.closeAsync() === closing) shouldBe true
            closing.isDone shouldBe false
            fixture.cleanup.isDone shouldBe false
            fixture.removed.size shouldBe displayCount * 3
            fixture.cleared.size shouldBe 3
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe emptyList()
            verify(exactly = 1) { fixture.displays.closeAsync() }
            fixture.cleanup.complete(null)
            closing.join()
            closing.isDone shouldBe true
            closing.isCompletedExceptionally shouldBe false
        }
    }

    test("empty default epitaphs leave tomb models without text displays or bossbars") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudNoText"),
                GraveAppearanceSettings(mapOf("slate" to 1.0)))
            val grave = fixture.grave(1, 0, fixture.owner)
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            fixture.spawned.size shouldBe GraveModels.slate.parts.size
            fixture.spawned.any { it.display is PacketTextDisplay } shouldBe false
            verify(exactly = 0) { fixture.displays.spawnText(any(), any()) }
            verify(exactly = 0) { fixture.player.showBossBar(any()) }
            fixture.hud.closeAsync()
            fixture.cleanup.complete(null)
        }
    }

    test("saved owner names are literal signatures and a name change rebuilds the existing marker") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudAuthor"))
            val grave = fixture.grave(1, 0).copy(ownerName = ".WWWWWWWWWWWWWWWW")
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            val initialCount = fixture.spawned.size
            val initialText = fixture.spawned.filter { it.display is PacketTextDisplay }
            initialText.size shouldBe 2
            val inscription = initialText.map { PlainTextComponentSerializer.plainText().serialize(checkNotNull(it.text)) }
            inscription.first() shouldBe "«Да не ссы, ничего не будет»"
            inscription.last().replace("\n", "") shouldBe "— .WWWWWWWWWWWWWWWW"
            val renamed = grave.copy(ownerName = "Alexey")
            fixture.hud.refreshGlow(fixture.player, listOf(renamed)) shouldBe listOf(GraveMarker(renamed, 1))
            fixture.removed.size shouldBe initialCount
            fixture.spawned.size shouldBe initialCount * 2 - 1
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(fixture.spawned.last().text)) shouldBe
                "«Да не ссы, ничего не будет»\n— Alexey"
            fixture.hud.closeAsync()
            fixture.cleanup.complete(null)
        }
    }

    test("a saved appearance drives the scene and changed or cleared last words rebuild its existing displays") {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudChoice"))
            val grave = fixture.grave(1, 0).copy(appearance = GraveAppearanceChoice("timber", false, "Мои слова"))
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            fixture.spawned.size shouldBe GraveModels.timber.parts.size + 1
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(fixture.spawned.last().text)) shouldBe
                "«Мои слова»\n— Игрок"
            val firstCount = fixture.spawned.size
            val changed = grave.copy(appearance = grave.appearance!!.copy(epitaph = "Другие слова"))
            fixture.hud.refreshGlow(fixture.player, listOf(changed)) shouldBe listOf(GraveMarker(changed, 1))
            fixture.removed.size shouldBe firstCount
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(fixture.spawned.last().text)) shouldBe
                "«Другие слова»\n— Игрок"
            val cleared = changed.copy(appearance = changed.appearance!!.copy(epitaph = ""))
            fixture.hud.refreshGlow(fixture.player, listOf(cleared)) shouldBe listOf(GraveMarker(cleared, 1))
            fixture.removed.size shouldBe firstCount * 2
            fixture.spawned.drop(firstCount * 2).any { it.display is PacketTextDisplay } shouldBe false
            fixture.hud.closeAsync()
            fixture.cleanup.complete(null)
        }
    }

    test("available Yamipa only decorates the stelae selected by the independent engraving chance") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("GravesHudMixed")
            val fixture = GravesHudFixture(plugin)
            val backend = mockk<YamipaGraveEngravings.Backend>(relaxed = true)
            every { backend.destroy(any(), any()) } returns CompletableFuture.completedFuture(null)
            val images = YamipaGraveEngravings(plugin, backend)
            fixture.hud.attachEngravings(images)
            val candidates = (1L..100L).map { fixture.grave(it, 0) }
            val original = candidates.first { !GraveAppearances.select(it.id, fixture.appearance).model.engraved }
            val engraved = candidates.first { GraveAppearances.select(it.id, fixture.appearance).model.engraved }.copy(x = 2)
            val expected = listOf(GraveMarker(original, 1), GraveMarker(engraved, 2))
            fixture.hud.refreshGlow(fixture.player, listOf(original, engraved)) shouldBe expected
            fixture.hud.refreshGlow(fixture.player, listOf(original, engraved)) shouldBe expected
            fixture.spawned.size shouldBe GraveModels.slate.parts.size + GraveModels.engravedSlate.parts.size + 2
            fixture.spawned.count { it.display is PacketTextDisplay } shouldBe 2
            verify(exactly = 1) { backend.create(fixture.player, any(), match { it.blockX == 2 }, any()) }
            verify(exactly = 0) { backend.create(fixture.player, any(), match { it.blockX == 0 }, any()) }
            fixture.hud.closeAsync()
            verify(exactly = 1) { backend.destroy(any(), fixture.player) }
            fixture.cleanup.complete(null)
        }
    }

    test("ceiling and provider changes rebuild the same grave between one-cell original and two-cell stele") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("GravesHudFallback")
            val fixture = GravesHudFixture(plugin,
                GraveAppearanceSettings(mapOf("slate" to 1.0), listOf("Да я свой"), engravedChance = 1.0))
            val backend = mockk<YamipaGraveEngravings.Backend>(relaxed = true)
            val nativeImages = List(2) { Any() }
            every { backend.provider } returns plugin
            every { backend.create(any(), any(), any(), any()) } returnsMany nativeImages
            every { backend.destroy(any(), any()) } returns CompletableFuture.completedFuture(null)
            val images = YamipaGraveEngravings(plugin, backend)
            fixture.hud.attachEngravings(images)
            val grave = fixture.grave(1, 0)
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 2))
            fixture.spawned.size shouldBe GraveModels.engravedSlate.parts.size + 1
            val ceiling = mockk<Block>()
            every { ceiling.isPassable } returns false
            every { fixture.world.getBlockAt(0, 65, 0) } returns ceiling
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            fixture.removed.size shouldBe GraveModels.engravedSlate.parts.size + 1
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            verify(exactly = 1) { backend.create(any(), any(), any(), any()) }
            verify(exactly = 1) { backend.destroy(nativeImages[0], fixture.player) }

            every { ceiling.isPassable } returns true
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 2))
            verify(exactly = 2) { backend.create(any(), any(), any(), any()) }
            images.providerDisabled(PluginDisableEvent(plugin))
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            verify(exactly = 1) { backend.destroy(nativeImages[1], fixture.player) }
            (fixture.spawned.size - fixture.removed.size) shouldBe GraveModels.slate.parts.size + 1

            val highest = grave.copy(y = 319)
            every { fixture.player.location } returns Location(fixture.world, .5, 319.0, 2.0)
            fixture.hud.refreshGlow(fixture.player, listOf(highest)) shouldBe listOf(GraveMarker(highest, 1))
            verify(exactly = 0) { fixture.world.getBlockAt(0, 320, 0) }
            fixture.hud.closeAsync()
            fixture.removed.size shouldBe fixture.spawned.size
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

    test("cairn inscriptions face upward on the existing lid at every cardinal grave orientation") {
        MockBukkitTestRuntime.open().use { paper ->
            val appearance = GraveAppearanceSettings(mapOf("cairn" to 1.0), listOf("Да не ссы, я сто раз так делал."), 0.0)
            val fixture = GravesHudFixture(paper.createSimplePlugin("GravesHudCairnLid"), appearance)
            val inscription = GraveModels.cairn.inscription
            val name = ".Alexey_Warrior23"
            val placements = inscription.placements(appearance.epitaphs.single(), name)
            for (angle in listOf(0f, 90f, 180f, 270f)) {
                val grave = (1L..100L).map { fixture.grave(it, 0).copy(ownerName = name) }
                    .first { GraveAppearances.select(it.id, appearance).yaw == angle }
                val start = fixture.spawned.size
                fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
                val texts = fixture.spawned.drop(start).filter { it.display is PacketTextDisplay }
                texts.size shouldBe placements.size
                val yaw = Quaternionf().rotationY(Math.toRadians(angle.toDouble()).toFloat())
                texts.zip(placements).forEach { (spawn, placement) ->
                    val transform = fixture.transforms.getValue(spawn.display)
                    val nativeRotation = Quaternionf().rotationY(Math.toRadians(-spawn.location.yaw.toDouble()).toFloat())
                        .mul(transform.leftRotation)
                    (nativeRotation.transform(Vector3f(0f, 0f, 1f)).distance(Vector3f(0f, 1f, 0f)) < .00001f) shouldBe true
                    (nativeRotation.transform(Vector3f(0f, 1f, 0f)).distance(yaw.transform(Vector3f(0f, 0f, 1f))) < .00001f) shouldBe true
                    val local = yaw.transform(Vector3f(inscription.x.toFloat(), inscription.centerY.toFloat(),
                        (inscription.z + placement.bottomY - inscription.centerY).toFloat()))
                    (spawn.location.distanceSquared(Location(fixture.world, .5 + local.x, 64.0 + local.y, .5 + local.z)) < .0000001) shouldBe true
                    transform.scale shouldBe Vector3f(placement.scale)
                    PlainTextComponentSerializer.plainText().serialize(checkNotNull(spawn.text)) shouldBe placement.text
                }
            }
            fixture.hud.closeAsync()
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
            fixture.hud.refreshGlow(fixture.player, input).map { it.grave } shouldBe expected
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
            val partCount = GraveAppearances.select(grave.id, appearance, engravingsAvailable = false).model.parts.size
            fixture.failText = true
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe emptyList()
            fixture.removed.size shouldBe partCount
            fixture.spawned.forEach { verify(exactly = 0) { it.display.showTo(any<Player>()) } }
            fixture.failText = false
            fixture.hud.refreshGlow(fixture.player, listOf(grave)) shouldBe listOf(GraveMarker(grave, 1))
            val current = fixture.spawned.drop(partCount)
            val text = current.last()
            text.location.yaw shouldBe -90f
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(text.text)) shouldBe "«<red>Похуй...»\n— Игрок"
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
    val appearance: GraveAppearanceSettings = GraveAppearanceSettings(mapOf("slate" to 1.0), listOf("Да не ссы, ничего не будет")),
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
    var passable = true
    val solidDecorations = mutableSetOf<UUID>()
    val hud: GravesHud

    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.name } returns "world"
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        val air = mockk<Block>()
        every { air.isPassable } answers { passable }
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
        hud = GravesHud(plugin, "survival", GravesConfig(ConfigManager.empty()).snapshot().copy(appearance = appearance), displays,
            decorativeBarrier = { id, _ -> id in solidDecorations }, onClear = { cleared += it })
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
