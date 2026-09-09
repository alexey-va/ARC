package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

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
})
