package ru.arc.survival

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Raid
import org.bukkit.block.BlockFace
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Wither
import org.bukkit.event.HandlerList
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.raid.RaidSpawnWaveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.commands.arc.subcommands.UnbindSubCommand
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class SurvivalGameplayModuleTest : FreeSpec({
    "admin unbind enforces the legacy permission and removes only native soulbind plus its lore line" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SurvivalGameplayTest")
            val player = paper.addPlayer("soulbind")
            player.isOp = false
            val item = soulboundItem()
            player.inventory.setItemInMainHand(item)
            val listener = listener()
            listener.start()
            try {
                listener.unbindHeldItem(player) shouldBe SoulbindUnbindResult.DENIED
                SurvivalSoulbind.hasSoulbind(player.inventory.itemInMainHand) shouldBe true

                player.addAttachment(plugin, SurvivalSoulbind.UNBIND_PERMISSION, true)
                player.inventory.setItemInMainHand(ItemStack(Material.AIR))
                listener.unbindHeldItem(player) shouldBe SoulbindUnbindResult.NO_ITEM

                player.inventory.setItemInMainHand(item)
                listener.unbindHeldItem(player) shouldBe SoulbindUnbindResult.UNBOUND
                val meta = player.inventory.itemInMainHand.itemMeta
                meta.persistentDataContainer.has(NamespacedKey("elitemobs", "soulbind")) shouldBe false
                meta.persistentDataContainer.get(NamespacedKey("elitemobs", "itemsource"), PersistentDataType.STRING) shouldBe "elite-source"
                loreText(meta.lore()) shouldBe listOf("keep before", "keep after")
            } finally {
                listener.close()
            }
        }
    }

    "empty or ordinary held items do not report an unbind success" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SurvivalGameplayNoItemTest")
            val player = paper.addPlayer("empty-hand")
            player.isOp = false
            player.addAttachment(plugin, SurvivalSoulbind.UNBIND_PERMISSION, true)
            val listener = listener().also { it.start() }
            try {
                listener.unbindHeldItem(player) shouldBe SoulbindUnbindResult.NO_ITEM
                player.inventory.setItemInMainHand(ItemStack(Material.IRON_SWORD))
                listener.unbindHeldItem(player) shouldBe SoulbindUnbindResult.NOT_SOULBOUND
            } finally {
                listener.close()
            }
        }
    }

    "cancelled spawner breaks are ignored and successful breaks show the resolved chance" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("spawner-break")
            val calls = mutableListOf<UUID>()
            val listener = listener(chancePercent = { calls += it.uniqueId; "12.5" })
            val plugin = paper.createSimplePlugin("SurvivalGameplaySpawnerTest")
            paper.server.pluginManager.registerEvents(listener, plugin)
            val block = player.world.getBlockAt(0, 80, 0).apply { type = Material.SPAWNER }
            try {
                val cancelled = BlockBreakEvent(block, player).apply { isCancelled = true }
                paper.callEvent(cancelled)
                calls shouldBe emptyList()

                paper.callEvent(BlockBreakEvent(block, player))
                val message = requireNotNull(player.nextComponentMessage())
                PlainTextComponentSerializer.plainText().serialize(message) shouldBe
                    "⛏ Спавнер выпадет с шансом 12.5% с шелковым касанием"
                calls shouldBe listOf(player.uniqueId)
            } finally {
                HandlerList.unregisterAll(listener)
                listener.close()
            }
        }
    }

    "raid waves glow each newly spawned raider" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("raid-test")
            val leader = world.spawn(world.spawnLocation, org.bukkit.entity.Pillager::class.java)
            val raider = world.spawn(world.spawnLocation, org.bukkit.entity.Vindicator::class.java)
            val event = RaidSpawnWaveEvent(mockRaid(), world, leader, listOf(leader, raider))
            val listener = listener()

            listener.glowRaidWave(event)

            leader.isGlowing shouldBe true
            raider.isGlowing shouldBe true
        }
    }

    "wither naming is blocked for a name tag in either hand" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("wither-name-test")
            val player = paper.addPlayer("wither-name")
            val wither = world.spawn(world.spawnLocation, Wither::class.java)
            player.inventory.setItemInMainHand(ItemStack(Material.NAME_TAG))
            val listener = listener()

            val mainHand = PlayerInteractEntityEvent(player, wither, EquipmentSlot.HAND)
            listener.preventWitherNaming(mainHand)
            mainHand.isCancelled shouldBe true

            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
            player.inventory.setItemInOffHand(ItemStack(Material.NAME_TAG))
            val offHand = PlayerInteractEntityEvent(player, wither, EquipmentSlot.OFF_HAND)
            listener.preventWitherNaming(offHand)
            offHand.isCancelled shouldBe true
        }
    }

    "old withers are removed only from the configured survival world and scope shutdown cancels sweeps" {
        MockBukkitTestRuntime.open().use { paper ->
            val survival = paper.addSimpleWorld("survival")
            val other = paper.addSimpleWorld("other")
            val old = survival.spawn(survival.spawnLocation, Wither::class.java).apply { ticksLived = 144_001 }
            val boundary = survival.spawn(survival.spawnLocation, Wither::class.java).apply { ticksLived = 144_000 }
            val foreign = other.spawn(other.spawnLocation, Wither::class.java).apply { ticksLived = 144_001 }
            val scheduler = TestTaskScheduler()
            val listener = SurvivalGameplayListener(settings(), tasks = LifecycleTaskScope(scheduler))
            listener.start()

            scheduler.tick(1_200)

            old.isDead shouldBe true
            boundary.isDead shouldBe false
            foreign.isDead shouldBe false
            listener.close()
            scheduler.timerCount() shouldBe 0
            scheduler.tick(2_400)
            boundary.isDead shouldBe false
            foreign.isDead shouldBe false
        }
    }

    "throwable cake consumes one outside creative and its impact cannot place or drop a block" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("cake-throw")
            val world = player.world
            player.inventory.setItemInMainHand(ItemStack(Material.CAKE))
            val listener = listener()
            val plugin = paper.createSimplePlugin("SurvivalGameplayCakeTest")
            paper.server.pluginManager.registerEvents(listener, plugin)
            listener.start()
            try {
                val protectionDenied = PlayerInteractEvent(
                    player,
                    Action.RIGHT_CLICK_AIR,
                    player.inventory.itemInMainHand,
                    null,
                    BlockFace.SELF,
                    EquipmentSlot.HAND,
                ).apply { setUseItemInHand(Event.Result.DENY) }
                paper.callEvent(protectionDenied)
                player.inventory.itemInMainHand.type shouldBe Material.CAKE
                world.entities.filterIsInstance<FallingBlock>().size shouldBe 0

                val interact = PlayerInteractEvent(
                    player,
                    Action.RIGHT_CLICK_AIR,
                    player.inventory.itemInMainHand,
                    null,
                    BlockFace.SELF,
                    EquipmentSlot.HAND,
                )
                // Paper marks an air interaction as block-use DENY; its item-use
                // remains DEFAULT, which must still reach the throwable-cake handler.
                interact.isCancelled shouldBe true
                interact.useItemInHand() shouldBe Event.Result.DEFAULT
                paper.callEvent(interact)

                player.inventory.itemInMainHand.type shouldBe Material.AIR
                val projectile = world.entities.filterIsInstance<FallingBlock>().single()
                projectile.blockData.material shouldBe Material.CAKE
                projectile.dropItem shouldBe false
                projectile.cancelDrop shouldBe true
                projectile.velocity.length() shouldBe 0.7

                val target = world.getBlockAt(1, 90, 1)
                target.type shouldBe Material.AIR
                val impact = EntityChangeBlockEvent(projectile, target, Material.CAKE.createBlockData())
                paper.callEvent(impact)
                impact.isCancelled shouldBe true
                projectile.isDead shouldBe true
                target.type shouldBe Material.AIR
                world.getEntitiesByClass(org.bukkit.entity.Item::class.java).size shouldBe 0
            } finally {
                HandlerList.unregisterAll(listener)
                listener.close()
            }
        }
    }

    "creative cake throws preserve the item and shutdown removes live projectiles" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("creative-cake")
            val world = player.world
            player.gameMode = org.bukkit.GameMode.CREATIVE
            player.inventory.setItemInMainHand(ItemStack(Material.CAKE))
            val scheduler = TestTaskScheduler()
            val listener = SurvivalGameplayListener(settings(), tasks = LifecycleTaskScope(scheduler))
            val plugin = paper.createSimplePlugin("SurvivalGameplayCakeCloseTest")
            paper.server.pluginManager.registerEvents(listener, plugin)
            listener.start()
            val interact = PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_AIR,
                player.inventory.itemInMainHand,
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND,
            )
            try {
                paper.callEvent(interact)
                player.inventory.itemInMainHand.type shouldBe Material.CAKE
                val projectile = world.entities.filterIsInstance<FallingBlock>().single()
                listener.close()
                projectile.isDead shouldBe true
                scheduler.pendingCount() shouldBe 0
                scheduler.timerCount() shouldBe 0
            } finally {
                HandlerList.unregisterAll(listener)
                listener.close()
            }
        }
    }

    "pickup unbind applies only to native unbind scrolls and retains the scroll marker and other data" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("unbind-pickup")
            val world = player.world
            val scroll = ItemStack(Material.PAPER).also { item ->
                val meta = item.itemMeta
                meta.persistentDataContainer.set(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING, "owner")
                meta.persistentDataContainer.set(NamespacedKey("elitemobs", "unbind_scroll.yml"), PersistentDataType.BYTE, 1)
                meta.persistentDataContainer.set(NamespacedKey("elitemobs", "itemsource"), PersistentDataType.STRING, "elite-source")
                meta.lore(listOf(Component.text("asd debug marker"), Component.text("Keep this line")))
                item.itemMeta = meta
            }
            val dropped = world.dropItem(world.spawnLocation, scroll)
            val listener = listener()
            val plugin = paper.createSimplePlugin("SurvivalGameplayPickupTest")
            paper.server.pluginManager.registerEvents(listener, plugin)
            try {
                val cancelled = EntityPickupItemEvent(player, dropped, 0).apply { isCancelled = true }
                paper.callEvent(cancelled)
                dropped.itemStack.itemMeta.persistentDataContainer.has(NamespacedKey("elitemobs", "soulbind")) shouldBe true

                paper.callEvent(EntityPickupItemEvent(player, dropped, 0))
                val meta = dropped.itemStack.itemMeta
                meta.persistentDataContainer.has(NamespacedKey("elitemobs", "soulbind")) shouldBe false
                meta.persistentDataContainer.has(NamespacedKey("elitemobs", "unbind_scroll.yml")) shouldBe true
                meta.persistentDataContainer.get(NamespacedKey("elitemobs", "itemsource"), PersistentDataType.STRING) shouldBe "elite-source"
                loreText(meta.lore()) shouldBe listOf("Keep this line")
            } finally {
                HandlerList.unregisterAll(listener)
                listener.close()
            }
        }
    }

    "canonical unbind command keeps the legacy permission, player-only scope and module availability" {
        UnbindSubCommand.configKey shouldBe "unbind"
        UnbindSubCommand.defaultName shouldBe "unbind"
        UnbindSubCommand.defaultPermission shouldBe "denizencommand.remove-soulbind"
        UnbindSubCommand.defaultPlayerOnly shouldBe true
        UnbindSubCommand.defaultUsage shouldBe "/arc unbind"
    }
})

