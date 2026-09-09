package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import io.kotest.assertions.throwables.shouldThrow

class ClaimBlockIdentityTest : FreeSpec({
    "recognizes native radius blocks and rejects lookalikes and oversized previews" {
        MockBukkitTestRuntime.open().use {
            val item = ItemStack(Material.GOLD_BLOCK)
            ClaimBlockIdentity.matches(item) shouldBe false
            item.editMeta { meta -> meta.displayName(Component.text("Блок привата")) }
            ClaimBlockIdentity.matches(item) shouldBe false
            item.editMeta { meta ->
                meta.persistentDataContainer.set(NamespacedKey("lands", "type"), PersistentDataType.STRING, "CLAIM_BLOCK")
                meta.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 0)
            }
            ClaimBlockIdentity.matches(item) shouldBe true
            ClaimBlockIdentity.radius(item) shouldBe 0
            item.editMeta { meta ->
                meta.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 1)
            }
            ClaimBlockIdentity.matches(item) shouldBe true
            ClaimBlockIdentity.radius(item) shouldBe 1
            item.editMeta { meta ->
                meta.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 0)
                meta.persistentDataContainer.set(NamespacedKey("lands", "type"), PersistentDataType.STRING, "CAMP")
            }
            ClaimBlockIdentity.matches(item) shouldBe false
        }
    }

    "changes the held stack radius in place without losing amount or ownership metadata" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("radius-owner")
            val item = ItemStack(Material.GOLD_BLOCK, 7).apply {
                editMeta { meta ->
                    meta.persistentDataContainer.set(NamespacedKey("lands", "type"), PersistentDataType.STRING, "CLAIM_BLOCK")
                    meta.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 1)
                    meta.persistentDataContainer.set(NamespacedKey("arc", "marker"), PersistentDataType.STRING, "keep")
                }
            }
            player.inventory.setItemInMainHand(item)

            ClaimBlockIdentity.setHeldRadius(player, 3, listOf(Component.text("new lore"))) shouldBe true
            val updated = player.inventory.itemInMainHand
            updated.amount shouldBe 7
            ClaimBlockIdentity.radius(updated) shouldBe 3
            updated.itemMeta.persistentDataContainer.get(NamespacedKey("lands", "type"), PersistentDataType.STRING) shouldBe "CLAIM_BLOCK"
            updated.itemMeta.persistentDataContainer.get(NamespacedKey("arc", "marker"), PersistentDataType.STRING) shouldBe "keep"
        }
    }

    "does not mint a block without a held item and rejects out of range radius" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("radius-empty")
            ClaimBlockIdentity.heldRadius(player) shouldBe null
            ClaimBlockIdentity.setHeldRadius(player, 2, emptyList()) shouldBe false
            player.inventory.itemInMainHand.type shouldBe Material.AIR
            player.inventory.itemInOffHand.type shouldBe Material.AIR

            val item = ItemStack(Material.GOLD_BLOCK).apply {
                editMeta { meta ->
                    meta.persistentDataContainer.set(NamespacedKey("lands", "type"), PersistentDataType.STRING, "CLAIM_BLOCK")
                    meta.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 5)
                }
            }
            ClaimBlockIdentity.matches(item) shouldBe false
            shouldThrow<IllegalArgumentException> { ClaimBlockIdentity.setHeldRadius(player, 5, emptyList()) }
        }
    }
})
