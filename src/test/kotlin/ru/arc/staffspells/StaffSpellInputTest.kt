package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.event.player.PlayerStopUsingItemEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.config.TestConfig
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class StaffSpellInputTest : FreeSpec({
    "staff use migration changes only the consumable component and is idempotent" {
        withInputHarness { h ->
            val item = StaffSpellConfig(TestConfig()).item(StaffSpell.CHAIN).apply {
                setData(DataComponentTypes.ITEM_MODEL, Key.key("example", "staff"))
                unsetData(DataComponentTypes.CONSUMABLE)
            }
            val before = item.clone()

            configureStaffUse(item) shouldBe true
            val use = requireNotNull(item.getData(DataComponentTypes.CONSUMABLE))
            use.consumeSeconds() shouldBe 3_600f
            use.animation() shouldBe ItemUseAnimation.NONE
            use.sound() shouldBe Key.key("minecraft:intentionally_empty")
            use.hasConsumeParticles() shouldBe false
            use.consumeEffects().isEmpty() shouldBe true
            item.getData(DataComponentTypes.ITEM_MODEL) shouldBe Key.key("example", "staff")
            StaffSpell.from(item) shouldBe StaffSpell.CHAIN
            item.displayName() shouldBe before.displayName()
            item.lore() shouldBe before.lore()

            val withoutUse = item.clone().apply { unsetData(DataComponentTypes.CONSUMABLE) }
            withoutUse.isSimilar(before) shouldBe true
            configureStaffUse(item) shouldBe false
            configureStaffUse(ItemStack(Material.STICK)) shouldBe false
        }
    }

    "native hold updates charge every four ticks and release casts once at the measured level" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge")
            val player = h.staffPlayer("staff-charge", world, StaffSpell.CHAIN)
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-input-charge"))

            val click = rightClick(player)
            h.paper.server.pluginManager.callEvent(click)
            click.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            player.hasActiveItem() shouldBe true
            h.actions.casts shouldBe emptyList()

            // A right-click animation must neither start a quick cast nor cancel the charge.
            h.paper.server.pluginManager.callEvent(io.papermc.paper.event.player.PlayerArmSwingEvent(
                player, EquipmentSlot.HAND))

            h.scheduler.tick(36)
            h.actions.charges.map { it.progress } shouldBe
                listOf(0.0, 0.0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0)
            h.actions.casts shouldBe emptyList()

            h.input.onStopUsing(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 36))
            h.actions.casts.size shouldBe 1
            h.actions.casts.single().spell shouldBe StaffSpell.CHAIN
            h.actions.casts.single().input shouldBe StaffCastInput.PRIMARY
            h.actions.casts.single().charge shouldBe 1.0
            h.actions.cancelled shouldBe listOf(player.uniqueId)
        }
    }

    "explicitly denied item or block use does not start a charge" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-use-denied")
            val player = h.staffPlayer("staff-use-denied", world, StaffSpell.CHAIN)
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-input-denied"))

            val itemDenied = rightClick(player).apply {
                setUseItemInHand(org.bukkit.event.Event.Result.DENY)
            }
            h.paper.server.pluginManager.callEvent(itemDenied)
            player.hasActiveItem() shouldBe false
            h.actions.charges shouldBe emptyList()

            val blockDenied = rightClick(player).apply {
                setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
            }
            h.paper.server.pluginManager.callEvent(blockDenied)
            player.hasActiveItem() shouldBe false
            h.actions.charges shouldBe emptyList()
            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe emptyList()
        }
    }

    "tap release remains uncharged, and holding past 200 ticks expires without auto-casting" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge-cap")
            val player = h.staffPlayer("staff-charge-cap", world, StaffSpell.CHAIN)
            h.input.start()
            h.input.onInteract(rightClick(player))
            h.input.onStopUsing(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 2))

            h.actions.casts.single().charge shouldBe 0.0
            h.actions.casts.single().input shouldBe StaffCastInput.PRIMARY
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            player.clearActiveItem()

            h.actions.casts.clear()
            h.actions.cancelled.clear()
            h.actions.usable = true
            h.input.onInteract(rightClick(player))
            h.scheduler.tick(200)
            h.actions.casts shouldBe emptyList()
            h.actions.charges.last().progress shouldBe 1.0
            h.scheduler.tick(1)
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.input.onStopUsing(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 201))
            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId)
        }
    }

    "left click casts the quick input once and staff cannot break blocks or melee normally" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-quick")
            val player = h.staffPlayer("staff-quick", world, StaffSpell.FAULT).apply { isSneaking = true }
            h.input.start()
            h.input.onInteract(PlayerInteractEvent(player, Action.LEFT_CLICK_AIR,
                player.inventory.itemInMainHand, null, BlockFace.SELF, EquipmentSlot.HAND))

            h.actions.casts.size shouldBe 1
            h.actions.casts.single().input shouldBe StaffCastInput.QUICK_SECONDARY
            h.actions.casts.single().charge shouldBe 0.0

            val target = world.spawn(Location(world, 0.5, 64.0, 3.0), org.bukkit.entity.Zombie::class.java)
            val attack = PrePlayerAttackEntityEvent(player, target, true)
            h.input.onPreAttack(attack)
            attack.isCancelled shouldBe true
            h.actions.casts.size shouldBe 1

            player.gameMode = org.bukkit.GameMode.CREATIVE
            val block = world.getBlockAt(0, 64, 0).apply { type = Material.STONE }
            val damage = BlockDamageEvent(player, block, player.inventory.itemInMainHand, true)
            val breakEvent = BlockBreakEvent(block, player)
            h.input.onBlockDamage(damage)
            h.input.onBlockBreak(breakEvent)
            damage.isCancelled shouldBe true
            breakEvent.isCancelled shouldBe true
            h.actions.casts.size shouldBe 1
        }
    }

    "sneak or selected-slot changes cancel a held charge before release" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge-cancel")
            val player = h.staffPlayer("staff-charge-cancel", world, StaffSpell.CHAIN)
            h.input.start()
            h.input.onInteract(rightClick(player))
            h.input.onSneak(org.bukkit.event.player.PlayerToggleSneakEvent(player, true))
            h.input.onStopUsing(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 12))

            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            player.clearActiveItem()

            h.input.onInteract(rightClick(player))
            h.input.onHeldSlot(org.bukkit.event.player.PlayerItemHeldEvent(player, 0, 1))
            h.input.onStopUsing(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 12))
            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId, player.uniqueId)
        }
    }
})

