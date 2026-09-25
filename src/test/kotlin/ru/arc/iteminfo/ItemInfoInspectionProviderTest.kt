package ru.arc.iteminfo

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.World
import ru.arc.hooks.economyshop.FURNITURE_GALLERY_WORLD

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
        every { player.world } returns world
        every { world.name } returns "rc_origin_spawn"
        val preferences: (Player) -> ItemInfoPreferences = { ItemInfoPreferences() }

        val excludedProvider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = { ItemInfoTarget(Component.text("Blackjack"), excludedId) },
            preferences = preferences,
        )
        excludedProvider.resolve(player) shouldBe null

        val ordinaryProvider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = {
                ItemInfoTarget(Component.text("Стул"), "elitecreatures:casino_decoration_v1_chair_brown_extra")
            },
            preferences = preferences,
        )
        (ordinaryProvider.resolve(player) == null) shouldBe false
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
        every { player.world } returns world
        every { world.name } returns FURNITURE_GALLERY_WORLD
        val plain = PlainTextComponentSerializer.plainText()
        var currentPrice = "1,250.00"
        var priceLookups = 0
        val provider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = { ItemInfoTarget(Component.text("Стул"), "furniture:oak_chair") },
            preferences = { ItemInfoPreferences() },
            galleryPurchasePrice = { _, _ -> priceLookups++; currentPrice },
        )

        val first = provider.resolve(player)!!
        plain.serialize(first.hologram) shouldBe "Стул\n1,250.00 💰\nПКМ — купить"
        plain.serialize(first.bossbar) shouldBe "Стул"
        priceLookups shouldBe 1

        currentPrice = "💰 1,375.00 💰"
        plain.serialize(provider.resolve(player)!!.hologram) shouldBe "Стул\n1,375.00 💰\nПКМ — купить"
        priceLookups shouldBe 2

        every { world.name } returns "rc_origin_spawn"
        plain.serialize(provider.resolve(player)!!.hologram) shouldBe "Стул"
        priceLookups shouldBe 2
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
        every { player.world } returns world
        every { world.name } returns FURNITURE_GALLERY_WORLD
        val provider = ItemInfoInspectionProvider(
            settings = settings,
            resolveTarget = { ItemInfoTarget(Component.text("Чёрный стол"), excludedId) },
            preferences = { ItemInfoPreferences() },
            galleryPurchasePrice = { _, id -> id.takeIf { it == excludedId }?.let { "8,500.00" } },
        )
        val plain = PlainTextComponentSerializer.plainText()

        plain.serialize(provider.resolve(player)!!.hologram) shouldBe "Чёрный стол\n8,500.00 💰\nПКМ — купить"
        every { world.name } returns "rc_origin_spawn"
        provider.resolve(player) shouldBe null
    }
})
