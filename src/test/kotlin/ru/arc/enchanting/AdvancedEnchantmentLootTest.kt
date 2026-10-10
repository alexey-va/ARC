package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.Player
import org.bukkit.entity.Item
import org.bukkit.event.player.PlayerFishEvent
import java.util.UUID
import ru.arc.helpcenter.HelpCenterEnchantment
import ru.arc.helpcenter.HelpCenterEnchantmentsCatalog
import ru.arc.paper.testing.MockBukkitTestRuntime

class AdvancedEnchantmentLootTest : StringSpec({
    "one probability roll selects at most one reward at exact boundaries" {
        advancedEnchantmentLootKind(0.0, 0.2, 0.8) shouldBe AdvancedEnchantmentLootKind.BOOK
        advancedEnchantmentLootKind(0.001999, 0.2, 0.8) shouldBe AdvancedEnchantmentLootKind.BOOK
        advancedEnchantmentLootKind(0.002, 0.2, 0.8) shouldBe AdvancedEnchantmentLootKind.DUST
        advancedEnchantmentLootKind(0.009999, 0.2, 0.8) shouldBe AdvancedEnchantmentLootKind.DUST
        advancedEnchantmentLootKind(0.01, 0.2, 0.8) shouldBe null
        advancedEnchantmentLootKind(0.5, 0.0, 0.0) shouldBe null
    }

    "natural mob eligibility rejects non-survival, non-natural, non-enemy and mismatched actors" {
        fun allowed(
            gameMode: GameMode = GameMode.SURVIVAL,
            survivalBackend: Boolean = true,
            worldAllowed: Boolean = true,
            enemy: Boolean = true,
            naturalSpawn: Boolean = true,
            playerAttack: Boolean = true,
            samePlayer: Boolean = true,
            elite: Boolean = false,
        ) = eligibleNaturalMobLoot(survivalBackend, worldAllowed, gameMode, enemy, naturalSpawn, playerAttack, samePlayer, elite)

        allowed() shouldBe true
        allowed(gameMode = GameMode.ADVENTURE) shouldBe true
        allowed(gameMode = GameMode.CREATIVE) shouldBe false
        allowed(survivalBackend = false) shouldBe false
        allowed(worldAllowed = false) shouldBe false
        allowed(enemy = false) shouldBe false
        allowed(naturalSpawn = false) shouldBe false
        allowed(playerAttack = false) shouldBe false
        allowed(samePlayer = false) shouldBe false
        allowed(elite = true) shouldBe false
    }

    "weighted rarity groups use only available positive configured weights" {
        val weights = mapOf("SIMPLE" to 55, "UNIQUE" to 25, "ELITE" to 15, "ULTIMATE" to 4, "LEGENDARY" to 1)
        weightedLootGroupTotal(weights, weights.keys) shouldBe 100
        selectWeightedLootGroup(weights, weights.keys, 0) shouldBe "SIMPLE"
        selectWeightedLootGroup(weights, weights.keys, 54) shouldBe "SIMPLE"
        selectWeightedLootGroup(weights, weights.keys, 55) shouldBe "UNIQUE"
        selectWeightedLootGroup(weights, weights.keys, 80) shouldBe "ELITE"
        selectWeightedLootGroup(weights, weights.keys, 95) shouldBe "ULTIMATE"
        selectWeightedLootGroup(weights, weights.keys, 99) shouldBe "LEGENDARY"
        weightedLootGroupTotal(weights, setOf("SIMPLE", "ELITE")) shouldBe 70
        selectWeightedLootGroup(weights, setOf("SIMPLE", "ELITE"), 55) shouldBe "ELITE"
    }

    "book candidates require public enchanter availability and configured native levels" {
        val catalog = HelpCenterEnchantmentsCatalog(
            available = true,
            entries = listOf(
                enchantment("simple_ok", "SIMPLE", available = true),
                enchantment("simple_unavailable", "SIMPLE", available = false),
                enchantment("fabled", "FABLED", available = true),
                enchantment("elite_missing_levels", "ELITE", available = true),
            ),
        )
        val candidates = eligibleAdvancedBookCandidates(
            catalog,
            mapOf("SIMPLE" to 55, "ELITE" to 15),
        ) { id -> if (id == "simple_ok") listOf(1, 3) else emptyList() }

        candidates.keys shouldBe setOf("SIMPLE")
        candidates.getValue("SIMPLE") shouldBe listOf(AdvancedBookCandidate("simple_ok", listOf(1, 3)))
    }

    "death bonus appends one independent item and leaves native drops untouched" {
        MockBukkitTestRuntime.open().use {
            val nativeDrop = ItemStack(Material.ROTTEN_FLESH, 3)
            val original = nativeDrop.clone()
            val bonus = ItemStack(Material.ENCHANTED_BOOK)
            val drops = mutableListOf(nativeDrop)

            appendSingleBonusDrop(drops, bonus)

            drops.size shouldBe 2
            drops[0] shouldBe original
            (drops[0] === nativeDrop) shouldBe true
            drops[1] shouldBe bonus
            (drops[1] === bonus) shouldBe false
        }
    }

    "fishing waits for final cancellation and refuses delivery after leaving the world" {
        MockBukkitTestRuntime.open().use {
            for (case in listOf("cancelled", "moved", "offline", "valid")) {
                val worldId = UUID.randomUUID()
                var currentWorldId = worldId
                var cancelled = false
                var online = true
                val player = mockk<Player>()
                every { player.world.name } returns "survival"
                every { player.world.uid } answers { currentWorldId }
                every { player.gameMode } returns GameMode.SURVIVAL
                every { player.isOnline } answers { online }
                val caught = mockk<Item>()
                every { caught.world.uid } returns worldId
                val event = mockk<PlayerFishEvent>()
                every { event.state } returns PlayerFishEvent.State.CAUGHT_FISH
                every { event.player } returns player
                every { event.caught } returns caught
                every { event.hook.isInOpenWater } returns true
                every { event.isCancelled } answers { cancelled }
                var deferred: (() -> Unit)? = null
                var delivered = 0
                val listener = AdvancedEnchantmentLoot(
                    AdvancedEnchantmentLootSettings(true, setOf("survival"), 0.0, 0.0, 0.0, 100.0, mapOf("SIMPLE" to 1)),
                    createDust = { _, _ -> ItemStack(Material.SUGAR) },
                    nextDouble = { 0.0 }, nextInt = { 0 },
                    tasks = mockk(relaxed = true),
                    afterEvent = { deferred = it },
                    deliverFishingReward = { _, _ -> delivered++ },
                )
                listener.onOpenWaterCatch(event)
                delivered shouldBe 0
                when (case) {
                    "cancelled" -> cancelled = true
                    "moved" -> currentWorldId = UUID.randomUUID()
                    "offline" -> online = false
                }
                requireNotNull(deferred).invoke()
                delivered shouldBe if (case == "valid") 1 else 0
            }
        }
    }
})

private fun enchantment(id: String, group: String, available: Boolean) = HelpCenterEnchantment(
    id = id,
    name = id,
    description = "",
    maxLevelDescription = "",
    materials = setOf("DIAMOND_SWORD"),
    group = group,
    maxLevel = 5,
    availableFromEnchanter = available,
)