private data class InputChargeCall(val playerId: UUID, val spell: StaffSpell, val progress: Double)
private data class InputCastCall(val playerId: UUID, val spell: StaffSpell, val input: StaffCastInput, val charge: Double)

private class RecordingStaffSpellInputActions : StaffSpellInputActions {
    val charges = mutableListOf<InputChargeCall>()
    val casts = mutableListOf<InputCastCall>()
    val cancelled = mutableListOf<UUID>()
    var usable = true

    override fun canCharge(player: org.bukkit.entity.Player, spell: StaffSpell) = usable
    override fun cast(player: org.bukkit.entity.Player, input: StaffCastInput, charge: Double) {
        casts += InputCastCall(player.uniqueId, StaffSpell.from(player.inventory.itemInMainHand)!!, input, charge)
        usable = false
    }
    override fun charge(player: org.bukkit.entity.Player, spell: StaffSpell, progress: Double) {
        charges += InputChargeCall(player.uniqueId, spell, progress)
    }
    override fun cancelCharge(player: org.bukkit.entity.Player) { cancelled += player.uniqueId }
}

private data class InputHarness(
    val paper: MockBukkitTestRuntime,
    val scheduler: TestTaskScheduler,
    val input: StaffSpellInput,
    val actions: RecordingStaffSpellInputActions,
) {
    fun staffPlayer(name: String, world: WorldMock, spell: StaffSpell): PlayerMock = paper.addPlayer(name).apply {
        teleport(Location(world, 0.5, 64.0, 0.5).apply { setDirection(Vector(0.0, 0.0, 1.0)) })
        inventory.setItemInMainHand(StaffSpellConfig(TestConfig()).item(spell).apply {
            unsetData(DataComponentTypes.CONSUMABLE) // A pre-upgrade staff exercises runtime migration.
        })
        syncMockBukkitUseEquipment()
    }
}

/**
 * MockBukkit's PlayerMock inventory overrides getEquipment(), but its inherited
 * startUsingItem() reads LivingEntityMock's separate private equipment field.
 * Keep that backing mock in sync so this test exercises the real listener call.
 */
private fun PlayerMock.syncMockBukkitUseEquipment() {
    val equipmentField = org.mockbukkit.mockbukkit.entity.LivingEntityMock::class.java
        .getDeclaredField("equipment")
        .apply { isAccessible = true }
    val backingEquipment = equipmentField.get(this) as org.bukkit.inventory.EntityEquipment
    backingEquipment.setItemInMainHand(inventory.itemInMainHand.clone())
    backingEquipment.setItemInOffHand(inventory.itemInOffHand.clone())
}

private fun withInputHarness(block: (InputHarness) -> Unit) {
    val scheduler = TestTaskScheduler()
    MockBukkitTestRuntime.open().use { paper ->
        Tasks.withScheduler(scheduler) {
            val actions = RecordingStaffSpellInputActions()
            val input = StaffSpellInput(actions, LifecycleTaskScope(scheduler))
            try { block(InputHarness(paper, scheduler, input, actions)) }
            finally { input.close() }
        }
    }
}

private fun rightClick(player: PlayerMock) = PlayerInteractEvent(
    player,
    Action.RIGHT_CLICK_BLOCK,
    player.inventory.itemInMainHand,
    player.world.getBlockAt(0, 64, 0),
    BlockFace.UP,
    EquipmentSlot.HAND,
).apply {
    setUseItemInHand(org.bukkit.event.Event.Result.DEFAULT)
    setUseInteractedBlock(org.bukkit.event.Event.Result.DEFAULT)
}
