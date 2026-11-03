package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class YamipaGraveEngravingsTest : FunSpec({
    test("missing or incompatible Yamipa leaves only the image layer unavailable and has no optional descriptors") {
        val plugin = imagePlugin()
        val manager = plugin.server.pluginManager
        every { manager.getPlugin("YamipaPlugin") } returns null
        val missing = YamipaGraveEngravings.create(plugin, emptyList())
        missing.health().available shouldBe false
        missing.health().detail shouldBe "YamipaPlugin is not enabled"
        missing.closeAsync().isDone shouldBe true
        val provider = imagePlugin()
        every { provider.description.version } returns "1.3"
        every { provider.isEnabled } returns true
        every { manager.getPlugin("YamipaPlugin") } returns provider
        val unsupported = YamipaGraveEngravings.create(plugin, emptyList())
        unsupported.health().available shouldBe false
        unsupported.health().detail!!.contains("Unsupported Yamipa version 1.3") shouldBe true
        // Java listener discovery must also work with no Yamipa on the test runtime classpath.
        YamipaGraveEngravings::class.java.declaredMethods.none { method ->
            (method.parameterTypes.toList() + method.returnType).any { it.name.startsWith("io.josemmo") }
        } shouldBe true
        verify(exactly = 0) { manager.registerEvents(any(), plugin) }
        unsupported.closeAsync()
    }

    test("UUID variants are stable and use both assets") {
        val graves = (0L..127L).map { UUID(0, it) }
        graves.map(YamipaGraveEngravings::variant).toSet() shouldBe setOf(0, 1)
        graves.forEach { YamipaGraveEngravings.variant(it) shouldBe YamipaGraveEngravings.variant(UUID.fromString(it.toString())) }
    }

    test("register precedes spawn and IDs belong only to this viewer until actual cleanup acknowledges") {
        val fixture = ImageFixture()
        val handle = fixture.spawn()
        fixture.backend.operations shouldBe listOf("create", "register", "listener", "spawn")
        fixture.backend.registered.size shouldBe 1
        handle.ids shouldBe emptySet()
        val image = fixture.backend.images.single()
        image.loaded.run()
        handle.ids shouldBe setOf(101, 102)
        fixture.owner.graveForFrame(fixture.player, 101) shouldBe fixture.grave
        fixture.owner.graveForFrame(fixture.player, 103) shouldBe null
        val reconnected = fixture.playerCopy()
        fixture.owner.graveForFrame(reconnected, 101) shouldBe null
        val remove = handle.removeAsync()
        handle.isValid shouldBe false
        handle.ids shouldBe emptySet()
        fixture.owner.graveForFrame(fixture.player, 101) shouldBe null
        remove.isDone shouldBe false
        fixture.backend.registered.size shouldBe 1
        handle.removeAsync().isDone shouldBe false
        fixture.backend.operations.count { it == "destroy" } shouldBe 1
        image.cleanup.complete(null)
        remove.join()
        fixture.backend.registered.size shouldBe 0
        fixture.owner.health().retainedImages shouldBe 0
        fixture.ids shouldBe listOf(setOf(101, 102), emptySet())
        fixture.owner.closeAsync().join()
    }

    test("close fences late loading and waits for every accepted cleanup including provider disable") {
        val fixture = ImageFixture()
        val first = fixture.spawn()
        val staleCallback = fixture.backend.images[0].loaded
        val second = fixture.spawn(UUID(0, 2))
        fixture.backend.images[1].ids = setOf(201, 202)
        fixture.backend.images[1].loaded.run()
        fixture.owner.providerDisabled(mockk<PluginDisableEvent>().also { event ->
            every { event.plugin } returns fixture.backend.provider
        })
        first.isValid shouldBe false
        second.isValid shouldBe false
        staleCallback.run()
        fixture.owner.graveForFrame(fixture.player, 101) shouldBe null
        fixture.owner.graveForFrame(fixture.player, 201) shouldBe null
        fixture.owner.health().available shouldBe false
        fixture.owner.spawn(fixture.player, UUID.randomUUID(), 0, fixture.location, BlockFace.NORTH) shouldBe null
        val closed = fixture.owner.closeAsync()
        closed.isDone shouldBe false
        fixture.backend.images[0].cleanup.complete(null)
        closed.isDone shouldBe false
        fixture.backend.images[1].cleanup.complete(null)
        closed.join()
        fixture.owner.closeAsync().join()
        fixture.backend.registered.size shouldBe 0
        fixture.backend.operations.count { it == "destroy" } shouldBe 2
    }

    test("failed cleanup retains native shutdown registration and fails close instead of claiming readiness") {
        val fixture = ImageFixture()
        val handle = fixture.spawn()
        val failure = IllegalStateException("transport failed")
        fixture.backend.images.single().cleanup.completeExceptionally(failure)
        handle.removeAsync().isCompletedExceptionally shouldBe true
        fixture.backend.registered.size shouldBe 1
        fixture.owner.health().retainedImages shouldBe 1
        fixture.owner.health().detail!!.contains("retained for Yamipa shutdown") shouldBe true
        fixture.owner.closeAsync().isCompletedExceptionally shouldBe true
    }

    test("a spawn failure retires already registered ownership and delayed retirement bounds replacement churn") {
        val fixture = ImageFixture()
        fixture.backend.failSpawn = true
        fixture.owner.spawn(fixture.player, fixture.grave, 0, fixture.location, BlockFace.NORTH) shouldBe null
        fixture.backend.registered.size shouldBe 1
        fixture.backend.operations.count { it == "destroy" } shouldBe 1
        fixture.backend.failSpawn = false
        repeat(7) { fixture.spawn(UUID(0, it + 10L)).removeAsync() }
        fixture.owner.spawn(fixture.player, UUID.randomUUID(), 0, fixture.location, BlockFace.NORTH) shouldBe null
        fixture.backend.images.size shouldBe 8
        fixture.backend.images.forEach { it.cleanup.complete(null) }
        fixture.owner.closeAsync().join()
    }

    test("a failed load callback reset still attempts actual native cleanup") {
        val fixture = ImageFixture()
        val handle = fixture.spawn()
        fixture.backend.failListener = true
        val removed = handle.removeAsync()
        fixture.backend.operations.count { it == "destroy" } shouldBe 1
        fixture.backend.registered.size shouldBe 1
        fixture.backend.images.single().cleanup.complete(null)
        removed.join()
        fixture.backend.registered.size shouldBe 0
        fixture.owner.closeAsync().join()
    }
})

