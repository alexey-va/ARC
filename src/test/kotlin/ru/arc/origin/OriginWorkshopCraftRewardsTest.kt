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
    "exchange consumes exact plain materials and grants one metadata-preserving chair atomically" {
        MockBukkitTestRuntime.open().use {
            val storage = arrayOfNulls<ItemStack>(36)
            storage[0] = ItemStack(Material.SPRUCE_PLANKS, 6)
            storage[1] = ItemStack(Material.SPRUCE_PLANKS, 4)
            storage[2] = ItemStack(Material.STICK, 4)
            val reward = ItemStack(Material.PAPER, 3).also { s -> s.editMeta { it.displayName(Component.text("Chair")) } }
            val cost = mapOf(Material.SPRUCE_PLANKS to 8, Material.STICK to 4)
            val ready = planWorkshopCraft(storage, cost, reward) as WorkshopCraftPlan.Ready
            storage[0]?.amount shouldBe 6
            ready.contents.filterNotNull().filter { it.type == Material.SPRUCE_PLANKS }.sumOf { it.amount } shouldBe 2
            val chair = ready.contents.filterNotNull().single { it.type == Material.PAPER }
            chair.amount shouldBe 1
            chair.itemMeta shouldBe reward.itemMeta
            (planWorkshopCraft(ready.contents, cost, reward) is WorkshopCraftPlan.Missing) shouldBe true
        }
    }
    "full inventory and custom material fail without touching the original snapshot" {
        MockBukkitTestRuntime.open().use {
            val full = Array<ItemStack?>(36) { ItemStack(Material.COBBLESTONE, 64) }
            full[0] = ItemStack(Material.STICK, 64)
            planWorkshopCraft(full, mapOf(Material.STICK to 4), ItemStack(Material.PAPER)) shouldBe WorkshopCraftPlan.Full
            full[0]?.amount shouldBe 64
            val custom = arrayOfNulls<ItemStack>(36)
            custom[0] = ItemStack(Material.STICK, 8).also { s -> s.editMeta { it.displayName(Component.text("Magic wand")) } }
            planWorkshopCraft(custom, mapOf(Material.STICK to 4), ItemStack(Material.PAPER)) shouldBe
                WorkshopCraftPlan.Missing(Material.STICK, 4)
            custom[0]?.amount shouldBe 8
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
