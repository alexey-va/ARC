package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class DungeonSupplyShopTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "defaults include supplies and the merchant scroll" {
        DungeonSupplyShop().list() shouldBe DEFAULT_SUPPLY_OFFERS
    }

    "healing purchase delivers one drinkable healing potion and debits its price" {
        val player = paper.addPlayer("healing-buyer")
        val expected = DungeonPanelView(paper.addSimpleWorld("dungeon").uid, DungeonVisit("run"), null)
        val economy = FakeSupplyEconomy(20.0)
        val shop = DungeonSupplyShop(current = { expected }, economy = economy)
        val offer = shop.list().single { it.id == "healing" }
        val quote = shop.quote(player, offer)!!
        shop.buy(player, quote, expected) shouldBe SupplyResult.BOUGHT
        val delivered = player.inventory.storageContents.filterNotNull().single { !it.type.isAir }
        delivered.type shouldBe Material.POTION
        delivered.amount shouldBe 1
        (delivered.itemMeta as PotionMeta).basePotionType shouldBe PotionType.HEALING
        economy.balance(player) shouldBe 0.0
        economy.withdraws shouldBe 1
    }

    "rejects stale offer and stale dungeon run before charging" {
        val player = paper.addPlayer("buyer")
        val world = paper.addSimpleWorld("dungeon")
        val expected = DungeonPanelView(world.uid, DungeonVisit("run-1"), null)
        val economy = FakeSupplyEconomy(100.0)
        var catalog = listOf(SupplyOffer("food", Material.BREAD, 2, 20.0))
        val shop = DungeonSupplyShop({ catalog }, { expected }, economy)
        val quote = shop.quote(player, catalog.single())!!
        catalog = listOf(SupplyOffer("food", Material.BREAD, 2, 21.0))
        shop.buy(player, quote, expected) shouldBe SupplyResult.ITEM_UNAVAILABLE
        val current = expected.copy(visit = expected.visit.copy(run = "run-2"))
        shop.buy(player, quote, current) shouldBe SupplyResult.OUTSIDE
        economy.withdraws shouldBe 0
    }

    "full storage is rejected without charging" {
        val player = paper.addPlayer("full")
        val world = paper.addSimpleWorld("dungeon")
        repeat(36) { player.inventory.setItem(it, ItemStack(Material.STONE, 64)) }
        val expected = DungeonPanelView(world.uid, DungeonVisit("run"), null)
        val economy = FakeSupplyEconomy(100.0)
        val offer = SupplyOffer("food", Material.BREAD, 2, 20.0)
        val shop = DungeonSupplyShop({ listOf(offer) }, { expected }, economy)
        shop.buy(player, shop.quote(player, offer)!!, expected) shouldBe SupplyResult.NO_SPACE
        economy.withdraws shouldBe 0
    }

    "insufficient funds leaves inventory unchanged" {
        val player = paper.addPlayer("poor")
        val world = paper.addSimpleWorld("dungeon")
        val expected = DungeonPanelView(world.uid, DungeonVisit("run"), null)
        val economy = FakeSupplyEconomy(1.0)
        val offer = SupplyOffer("food", Material.BREAD, 2, 20.0)
        val shop = DungeonSupplyShop({ listOf(offer) }, { expected }, economy)
        shop.buy(player, shop.quote(player, offer)!!, expected) shouldBe SupplyResult.NO_MONEY
        player.inventory.itemInMainHand.type shouldBe Material.AIR
        economy.withdraws shouldBe 0
    }

    "successful purchase charges once and keeps exact amount" {
        val player = paper.addPlayer("success")
        val world = paper.addSimpleWorld("dungeon")
        val expected = DungeonPanelView(world.uid, DungeonVisit("run"), null)
        val economy = FakeSupplyEconomy(100.0)
        val offer = SupplyOffer("food", Material.BREAD, 16, 20.0)
        val shop = DungeonSupplyShop({ listOf(offer) }, { expected }, economy)
        shop.buy(player, shop.quote(player, offer)!!, expected) shouldBe SupplyResult.BOUGHT
        player.inventory.storageContents.filterNotNull().sumOf { if (it.type == Material.BREAD) it.amount else 0 } shouldBe 16
        economy.withdraws shouldBe 1
        shop.buy(player, shop.quote(player, offer)!!, expected) shouldBe SupplyResult.BOUGHT
        economy.withdraws shouldBe 2
    }

    "invalid catalog values are disabled" {
        val shop = DungeonSupplyShop(offers = { listOf(
            SupplyOffer("zero", Material.BREAD, 0, 20.0),
            SupplyOffer("negative", Material.BREAD, 1, -1.0),
            SupplyOffer("nan", Material.BREAD, 1, Double.NaN),
        ) })
        shop.list() shouldBe emptyList()
    }

    "a throwing debit restores the delivered item" {
        val player = paper.addPlayer("debit-failure")
        val world = paper.addSimpleWorld("dungeon")
        val expected = DungeonPanelView(world.uid, DungeonVisit("run"), null)
        val economy = object : DungeonSupplyEconomy {
            override fun balance(player: org.bukkit.entity.Player) = 20.0
            override fun withdraw(player: org.bukkit.entity.Player, amount: Double): Boolean = error("wallet unavailable")
        }
        val offer = SupplyOffer("food", Material.BREAD, 2, 20.0)
        val shop = DungeonSupplyShop({ listOf(offer) }, { expected }, economy)
        shop.buy(player, shop.quote(player, offer)!!, expected) shouldBe SupplyResult.PAYMENT_FAILED
        player.inventory.storageContents.filterNotNull().sumOf { if (it.type == Material.BREAD) it.amount else 0 } shouldBe 0
    }

    "quote preserves custom item metadata and metadata changes invalidate it" {
        val player = paper.addPlayer("metadata")
        val world = paper.addSimpleWorld("dungeon")
        val expected = DungeonPanelView(world.uid, DungeonVisit("run"), null)
        val offer = SupplyOffer("scroll", Material.PAPER, 1, 20.0, "merchant")
        val key = NamespacedKey("arc", "supply")
        var marker = "one"
        val economy = FakeSupplyEconomy(100.0)
        val shop = DungeonSupplyShop({ listOf(offer) }, { expected }, economy, createItem = { _, item ->
            ItemStack(item.material, item.amount).also { stack ->
                stack.editMeta { meta ->
                    meta.displayName(Component.text("Scroll"))
                    meta.persistentDataContainer.set(key, PersistentDataType.STRING, marker)
                }
            }
        })
        val quote = shop.quote(player, offer)!!
        marker = "two"
        shop.buy(player, quote, expected) shouldBe SupplyResult.CHANGED
        marker = "one"
        shop.buy(player, quote, expected) shouldBe SupplyResult.BOUGHT
        val delivered = player.inventory.storageContents.filterNotNull().single { it.type == Material.PAPER }
        delivered.itemMeta!!.persistentDataContainer.get(key, PersistentDataType.STRING) shouldBe "one"
    }
})

private class FakeSupplyEconomy(private var funds: Double) : DungeonSupplyEconomy {
    var withdraws = 0
    override fun balance(player: org.bukkit.entity.Player): Double = funds
    override fun withdraw(player: org.bukkit.entity.Player, amount: Double): Boolean {
        if (funds < amount) return false
        funds -= amount
        withdraws++
        return true
    }
}
