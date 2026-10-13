package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.event.player.PlayerStopUsingItemEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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

    "only Ember and Icicle primary casts charge; other attacks are instant and channels stay held" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-input-policy")
            h.input.start()
            val chargedPrimaries = setOf(StaffSpell.EMBER, StaffSpell.ICICLE)
            val channels = setOf(StaffSpell.INFERNO, StaffSpell.GRAVITY)

            StaffSpell.entries.forEach { spell ->
                listOf(false, true).forEach { secondary ->
                    val player = h.staffPlayer("policy-${spell.id}-$secondary", world, spell).apply {
                        isSneaking = secondary
                    }
                    h.actions.usable = true
                    h.actions.channelUsable = true
                    val castsBefore = h.actions.casts.size
                    val chargesBefore = h.actions.charges.size
                    val channelsBefore = h.actions.channelBegins.size

                    h.input.onInteract(rightClick(player))

                    when {
                        spell in channels -> {
                            h.actions.channelBegins.size shouldBe channelsBefore + 1
                            h.actions.casts.size shouldBe castsBefore
                            h.actions.charges.size shouldBe chargesBefore
                            player.hasActiveItem() shouldBe true
                            h.input.cancel(player.uniqueId)
                        }
                        spell in chargedPrimaries && !secondary -> {
                            h.actions.channelBegins.size shouldBe channelsBefore
                            h.actions.casts.size shouldBe castsBefore
                            h.actions.charges.size shouldBe chargesBefore + 1
                            h.actions.charges.last().let {
                                it.playerId shouldBe player.uniqueId
                                it.spell shouldBe spell
                                it.progress shouldBe 0.0
                            }
                            player.hasActiveItem() shouldBe true
                            h.input.cancel(player.uniqueId)
                        }
                        else -> {
                            h.actions.channelBegins.size shouldBe channelsBefore
                            h.actions.casts.size shouldBe castsBefore + 1
                            h.actions.casts.last().let {
                                it.playerId shouldBe player.uniqueId
                                it.spell shouldBe spell
                                it.input shouldBe StaffCastInput.of(secondary, quick = false)
                                it.charge shouldBe 0.0
                            }
                            h.actions.charges.size shouldBe chargesBefore
                            player.hasActiveItem() shouldBe false
                        }
                    }
                }
            }
        }
    }

    "staff lore explains instant, charged, and channel input separately" {
        withInputHarness { _ ->
            val config = StaffSpellConfig(TestConfig(mapOf(
                "messages.use" to "cast-on-click",
                "messages.charge-use" to "hold-for-charge",
                "messages.channel-use" to "hold-channel",
            )))
            fun lore(spell: StaffSpell) = config.item(spell).lore()!!.joinToString(" ") {
                PlainTextComponentSerializer.plainText().serialize(it)
            }

            lore(StaffSpell.CHAIN).contains("cast-on-click") shouldBe true
            lore(StaffSpell.EMBER).contains("hold-for-charge") shouldBe true
            lore(StaffSpell.ICICLE).contains("hold-for-charge") shouldBe true
            lore(StaffSpell.GRAVITY).contains("hold-channel") shouldBe true
            lore(StaffSpell.INFERNO).contains("hold-channel") shouldBe true
        }
    }

    "native hold updates charge every four ticks and release casts once at the measured level" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge")
            val player = h.staffPlayer("staff-charge", world, StaffSpell.EMBER)
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
            h.actions.casts.single().spell shouldBe StaffSpell.EMBER
            h.actions.casts.single().input shouldBe StaffCastInput.PRIMARY
            h.actions.casts.single().charge shouldBe 1.0
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.actions.releaseOrder shouldBe listOf("cast", "cancel")
        }
    }

    "charge is cleaned up without casting if the attack becomes unavailable before release" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge-cooldown")
            val player = h.staffPlayer("staff-charge-cooldown", world, StaffSpell.EMBER)
            h.input.start()
            h.input.onInteract(rightClick(player))

            h.actions.usable = false
            h.input.onStopUsing(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 12))

            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.actions.releaseOrder shouldBe listOf("cancel")
        }
    }

    "explicitly denied item or block use does not start a charge" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-use-denied")
            val player = h.staffPlayer("staff-use-denied", world, StaffSpell.EMBER)
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
            val player = h.staffPlayer("staff-charge-cap", world, StaffSpell.EMBER)
            h.input.start()
            h.input.onInteract(rightClick(player))
            h.input.onStopUsing(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 2))

            h.actions.casts.single().charge shouldBe 0.0
            h.actions.casts.single().input shouldBe StaffCastInput.PRIMARY
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.actions.releaseOrder shouldBe listOf("cast", "cancel")
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

    "left click defends without charge permission and duplicate attack input is ignored" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-quick")
            val player = h.staffPlayer("staff-quick", world, StaffSpell.FAULT).apply { isSneaking = true }
            h.actions.usable = false
            h.input.start()
            h.input.onInteract(PlayerInteractEvent(player, Action.LEFT_CLICK_AIR,
                player.inventory.itemInMainHand, null, BlockFace.SELF, EquipmentSlot.HAND))

            h.actions.defenses shouldBe listOf(InputDefenseCall(player.uniqueId, StaffSpell.FAULT))
            h.actions.casts shouldBe emptyList()

            val target = world.spawn(Location(world, 0.5, 64.0, 3.0), org.bukkit.entity.Zombie::class.java)
            val attack = PrePlayerAttackEntityEvent(player, target, true)
            h.input.onPreAttack(attack)
            attack.isCancelled shouldBe true
            h.actions.defenses.size shouldBe 1
            h.actions.casts shouldBe emptyList()

            player.gameMode = org.bukkit.GameMode.CREATIVE
            val block = world.getBlockAt(0, 64, 0).apply { type = Material.STONE }
            val damage = BlockDamageEvent(player, block, player.inventory.itemInMainHand, true)
            val breakEvent = BlockBreakEvent(block, player)
            h.input.onBlockDamage(damage)
            h.input.onBlockBreak(breakEvent)
            damage.isCancelled shouldBe true
            breakEvent.isCancelled shouldBe true
            h.actions.defenses.size shouldBe 1
            h.actions.casts shouldBe emptyList()
        }
    }

    "channel hold updates and release differs from slot or quit cancellation" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-channel-input")
            val releasing = h.staffPlayer("staff-channel-release", world, StaffSpell.INFERNO)
            val slotCancel = h.staffPlayer("staff-channel-slot", world, StaffSpell.GRAVITY)
            val quitCancel = h.staffPlayer("staff-channel-quit", world, StaffSpell.GRAVITY)
            h.input.start()

            listOf(releasing, slotCancel, quitCancel).forEach { player ->
                h.input.onInteract(rightClick(player))
            }
            h.scheduler.tick(4)
            h.actions.channelBegins shouldBe listOf(
                InputDefenseCall(releasing.uniqueId, StaffSpell.INFERNO),
                InputDefenseCall(slotCancel.uniqueId, StaffSpell.GRAVITY),
                InputDefenseCall(quitCancel.uniqueId, StaffSpell.GRAVITY),
            )
            h.actions.channelUpdates.groupBy { it.playerId }.mapValues { (_, updates) -> updates.map { it.ticksHeld } } shouldBe
                listOf(releasing, slotCancel, quitCancel).associate { it.uniqueId to listOf(1, 2, 3, 4) }

            h.input.onStopUsing(PlayerStopUsingItemEvent(releasing,
                releasing.inventory.itemInMainHand.clone(), 12))
            h.input.onHeldSlot(org.bukkit.event.player.PlayerItemHeldEvent(slotCancel, 0, 1))
            h.input.onQuit(org.bukkit.event.player.PlayerQuitEvent(quitCancel, net.kyori.adventure.text.Component.empty()))

            h.actions.channelReleases shouldBe listOf(releasing.uniqueId)
            h.actions.channelCancels shouldBe listOf(slotCancel.uniqueId, quitCancel.uniqueId)
            h.actions.casts shouldBe emptyList()
        }
    }

    "sneak or selected-slot changes cancel a held charge before release" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge-cancel")
            val player = h.staffPlayer("staff-charge-cancel", world, StaffSpell.EMBER)
            h.input.start()
            h.input.onInteract(rightClick(player))
            h.input.onSneak(org.bukkit.event.player.PlayerToggleSneakEvent(player, true))
            h.input.onStopUsing(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 12))

            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            player.clearActiveItem()
            player.isSneaking = false

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
private data class InputDefenseCall(val playerId: UUID, val spell: StaffSpell)
private data class InputChannelUpdate(val playerId: UUID, val ticksHeld: Int)

