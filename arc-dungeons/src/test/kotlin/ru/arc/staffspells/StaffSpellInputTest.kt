package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.event.player.PlayerStopUsingItemEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.block.Action
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EntityEquipment
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.meta.Damageable
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
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

    "owned left-click defense denies both native use channels before FMM highest priority" {
        withInputHarness { h ->
            val player = h.staffPlayer("staff-native-input", h.paper.addSimpleWorld("native-input"), StaffSpell.CHAIN)
            val input = leftClick(player)
            h.input.onInteract(input)
            h.actions.casts shouldBe emptyList()
            h.actions.defenses shouldBe listOf(InputDefenseCall(player.uniqueId, StaffSpell.CHAIN, sneaking = false))
            input.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
            input.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            input.isCancelled shouldBe true
            StaffSpellInput::class.java.getMethod("onInteract", PlayerInteractEvent::class.java)
                .getAnnotation(org.bukkit.event.EventHandler::class.java).priority shouldBe org.bukkit.event.EventPriority.HIGH
        }
    }

    "legacy consumable staff attacks on right click without starting native use" {
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
            h.actions.defenses shouldBe emptyList()
            h.actions.casts shouldBe listOf(InputCastCall(player.uniqueId, StaffSpell.CHAIN, StaffCastInput.PRIMARY, 0.0))
            h.actions.charges shouldBe emptyList()

            h.paper.server.pluginManager.callEvent(PlayerStopUsingItemEvent(
                player, player.inventory.itemInMainHand.clone(), 36))
            h.actions.casts.size shouldBe 1
            h.actions.defenses shouldBe emptyList()
            h.actions.charges shouldBe emptyList()
        }
    }

    "retired Inferno stays identified for vanilla suppression without activating" {
        withInputHarness { h ->
            val player = h.staffPlayer("retired-inferno-input",
                h.paper.addSimpleWorld("retired-inferno-input"), StaffSpell.INFERNO)

            StaffSpell.from(player.inventory.itemInMainHand) shouldBe StaffSpell.INFERNO
            val defense = leftClick(player)
            h.input.onInteract(defense)
            val attack = rightClick(player)
            h.input.onInteract(attack)

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

    "all available staves attack with right click and defend with left click, including Shift variants" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-input-policy")
            StaffSpell.entries.forEach { spell ->
                if (spell !in StaffSpell.available) return@forEach
                staffAttackInputButton(spell) shouldBe StaffSpellInputButton.RIGHT
                staffDefenseInputButton(spell) shouldBe StaffSpellInputButton.LEFT

                val primary = h.staffPlayer("policy-${spell.id}-primary", world, spell)
                val defense = leftClick(primary)
                h.input.onInteract(defense)
                h.actions.defenses.last() shouldBe InputDefenseCall(primary.uniqueId, spell, sneaking = false)
                defense.isCancelled shouldBe true
                defense.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
                defense.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
                h.actions.casts.none { it.playerId == primary.uniqueId } shouldBe true
                h.actions.charges.none { it.playerId == primary.uniqueId } shouldBe true
                h.actions.channelBegins.none { it.playerId == primary.uniqueId } shouldBe true

                h.advanceInputTicks(1)
                val attack = rightClick(primary)
                h.actions.usable = true
                h.actions.channelUsable = true
                val castsBefore = h.actions.casts.size
                val chargesBefore = h.actions.charges.size
                val channelsBefore = h.actions.channelBegins.size
                h.input.onInteract(attack)
                attack.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
                attack.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY

                when (staffAttackInputMode(spell, secondary = false)) {
                    StaffAttackInputMode.HOLD_RELEASE -> {
                        h.actions.casts.size shouldBe castsBefore
                        if (spell == StaffSpell.GRAVITY) {
                            h.actions.channelBegins.size shouldBe channelsBefore + 1
                            h.actions.charges.size shouldBe chargesBefore
                        } else {
                            h.actions.channelBegins.size shouldBe channelsBefore
                            h.actions.charges.size shouldBe chargesBefore + 1
                            h.actions.charges.last().let {
                                it.playerId shouldBe primary.uniqueId
                                it.spell shouldBe spell
                                it.progress shouldBe 0.0
                            }
                        }
                    }
                    StaffAttackInputMode.INSTANT -> {
                        h.actions.channelBegins.size shouldBe channelsBefore
                        h.actions.casts.size shouldBe castsBefore + 1
                        h.actions.casts.last().let {
                            it.playerId shouldBe primary.uniqueId
                            it.spell shouldBe spell
                            it.input shouldBe StaffCastInput.PRIMARY
                            it.charge shouldBe 0.0
                        }
                        h.actions.charges.size shouldBe chargesBefore
                    }
                }
                h.input.cancel(primary.uniqueId)

                val shifted = h.staffPlayer("policy-${spell.id}-shift", world, spell).apply { isSneaking = true }
                h.advanceInputTicks(1)
                val shiftedCastsBeforeDefense = h.actions.casts.size
                val shiftedChargesBeforeDefense = h.actions.charges.size
                val shiftedChannelsBeforeDefense = h.actions.channelBegins.size
                h.input.onInteract(leftClick(shifted))
                h.actions.defenses.last() shouldBe InputDefenseCall(shifted.uniqueId, spell, sneaking = true)
                h.actions.casts.size shouldBe shiftedCastsBeforeDefense
                h.actions.charges.size shouldBe shiftedChargesBeforeDefense
                h.actions.channelBegins.size shouldBe shiftedChannelsBeforeDefense
                h.advanceInputTicks(1)
                val shiftedAttack = rightClick(shifted)
                h.actions.usable = true
                val shiftedCastsBefore = h.actions.casts.size
                val shiftedChargesBefore = h.actions.charges.size
                val shiftedChannelsBefore = h.actions.channelBegins.size
                h.input.onInteract(shiftedAttack)
                h.actions.casts.size shouldBe shiftedCastsBefore + 1
                h.actions.casts.last().let {
                    it.playerId shouldBe shifted.uniqueId
                    it.spell shouldBe spell
                    it.input shouldBe StaffCastInput.SECONDARY
                    it.charge shouldBe 0.0
                }
                h.actions.charges.size shouldBe shiftedChargesBefore
                h.actions.channelBegins.size shouldBe shiftedChannelsBefore
            }
        }
    }

    "staff lore explains instant, charged, and channel input separately" {
        withInputHarness { _ ->
            val config = StaffSpellConfig(StaffTestConfig(mapOf(
                "messages.use" to "<attack> <attack-secondary> <defense> <defense-secondary>",
                "messages.charge-use" to "hold-for-charge <attack> <attack-secondary> <defense> <defense-secondary>",
                "messages.channel-use" to "hold-channel <attack> <attack-secondary> <defense> <defense-secondary>",
            )))
            fun lore(spell: StaffSpell) = config.item(spell).lore()!!.joinToString(" ") {
                PlainTextComponentSerializer.plainText().serialize(it)
            }

            lore(StaffSpell.CHAIN).contains("ПКМ Shift + ПКМ ЛКМ Shift + ЛКМ") shouldBe true
            lore(StaffSpell.EMBER).contains("hold-for-charge ПКМ Shift + ПКМ ЛКМ Shift + ЛКМ") shouldBe true
            lore(StaffSpell.ICICLE).contains("hold-for-charge ПКМ Shift + ПКМ ЛКМ Shift + ЛКМ") shouldBe true
            lore(StaffSpell.GRAVITY).contains("hold-channel") shouldBe true
            lore(StaffSpell.INFERNO).contains("hold-channel") shouldBe true
        }
    }

    "Q waits for the restored main-hand staff and routes once after charge eligibility" {
        withInputHarness { h ->
            val player = h.staffPlayer("staff-q-restore", h.paper.addSimpleWorld("staff-q-restore"), StaffSpell.EMBER)
            player.syncMockActiveUseEquipment()
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-q-restore-listener"))
            h.input.onInteract(rightClick(player))
            val staff = player.inventory.itemInMainHand.clone()

            player.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(Material.AIR))
            val firstDrop = player.world.dropItem(player.location, staff.clone())
            val firstEvent = PlayerDropItemEvent(player, firstDrop)
            h.paper.server.pluginManager.callEvent(firstEvent)
            firstEvent.isCancelled shouldBe true
            player.inventory.itemInMainHand.type shouldBe Material.AIR
            h.actions.hotkeyChecks shouldBe emptyList()
            h.actions.hotkeys shouldBe emptyList()

            val duplicateDrop = player.world.dropItem(player.location, staff.clone())
            val duplicateEvent = PlayerDropItemEvent(player, duplicateDrop)
            h.paper.server.pluginManager.callEvent(duplicateEvent)
            duplicateEvent.isCancelled shouldBe true

            // Paper restores a cancelled Q drop after the event; the handler never writes the stack itself.
            player.inventory.setItemInMainHand(staff.clone())
            val sameTickF = PlayerSwapHandItemsEvent(player, staff.clone(),
                org.bukkit.inventory.ItemStack(Material.AIR))
            h.paper.server.pluginManager.callEvent(sameTickF)
            sameTickF.isCancelled shouldBe true
            h.advanceInputTicks(1)

            val expected = InputHotkeyCall(player.uniqueId, StaffSpell.EMBER, StaffHotkey.Q)
            h.actions.hotkeyChecks shouldBe listOf(expected)
            h.actions.hotkeys shouldBe listOf(expected)
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.actions.hotkeyOrder shouldBe listOf("check:Q", "cancel", "hotkey:Q")
            player.inventory.itemInMainHand.isSimilar(staff) shouldBe true
        }
    }

    "Creative world Q keeps the staff and casts while creative inventory drops stay native" {
        withInputHarness { h ->
            val player = h.staffPlayer("creative-q", h.paper.addSimpleWorld("creative-q"), StaffSpell.CHAIN)
            player.gameMode = org.bukkit.GameMode.CREATIVE
            // Paper CraftInventoryView.getType returns CREATIVE for the player's default crafting view.
            // MockBukkit does not implement this gamemode conversion, so supply that exact view contract.
            val view = mockk<InventoryView>(relaxed = true)
            every { view.player } returns player
            every { view.type } returns InventoryType.CREATIVE
            every { view.topInventory } returns player.openInventory.topInventory
            every { view.bottomInventory } returns player.inventory
            every { view.convertSlot(any()) } answers { firstArg() }
            player.openInventory(view)
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("creative-q-listener"))
            val staff = player.inventory.itemInMainHand.clone()
            player.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(Material.AIR))
            val worldDrop = PlayerDropItemEvent(player, player.world.dropItem(player.location, staff.clone()))
            h.paper.server.pluginManager.callEvent(worldDrop)
            worldDrop.isCancelled shouldBe true
            player.inventory.setItemInMainHand(staff.clone())
            h.advanceInputTicks(1)
            h.actions.hotkeys shouldBe listOf(InputHotkeyCall(player.uniqueId, StaffSpell.CHAIN, StaffHotkey.Q))
            player.inventory.itemInMainHand.isSimilar(staff) shouldBe true

            val creativeDrop = InventoryCreativeEvent(view, InventoryType.SlotType.OUTSIDE, -999, staff.clone())
            h.paper.server.pluginManager.callEvent(creativeDrop)
            val inventoryDrop = PlayerDropItemEvent(player, player.world.dropItem(player.location, staff.clone()))
            h.paper.server.pluginManager.callEvent(inventoryDrop)
            inventoryDrop.isCancelled shouldBe false
            h.advanceInputTicks(1)
            h.actions.hotkeys.size shouldBe 1
        }
    }

    "F routes an eligible main-hand staff but leaves Shift+F and cancelled swaps alone" {
        withInputHarness { h ->
            val player = h.staffPlayer("staff-f-hotkey", h.paper.addSimpleWorld("staff-f-hotkey"), StaffSpell.CHAIN)
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-f-hotkey-listener"))

            val swap = PlayerSwapHandItemsEvent(player, player.inventory.itemInMainHand.clone(),
                org.bukkit.inventory.ItemStack(Material.AIR))
            h.paper.server.pluginManager.callEvent(swap)
            swap.isCancelled shouldBe true
            val expected = InputHotkeyCall(player.uniqueId, StaffSpell.CHAIN, StaffHotkey.F)
            h.actions.hotkeyChecks shouldBe listOf(expected)
            h.actions.hotkeys shouldBe listOf(expected)

            val duplicateSwap = PlayerSwapHandItemsEvent(player, player.inventory.itemInMainHand.clone(),
                org.bukkit.inventory.ItemStack(Material.AIR))
            h.paper.server.pluginManager.callEvent(duplicateSwap)
            duplicateSwap.isCancelled shouldBe true
            h.actions.hotkeyChecks.size shouldBe 1
            h.actions.hotkeys.size shouldBe 1

            val charging = h.staffPlayer("staff-f-blocked", h.paper.addSimpleWorld("staff-f-blocked"), StaffSpell.EMBER)
            charging.syncMockActiveUseEquipment()
            h.input.onInteract(rightClick(charging))
            h.actions.hotkeyUsable = false
            val unavailableSwap = PlayerSwapHandItemsEvent(charging,
                charging.inventory.itemInMainHand.clone(), org.bukkit.inventory.ItemStack(Material.AIR))
            h.paper.server.pluginManager.callEvent(unavailableSwap)
            unavailableSwap.isCancelled shouldBe true
            h.actions.hotkeyChecks.last() shouldBe InputHotkeyCall(charging.uniqueId, StaffSpell.EMBER, StaffHotkey.F)
            h.actions.hotkeys.size shouldBe 1
            h.actions.cancelled shouldBe emptyList()
            charging.hasActiveItem() shouldBe true

            h.advanceInputTicks(1)
            player.isSneaking = true
            val shiftSwap = PlayerSwapHandItemsEvent(player, player.inventory.itemInMainHand.clone(),
                org.bukkit.inventory.ItemStack(Material.AIR))
            h.paper.server.pluginManager.callEvent(shiftSwap)
            shiftSwap.isCancelled shouldBe false
            h.actions.hotkeys.size shouldBe 1

            val checksBeforeBlockedSwap = h.actions.hotkeyChecks.size
            val blockedSwap = PlayerSwapHandItemsEvent(player, player.inventory.itemInMainHand.clone(),
                org.bukkit.inventory.ItemStack(Material.AIR)).apply { isCancelled = true }
            h.paper.server.pluginManager.callEvent(blockedSwap)
            blockedSwap.isCancelled shouldBe true
            h.actions.hotkeyChecks.size shouldBe checksBeforeBlockedSwap
            h.actions.hotkeys.size shouldBe 1
        }
    }

    "inventory drops stay native and already-cancelled world drops cannot activate a staff hotkey" {
        withInputHarness { h ->
            val player = h.staffPlayer("staff-inventory-q", h.paper.addSimpleWorld("staff-inventory-q"), StaffSpell.CHAIN)
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-inventory-q-listener"))

            val inventoryDrop = inventoryClick(player, ClickType.DROP, InventoryAction.DROP_ONE_SLOT)
            h.paper.server.pluginManager.callEvent(inventoryDrop)
            val pairedDrop = PlayerDropItemEvent(player,
                player.world.dropItem(player.location, player.inventory.itemInMainHand.clone()))
            h.paper.server.pluginManager.callEvent(pairedDrop)
            pairedDrop.isCancelled shouldBe false
            h.actions.hotkeys shouldBe emptyList()

            val controlInventoryDrop = inventoryClick(player, ClickType.CONTROL_DROP, InventoryAction.DROP_ALL_SLOT)
            h.paper.server.pluginManager.callEvent(controlInventoryDrop)
            val controlPairedDrop = PlayerDropItemEvent(player,
                player.world.dropItem(player.location, player.inventory.itemInMainHand.clone()))
            h.paper.server.pluginManager.callEvent(controlPairedDrop)
            controlPairedDrop.isCancelled shouldBe false
            h.actions.hotkeys shouldBe emptyList()

            player.openInventory(h.paper.server.createInventory(player, InventoryType.CHEST))
            val containerDrop = PlayerDropItemEvent(player,
                player.world.dropItem(player.location, player.inventory.itemInMainHand.clone()))
            h.paper.server.pluginManager.callEvent(containerDrop)
            containerDrop.isCancelled shouldBe false
            player.closeInventory()

            h.advanceInputTicks(1)
            val preCancelled = PlayerDropItemEvent(player,
                player.world.dropItem(player.location, player.inventory.itemInMainHand.clone())).apply { isCancelled = true }
            h.paper.server.pluginManager.callEvent(preCancelled)
            preCancelled.isCancelled shouldBe true
            h.advanceInputTicks(1)

            val stackedStaff = player.inventory.itemInMainHand.clone().apply { amount = 2 }
            player.inventory.setItemInMainHand(stackedStaff.clone())
            val multiItemDrop = PlayerDropItemEvent(player,
                player.world.dropItem(player.location, stackedStaff.clone()))
            h.paper.server.pluginManager.callEvent(multiItemDrop)
            multiItemDrop.isCancelled shouldBe true
            h.advanceInputTicks(1)
            h.actions.hotkeyChecks shouldBe emptyList()
            h.actions.hotkeys shouldBe emptyList()
        }
    }

    "pending Q is discarded on inventory, slot, world, and quit transitions" {
        withInputHarness { h ->
            val originalWorld = h.paper.addSimpleWorld("staff-q-stale")
            val inventoryPlayer = h.staffPlayer("staff-q-inventory-stale", originalWorld, StaffSpell.CHAIN)
            val slotPlayer = h.staffPlayer("staff-q-slot-stale", originalWorld, StaffSpell.CHAIN)
            val worldPlayer = h.staffPlayer("staff-q-world-stale", originalWorld, StaffSpell.CHAIN)
            val quitPlayer = h.staffPlayer("staff-q-quit-stale", originalWorld, StaffSpell.CHAIN)
            val deathPlayer = h.staffPlayer("staff-q-death-stale", originalWorld, StaffSpell.CHAIN)
            val changedItemPlayer = h.staffPlayer("staff-q-item-stale", originalWorld, StaffSpell.CHAIN)
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-q-stale-listener"))

            fun queueRestoredQ(player: PlayerMock) {
                val staff = player.inventory.itemInMainHand.clone()
                player.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(Material.AIR))
                h.paper.server.pluginManager.callEvent(PlayerDropItemEvent(player,
                    player.world.dropItem(player.location, staff.clone())))
                player.inventory.setItemInMainHand(staff)
            }
            queueRestoredQ(inventoryPlayer)
            queueRestoredQ(slotPlayer)
            queueRestoredQ(worldPlayer)
            queueRestoredQ(quitPlayer)
            queueRestoredQ(deathPlayer)
            queueRestoredQ(changedItemPlayer)

            h.paper.server.pluginManager.callEvent(
                inventoryClick(inventoryPlayer, ClickType.LEFT, InventoryAction.PICKUP_ALL))

            slotPlayer.inventory.setItem(1, slotPlayer.inventory.itemInMainHand.clone())
            slotPlayer.inventory.heldItemSlot = 1
            h.paper.server.pluginManager.callEvent(PlayerItemHeldEvent(slotPlayer, 0, 1))

            val destination = h.paper.addSimpleWorld("staff-q-destination")
            worldPlayer.teleport(Location(destination, 0.5, 64.0, 0.5))
            h.paper.server.pluginManager.callEvent(PlayerChangedWorldEvent(worldPlayer, originalWorld))

            h.paper.server.pluginManager.callEvent(PlayerQuitEvent(quitPlayer, Component.empty()))
            h.paper.server.pluginManager.callEvent(PlayerDeathEvent(deathPlayer,
                DamageSource.builder(DamageType.GENERIC).build(), emptyList(), 0, "died"))
            changedItemPlayer.inventory.setItemInMainHand(
                StaffSpellConfig(StaffTestConfig()).item(StaffSpell.FAULT))
            h.advanceInputTicks(1)

            h.actions.hotkeyChecks shouldBe emptyList()
            h.actions.hotkeys shouldBe emptyList()
        }
    }

    "close discards pending Q work" {
        withInputHarness { h ->
            val player = h.staffPlayer("staff-q-close", h.paper.addSimpleWorld("staff-q-close"), StaffSpell.CHAIN)
            h.input.start()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-q-close-listener"))
            val staff = player.inventory.itemInMainHand.clone()
            player.inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(Material.AIR))
            h.paper.server.pluginManager.callEvent(PlayerDropItemEvent(player,
                player.world.dropItem(player.location, staff.clone())))
            player.inventory.setItemInMainHand(staff)

            h.input.close()
            h.advanceInputTicks(1)
            h.actions.hotkeyChecks shouldBe emptyList()
            h.actions.hotkeys shouldBe emptyList()
        }
    }

    "Ember, Icicle and Gravity items carry a silent long-use component and legacy stacks are upgraded once" {
        withInputHarness { _ ->
            val config = StaffSpellConfig(StaffTestConfig())
            listOf(StaffSpell.EMBER, StaffSpell.ICICLE, StaffSpell.GRAVITY).forEach { spell ->
                val item = config.item(spell)
                val consumable = item.getData(DataComponentTypes.CONSUMABLE)!!
                consumable.consumeSeconds() shouldBe 3_600f
                consumable.animation() shouldBe ItemUseAnimation.NONE
                consumable.hasConsumeParticles() shouldBe false
                consumable.consumeEffects() shouldBe emptyList()
                ensureStaffChargeUseComponent(item, spell) shouldBe false
            }
            config.item(StaffSpell.CHAIN).getData(DataComponentTypes.CONSUMABLE) shouldBe null

            val legacy = config.item(StaffSpell.EMBER).apply {
                setData(DataComponentTypes.CONSUMABLE,
                    Consumable.consumable().consumeSeconds(0.05f).build())
            }
            ensureStaffChargeUseComponent(legacy, StaffSpell.EMBER) shouldBe true
            ensureStaffChargeUseComponent(legacy, StaffSpell.EMBER) shouldBe false
            legacy.getData(DataComponentTypes.CONSUMABLE)!!.consumeSeconds() shouldBe 3_600f
        }
    }

    "held legacy prototype refreshes only its owned lore once" {
        withInputHarness { h ->
            val config = StaffSpellConfig(StaffTestConfig())
            h.actions.config = config
            val expectedLore = config.item(StaffSpell.EMBER).lore()!!
            val item = org.bukkit.inventory.ItemStack(Material.DIAMOND_SWORD)
            val meta = item.itemMeta as Damageable
            val loreWithStaleInstructions = expectedLore.dropLast(1).toMutableList().apply {
                add(Component.text("Старое управление: ЛКМ"))
                add(Component.text("Посторонняя строка сохранена"))
            }
            meta.displayName(Component.text("Сохранённый предмет"))
            meta.lore(loreWithStaleInstructions)
            meta.damage = 17
            meta.addEnchant(Enchantment.UNBREAKING, 2, true)
            meta.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, StaffSpell.EMBER.id)
            val marker = NamespacedKey("arc", "staff_lore_refresh_test")
            meta.persistentDataContainer.set(marker, PersistentDataType.STRING, "preserve-me")
            item.itemMeta = meta
            val player = h.paper.addPlayer("staff-lore-refresh")
            player.inventory.setItemInMainHand(item)

            h.input.start()
            val refreshed = player.inventory.itemInMainHand
            h.actions.prototypeLoreRefreshWrites shouldBe 1
            refreshed.lore()!!.take(10) shouldBe expectedLore
            refreshed.lore()!!.last() shouldBe Component.text("Посторонняя строка сохранена")
            refreshed.type shouldBe Material.DIAMOND_SWORD
            refreshed.itemMeta!!.displayName() shouldBe Component.text("Сохранённый предмет")
            (refreshed.itemMeta as Damageable).damage shouldBe 17
            refreshed.itemMeta!!.hasEnchant(Enchantment.UNBREAKING) shouldBe true
            refreshed.itemMeta!!.persistentDataContainer.get(marker, PersistentDataType.STRING) shouldBe "preserve-me"

            h.input.onJoin(org.bukkit.event.player.PlayerJoinEvent(player, "rejoined"))
            h.actions.prototypeLoreRefreshAttempts shouldBe 2
            h.actions.prototypeLoreRefreshWrites shouldBe 1
            player.inventory.itemInMainHand.lore() shouldBe refreshed.lore()
        }
    }

    "native right-button release fires the bounded charge once without consuming the staff" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge")
            val player = h.staffPlayer("staff-charge", world, StaffSpell.EMBER)
            h.input.start()
            player.syncMockActiveUseEquipment()
            h.paper.server.pluginManager.registerEvents(h.input, h.paper.createSimplePlugin("staff-input-charge"))
            var modeledLaunches = 0
            val fmmInput = object : Listener {
                @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
                fun onInteract(event: PlayerInteractEvent) {
                    if (event.useItemInHand() != org.bukkit.event.Event.Result.DENY ||
                        event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY) modeledLaunches++
                }
            }
            h.paper.server.pluginManager.registerEvents(fmmInput, h.paper.createSimplePlugin("staff-fmm-charge"))

            val click = rightClick(player)
            h.paper.server.pluginManager.callEvent(click)
            click.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
            click.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            modeledLaunches shouldBe 0
            h.actions.nativeUseWhenCharged shouldBe listOf(true)
            player.hasActiveItem() shouldBe true
            h.actions.casts shouldBe emptyList()

            h.advanceInputTicks(36)
            h.actions.charges.map { it.progress } shouldBe
                listOf(0.0, 0.0, 0.125, 0.25, 0.375, 0.5, 0.625, 0.75, 0.875, 1.0)
            h.actions.casts shouldBe emptyList()

            val release = PlayerStopUsingItemEvent(player, player.inventory.itemInMainHand.clone(), 36)
            h.paper.server.pluginManager.callEvent(release)
            h.actions.casts.size shouldBe 1
            h.actions.casts.single().spell shouldBe StaffSpell.EMBER
            h.actions.casts.single().input shouldBe StaffCastInput.PRIMARY
            h.actions.casts.single().charge shouldBe 1.0
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.actions.releaseOrder shouldBe listOf("cast", "cancel")
            player.hasActiveItem() shouldBe false

            h.paper.server.pluginManager.callEvent(release)
            h.actions.casts.size shouldBe 1
        }
    }

    "modeled right-click starts the same native hold-release path" {
        withInputHarness { h ->
            val player = h.staffPlayer("staff-modeled-hold", h.paper.addSimpleWorld("staff-modeled-hold"), StaffSpell.ICICLE)
            player.syncMockActiveUseEquipment()
            h.input.interactEntity(player, EquipmentSlot.HAND) {}

            h.actions.nativeUseWhenCharged shouldBe listOf(true)
            player.hasActiveItem() shouldBe true
            h.actions.charges.single().let {
                it.playerId shouldBe player.uniqueId
                it.spell shouldBe StaffSpell.ICICLE
                it.progress shouldBe 0.0
            }
            h.input.onStopUsingItem(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 20))
            h.actions.casts.single().let {
                it.spell shouldBe StaffSpell.ICICLE
                it.input shouldBe StaffCastInput.PRIMARY
                it.charge shouldBe 0.5
            }
        }
    }

    "native release is uncharged on a tap and unavailable casts still clean up" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge-cooldown")
            val player = h.staffPlayer("staff-charge-cooldown", world, StaffSpell.EMBER)
            h.input.start()
            h.input.onInteract(rightClick(player))
            player.startUsingItem(EquipmentSlot.HAND)

            val tap = PlayerStopUsingItemEvent(player, player.inventory.itemInMainHand.clone(), 2)
            h.input.onStopUsingItem(tap)
            h.actions.casts.single().charge shouldBe 0.0
            h.actions.releaseOrder.clear()
            h.actions.casts.clear()

            h.advanceInputTicks(1)
            h.actions.usable = true
            h.input.onInteract(rightClick(player))
            player.startUsingItem(EquipmentSlot.HAND)
            h.actions.usable = false
            h.input.onStopUsingItem(PlayerStopUsingItemEvent(player,
                player.inventory.itemInMainHand.clone(), 36))

            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId, player.uniqueId)
            h.actions.releaseOrder shouldBe listOf("cancel")
        }
    }

    "a native hold cannot outlive its bounded charge timer" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-charge-cap")
            val player = h.staffPlayer("staff-charge-cap", world, StaffSpell.EMBER)
            h.input.start()
            h.input.onInteract(rightClick(player))
            player.startUsingItem(EquipmentSlot.HAND)
            h.advanceInputTicks(200)
            h.actions.casts shouldBe emptyList()
            h.actions.charges.last().progress shouldBe 1.0
            h.advanceInputTicks(1)
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            h.actions.casts shouldBe emptyList()
            player.hasActiveItem() shouldBe false
        }
    }

    "left click defends without charge permission and Shift selects absorption" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense")
            val player = h.staffPlayer("staff-defense", world, StaffSpell.FAULT)
            h.actions.usable = false
            h.input.start()
            val normal = leftClick(player)
            h.input.onInteract(normal)

            h.actions.defenses shouldBe listOf(InputDefenseCall(player.uniqueId, StaffSpell.FAULT, sneaking = false))
            h.actions.casts shouldBe emptyList()
            normal.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY

            h.advanceInputTicks(1)
            player.isSneaking = true
            h.input.onInteract(leftClick(player))
            h.actions.defenses.last() shouldBe InputDefenseCall(player.uniqueId, StaffSpell.FAULT, sneaking = true)
            h.actions.casts shouldBe emptyList()
        }
    }

    "sprinting does not change right-click attacks or native charge release" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-sprint-input")
            val sprinting = h.staffPlayer("staff-sprinting", world, StaffSpell.CHAIN).apply { isSprinting = true }
            h.input.start()
            h.input.onInteract(rightClick(sprinting))
            h.actions.casts.single().let {
                it.playerId shouldBe sprinting.uniqueId
                it.input shouldBe StaffCastInput.PRIMARY
            }
            h.actions.defenses shouldBe emptyList()

            val charging = h.staffPlayer("staff-charge-melee", world, StaffSpell.EMBER)
            h.actions.usable = true
            h.advanceInputTicks(1)
            h.input.onInteract(rightClick(charging))
            charging.startUsingItem(EquipmentSlot.HAND)
            h.advanceInputTicks(1)
            charging.isSprinting = true
            h.input.onStopUsingItem(PlayerStopUsingItemEvent(charging,
                charging.inventory.itemInMainHand.clone(), 2))
            h.actions.casts.last().spell shouldBe StaffSpell.EMBER
            h.actions.casts.last().charge shouldBe 0.0
        }
    }

    "modeled and native left-click defenses share deduplication in either event order" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-modeled-input")
            val modeledFirst = h.staffPlayer("staff-modeled-first", world, StaffSpell.CHAIN)
            val nativeFirst = h.staffPlayer("staff-native-first", world, StaffSpell.CHAIN)
            h.input.start()

            var modeledFirstCanceled = 0
            h.input.leftClickModeledEntity(modeledFirst) { modeledFirstCanceled++ }
            val target = world.spawn(Location(world, 0.5, 64.0, 3.0), org.bukkit.entity.Zombie::class.java)
            val modeledFirstNative = PrePlayerAttackEntityEvent(modeledFirst, target, true)
            h.input.onPreAttack(modeledFirstNative)
            modeledFirstNative.isCancelled shouldBe true
            modeledFirstCanceled shouldBe 1
            h.actions.casts shouldBe emptyList()
            h.actions.defenses shouldBe listOf(InputDefenseCall(modeledFirst.uniqueId, StaffSpell.CHAIN, sneaking = false))

            h.advanceInputTicks(1)
            h.actions.usable = true
            val nativeFirstAttack = PrePlayerAttackEntityEvent(nativeFirst, target, true)
            h.input.onPreAttack(nativeFirstAttack)
            nativeFirstAttack.isCancelled shouldBe true
            var nativeFirstCanceled = 0
            h.input.leftClickModeledEntity(nativeFirst) { nativeFirstCanceled++ }
            nativeFirstCanceled shouldBe 1
            h.actions.casts shouldBe emptyList()
            h.actions.defenses shouldBe listOf(
                InputDefenseCall(modeledFirst.uniqueId, StaffSpell.CHAIN, sneaking = false),
                InputDefenseCall(nativeFirst.uniqueId, StaffSpell.CHAIN, sneaking = false),
            )
        }
    }

    "gravity holds the same channel through repeated use and releases once on native stop" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-channel-toggle")
            val player = h.staffPlayer("staff-gravity-toggle", world, StaffSpell.GRAVITY)
            player.syncMockActiveUseEquipment()
            h.input.start()
            h.input.onInteract(rightClick(player))

            h.actions.channelBegins shouldBe listOf(InputSpellCall(player.uniqueId, StaffSpell.GRAVITY))
            player.hasActiveItem() shouldBe true
            h.actions.casts shouldBe emptyList()
            h.advanceInputTicks(4)
            h.actions.channelUpdates.map { it.ticksHeld } shouldBe listOf(1, 2, 3, 4)

            h.input.onInteract(rightClick(player))
            h.actions.channelBegins.size shouldBe 1
            h.actions.channelReleases shouldBe emptyList()
            val stop = PlayerStopUsingItemEvent(player, player.inventory.itemInMainHand.clone(), 4)
            h.input.onStopUsingItem(stop)
            h.input.onStopUsingItem(stop)
            player.hasActiveItem() shouldBe false
            h.actions.channelReleases shouldBe listOf(player.uniqueId)
            h.actions.channelCancels shouldBe emptyList()
            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId)
        }
    }

    "left click cancels charge and gravity channel before defending" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-cancel")
            val charging = h.staffPlayer("staff-defense-charge", world, StaffSpell.EMBER)
            val channeling = h.staffPlayer("staff-defense-channel", world, StaffSpell.GRAVITY)
            h.input.start()
            h.input.onInteract(rightClick(charging))
            h.advanceInputTicks(1)
            h.input.onInteract(leftClick(charging))
            h.actions.cancelled shouldBe listOf(charging.uniqueId)
            h.actions.defenses shouldBe listOf(InputDefenseCall(charging.uniqueId, StaffSpell.EMBER, sneaking = false))

            h.advanceInputTicks(1)
            h.input.onInteract(rightClick(channeling))
            h.advanceInputTicks(1)
            h.input.onInteract(leftClick(channeling))
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
            h.actions.casts shouldBe emptyList()
            h.actions.defenses shouldBe listOf(InputDefenseCall(player.uniqueId, StaffSpell.CHAIN, sneaking = false))
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

    "gravity channel cancellation on slot change or quit remains distinct from native release" {
        withInputHarness { h ->
            val world = h.paper.addSimpleWorld("staff-channel-input")
            val releasing = h.staffPlayer("staff-channel-release", world, StaffSpell.GRAVITY)
            val slotCancel = h.staffPlayer("staff-channel-slot", world, StaffSpell.GRAVITY)
            val quitCancel = h.staffPlayer("staff-channel-quit", world, StaffSpell.GRAVITY)
            h.input.start()

            listOf(releasing, slotCancel, quitCancel).forEach { player ->
                h.input.onInteract(rightClick(player))
            }
            h.advanceInputTicks(4)
            h.actions.channelBegins shouldBe listOf(
                InputSpellCall(releasing.uniqueId, StaffSpell.GRAVITY),
                InputSpellCall(slotCancel.uniqueId, StaffSpell.GRAVITY),
                InputSpellCall(quitCancel.uniqueId, StaffSpell.GRAVITY),
            )
            h.actions.channelUpdates.groupBy { it.playerId }.mapValues { (_, updates) -> updates.map { it.ticksHeld } } shouldBe
                listOf(releasing, slotCancel, quitCancel).associate { it.uniqueId to listOf(1, 2, 3, 4) }

            h.input.onStopUsingItem(PlayerStopUsingItemEvent(releasing, releasing.inventory.itemInMainHand.clone(), 4))
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
            h.input.onInteract(rightClick(player))
            player.startUsingItem(EquipmentSlot.HAND)
            h.input.onSneak(org.bukkit.event.player.PlayerToggleSneakEvent(player, true))
            h.input.onInteract(leftClick(player))

            h.actions.casts shouldBe emptyList()
            h.actions.cancelled shouldBe listOf(player.uniqueId)
            player.isSneaking = false

            h.advanceInputTicks(1)

            h.input.onInteract(rightClick(player))
            player.startUsingItem(EquipmentSlot.HAND)
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
private data class InputHotkeyCall(val playerId: UUID, val spell: StaffSpell, val key: StaffHotkey)

private class RecordingStaffSpellInputActions : StaffSpellInputActions {
    val charges = mutableListOf<InputChargeCall>()
    val casts = mutableListOf<InputCastCall>()
    val cancelled = mutableListOf<UUID>()
    val defenses = mutableListOf<InputDefenseCall>()
    val channelBegins = mutableListOf<InputSpellCall>()
    val channelUpdates = mutableListOf<InputChannelUpdate>()
    val channelReleases = mutableListOf<UUID>()
    val channelCancels = mutableListOf<UUID>()
    val nativeUseWhenCharged = mutableListOf<Boolean>()
    val releaseOrder = mutableListOf<String>()
    val hotkeyChecks = mutableListOf<InputHotkeyCall>()
    val hotkeys = mutableListOf<InputHotkeyCall>()
    val hotkeyOrder = mutableListOf<String>()
    var usable = true
    var hotkeyUsable = true
    var channelUsable = true
    var config: StaffSpellConfig? = null
    var prototypeLoreRefreshAttempts = 0
    var prototypeLoreRefreshWrites = 0

    override fun refreshPrototypeLore(item: org.bukkit.inventory.ItemStack, spell: StaffSpell): Boolean {
        prototypeLoreRefreshAttempts++
        val updated = config?.refreshPrototypeLore(item, spell) ?: false
        if (updated) prototypeLoreRefreshWrites++
        return updated
    }

    override fun canCharge(player: org.bukkit.entity.Player, spell: StaffSpell, secondary: Boolean) =
        usable && spell in StaffSpell.available
    override fun canHotkey(player: org.bukkit.entity.Player, spell: StaffSpell, key: StaffHotkey): Boolean {
        hotkeyChecks += InputHotkeyCall(player.uniqueId, spell, key)
        hotkeyOrder += "check:${key.name}"
        return hotkeyUsable && spell in StaffSpell.available
    }
    override fun hotkey(player: org.bukkit.entity.Player, spell: StaffSpell, key: StaffHotkey) {
        hotkeys += InputHotkeyCall(player.uniqueId, spell, key)
        hotkeyOrder += "hotkey:${key.name}"
    }
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
        nativeUseWhenCharged += player.hasActiveItem()
        charges += InputChargeCall(player.uniqueId, spell, progress)
    }
    override fun cancelCharge(player: org.bukkit.entity.Player) {
        cancelled += player.uniqueId
        releaseOrder += "cancel"
        hotkeyOrder += "cancel"
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
        inventory.setItemInMainHand(StaffSpellConfig(StaffTestConfig()).item(spell))
    }
}

private fun withInputHarness(block: (InputHarness) -> Unit) = failOnUnsupportedMockBukkitOperation {
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

/** MockBukkit 4.116.3's inherited startUsingItem reads a separate backing equipment field. */
private fun PlayerMock.syncMockActiveUseEquipment() {
    val equipmentField = org.mockbukkit.mockbukkit.entity.LivingEntityMock::class.java
        .getDeclaredField("equipment")
    equipmentField.isAccessible = true
    val backingEquipment = equipmentField.get(this) as EntityEquipment
    backingEquipment.setItemInMainHand(inventory.itemInMainHand.clone())
}

private fun inventoryClick(player: PlayerMock, click: ClickType, action: InventoryAction): InventoryClickEvent {
    val view = mockk<InventoryView>()
    every { view.convertSlot(any()) } answers { firstArg() }
    every { view.player } returns player
    every { view.type } returns InventoryType.CRAFTING
    return InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, click, action)
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
