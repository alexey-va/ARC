package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.event.player.PlayerStopUsingItemEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class StaffSpellInputTest : FreeSpec({
    "consumed class ability and denied block inputs do not activate staff spells" {
        withInputHarness { h ->
            val player = h.staffPlayer("staff-class-input", h.paper.addSimpleWorld("class-input"), StaffSpell.CHAIN)
            val classInput = PlayerInteractEvent(player, Action.LEFT_CLICK_AIR,
                player.inventory.itemInMainHand, null, BlockFace.SELF, EquipmentSlot.HAND).apply {
                isCancelled = true
            }
            h.input.onInteract(classInput)
            h.actions.defenses shouldBe emptyList()
            h.actions.casts shouldBe emptyList()

            val deniedBlock = rightClick(player).apply { setUseInteractedBlock(org.bukkit.event.Event.Result.DENY) }
            h.input.onInteract(deniedBlock)
            h.actions.defenses shouldBe emptyList()
            h.actions.casts shouldBe emptyList()
        }
    }

    "owned left click denies both native use channels before FMM highest priority" {
        withInputHarness { h ->
            val player = h.staffPlayer("staff-native-input", h.paper.addSimpleWorld("native-input"), StaffSpell.CHAIN)
            val input = leftClick(player)
            h.input.onInteract(input)
            h.actions.casts.single().input shouldBe StaffCastInput.PRIMARY
            h.actions.defenses shouldBe emptyList()
            input.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
            input.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            input.isCancelled shouldBe true
            StaffSpellInput::class.java.getMethod("onInteract", PlayerInteractEvent::class.java)
                .getAnnotation(org.bukkit.event.EventHandler::class.java).priority shouldBe org.bukkit.event.EventPriority.HIGH
        }
    }

    "legacy consumable staff still defends on right click without starting native use" {
        withInputHarness { h ->
            val player = h.staffPlayer("legacy-use", h.paper.addSimpleWorld("legacy-use"), StaffSpell.CHAIN)
            val legacyStaff = player.inventory.itemInMainHand.apply {
                setData(DataComponentTypes.CONSUMABLE,
                    Consumable.consumable().consumeSeconds(3_600f).animation(ItemUseAnimation.NONE).build())
            }
            player.inventory.setItemInMainHand(legacyStaff)
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-input-legacy-use"))

            val event = rightClick(player)
            h.paper.server.pluginManager.callEvent(event)
            event.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
            event.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            player.hasActiveItem() shouldBe false
            h.actions.defenses shouldBe listOf(InputDefenseCall(player.uniqueId, StaffSpell.CHAIN, sneaking = false))
            h.actions.casts shouldBe emptyList()
            h.actions.charges shouldBe emptyList()

            h.paper.server.pluginManager.callEvent(PlayerStopUsingItemEvent(
                player, player.inventory.itemInMainHand.clone(), 36))
            h.actions.defenses.size shouldBe 1
            h.actions.casts shouldBe emptyList()
            h.actions.charges shouldBe emptyList()
        }
    }

    "retired Inferno stays identified for vanilla suppression without activating" {
        withInputHarness { h ->
            val player = h.staffPlayer("retired-inferno-input",
                h.paper.addSimpleWorld("retired-inferno-input"), StaffSpell.INFERNO)

            StaffSpell.from(player.inventory.itemInMainHand) shouldBe StaffSpell.INFERNO
            val attack = leftClick(player)
            h.input.onInteract(attack)
            val defense = rightClick(player)
            h.input.onInteract(defense)

            attack.isCancelled shouldBe true
            attack.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
            attack.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            defense.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
            defense.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            h.actions.casts shouldBe emptyList()
            h.actions.charges shouldBe emptyList()
            h.actions.channelBegins shouldBe emptyList()
            h.actions.defenses shouldBe emptyList()
        }
    }

    "primary and Shift attacks use left click, with only Ember and Icicle primary charging" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-input-policy")
            val chargedPrimaries = setOf(StaffSpell.EMBER, StaffSpell.ICICLE)
            StaffSpell.entries.forEach { spell ->
                if (spell !in StaffSpell.available || spell == StaffSpell.GRAVITY) return@forEach
                listOf(false, true).forEach { secondary ->
                    val player = h.staffPlayer("policy-${spell.id}-$secondary", world, spell).apply {
                        isSneaking = secondary
                    }
                    h.actions.usable = true
                    h.actions.channelUsable = true
                    val castsBefore = h.actions.casts.size
                    val chargesBefore = h.actions.charges.size
                    val channelsBefore = h.actions.channelBegins.size

                    h.input.onInteract(leftClick(player))

                    when {
                        spell in chargedPrimaries && !secondary -> {
                            h.actions.channelBegins.size shouldBe channelsBefore
                            h.actions.casts.size shouldBe castsBefore
                            h.actions.charges.size shouldBe chargesBefore + 1
                            h.actions.charges.last().let {
                                it.playerId shouldBe player.uniqueId
                                it.spell shouldBe spell
                                it.progress shouldBe 0.0
                            }
                            player.hasActiveItem() shouldBe false
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
            val config = StaffSpellConfig(StaffTestConfig(mapOf(
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

    "charge updates during the bounded click-toggle and the next left click releases it" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge")
            val player = h.staffPlayer("staff-charge", world, StaffSpell.EMBER)
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-input-charge"))

            val click = leftClick(player)
            h.paper.server.pluginManager.callEvent(click)
            click.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
            click.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            player.hasActiveItem() shouldBe false
            h.actions.casts shouldBe emptyList()

            h.advanceInputTicks(36)
            h.actions.charges.map { it.progress } shouldBe
                listOf(0.0, 0.0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0)
            h.actions.casts shouldBe emptyList()

            h.input.onInteract(leftClick(player))
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
            h.input.onInteract(leftClick(player))

            h.advanceInputTicks(1)
            h.actions.usable = false
            h.input.onInteract(leftClick(player))

            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.actions.releaseOrder shouldBe listOf("cancel")
        }
    }

    "tap release stays uncharged and a charge cannot outlive its bounded timer" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge-cap")
            val player = h.staffPlayer("staff-charge-cap", world, StaffSpell.EMBER)
            h.input.start()
            h.input.onInteract(leftClick(player))
            h.advanceInputTicks(2)
            h.input.onInteract(leftClick(player))

            h.actions.casts.single().charge shouldBe 0.0
            h.actions.casts.single().input shouldBe StaffCastInput.PRIMARY
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.actions.releaseOrder shouldBe listOf("cast", "cancel")

            h.actions.casts.clear()
            h.actions.cancelled.clear()
            h.actions.usable = true
            h.advanceInputTicks(1)
            h.input.onInteract(leftClick(player))
            h.advanceInputTicks(200)
            h.actions.casts shouldBe emptyList()
            h.actions.charges.last().progress shouldBe 1.0
            h.advanceInputTicks(1)
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.actions.casts shouldBe emptyList()
        }
    }

    "right click defends without charge permission and Shift selects the ward" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense")
            val player = h.staffPlayer("staff-defense", world, StaffSpell.FAULT)
            h.actions.usable = false
            h.input.start()
            val normal = rightClick(player)
            h.input.onInteract(normal)

            h.actions.defenses shouldBe listOf(InputDefenseCall(player.uniqueId, StaffSpell.FAULT, sneaking = false))
            h.actions.casts shouldBe emptyList()
            normal.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY

            h.input.interactEntity(player, EquipmentSlot.HAND) {}
            h.actions.defenses.size shouldBe 1

            h.advanceInputTicks(1)
            player.isSneaking = true
            h.input.onInteract(rightClick(player))
            h.actions.defenses.last() shouldBe InputDefenseCall(player.uniqueId, StaffSpell.FAULT, sneaking = true)
            h.actions.casts shouldBe emptyList()
        }
    }

    "sprinting uses normal spell input, and an active charge still releases on left click" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-sprint-input")
            val sprinting = h.staffPlayer("staff-sprinting", world, StaffSpell.CHAIN).apply { isSprinting = true }
            h.input.start()
            h.input.onInteract(leftClick(sprinting))
            h.actions.casts.single().let {
                it.playerId shouldBe sprinting.uniqueId
                it.input shouldBe StaffCastInput.PRIMARY
            }
            h.actions.defenses shouldBe emptyList()

            val charging = h.staffPlayer("staff-charge-melee", world, StaffSpell.EMBER)
            h.actions.usable = true
            h.advanceInputTicks(1)
            h.input.onInteract(leftClick(charging))
            h.advanceInputTicks(1)
            charging.isSprinting = true
            h.input.onInteract(leftClick(charging))
            h.actions.casts.last().spell shouldBe StaffSpell.EMBER
            h.actions.casts.last().charge shouldBe 0.0
        }
    }

    "modeled and native attacks share deduplication in either event order" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-modeled-input")
            val modeledFirst = h.staffPlayer("staff-modeled-first", world, StaffSpell.CHAIN)
            val nativeFirst = h.staffPlayer("staff-native-first", world, StaffSpell.CHAIN)
            h.input.start()

            var modeledFirstCanceled = 0
            h.input.attackModeledEntity(modeledFirst) { modeledFirstCanceled++ }
            val target = world.spawn(Location(world, 0.5, 64.0, 3.0), org.bukkit.entity.Zombie::class.java)
            val modeledFirstNative = PrePlayerAttackEntityEvent(modeledFirst, target, true)
            h.input.onPreAttack(modeledFirstNative)
            modeledFirstNative.isCancelled shouldBe true
            modeledFirstCanceled shouldBe 1
            h.actions.casts.size shouldBe 1

            h.advanceInputTicks(1)
            h.actions.usable = true
            val nativeFirstAttack = PrePlayerAttackEntityEvent(nativeFirst, target, true)
            h.input.onPreAttack(nativeFirstAttack)
            nativeFirstAttack.isCancelled shouldBe true
            var nativeFirstCanceled = 0
            h.input.attackModeledEntity(nativeFirst) { nativeFirstCanceled++ }
            nativeFirstCanceled shouldBe 1
            h.actions.casts.size shouldBe 2
            h.actions.casts.map { it.playerId } shouldBe listOf(modeledFirst.uniqueId, nativeFirst.uniqueId)
            h.actions.defenses shouldBe emptyList()
        }
    }

    "gravity channel begins on one left click and releases on the next" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-channel-toggle")
            val player = h.staffPlayer("staff-gravity-toggle", world, StaffSpell.GRAVITY)
            h.input.start()
            h.input.onInteract(leftClick(player))

            h.actions.channelBegins shouldBe listOf(InputSpellCall(player.uniqueId, StaffSpell.GRAVITY))
            player.hasActiveItem() shouldBe false
            h.actions.casts shouldBe emptyList()
            h.advanceInputTicks(4)
            h.actions.channelUpdates.map { it.ticksHeld } shouldBe listOf(1, 2, 3, 4)

            h.input.onInteract(leftClick(player))
            h.actions.channelReleases shouldBe listOf(player.uniqueId)
            h.actions.channelCancels shouldBe emptyList()
            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId)
        }
    }

    "right click cancels charge and gravity channel before defending" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-cancel")
            val charging = h.staffPlayer("staff-defense-charge", world, StaffSpell.EMBER)
            val channeling = h.staffPlayer("staff-defense-channel", world, StaffSpell.GRAVITY)
            h.input.start()
            h.input.onInteract(leftClick(charging))
            h.advanceInputTicks(1)
            h.input.onInteract(rightClick(charging))
            h.actions.cancelled shouldBe listOf(charging.uniqueId)
            h.actions.defenses shouldBe listOf(InputDefenseCall(charging.uniqueId, StaffSpell.EMBER, sneaking = false))

            h.advanceInputTicks(1)
            h.input.onInteract(leftClick(channeling))
            h.advanceInputTicks(1)
            h.input.onInteract(rightClick(channeling))
            h.actions.channelCancels shouldBe listOf(channeling.uniqueId)
            h.actions.defenses.last() shouldBe InputDefenseCall(channeling.uniqueId, StaffSpell.GRAVITY, sneaking = false)
        }
    }

    "vanilla melee and block breaking remain suppressed in Creative" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-creative")
            val player = h.staffPlayer("staff-creative", world, StaffSpell.CHAIN).apply {
                gameMode = org.bukkit.GameMode.CREATIVE
            }
            h.input.start()
            val block = world.getBlockAt(0, 64, 0).apply { type = Material.STONE }
            val damage = BlockDamageEvent(player, block, player.inventory.itemInMainHand, true)
            val breakEvent = BlockBreakEvent(block, player)
            h.input.onBlockDamage(damage)
            h.input.onBlockBreak(breakEvent)
            damage.isCancelled shouldBe true
            breakEvent.isCancelled shouldBe true
            val target = world.spawn(Location(world, 0.5, 64.0, 3.0), org.bukkit.entity.Zombie::class.java)
            val attack = PrePlayerAttackEntityEvent(player, target, true)
            h.input.onPreAttack(attack)
            attack.isCancelled shouldBe true
            h.actions.casts.single().input shouldBe StaffCastInput.PRIMARY
        }
    }

    "pre-cancelled native attacks do not activate staff spells" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-cancelled-attack")
            val player = h.staffPlayer("staff-cancelled-attack", world, StaffSpell.CHAIN)
            val target = world.spawn(Location(world, 0.5, 64.0, 3.0), org.bukkit.entity.Zombie::class.java)
            val attack = PrePlayerAttackEntityEvent(player, target, true).apply { isCancelled = true }

            h.input.onPreAttack(attack)

            attack.isCancelled shouldBe true
            h.actions.casts shouldBe emptyList()
            h.actions.defenses shouldBe emptyList()
        }
    }

    "gravity channel cancellation on slot change or quit remains distinct from click release" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-channel-input")
            val releasing = h.staffPlayer("staff-channel-release", world, StaffSpell.GRAVITY)
            val slotCancel = h.staffPlayer("staff-channel-slot", world, StaffSpell.GRAVITY)
            val quitCancel = h.staffPlayer("staff-channel-quit", world, StaffSpell.GRAVITY)
            h.input.start()

            listOf(releasing, slotCancel, quitCancel).forEach { player ->
                h.input.onInteract(leftClick(player))
            }
            h.advanceInputTicks(4)
            h.actions.channelBegins shouldBe listOf(
                InputSpellCall(releasing.uniqueId, StaffSpell.GRAVITY),
                InputSpellCall(slotCancel.uniqueId, StaffSpell.GRAVITY),
                InputSpellCall(quitCancel.uniqueId, StaffSpell.GRAVITY),
            )
            h.actions.channelUpdates.groupBy { it.playerId }.mapValues { (_, updates) -> updates.map { it.ticksHeld } } shouldBe
                listOf(releasing, slotCancel, quitCancel).associate { it.uniqueId to listOf(1, 2, 3, 4) }

            h.input.onInteract(leftClick(releasing))
            h.input.onHeldSlot(org.bukkit.event.player.PlayerItemHeldEvent(slotCancel, 0, 1))
            h.input.onQuit(org.bukkit.event.player.PlayerQuitEvent(quitCancel, net.kyori.adventure.text.Component.empty()))

            h.actions.channelReleases shouldBe listOf(releasing.uniqueId)
            h.actions.channelCancels shouldBe listOf(slotCancel.uniqueId, quitCancel.uniqueId)
            h.actions.casts shouldBe emptyList()
        }
    }

    "sneak or selected-slot changes cancel a charge before its next click" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge-cancel")
            val player = h.staffPlayer("staff-charge-cancel", world, StaffSpell.EMBER)
            h.input.start()
            h.input.onInteract(leftClick(player))
            h.input.onSneak(org.bukkit.event.player.PlayerToggleSneakEvent(player, true))
            h.input.onInteract(leftClick(player))

            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            player.isSneaking = false

            h.advanceInputTicks(1)

            h.input.onInteract(leftClick(player))
            h.input.onHeldSlot(org.bukkit.event.player.PlayerItemHeldEvent(player, 0, 1))
            h.input.onInteract(leftClick(player))
            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId, player.uniqueId)
        }
    }
})

