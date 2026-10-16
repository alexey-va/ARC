package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID

@Suppress("DEPRECATION")
class StaffAbilityAdmissionTest : FreeSpec({
    "the first Q F or defense input cancels a Gravity hold and performs the requested action" {
        for (action in listOf("Q", "F", "defense")) withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("gravity-interrupt-$action")
            val player = h.player("gravity-interrupt-$action", world, StaffSpell.GRAVITY)
            world.getBlockAt(0, 65, 10).type = Material.STONE
            val target = h.zombie(world, .5, 5.5)
            h.controller.start()
            h.controller.input.onInteract(abilityClick(player, Action.RIGHT_CLICK_BLOCK))
            h.captures.size shouldBe 1

            when (action) {
                "Q" -> {
                    val dropped = world.dropItem(player.location, player.inventory.itemInMainHand.clone())
                    h.controller.input.onHotkeyDrop(PlayerDropItemEvent(player, dropped))
                    h.scheduler.tick(1)
                    h.hits shouldBe listOf(target)
                }
                "F" -> {
                    h.controller.input.onHotkeySwap(PlayerSwapHandItemsEvent(player,
                        player.inventory.itemInMainHand.clone(), ItemStack(Material.AIR)))
                    (player.velocity.y > 0.0) shouldBe true
                }
                else -> {
                    h.controller.input.onInteract(abilityClick(player, Action.LEFT_CLICK_AIR))
                    val hit = EntityDamageByEntityEvent(target, player, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0)
                    h.controller.defense.onDamage(hit)
                    hit.isCancelled shouldBe true
                }
            }

            val elapsed = if (action == "Q") 1L else 0L
            h.captures.size shouldBe 2
            h.controller.skillRemaining(player.uniqueId, StaffSpell.GRAVITY) shouldBe 20L - elapsed
            h.cooldowns.remainingTicks(player.uniqueId) shouldBe 4L - elapsed
            h.controller.canCharge(player, StaffSpell.GRAVITY, secondary = true) shouldBe false
            h.scheduler.tick(4)
            h.controller.skillRemaining(player.uniqueId, StaffSpell.GRAVITY) shouldBe 16L - elapsed
            h.controller.canCharge(player, StaffSpell.GRAVITY, secondary = true) shouldBe true
        }
    }

    "Gravity release starts the global cooldown while cancellation preserves only its primary budget" {
        for (release in listOf(true, false)) withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("gravity-finish-$release")
            val player = h.player("gravity-finish-$release", world, StaffSpell.GRAVITY)
            world.getBlockAt(0, 65, 10).type = Material.STONE
            h.controller.start()
            h.controller.beginChannel(player, StaffSpell.GRAVITY) shouldBe true

            if (release) h.controller.releaseChannel(player) else h.controller.cancelChannel(player)

            h.controller.skillRemaining(player.uniqueId, StaffSpell.GRAVITY) shouldBe 20L
            h.cooldowns.remainingTicks(player.uniqueId) shouldBe if (release) 4L else 0L
            if (release) {
                h.scheduler.tick(3)
                h.controller.canCharge(player, StaffSpell.GRAVITY, secondary = true) shouldBe false
                h.scheduler.tick(1)
            }
            h.controller.canCharge(player, StaffSpell.GRAVITY, secondary = false) shouldBe false
            h.controller.canCharge(player, StaffSpell.GRAVITY, secondary = true) shouldBe true
        }
    }

    "guard and shield have separate recovery identities shared across every staff" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("defense-admission")
            val player = h.player("defense-admission", world, StaffSpell.CHAIN)
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val defense = h.controller.defense
            h.controller.start()

            defense.activate(player, StaffSpell.CHAIN, cast, allAround = false) shouldBe true
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.EMBER))
            defense.activate(player, StaffSpell.EMBER, cast, allAround = false) shouldBe false
            defense.activate(player, StaffSpell.EMBER, cast, allAround = true) shouldBe true
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.FAULT))
            defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe false
            defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe false

            h.scheduler.tick(80)
            defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe true
            defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe false
            h.scheduler.tick(420)
            defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe true
        }
    }
})

private fun abilityClick(player: Player, action: Action) = PlayerInteractEvent(
    player, action, player.inventory.itemInMainHand,
    if (action == Action.RIGHT_CLICK_BLOCK) player.world.getBlockAt(0, 64, 0) else null,
    BlockFace.SELF, EquipmentSlot.HAND,
).apply {
    setUseItemInHand(Event.Result.DEFAULT)
    setUseInteractedBlock(Event.Result.DEFAULT)
}