private fun imagePlugin(): Plugin = mockk<Plugin>(relaxed = true).also { plugin ->
    every { plugin.logger } returns Logger.getLogger("YamipaGraveEngravingsTest").apply { useParentHandlers = false }
}

private class ImageFixture {
    val plugin = imagePlugin()
    val backend = FakeImageBackend()
    val world = mockk<World>().also { every { it.uid } returns UUID(0, 9) }
    private val playerId = UUID(0, 8)
    val player: Player = playerCopy()
    val grave = UUID(0, 1)
    val location = Location(world, 1.0, 65.0, 1.0)
    val ids = mutableListOf<Set<Int>>()
    val owner = YamipaGraveEngravings(plugin, backend, onFrameIds = { _, frames -> ids += frames })
    fun playerCopy(): Player = mockk<Player>(relaxed = true).also { player ->
        every { player.uniqueId } returns playerId
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.world } returns world
    }
    fun spawn(id: UUID = grave): YamipaGraveEngravings.Handle = checkNotNull(owner.spawn(player, id, 0, location, BlockFace.NORTH))
}

private class FakeImageBackend : YamipaGraveEngravings.Backend {
    class Image {
        var loaded = Runnable {}
        var ids = setOf(101, 102)
        val cleanup = CompletableFuture<Void>()
    }
    override val provider = imagePlugin()
    val images = mutableListOf<Image>()
    val registered = mutableSetOf<Any>()
    val operations = mutableListOf<String>()
    var failSpawn = false
    var failListener = false
    override fun create(player: Player, variant: Int, location: Location, face: BlockFace): Any = Image().also {
        images += it; operations += "create"
    }
    override fun register(image: Any) { registered += image; operations += "register" }
    override fun unregister(image: Any) { registered -= image; operations += "unregister" }
    override fun onLoaded(image: Any, callback: Runnable) {
        if (failListener) error("listener reset failed")
        (image as Image).loaded = callback
        operations += "listener"
    }
    override fun frameIds(image: Any): Set<Int> = (image as Image).ids
    override fun spawn(image: Any, player: Player) { operations += "spawn"; if (failSpawn) error("spawn rejected") }
    override fun destroy(image: Any, player: Player): CompletableFuture<Void> {
        operations += "destroy"
        return (image as Image).cleanup
    }
}
