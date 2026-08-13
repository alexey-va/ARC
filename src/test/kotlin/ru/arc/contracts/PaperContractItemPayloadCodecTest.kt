package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.mockbukkit.mockbukkit.MockBukkit

class PaperContractItemPayloadCodecTest : StringSpec({
    beforeSpec {
        MockBukkit.mock()
    }

    afterSpec {
        MockBukkit.unmock()
    }

    "round-trips an exact Paper item payload" {
        val source = ItemStack(Material.DIAMOND_PICKAXE, 1)
        val meta = source.itemMeta as Damageable
        meta.damage = 17
        source.itemMeta = meta

        val payload = PaperContractItemPayloadCodec.captureVerified("minecraft:diamond_pickaxe", source)
        val restored = PaperContractItemPayloadCodec.restore(payload)

        restored.type shouldBe Material.DIAMOND_PICKAXE
        restored.amount shouldBe 1
        (restored.itemMeta as Damageable).damage shouldBe 17
    }
})