private fun listener(
    chancePercent: (org.bukkit.entity.Player) -> String? = { null },
): SurvivalGameplayListener = SurvivalGameplayListener(
    settings(),
    chancePercent,
    LifecycleTaskScope(TestTaskScheduler()),
)

private fun settings() = SurvivalGameplaySettings(
    enabled = true,
    witherWorld = "survival",
    witherAgeLimitTicks = 144_000,
    witherSweepPeriodTicks = 1_200,
    spawnerChanceMessage = "⛏ Спавнер выпадет с шансом <chance>% с шелковым касанием",
)

private fun soulboundItem(): ItemStack = ItemStack(Material.IRON_SWORD).also { item ->
    val meta = item.itemMeta
    meta.persistentDataContainer.set(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING, "owner")
    meta.persistentDataContainer.set(NamespacedKey("elitemobs", "itemsource"), PersistentDataType.STRING, "elite-source")
    meta.lore(listOf(Component.text("keep before"), Component.text("Привязано к owner"), Component.text("keep after")))
    item.itemMeta = meta
}

private fun loreText(lore: List<Component>?): List<String> =
    lore.orEmpty().map(PlainTextComponentSerializer.plainText()::serialize)

private fun mockRaid(): Raid = io.mockk.mockk<Raid>(relaxed = true)
