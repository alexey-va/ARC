package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.World
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.hooks.economyshop.FURNITURE_GALLERY_WORLD
import ru.arc.paper.api.ArcInspectionFrame
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ItemInfoInspectionProviderTest : StringSpec({
    "excluded item-info IDs suppress only their exact target" {
        val excludedId = "elitecreatures:casino_decoration_v1_table_blackjack"
        val settings = ItemInfoSettings(
            enabled = true,
            targetDistance = 5.0,
            nameOnlyTemplate = "<name>",
            hologramTemplate = "<name>",
            bossbarTemplate = "<name>",
            excludedItemIds = setOf(excludedId),
        )
        val player = mockk<Player>()
        val world = mockk<World>()
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { world.uid } returns UUID.randomUUID()
        every { world.name } returns "rc_origin_spawn"
        val preferences: (Player) -> ItemInfoPreferences = { ItemInfoPreferences() }

        val excludedProvider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = { ItemInfoTarget(Component.text("Blackjack"), excludedId) },
            preferences = preferences,
            work = ItemInfoTestPreparation().work,
        )
        excludedProvider.resolve(player) shouldBe null

        val preparation = ItemInfoTestPreparation()
        val ordinaryProvider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = {
                ItemInfoTarget(Component.text("Стул"), "elitecreatures:casino_decoration_v1_chair_brown_extra")
            },
            preferences = preferences,
            work = preparation.work,
        )
        ordinaryProvider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.completeNext()
        ordinaryProvider.resolve(player)?.suppressesLowerSources shouldBe false
    }

    "uses a fresh live furniture quote and shows the purchase hint only in the gallery hologram" {
        val settings = ItemInfoSettings(
            enabled = true,
            targetDistance = 5.0,
            nameOnlyTemplate = "<white><name>",
            hologramTemplate = "<white><name>",
            bossbarTemplate = "<white><name>",
        )
        val player = mockk<Player>()
        val world = mockk<World>()
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { world.uid } returns UUID.randomUUID()
        every { world.name } returns FURNITURE_GALLERY_WORLD
        val plain = PlainTextComponentSerializer.plainText()
        var currentPrice = "1,250.00"
        var priceLookups = 0
        val preparation = ItemInfoTestPreparation()
        val provider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = { ItemInfoTarget(Component.text("Стул"), "furniture:oak_chair") },
            preferences = { ItemInfoPreferences() },
            galleryPurchasePrice = { _, _ -> priceLookups++; currentPrice },
            work = preparation.work,
        )

        provider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.completeNext()
        val first = provider.resolve(player)!!
        plain.serialize(first.hologram) shouldBe "Стул\n1,250.00 💰\nПКМ — купить"
        plain.serialize(first.bossbar) shouldBe "Стул"
        priceLookups shouldBe 2

        currentPrice = "💰 1,375.00 💰"
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.completeNext()
        plain.serialize(provider.resolve(player)!!.hologram) shouldBe "Стул\n1,375.00 💰\nПКМ — купить"
        priceLookups shouldBe 4

        every { world.name } returns "rc_origin_spawn"
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.completeNext()
        plain.serialize(provider.resolve(player)!!.hologram) shouldBe "Стул"
        priceLookups shouldBe 4
    }

    "a current gallery offer can surface a normally excluded item while other worlds keep suppressing it" {
        val excludedId = "elitecreatures:casino_decoration_v1_table_blackjack"
        val settings = ItemInfoSettings(
            enabled = true,
            targetDistance = 5.0,
            nameOnlyTemplate = "<white><name>",
            hologramTemplate = "<white><name>",
            bossbarTemplate = "<white><name>",
            excludedItemIds = setOf(excludedId),
        )
        val player = mockk<Player>()
        val world = mockk<World>()
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { world.uid } returns UUID.randomUUID()
        every { world.name } returns FURNITURE_GALLERY_WORLD
        val preparation = ItemInfoTestPreparation()
        val provider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = { ItemInfoTarget(Component.text("Чёрный стол"), excludedId) },
            preferences = { ItemInfoPreferences() },
            galleryPurchasePrice = { _, id -> id.takeIf { it == excludedId }?.let { "8,500.00" } },
            work = preparation.work,
        )
        val plain = PlainTextComponentSerializer.plainText()

        provider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.completeNext()
        plain.serialize(provider.resolve(player)!!.hologram) shouldBe "Чёрный стол\n8,500.00 💰\nПКМ — купить"
        every { world.name } returns "rc_origin_spawn"
        provider.resolve(player) shouldBe null
    }

    "delayed preparation coalesces changed targets and never returns the old target" {
        val player = preparationPlayer()
        val preparation = ItemInfoTestPreparation()
        var target = ItemInfoTarget(Component.text("First"), "furniture:first")
        val provider = ItemInfoInspectionProvider(
            preparationSettings(), { target }, { ItemInfoPreferences.DEFAULT }, work = preparation.work,
        )
        val plain = PlainTextComponentSerializer.plainText()

        provider.resolve(player)?.suppressesLowerSources shouldBe true
        target = ItemInfoTarget(Component.text("Discarded"), "furniture:discarded")
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        target = ItemInfoTarget(Component.text("Latest"), "furniture:latest")
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.results.size shouldBe 1

        preparation.completeNext()
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.results.size shouldBe 2
        preparation.completeNext()
        val ready = provider.resolve(player)!!
        plain.serialize(ready.hologram) shouldBe "Latest"
        repeat(3) { provider.resolve(player) shouldBe ready }
        preparation.results.size shouldBe 2
    }

    "A to B to A accepts only a completion matching the current captured input" {
        val player = preparationPlayer()
        val preparation = ItemInfoTestPreparation()
        val first = ItemInfoTarget(Component.text("First"), "furniture:first")
        var target = first
        val provider = ItemInfoInspectionProvider(
            preparationSettings(), { target }, { ItemInfoPreferences.DEFAULT }, work = preparation.work,
        )

        provider.resolve(player)?.suppressesLowerSources shouldBe true
        target = first.copy(namespacedId = "furniture:other")
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        target = first
        preparation.completeNext()
        PlainTextComponentSerializer.plainText().serialize(provider.resolve(player)!!.hologram) shouldBe "First"
        preparation.results.size shouldBe 1
    }

    "world preferences missing target and explicit reset invalidate pending and ready frames" {
        val player = preparationPlayer()
        val world = player.world
        val preparation = ItemInfoTestPreparation()
        var selected = ItemInfoPreferences.DEFAULT
        var target: ItemInfoTarget? = ItemInfoTarget(Component.text("Chair"), "furniture:chair")
        val provider = ItemInfoInspectionProvider(
            preparationSettings(), { target }, { selected }, work = preparation.work,
        )
        val plain = PlainTextComponentSerializer.plainText()

        provider.resolve(player)?.suppressesLowerSources shouldBe true
        selected = selected.copy(showNamespacedId = true)
        preparation.completeNext()
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.completeNext()
        plain.serialize(provider.resolve(player)!!.hologram) shouldBe "Chair furniture:chair"

        every { world.uid } returns UUID.randomUUID()
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        provider.clear(player)
        preparation.completeNext()
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.results.size shouldBe 4
        target = null
        provider.resolve(player) shouldBe null
        preparation.completeNext()
        target = ItemInfoTarget(Component.text("Chair"), "furniture:chair")
        provider.resolve(player)?.suppressesLowerSources shouldBe true
        preparation.results.size shouldBe 5
        preparation.completeNext()
        provider.resolve(player)?.suppressesLowerSources shouldBe false
    }

    "close forgets delayed work seals admission once and never resurrects a viewer" {
        val player = preparationPlayer()
        val preparation = ItemInfoTestPreparation()
        var captures = 0
        val provider = ItemInfoInspectionProvider(
            preparationSettings(),
            { captures++; ItemInfoTarget(Component.text("Chair"), "furniture:chair") },
            { ItemInfoPreferences.DEFAULT },
            work = preparation.work,
        )

        provider.resolve(player)?.suppressesLowerSources shouldBe true
        provider.close()
        provider.close()
        preparation.completeNext()
        provider.resolve(player) shouldBe null
        captures shouldBe 1
        verify(exactly = 1) { preparation.work.close() }
    }

    "saturated real worker retries without inline rendering and captures native inputs on the caller" {
        val caller = Thread.currentThread()
        val player = preparationPlayer()
        val settings = spyk(preparationSettings())
        val renderThreads = CopyOnWriteArrayList<Thread>()
        val rendered = CountDownLatch(2)
        every { settings.hologramText(any(), any()) } answers {
            renderThreads += Thread.currentThread()
            callOriginal().also { rendered.countDown() }
        }
        every { settings.bossbarText(any(), any()) } answers {
            renderThreads += Thread.currentThread()
            callOriginal().also { rendered.countDown() }
        }
        val work = BoundedAsyncExecutor("item-info-test", capacity = 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = work.submit {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        started.await(5, TimeUnit.SECONDS) shouldBe true
        val queued = work.submit { Unit }
        var captures = 0
        val provider = ItemInfoInspectionProvider(
            settings,
            {
                Thread.currentThread() shouldBe caller
                captures++
                ItemInfoTarget(Component.text("Chair"), "furniture:chair")
            },
            { Thread.currentThread() shouldBe caller; ItemInfoPreferences.DEFAULT },
            work = work,
        )
        try {
            provider.resolve(player)?.suppressesLowerSources shouldBe true
            renderThreads shouldBe emptyList()
            release.countDown()
            blocking.get(5, TimeUnit.SECONDS)
            queued.get(5, TimeUnit.SECONDS)

            provider.resolve(player)?.suppressesLowerSources shouldBe true
            rendered.await(5, TimeUnit.SECONDS) shouldBe true
            work.submit { Unit }.get(5, TimeUnit.SECONDS)
            provider.resolve(player)?.suppressesLowerSources shouldBe false
            renderThreads.size shouldBe 2
            renderThreads.all { it !== caller && it.name.startsWith("item-info-test-") } shouldBe true
            captures shouldBe 3
        } finally {
            release.countDown()
            provider.close()
        }
    }
})

internal class ItemInfoTestPreparation {
    private val operations = mutableListOf<() -> ArcInspectionFrame>()
    val results = mutableListOf<CompletableFuture<ArcInspectionFrame>>()
    val work = mockk<BoundedAsyncExecutor>(relaxed = true) {
        every { submit<ArcInspectionFrame>(any()) } answers {
            operations += firstArg<() -> ArcInspectionFrame>()
            CompletableFuture<ArcInspectionFrame>().also(results::add)
        }
    }

    fun completeNext() {
        val index = results.indexOfFirst { !it.isDone }
        check(index >= 0) { "No pending item-info preparation" }
        results[index].complete(operations[index]())
    }
}

private fun preparationSettings() = ItemInfoSettings(true, 5.0, "<name>", "<name> <id>", "<name> <id>")

private fun preparationPlayer(): Player = mockk {
    every { uniqueId } returns UUID.randomUUID()
    every { world } returns mockk {
        every { uid } returns UUID.randomUUID()
        every { name } returns "survival"
    }
}