private data class InputChargeCall(val playerId: UUID, val spell: StaffSpell, val progress: Double)
private data class InputCastCall(val playerId: UUID, val spell: StaffSpell, val input: StaffCastInput, val charge: Double)
private data class InputDefenseCall(val playerId: UUID, val spell: StaffSpell, val sneaking: Boolean)
private data class InputSpellCall(val playerId: UUID, val spell: StaffSpell)
private data class InputChannelUpdate(val playerId: UUID, val ticksHeld: Int)

private class RecordingStaffSpellInputActions : StaffSpellInputActions {
    val charges = mutableListOf<InputChargeCall>()
    val casts = mutableListOf<InputCastCall>()
    val cancelled = mutableListOf<UUID>()
    val defenses = mutableListOf<InputDefenseCall>()
    val channelBegins = mutableListOf<InputSpellCall>()
    val channelUpdates = mutableListOf<InputChannelUpdate>()
    val channelReleases = mutableListOf<UUID>()
    val channelCancels = mutableListOf<UUID>()
    val releaseOrder = mutableListOf<String>()
    var usable = true
    var channelUsable = true

    override fun canCharge(player: org.bukkit.entity.Player, spell: StaffSpell) =
        usable && spell in StaffSpell.available
    override fun defend(player: org.bukkit.entity.Player, spell: StaffSpell) {
        if (spell in StaffSpell.available) defenses += InputDefenseCall(player.uniqueId, spell, player.isSneaking)
    }
    override fun beginChannel(player: org.bukkit.entity.Player, spell: StaffSpell): Boolean {
        channelBegins += InputSpellCall(player.uniqueId, spell)
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
    fun advanceInputTicks(ticks: Long) {
        scheduler.tick(ticks)
        paper.performTicks(ticks)
    }

    fun staffPlayer(name: String, world: WorldMock, spell: StaffSpell): PlayerMock = paper.addPlayer(name).apply {
        teleport(Location(world, 0.5, 64.0, 0.5).apply { setDirection(Vector(0.0, 0.0, 1.0)) })
        inventory.setItemInMainHand(StaffSpellConfig(StaffTestConfig()).item(spell).apply {
            unsetData(DataComponentTypes.CONSUMABLE) // A pre-upgrade staff exercises runtime migration.
        })
        syncMockBukkitUseEquipment()
    }
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

private fun leftClick(player: PlayerMock) = PlayerInteractEvent(
    player,
    Action.LEFT_CLICK_AIR,
    player.inventory.itemInMainHand,
    null,
    BlockFace.SELF,
    EquipmentSlot.HAND,
).apply {
    setUseItemInHand(org.bukkit.event.Event.Result.DEFAULT)
    setUseInteractedBlock(org.bukkit.event.Event.Result.DEFAULT)
}
