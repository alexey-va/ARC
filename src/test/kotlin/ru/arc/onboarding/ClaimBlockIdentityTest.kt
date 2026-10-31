package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.landsui.ClaimBlockItem
import ru.arc.landsui.LandsUiConfig
import ru.arc.paper.testing.MockBukkitTestRuntime
import io.kotest.assertions.throwables.shouldThrow

class ClaimBlockIdentityTest : FreeSpec({
    "ARC tool keeps its owner and stack while changing radius without native Lands metadata" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("arc-claim-owner")
            val other = paper.addPlayer("other-owner")
            val item = ClaimBlockItem.create(player.uniqueId, LandsUiConfig.defaults())
            item.type shouldBe Material.GRASS_BLOCK
            item.amount shouldBe 1
            val meta = item.itemMeta
            meta.persistentDataContainer.keys.map { it.toString() }.toSet() shouldBe setOf(
                "arc:claim_block", "arc:claim_block_radius", "arc:claim_block_owner",
            )
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(meta.displayName())) shouldBe "Блок привата"
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(meta.lore()).first()) shouldBe "Захват: 1×1 · чанков: 1"
            (listOf(checkNotNull(meta.displayName())) + checkNotNull(meta.lore())).forEach {
                it.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            }
            ClaimBlockIdentity.matches(item) shouldBe true
            ClaimBlockIdentity.radius(item) shouldBe 0
            ClaimBlockIdentity.usableBy(item, player) shouldBe true
            ClaimBlockIdentity.usableBy(item, other) shouldBe false

            item.amount = 7
            item.editMeta { it.persistentDataContainer.set(NamespacedKey("arc", "marker"), PersistentDataType.STRING, "keep") }
            player.inventory.setItemInOffHand(item)
            val lore = listOf(Component.text("new radius lore"))

            ClaimBlockIdentity.setHeldRadius(player, 3, lore) shouldBe true

            val updated = player.inventory.itemInOffHand
            updated.amount shouldBe 7
            ClaimBlockIdentity.radius(updated) shouldBe 3
            ClaimBlockIdentity.usableBy(updated, player) shouldBe true
            ClaimBlockIdentity.usableBy(updated, other) shouldBe false
            updated.itemMeta.lore() shouldBe lore
            val data = updated.itemMeta.persistentDataContainer
            data.get(ClaimBlockItem.markerKey, PersistentDataType.BYTE) shouldBe 1.toByte()
            data.get(ClaimBlockItem.ownerKey, PersistentDataType.STRING) shouldBe player.uniqueId.toString()
            data.get(NamespacedKey("arc", "marker"), PersistentDataType.STRING) shouldBe "keep"
            data.keys.none { it.namespace == "lands" } shouldBe true
        }
    }

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
                    meta.persistentDataContainer.set(NamespacedKey("lands", "o"), PersistentDataType.STRING, player.uniqueId.toString())
                    meta.persistentDataContainer.set(NamespacedKey("arc", "marker"), PersistentDataType.STRING, "keep")
                }
            }
            player.inventory.setItemInMainHand(item)

            ClaimBlockIdentity.setHeldRadius(player, 3, listOf(Component.text("new lore"))) shouldBe true
            val updated = player.inventory.itemInMainHand
            updated.amount shouldBe 7
            ClaimBlockIdentity.radius(updated) shouldBe 3
            updated.itemMeta.persistentDataContainer.get(NamespacedKey("lands", "type"), PersistentDataType.STRING) shouldBe "CLAIM_BLOCK"
            updated.itemMeta.persistentDataContainer.get(NamespacedKey("lands", "o"), PersistentDataType.STRING) shouldBe player.uniqueId.toString()
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
            val arcItem = ClaimBlockItem.create(player.uniqueId, LandsUiConfig.defaults()).apply {
                editMeta { it.persistentDataContainer.set(ClaimBlockItem.radiusKey, PersistentDataType.INTEGER, 5) }
            }
            ClaimBlockIdentity.matches(arcItem) shouldBe false
            shouldThrow<IllegalArgumentException> { ClaimBlockIdentity.setHeldRadius(player, 5, emptyList()) }
        }
    }
})
