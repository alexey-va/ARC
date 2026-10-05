package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class OriginWorkshopCraftRewardsTest : StringSpec({
    "quota wire rejects coercion and malformed records" {
        val codec = workshopCraftClaimCodec()
        val claim = WorkshopCraftClaim(UUID.randomUUID().toString(), 1791231000000)
        codec.decode(codec.encode(claim)) shouldBe claim
        for (time in listOf("1.5", "1e3", "\"1000\"", "-1", "0", "null", "9999999999999999999")) {
            shouldThrowAny { codec.decode("""{"request":"${claim.request}","nextAt":$time}""") }
        }
        shouldThrowAny { codec.decode("""{"request":"invalid","nextAt":1000}""") }
        shouldThrowAny { codec.decode("""{"request":"${claim.request}"}""") }
    }
    "empty inventory receives one chair with metadata and requires no ingredients" {
        MockBukkitTestRuntime.open().use {
            val storage = arrayOfNulls<ItemStack>(36)
            val reward = ItemStack(Material.PAPER, 3).also { s -> s.editMeta { it.displayName(Component.text("Chair")) } }
            val ready = planWorkshopCraft(storage, reward) as WorkshopCraftPlan.Ready
            storage.filterNotNull().size shouldBe 0
            val chair = ready.contents.filterNotNull().single()
            chair.amount shouldBe 1
            chair.itemMeta shouldBe reward.itemMeta
        }
    }
    "reward preserves unrelated materials and stacks only with the same chair" {
        MockBukkitTestRuntime.open().use {
            val reward = ItemStack(Material.PAPER).also { s -> s.editMeta { it.displayName(Component.text("Chair")) } }
            val storage = arrayOfNulls<ItemStack>(36)
            storage[0] = ItemStack(Material.SPRUCE_LOG, 2)
            storage[1] = ItemStack(Material.YELLOW_WOOL)
            storage[2] = ItemStack(Material.PAPER, 4)
            storage[3] = reward.clone().also { it.amount = 2 }
            val ready = planWorkshopCraft(storage, reward) as WorkshopCraftPlan.Ready
            ready.contents[0]?.amount shouldBe 2
            ready.contents[1]?.amount shouldBe 1
            ready.contents[2]?.amount shouldBe 4
            ready.contents[3]?.amount shouldBe 3
            storage[3]?.amount shouldBe 2
        }
    }
    "full inventory rejects delivery without modifying its snapshot" {
        MockBukkitTestRuntime.open().use {
            val full = Array<ItemStack?>(36) { ItemStack(Material.COBBLESTONE, 64) }
            planWorkshopCraft(full, ItemStack(Material.PAPER)) shouldBe WorkshopCraftPlan.Full
            full.forEach { it?.amount shouldBe 64 }
        }
    }
    "durable quota rejects duplicate completion and survives a fresh session until exact expiry" {
        val request = UUID.randomUUID()
        val claim = WorkshopCraftClaim(request.toString(), 1000)
        workshopClaimAllowed(null, request, 0) shouldBe true
        workshopClaimAllowed(claim, request, 2000) shouldBe false
        workshopClaimAllowed(claim, UUID.randomUUID(), 999) shouldBe false
        workshopClaimAllowed(claim, UUID.randomUUID(), 1000) shouldBe true
    }
})
