package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.hooks.elitemobs.dungeonMasteryCooldownTicks

class StaffMasteryCooldownTest : FreeSpec({
    "primary and secondary cast budgets use mastery once and preserve cooldown admission" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("mastery-casts")
            val primary = h.player("mastery-primary", world, StaffSpell.CHAIN)
            val secondary = h.player("mastery-secondary", world, StaffSpell.CHAIN)
            val unchanged = h.player("mastery-unchanged", world, StaffSpell.CHAIN)
            val durations = mutableListOf<Long>()
            StaffSpellsModule.installMasteryCooldown { player, _, ticks ->
                durations += ticks
                dungeonMasteryCooldownTicks(ticks, if (player == unchanged) 0 else 100)
            }
            try {
                h.controller.start()
                h.controller.cast(primary)
                h.controller.cast(secondary, StaffCastInput.SECONDARY, 0.0)
                h.controller.cast(unchanged, StaffCastInput.SECONDARY, 0.0)
                durations shouldBe listOf(20L, 80L, 80L)
                h.cooldowns.remainingTicks(primary.uniqueId) shouldBe 19L
                h.cooldowns.remainingTicks(secondary.uniqueId) shouldBe 73L
                h.cooldowns.remainingTicks(unchanged.uniqueId) shouldBe 80L
                h.scheduler.tick(18)
                h.cooldowns.remainingTicks(primary.uniqueId) shouldBe 1L
                h.controller.canCharge(primary, StaffSpell.CHAIN) shouldBe false
                h.scheduler.tick(1)
                h.controller.canCharge(primary, StaffSpell.CHAIN) shouldBe true
                h.scheduler.tick(53)
                h.cooldowns.remainingTicks(secondary.uniqueId) shouldBe 1L
                h.controller.canCharge(secondary, StaffSpell.CHAIN) shouldBe false
                h.scheduler.tick(1)
                h.controller.canCharge(secondary, StaffSpell.CHAIN) shouldBe true
                h.controller.canCharge(unchanged, StaffSpell.CHAIN) shouldBe false
                h.scheduler.tick(7)
                h.controller.canCharge(unchanged, StaffSpell.CHAIN) shouldBe true
            } finally {
                StaffSpellsModule.installMasteryCooldown(null)
            }
        }
    }

    "channel cancellation passes the original cast snapshot without capturing another weapon" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("mastery-channel")
            val player = h.player("mastery-channel", world, StaffSpell.GRAVITY)
            world.getBlockAt(0, 65, 10).type = Material.STONE
            val casts = mutableListOf<StaffSpellCast>()
            StaffSpellsModule.installMasteryCooldown { _, cast, ticks ->
                casts += cast
                dungeonMasteryCooldownTicks(ticks, 100)
            }
            try {
                h.controller.start()
                h.controller.beginChannel(player, StaffSpell.GRAVITY) shouldBe true
                h.captures.size shouldBe 1
                player.inventory.setItemInMainHand(h.config.item(StaffSpell.CHAIN))
                h.controller.cancelChannel(player)
                h.captures.size shouldBe 1
                casts.single().casterId shouldBe player.uniqueId
                h.cooldowns.remainingTicks(player.uniqueId) shouldBe 19L
                h.controller.cancelChannel(player)
                casts.size shouldBe 1
            } finally {
                StaffSpellsModule.installMasteryCooldown(null)
            }
        }
    }

    "quit and death retain admission budgets while reset and close release owned deadlines" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("mastery-cooldown-lifecycle")
            val quitting = h.player("mastery-quit", world, StaffSpell.CHAIN)
            val dying = h.player("mastery-death", world, StaffSpell.CHAIN)
            val resetting = h.player("mastery-reset", world, StaffSpell.CHAIN)
            val closing = h.player("mastery-close", world, StaffSpell.CHAIN)
            h.controller.start()
            listOf(quitting, dying, resetting, closing).forEach { h.controller.cast(it) }
            h.captures.size shouldBe 4
            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))
            h.controller.onDeath(mockk<PlayerDeathEvent> { every { entity } returns dying })
            h.cooldowns.remainingTicks(quitting.uniqueId) shouldBe 20L
            h.cooldowns.remainingTicks(dying.uniqueId) shouldBe 20L
            h.controller.cast(quitting)
            h.controller.cast(dying)
            h.captures.size shouldBe 4
            h.controller.resetPlayer(resetting.uniqueId)
            h.cooldowns.remainingTicks(resetting.uniqueId) shouldBe 0L
            h.scheduler.tick(19)
            h.cooldowns.remainingTicks(quitting.uniqueId) shouldBe 1L
            h.cooldowns.remainingTicks(dying.uniqueId) shouldBe 1L
            h.cooldowns.remainingTicks(closing.uniqueId) shouldBe 1L
            h.controller.close()
            listOf(quitting, dying, closing).forEach {
                h.cooldowns.remainingTicks(it.uniqueId) shouldBe 0L
            }
        }
    }

    "active hotkeys and defense do not consume the mastery hook and an absent hook preserves the budget" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("mastery-actives")
            val active = h.player("mastery-active", world, StaffSpell.CHAIN)
            val ordinary = h.player("mastery-ordinary", world, StaffSpell.CHAIN)
            var calls = 0
            StaffSpellsModule.installMasteryCooldown { _, _, ticks -> calls++; ticks }
            try {
                h.controller.start()
                h.controller.canHotkey(active, StaffSpell.CHAIN, StaffHotkey.Q) shouldBe true
                h.controller.hotkey(active, StaffSpell.CHAIN, StaffHotkey.Q)
                h.controller.defend(active, StaffSpell.CHAIN)
                calls shouldBe 0
                StaffSpellsModule.installMasteryCooldown(null)
                h.controller.cast(ordinary)
                h.cooldowns.remainingTicks(ordinary.uniqueId) shouldBe 20L
            } finally {
                StaffSpellsModule.installMasteryCooldown(null)
            }
        }
    }
})
