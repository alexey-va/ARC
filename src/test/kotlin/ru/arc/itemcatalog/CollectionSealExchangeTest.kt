package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class CollectionSealExchangeTest : StringSpec({
    "stacked seal consumes exactly one token and preserves the reward metadata" {
        MockBukkitTestRuntime.open().use {
            val seal = seal("set_frost", 3)
            val reward =
                ItemStack(Material.DIAMOND).apply {
                    editMeta { meta ->
                        meta.displayName(Component.text("Ледяной клинок"))
                        meta.lore(listOf(Component.text("Сохраняется при выдаче")))
                        meta.addEnchant(Enchantment.SHARPNESS, 5, true)
                        meta.persistentDataContainer.set(
                            NamespacedKey("arc", "test-marker"),
                            PersistentDataType.STRING,
                            "keep",
                        )
                    }
                }
            val storage = emptyStorage().also { it[0] = seal }

            val result = CollectionSealExchange().plan(storage, 0, "set_frost", reward)
            val ready = result as CollectionSealExchange.PlanResult.Ready

            storage[0]?.amount shouldBe 3
            ready.contents[0]?.amount shouldBe 2
            val granted = ready.contents.drop(1).filterNotNull().single()
            ready.contents.count { it?.type == Material.DIAMOND } shouldBe 1
            granted.amount shouldBe 1
            granted.itemMeta shouldBe reward.itemMeta
            granted.itemMeta?.enchants?.get(Enchantment.SHARPNESS) shouldBe 5
            granted.itemMeta?.persistentDataContainer?.get(
                NamespacedKey("arc", "test-marker"),
                PersistentDataType.STRING,
            ) shouldBe "keep"
        }
    }

    "a full inventory fails before mutation and keeps the seal" {
        MockBukkitTestRuntime.open().use {
            val storage = Array<ItemStack?>(36) { ItemStack(Material.STONE, 64) }
            storage[0] = seal("set_frost", 2)

            CollectionSealExchange().plan(storage, 0, "set_frost", ItemStack(Material.DIAMOND)) shouldBe
                CollectionSealExchange.PlanResult.InventoryFull
            storage[0]?.amount shouldBe 2
            storage[0]?.let(CollectionSealIdentity::categoryId) shouldBe "set_frost"
        }
    }

    "a stale second plan cannot reuse a seal consumed by the first plan" {
        MockBukkitTestRuntime.open().use {
            val exchange = CollectionSealExchange()
            val first =
                exchange.plan(emptyStorage().also { it[0] = seal("set_frost") }, 0, "set_frost", ItemStack(Material.DIAMOND))
                    as CollectionSealExchange.PlanResult.Ready

            exchange.plan(first.contents, 0, "set_frost", ItemStack(Material.DIAMOND)) shouldBe
                CollectionSealExchange.PlanResult.SealMissing
        }
    }

    "redeem commits one cloned reward and decrements the live main-hand stack" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("seal-owner")
            player.inventory.setItemInMainHand(seal("set_frost", 2))
            val reward = ItemStack(Material.IRON_HELMET).apply {
                editMeta { it.displayName(Component.text("Шлем сета")) }
            }

            CollectionSealExchange().redeem(player, "set_frost", reward) shouldBe
                CollectionSealExchange.RedemptionResult.Success
            player.inventory.itemInMainHand.amount shouldBe 1
            val granted = player.inventory.storageContents.filterNotNull().single { it.type == Material.IRON_HELMET }
            granted.amount shouldBe 1
            granted.itemMeta shouldBe reward.itemMeta
        }
    }

    "invalid category and invalid reward are rejected without reading inventory" {
        MockBukkitTestRuntime.open().use {
            val exchange = CollectionSealExchange()
            val storage = emptyStorage()

            exchange.plan(storage, 0, "common", ItemStack(Material.DIAMOND)) shouldBe
                CollectionSealExchange.PlanResult.InvalidCategory
            exchange.plan(storage, 0, "set_frost", ItemStack(Material.AIR)) shouldBe
                CollectionSealExchange.PlanResult.InvalidReward
            storage.all { it == null } shouldBe true
        }
    }
}) {
    companion object {
        private fun emptyStorage(): Array<ItemStack?> = Array(36) { null }

        private fun seal(categoryId: String, amount: Int = 1): ItemStack =
            CollectionSealIdentity.mark(ItemStack(Material.PAPER, amount), categoryId)
    }
}