private class RecordingStaffSpellInputActions : StaffSpellInputActions {
    val charges = mutableListOf<InputChargeCall>()
    val casts = mutableListOf<InputCastCall>()
    val cancelled = mutableListOf<UUID>()
    val defenses = mutableListOf<InputDefenseCall>()
    val channelBegins = mutableListOf<InputDefenseCall>()
    val channelUpdates = mutableListOf<InputChannelUpdate>()
    val channelReleases = mutableListOf<UUID>()
    val channelCancels = mutableListOf<UUID>()
    val releaseOrder = mutableListOf<String>()
    var usable = true
    var channelUsable = true

    override fun canCharge(player: org.bukkit.entity.Player, spell: StaffSpell) = usable
    override fun defend(player: org.bukkit.entity.Player, spell: StaffSpell) {
        defenses += InputDefenseCall(player.uniqueId, spell)
    }
    override fun beginChannel(player: org.bukkit.entity.Player, spell: StaffSpell): Boolean {
        channelBegins += InputDefenseCall(player.uniqueId, spell)
        return channelUsable
    }
    override fun updateChannel(player: org.bukkit.entity.Player, ticksHeld: Int): Boolean {
        channelUpdates += InputChannelUpdate(player.uniqueId, ticksHeld)
        return channelUsable
    }
    override fun releaseChannel(player: org.bukkit.entity.Player) { channelReleases += player.uniqueId }
    override fun cancelChannel(player: org.bukkit.entity.Player) { channelCancels += player.uniqueId }
    override fun cast(player: org.bukkit.entity.Player, input: StaffCastInput, charge: Double) {
        casts += InputCastCall(player.uniqueId, StaffSpell.from(player.inventory.itemInMainHand)!!, input, charge)
        releaseOrder += "cast"
        usable = false
    }
    override fun charge(player: org.bukkit.entity.Player, spell: StaffSpell, progress: Double) {
        charges += InputChargeCall(player.uniqueId, spell, progress)
    }
    override fun cancelCharge(player: org.bukkit.entity.Player) {
        cancelled += player.uniqueId
        releaseOrder += "cancel"
    }
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
