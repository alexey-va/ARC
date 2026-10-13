package ru.arc.staffspells

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.key.Key
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.bukkit.FluidCollisionMode
import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.potion.PotionEffectType
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.config.TestConfig
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.util.CooldownManager
import java.util.UUID

class StaffSpellsTest : FreeSpec({
    "duel staff is native, marked, keeps spell instructions, and replaces PvE prototype lore" {
        val config = StaffSpellConfig(TestConfig(mapOf(
            "chain.skin" to "missing:appearance_should_not_be_loaded",
            "chain.duel-appearance.item-model" to "minecraft:crossbow",
            "chain.duel-appearance.custom-model-data" to 10009,
            "chain.description" to "Направленный разряд",
            "chain.aim" to "Прицельтесь во врага",
            "chain.secondary" to "Shift + ЛКМ — цепная молния",
            "messages.duel-only" to "Только в активной дуэли",
            "messages.prototype" to "Только прототип",
            "messages.use" to "ЛКМ — применить",
        )))
        withStaffHarness(config = config) { h ->
            val item = h.controller.duelItem(StaffSpell.CHAIN)
            item.type shouldBe Material.BLAZE_ROD
            item.getData(DataComponentTypes.ITEM_MODEL) shouldBe Key.key("minecraft:crossbow")
            item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)!!.floats() shouldBe listOf(10009f)
            item.getData(DataComponentTypes.CONSUMABLE) shouldBe null
            StaffSpell.from(item) shouldBe StaffSpell.CHAIN
            StaffSpell.isDuelOnly(item) shouldBe true
            val lore = item.itemMeta.lore()!!.joinToString(" ") { PlainTextComponentSerializer.plainText().serialize(it) }
            lore.contains("Направленный разряд") shouldBe true
            lore.contains("Прицельтесь во врага") shouldBe true
            lore.contains("Shift + ЛКМ — цепная молния") shouldBe true
            lore.contains("Только в активной дуэли") shouldBe true
            lore.contains("ЛКМ — применить") shouldBe true
            lore.contains("Только прототип") shouldBe false
        }
    }

    "duel appearance defaults stay portable and invalid item-model/data pairs fail closed" {
        withStaffHarness { h ->
            val defaultItem = h.config.duelItem(StaffSpell.CHAIN)
            defaultItem.getData(DataComponentTypes.ITEM_MODEL) shouldBe null
            defaultItem.getData(DataComponentTypes.CUSTOM_MODEL_DATA) shouldBe null

            listOf(
                mapOf("chain.duel-appearance.item-model" to "minecraft:crossbow"),
                mapOf("chain.duel-appearance.custom-model-data" to 10009),
                mapOf(
                    "chain.duel-appearance.item-model" to "bad key",
                    "chain.duel-appearance.custom-model-data" to 10009,
                ),
                mapOf(
                    "chain.duel-appearance.item-model" to "minecraft:crossbow",
                    "chain.duel-appearance.custom-model-data" to 10009.5,
                ),
            ).forEach { values ->
                shouldThrow<IllegalArgumentException> {
                    StaffSpellConfig(TestConfig(values)).duelItem(StaffSpell.CHAIN)
                }
            }
        }
    }

    "staff identity stays on its ARC marker" {
        withStaffHarness { h ->
            StaffSpell.from(h.config.item(StaffSpell.CHAIN)) shouldBe StaffSpell.CHAIN
            StaffSpell.from(ItemStack(Material.STICK)) shouldBe null
        }
    }

    "every staff defense is independent from charged attack cooldown" {
        StaffSpell.available.forEach { spell ->
            listOf(StaffCastInput.QUICK, StaffCastInput.QUICK_SECONDARY).forEach { input ->
                withStaffHarness { h ->
                    val world = h.paper.addSimpleWorld("quick-${spell.id}-${input.name}")
                    val player = h.player("quick", world, spell)
                    h.controller.start()
                    h.controller.cast(player, input, 0.0)
                    h.controller.canCharge(player, spell) shouldBe true
                    h.captures.size shouldBe 1
                    h.scheduler.tick(100)
                }
            }
        }
    }

    "player reset cancels a quick gesture before its contact frame" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-quick-reset")
            val player = h.player("staff-quick-reset", world, StaffSpell.ICICLE)
            h.zombie(world, x = 0.5, z = 3.0)
            h.controller.start()

            h.controller.cast(player, StaffCastInput.QUICK, 0.0)
            h.scheduler.tick(2)
            h.controller.resetPlayer(player.uniqueId)
            h.scheduler.tick(12)

            h.hits shouldBe emptyList()
            verify(exactly = 1) { h.effects.cancel(player.uniqueId) }
        }
    }

    "release transfers charged comet and crown scenes before charge cleanup" {
        for (spell in listOf(StaffSpell.EMBER, StaffSpell.ICICLE)) withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("charge-transfer-${spell.id}")
            val player = h.player("charge-transfer", world, spell)
            h.controller.start()
            h.controller.charge(player, spell, 1.0)
            val held = h.visualPlays.toList()
            held.size shouldBe if (spell == StaffSpell.ICICLE) 11 else 1
            every { h.effects.chargeOrigin(any()) } answers {
                held.firstOrNull { it.id == firstArg<UUID?>() }?.from?.clone()?.apply {
                    if (spell == StaffSpell.EMBER) {
                        val offset = staffEmberChargeOffset()
                        add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
                    }
                }
            }
            every { h.effects.releaseCharge(any(), any(), any()) } answers { firstArg<UUID?>() }
            h.controller.cast(player, StaffCastInput.PRIMARY, 1.0)
            h.controller.cancelCharge(player)
            h.visualPlays.size shouldBe held.size
            held.forEach { shot ->
                verify(exactly = 1) { h.effects.releaseCharge(shot.id, any(), any()) }
                (shot.id in h.removedEffects) shouldBe false
            }
            h.scheduler.tick(12)
            val launched = if (spell == StaffSpell.EMBER) h.movedEffects.map { it.first }
                else h.projectileMoves.map { it.id }
            launched.isNotEmpty() shouldBe true
            launched.all { id -> held.any { it.id == id } } shouldBe true
        }
    }

    "fully charged icicle volley grows to eleven without multiplying one-target hits" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("charged-crown")
            val player = h.player("charged", world, StaffSpell.ICICLE)
            val victim = h.zombie(world, x = 0.5, z = 12.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 1.0)
            h.visualPlays.count { it.spell == StaffSpell.ICICLE } shouldBe 11
            h.scheduler.tick(90)
            h.hits.map { it.uniqueId } shouldBe listOf(victim.uniqueId)
            h.damageScales shouldBe listOf(1.75 to 10.5)
        }
    }

    "primary lightning launches through the reticle without an artificial sideways turn" {
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-chain-centered")
            val player = h.player("staff-chain-centered", world, StaffSpell.CHAIN)
            val eye = player.eyeLocation
            val forward = eye.direction.normalize()
            h.controller.start()
            h.controller.cast(player)
            h.scheduler.tick(4)

            h.trailMoves.size shouldBe 4
            h.trailMoves.forEach { move ->
                val offset = move.points.last().toVector().subtract(eye.toVector())
                (offset.clone().normalize().dot(forward) > 0.9999) shouldBe true
            }
            h.trailMoves.first().points.last().distance(eye) shouldBe staffLightningStep(1)
            h.hits shouldBe emptyList()
        }
    }

    "empty-air lightning still captures one cast and cooldown clicks do not launch again" {
        listOf(false, true).forEach { secondary ->
            withStaffHarness(settings = staffSettings(range = 12.0)) { h ->
                val world = h.paper.addSimpleWorld("staff-lightning-miss")
                val player = h.player("staff-lightning-miss", world, StaffSpell.CHAIN).apply { isSneaking = secondary }
                h.controller.start()
                h.controller.cast(player)
                h.controller.cast(player)
                h.scheduler.tick(80)
                h.hits shouldBe emptyList()
                h.captures.size shouldBe 1
            }
        }
    }

    "a later missing skin leaves a multi-spell grant untouched" {
        val config = spyk(StaffSpellConfig(TestConfig()))
        every { config.item(StaffSpell.FROST) } throws
            StaffSpellSkinUnavailableException("missing:frost_staff", IllegalStateException("skin not registered"))

        withStaffHarness(config = config) { h ->
            val player = h.paper.addPlayer("missing-skin")
            player.inventory.setItem(0, ItemStack(Material.DIAMOND))
            val before = player.inventory.storageContents.map { it?.clone() }

            h.controller.give(player, listOf(StaffSpell.CHAIN, StaffSpell.FROST)) shouldBe false

            player.inventory.storageContents.toList() shouldBe before
        }
    }

    "all available prototype items can be granted without optional ItemsAdder skins" {
        withStaffHarness { h ->
            val player = h.paper.addPlayer("all-prototypes")

            StaffSpell.available.map { it.id } shouldBe listOf("chain", "mark", "frost", "lance", "ember", "nova", "icicle", "fault", "lunar", "gale", "tide", "requiem", "prism", "necromancer", "gravity", "ossuary")
            h.controller.give(player, StaffSpell.available) shouldBe true

            player.inventory.storageContents.filterNotNull().mapNotNull { StaffSpell.from(it) } shouldBe
                StaffSpell.available
        }
    }

    "granting all available prototypes refuses fifteen empty slots without changing existing items" {
        withStaffHarness { h ->
            val player = h.paper.addPlayer("staff-capacity")
            repeat(player.inventory.storageContents.size - 15) { player.inventory.setItem(it, ItemStack(Material.DIAMOND)) }
            val before = player.inventory.storageContents.map { it?.clone() }
            h.controller.give(player, StaffSpell.available) shouldBe false
            player.inventory.storageContents.toList() shouldBe before
        }
    }

    "icicle secondary rains below a ceiling and slows each area target only once" {
        var denied: UUID? = null
        withStaffHarness(settings = staffSettings(range = 16.0), hit = { _, mob -> mob.uniqueId != denied }) { h ->
            val world = h.paper.addSimpleWorld("staff-hail")
            for (x in -4..4) for (z in 7..14) {
                world.loadChunk(x shr 4, z shr 4)
                world.getBlockAt(x, 63, z).type = Material.STONE
                world.getBlockAt(x, 70, z).type = Material.STONE
            }
            val player = h.player("staff-hail", world, StaffSpell.ICICLE).apply { isSneaking = true }
            val victim = h.zombie(world, 0.5, 10.0)
            val protected = h.zombie(world, 0.8, 10.3)
            denied = protected.uniqueId
            h.controller.start()
            h.controller.cast(player)
            h.visualPlays.size shouldBe 5
            h.visualPlays.all { it.spell == StaffSpell.ICICLE && it.secondary && it.from.y in 64.5..70.0 } shouldBe true
            h.scheduler.tick(3)
            h.projectileMoves shouldBe emptyList()
            h.hits shouldBe emptyList()
            h.scheduler.tick(39)
            h.projectileMoves.all { it.direction == Vector(0.0, -1.0, 0.0) } shouldBe true
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(victim.uniqueId, protected.uniqueId)
            h.hits.size shouldBe 2
            victim.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe true
            protected.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
        }
    }

    "fault secondary cannot erupt in the sky without a ground surface" {
        withStaffHarness(settings = staffSettings(range = 48.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-fault-sky")
            world.loadChunk(0, 1)
            val player = h.player("staff-fault-sky", world, StaffSpell.FAULT).apply {
                isSneaking = true
                teleport(location.apply { pitch = -60f })
            }
            h.controller.start()
            h.controller.cast(player)
            h.scheduler.tick(35)
            h.visualPlays shouldBe emptyList()
            h.hits shouldBe emptyList()
        }
    }

    "fault secondary warns before lifting successful hits and delayed ruptures cancel on quit" {
        var denied: UUID? = null
        withStaffHarness(hit = { _, mob -> mob.uniqueId != denied }) { h ->
            val world = h.paper.addSimpleWorld("staff-fault-secondary")
            for (x in -2..3) for (z in 0..20) {
                world.loadChunk(x shr 4, z shr 4)
                world.getBlockAt(x, 63, z).type = Material.STONE
            }
            val player = h.player("staff-fault-secondary", world, StaffSpell.FAULT).apply { isSneaking = true }
            val victim = h.zombie(world, 0.5, 10.0)
            val protected = h.zombie(world, 1.0, 10.0)
            denied = protected.uniqueId
            h.controller.start()
            h.controller.cast(player)
            h.scheduler.tick(7)
            h.hits shouldBe emptyList()
            h.visualPlays shouldBe emptyList()
            h.scheduler.tick(1)
            h.visualPlays.single().secondary shouldBe true
            h.hits.toSet() shouldBe setOf(victim, protected)
            victim.velocity.y shouldBe 0.55
            protected.velocity.y shouldBe 0.0

            for (shift in listOf(false, true)) {
                val quitting = h.player("fault-quit-$shift", world, StaffSpell.FAULT).apply { isSneaking = shift }
                h.controller.cast(quitting)
                h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))
                val before = h.visualPlays.size
                h.scheduler.tick(35)
                h.visualPlays.size shouldBe before
            }
        }
    }

    "auto-aim follows a moved off-axis mob and excludes mobs behind or beyond range" {
        withStaffHarness(settings = staffSettings(range = 48.0, aimDegrees = 20.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-aim")
            val player = h.player("staff-aim", world, StaffSpell.CHAIN)
            val moving = h.zombie(world, x = 2.0, z = 40.0)
            h.zombie(world, x = 0.5, z = -3.0)
            h.zombie(world, x = 0.5, z = 52.0)
            val castOrigin = player.eyeLocation.clone()
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            moving.teleport(Location(world, 3.0, 64.0, 41.0))
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(moving.uniqueId)
            h.trailMoves.any { move ->
                move.points.size in 2..41 && move.points.last().distance(moving.location.clone().add(0.0, 1.0, 0.0)) < 1.5
            } shouldBe true
            h.trailMoves.all { it.points.size <= 41 } shouldBe true
            (h.trailMoves.last().points.size > 8) shouldBe true
            h.trailMoves.all { move ->
                (move.points.first().distance(castOrigin) < 0.001) &&
                    move.points.zipWithNext().all { (from, to) -> from.distance(to) <= 5.501 }
            } shouldBe true
            h.finishedTrails shouldBe listOf(h.visualPlays.single().id)
            h.trailMoves.map { it.id }.distinct() shouldBe listOf(h.visualPlays.single().id)
            h.removedEffects.filterNotNull() shouldBe emptyList()
        }
    }

    "a solid wall blocks an otherwise valid direct target" {
        withStaffHarness(settings = staffSettings(range = 8.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-wall")
            val player = h.player("staff-wall", world, StaffSpell.CHAIN)
            val target = h.zombie(world, x = 0.5, z = 8.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(1)
            for (x in -2..2) for (y in 63..67) world.getBlockAt(x, y, 3).type = Material.STONE
            h.scheduler.tick(12)

            h.hits.map { it.uniqueId } shouldBe emptyList()
            target.isValid shouldBe true
        }
    }

    "frost melee waits for contact and slows only after successful damage" {
        val cancelledTargets = mutableSetOf<UUID>()
        withStaffHarness(
            settings = staffSettings(),
            hit = { _, target -> target.uniqueId !in cancelledTargets },
        ) { h ->
            val world = h.paper.addSimpleWorld("staff-frost")
            val player = h.player("staff-frost", world, StaffSpell.FROST)
            val cancelled = h.zombie(world, x = 0.5, z = 2.0)
            val landed = h.zombie(world, x = 0.5, z = 3.5)
            cancelledTargets += cancelled.uniqueId
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(3)

            cancelled.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            landed.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            h.hits shouldBe emptyList()

            h.scheduler.tick(1)
            landed.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe true
            h.hits.map { it.uniqueId } shouldBe listOf(cancelled.uniqueId, landed.uniqueId)
            h.scheduler.tick(6)
            h.hits.map { it.uniqueId }.distinct().size shouldBe h.hits.size
        }
    }

    "frost melee rechecks cover at contact and ignores late arrivals" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-frost-wall")
            val player = h.player("staff-frost-wall", world, StaffSpell.FROST)
            h.zombie(world, x = 0.5, z = 3.5)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(1)
            val lateArrival = h.zombie(world, x = 0.5, z = 3.0)
            for (y in 64..66) world.getBlockAt(0, y, 2).type = Material.STONE
            h.scheduler.tick(3)

            h.hits shouldBe emptyList()
            lateArrival.isValid shouldBe true
        }
    }

    "frost melee accepts a target inside its narrow front arc and contacts it once" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-frost-side")
            val player = h.player("staff-frost-side", world, StaffSpell.FROST)
            val diagonal = h.zombie(world, x = 1.5, z = 3.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(3)
            h.hits shouldBe emptyList()

            h.scheduler.tick(1)
            h.hits.map { it.uniqueId } shouldBe listOf(diagonal.uniqueId)
            h.scheduler.tick(6)
            h.hits.map { it.uniqueId }.distinct().size shouldBe 1
        }
    }

    "all staff spells share cooldown across an item swap" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-cooldown")
            val player = h.player("staff-cooldown", world, StaffSpell.CHAIN)
            val target = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(60)
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.FROST))
            h.controller.cast(player)

            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
        }
    }

    "staff cooldown owns one bossbar and hides it on item removal quit and shutdown" {
        val config = StaffSpellConfig(TestConfig(mapOf(
            "icicle.name" to "Ледяной венец", "icicle.secondary-name" to "Ледяной дождь",
            "fault.name" to "Обсидиановый раскол", "fault.secondary-name" to "Каменный выброс",
            "messages.cooldown-bar" to "<ability> <seconds> с", "messages.ready-bar" to "<ability> Готово",
        )))
        withStaffHarness(config = config) { h ->
            val world = h.paper.addSimpleWorld("staff-bossbar")
            val player = h.player("staff-bossbar", world, StaffSpell.ICICLE) as PlayerMock
            h.controller.start()
            h.scheduler.tick(4)
            val bar = player.bossBars.single()
            bar.progress() shouldBe 1f
            bar.color() shouldBe BossBar.Color.GREEN
            h.controller.cast(player)
            player.bossBars.single() shouldBe bar
            bar.progress() shouldBe 0f
            bar.color() shouldBe BossBar.Color.BLUE
            h.scheduler.tick(8)
            (bar.progress() > 0f && bar.progress() < 1f) shouldBe true
            player.isSneaking = true
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.FAULT))
            h.scheduler.tick(4)
            player.bossBars.single() shouldBe bar
            PlainTextComponentSerializer.plainText().serialize(bar.name()).startsWith("Обсидиановый раскол") shouldBe true
            player.nextActionBar() shouldBe null
            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
            h.scheduler.tick(4)
            player.bossBars.size shouldBe 0
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.ICICLE))
            h.scheduler.tick(4)
            player.bossBars.size shouldBe 1
            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            player.bossBars.size shouldBe 0
            h.scheduler.tick(4)
            player.bossBars.size shouldBe 1 // Mock quit leaves the player online; close must still hide it.
            h.controller.close()
            player.bossBars.size shouldBe 0
        }
    }

    "cancelling active effects retains cooldown duration and input label" {
        val config = StaffSpellConfig(TestConfig(mapOf(
            "icicle.name" to "Венец", "icicle.secondary-name" to "Дождь",
            "messages.cooldown-bar" to "<ability> <seconds> с",
        )))
        withStaffHarness(config = config) { h ->
            val world = h.paper.addSimpleWorld("staff-cooldown-return")
            val player = h.player("staff-cooldown-return", world, StaffSpell.ICICLE) as PlayerMock
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(8)
            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            h.scheduler.tick(4) // Mock quit leaves the player online, modelling the returning HUD.
            val bar = player.bossBars.single()
            PlainTextComponentSerializer.plainText().serialize(bar.name()).startsWith("Дождь") shouldBe true
            bar.progress() shouldBe staffCooldownProgress(CooldownManager.cooldown(player.uniqueId, "staff-spells"), 30)
            h.controller.cast(player)
            h.captures.size shouldBe 1
        }
    }

    "travelling lance and quick gesture stop when the captured combat context expires" {
        listOf(StaffSpell.LANCE to StaffCastInput.SECONDARY, StaffSpell.MARK to StaffCastInput.QUICK).forEach { (spell, input) ->
            var current = true
            withStaffHarness(currentCast = { _, _ -> current }) { h ->
                val world = h.paper.addSimpleWorld("staff-stale-${spell.id}")
                val player = h.player("stale-caster", world, spell)
                h.zombie(world, 0.5, 10.0)
                h.controller.start()
                h.controller.cast(player, input, 0.0)
                h.scheduler.tick(2)
                current = false
                h.scheduler.tick(40)
                h.hits shouldBe emptyList()
            }
        }
    }

    "all spells cast into empty space and use the shared cooldown" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-empty-casts")
            val player = h.player("staff-empty-casts", world, StaffSpell.CHAIN)
            val spells = StaffSpell.entries.filterNot { it in setOf(StaffSpell.NECROMANCER, StaffSpell.INFERNO, StaffSpell.GRAVITY) }
            h.controller.start()

            spells.forEachIndexed { index, spell ->
                player.inventory.setItemInMainHand(h.config.item(spell))
                h.controller.cast(player)
                h.captures.size shouldBe index + 1

                player.inventory.setItemInMainHand(h.config.item(spells[(index + 1) % spells.size]))
                h.controller.cast(player)
                h.captures.size shouldBe index + 1
                h.scheduler.tick(20)
            }

            h.hits shouldBe emptyList()
        }
    }

    "comet accelerates monotonically while retaining swept collision at high speed" {
        withStaffHarness(settings = staffSettings(range = 48.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-comet-acceleration")
            val player = h.player("staff-comet-acceleration", world, StaffSpell.EMBER)
            h.controller.start()
            h.controller.cast(player)
            // Release itself sends a moving frame, before the scheduler advances even one tick.
            h.movedEffects.size shouldBe 1
            (h.movedEffects.single().second.distance(h.visualPlays.single().from) >= 0.75) shouldBe true
            h.scheduler.tick(1)
            h.movedEffects.size shouldBe 1 // Do not restart the initial two-tick tween halfway through.
            h.scheduler.tick(1)
            h.movedEffects.size shouldBe 2
            h.scheduler.tick(18)
            val muzzle = h.visualPlays.single().from
            (kotlin.math.abs(muzzle.distance(player.eyeLocation) - staffEmberChargeOffset().length()) < .0001) shouldBe true
            val distances = listOf(muzzle) + h.movedEffects.map { it.second }
            val steps = distances.zipWithNext().map { (a, b) -> a.distance(b) }
            steps.size shouldBe 11
            steps.zipWithNext().all { (a, b) -> b + 0.000001 >= a } shouldBe true
            (steps.last() > steps.first() * 3.0) shouldBe true
            (distances.last().z > distances.first().z) shouldBe true

            val actualHead = muzzle.z + (1..21).sumOf { staffEmberStep(it, 1.2) }
            val victim = h.zombie(world, 0.5, actualHead + 1.3)
            h.scheduler.tick(1)
            h.hits shouldBe listOf(victim)
        }
    }

    "manual lance and ember do not acquire an off-axis mob" {
        withStaffHarness(settings = staffSettings(range = 10.0)) { h ->
            h.controller.start()
            for (spell in listOf(StaffSpell.LANCE, StaffSpell.EMBER)) {
                val world = h.paper.addSimpleWorld("staff-manual-${spell.id}")
                val player = h.player("staff-manual-${spell.id}", world, spell)
                // Within the 12-degree soft aim cone, but outside the manual beam/projectile path.
                h.zombie(world, x = 1.5, z = 7.0)

                h.controller.cast(player, if (spell == StaffSpell.LANCE) StaffCastInput.SECONDARY else StaffCastInput.PRIMARY, 0.0)
                h.scheduler.tick(12)

                h.hits shouldBe emptyList()
            }
        }
    }

    "lance pierces up to its target limit" {
        withStaffHarness(settings = staffSettings(lanceTargets = 3)) { h ->
            val world = h.paper.addSimpleWorld("staff-lance-pierce")
            val player = h.player("staff-lance-pierce", world, StaffSpell.LANCE)
            val first = h.zombie(world, x = 0.5, z = 4.0)
            val second = h.zombie(world, x = 0.5, z = 6.0)
            val third = h.zombie(world, x = 0.5, z = 8.5)
            val beyondTargetLimit = h.zombie(world, x = 0.5, z = 11.0)

            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            h.hits shouldBe emptyList()
            h.scheduler.tick(5)
            h.hits shouldBe emptyList()
            h.scheduler.tick(20)

            h.hits.map { it.uniqueId } shouldBe listOf(first.uniqueId, second.uniqueId, third.uniqueId)
            beyondTargetLimit.isValid shouldBe true
        }
    }

    "lance wall collision stops the beam while target budget remains" {
        withStaffHarness(settings = staffSettings(lanceTargets = 8)) { h ->
            val world = h.paper.addSimpleWorld("staff-lance-wall")
            val player = h.player("staff-lance-wall", world, StaffSpell.LANCE)
            val inFront = h.zombie(world, x = 0.5, z = 4.0)
            val behindWall = h.zombie(world, x = 0.5, z = 12.0)
            for (x in -1..1) for (y in 64..67) world.getBlockAt(x, y, 10).type = Material.STONE

            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            h.hits shouldBe emptyList()
            h.scheduler.tick(5)
            h.hits shouldBe emptyList()
            h.scheduler.tick(20)

            h.hits.map { it.uniqueId } shouldBe listOf(inFront.uniqueId)
            behindWall.isValid shouldBe true
        }
    }

    "ember swept segment hits a mob and quit cancels a pending projectile" {
        withStaffHarness(settings = staffSettings(range = 10.0, emberSpeed = 2.5)) { h ->
            h.controller.start()
            val hitWorld = h.paper.addSimpleWorld("staff-ember-hit")
            val player = h.player("staff-ember-hit", hitWorld, StaffSpell.EMBER)
            val target = h.zombie(hitWorld, x = 0.5, z = 3.2)

            h.controller.cast(player)
            h.scheduler.tick(8)

            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            h.visualPlays.single().impact shouldBe true // Target lies in the release-time muzzle sweep.

            h.hits.clear()
            val wallWorld = h.paper.addSimpleWorld("staff-ember-wall")
            val wallPlayer = h.player("staff-ember-wall", wallWorld, StaffSpell.EMBER)
            h.zombie(wallWorld, x = 0.5, z = 7.0)
            for (x in -1..1) for (y in 64..67) wallWorld.getBlockAt(x, y, 3).type = Material.STONE

            h.controller.cast(wallPlayer)
            h.scheduler.tick(8)

            h.hits shouldBe emptyList()
            val wallKeys = h.movedEffects.filter { it.second.world == wallWorld }.map { it.second }
            // The wall now lies inside the held body's release sweep, so it bursts
            // synchronously without creating a flight scene first.
            h.visualPlays.single { it.from.world == wallWorld }.let {
                it.impact shouldBe true
                (it.from.z < 3.0) shouldBe true
            }
            wallKeys.all { it.z < 3.0 } shouldBe true
            val mobKeys = h.movedEffects.filter { it.second.world == hitWorld }.map { it.second }
            mobKeys.all { it.z <= target.boundingBox.minZ - 0.25 + 0.00001 } shouldBe true

            h.hits.clear()
            val cancelledWorld = h.paper.addSimpleWorld("staff-ember-quit")
            val quitting = h.player("staff-ember-quit", cancelledWorld, StaffSpell.EMBER)
            h.zombie(cancelledWorld, x = 0.5, z = 7.2) // Beyond the synchronous release sweep.
            h.controller.cast(quitting)
            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))
            h.scheduler.tick(10)

            h.hits shouldBe emptyList()
            verify(exactly = 1) { h.effects.cancel(quitting.uniqueId) }

            val flightWorld = h.paper.addSimpleWorld("staff-ember-flight")
            val flying = h.player("staff-ember-flight", flightWorld, StaffSpell.EMBER)
            h.controller.cast(flying)
            h.scheduler.tick(8)
            h.movedEffects.isNotEmpty() shouldBe true
            h.controller.onQuit(PlayerQuitEvent(flying, Component.empty()))
            verify(exactly = 1) { h.effects.cancel(flying.uniqueId) }
        }
    }

    "mark melee contacts after the swing delay and cancels pending contact on quit" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-mark-point")
            val player = h.player("staff-mark-point", world, StaffSpell.MARK)
            val target = h.zombie(world, x = 0.5, z = 3.0)
            h.controller.start()

            h.controller.cast(player)
            h.captures.size shouldBe 1
            h.hits shouldBe emptyList()
            h.scheduler.tick(3)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)

            val quitting = h.player("staff-mark-quit", world, StaffSpell.MARK)
            val quitTarget = h.zombie(world, x = 0.5, z = 3.5)
            h.controller.cast(quitting)
            val hitsBeforeQuit = h.hits.size
            h.scheduler.tick(3)
            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))
            h.scheduler.tick(2)

            h.hits.size shouldBe hitsBeforeQuit
            (quitTarget.uniqueId in h.hits.map { it.uniqueId }) shouldBe false
        }
    }

    "nova melee waits for contact, slows only successful hits, and respects its target cap" {
        var deniedTarget: UUID? = null
        withStaffHarness(settings = staffSettings(maxAreaTargets = 2),
            hit = { _, target -> target.uniqueId != deniedTarget }) { h ->
            val world = h.paper.addSimpleWorld("staff-vine")
            val player = h.player("vine", world, StaffSpell.NOVA)
            val denied = h.zombie(world, x = 0.5, z = 1.8)
            val accepted = h.zombie(world, x = 0.5, z = 2.6)
            val overCap = h.zombie(world, x = 0.5, z = 3.4)
            deniedTarget = denied.uniqueId
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(3)
            h.hits shouldBe emptyList()

            h.scheduler.tick(1)
            h.hits.map { it.uniqueId } shouldBe listOf(denied.uniqueId, accepted.uniqueId)
            denied.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            accepted.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe true
            overCap.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false

            h.scheduler.tick(6)
            h.hits.map { it.uniqueId } shouldBe listOf(denied.uniqueId, accepted.uniqueId)
        }
    }

    "shift right-click chain is a radial nova with the extended cooldown" {
        withStaffHarness(settings = staffSettings(range = 12.0, chainTargets = 4, aimDegrees = 20.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-chain-secondary")
            val player = h.player("staff-chain-secondary", world, StaffSpell.CHAIN).apply { isSneaking = true }
            val behind = h.zombie(world, x = 0.5, z = -2.5)
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.projectileMoves shouldBe emptyList()
            CooldownManager.cooldown(player.uniqueId, "staff-spells") shouldBe 80L
            h.scheduler.tick(8)

            h.hits shouldBe listOf(behind)
            h.projectileMoves shouldBe emptyList()
        }
    }

    "secondary frost grows radially to eight blocks, including lateral targets" {
        withStaffHarness(settings = staffSettings(range = 12.0, novaRadius = 8.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-frost-secondary")
            val player = h.player("staff-frost-secondary", world, StaffSpell.FROST).apply { isSneaking = true }
            val near = h.zombie(world, x = 0.5, z = 1.5)
            val far = h.zombie(world, x = 0.5, z = 8.0)
            val lateral = h.zombie(world, x = 7.0, z = 0.5)
            val outside = h.zombie(world, x = 0.5, z = 9.5)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(2)
            h.hits.map { it.uniqueId } shouldBe listOf(near.uniqueId)
            h.scheduler.tick(16)

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(near.uniqueId, far.uniqueId, lateral.uniqueId)
            (outside.uniqueId in h.hits.map { it.uniqueId }) shouldBe false
            h.visualPlays.any { it.spell == StaffSpell.FROST && it.secondary } shouldBe true
        }
    }

    "icicle primary aims each staggered shot at the current reticle and slows only successful hits" {
        var deniedId: UUID? = null
        withStaffHarness(settings = staffSettings(range = 24.0, aimDegrees = 40.0),
            hit = { _, target -> target.uniqueId != deniedId }) { h ->
            val world = h.paper.addSimpleWorld("staff-frost-secondary")
            val player = h.player("staff-frost-secondary", world, StaffSpell.ICICLE)
            val targets = listOf(
                h.zombie(world, x = -3.5, z = 10.0),
                h.zombie(world, x = 4.5, z = 12.0),
                h.zombie(world, x = 4.5, z = 8.0),
            )
            deniedId = targets.last().uniqueId
            h.controller.start()

            h.controller.cast(player)
            val icicles = h.visualPlays.filter { it.spell == StaffSpell.ICICLE && !it.secondary }
            icicles.size shouldBe 7
            val eye = player.eyeLocation
            icicles.indices.all { index ->
                val offset = staffIcicleAnchorOffset(index)
                kotlin.math.abs(icicles[index].from.x - (eye.x + offset.x.toDouble())) < 0.001 &&
                    kotlin.math.abs(icicles[index].from.y - (eye.y + offset.y.toDouble())) < 0.001 &&
                    kotlin.math.abs(icicles[index].from.z - (eye.z + offset.z.toDouble())) < 0.001
            } shouldBe true

            fun aimAt(victim: LivingEntity) {
                player.teleport(player.location.apply {
                    direction = center(victim).toVector().subtract(player.eyeLocation.toVector())
                })
            }
            aimAt(targets[0])
            h.scheduler.tick(3)
            h.projectileMoves shouldBe emptyList()
            h.scheduler.tick(1)
            h.projectileMoves.map { it.id } shouldBe listOf(icicles[0].id)
            aimAt(targets[1])
            h.scheduler.tick(2)
            h.projectileMoves.count { it.id == icicles[1].id } shouldBe 1
            aimAt(targets[2])
            h.scheduler.tick(2)
            h.projectileMoves.count { it.id == icicles[2].id } shouldBe 1
            h.scheduler.tick(50)

            h.hits.map { it.uniqueId }.toSet() shouldBe targets.map { it.uniqueId }.toSet()
            h.hits.size shouldBe 3
            h.damageScales shouldBe List(3) { 1.0 to 6.0 }
            targets.filter { it.uniqueId != deniedId }.all { it.hasPotionEffect(PotionEffectType.SLOWNESS) } shouldBe true
            targets.last().hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            verify(exactly = 7) { h.effects.impact(any(), any(), 0.9, 10) }
        }
    }

    "icicle volley follows reticle turns in empty space without redirecting the first airborne shot" {
        withStaffHarness(settings = staffSettings(range = 32.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-icicle-live-reticle")
            val player = h.player("live-reticle", world, StaffSpell.ICICLE)
            h.controller.start()
            h.controller.cast(player)
            val shots = h.visualPlays.filter { it.spell == StaffSpell.ICICLE }
            h.scheduler.tick(4)
            player.teleport(player.location.apply { yaw = -55f })
            h.scheduler.tick(16)
            val first = h.projectileMoves.last { it.id == shots[0].id }
            val second = h.projectileMoves.last { it.id == shots[1].id }
            // The initial shot retains the original forward destination. Later shots turn right.
            (first.at.x < player.location.x + 1.0) shouldBe true
            (second.at.x > player.location.x + 8.0) shouldBe true
            (second.direction.x > 0.5) shouldBe true
            h.hits shouldBe emptyList()
        }
    }

    "icicle primary clears the camera before turning toward a downward aim" {
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-frost-camera")
            val player = h.player("staff-frost-camera", world, StaffSpell.ICICLE).apply {
                teleport(location.apply { pitch = 62.45f })
            }
            val eye = player.eyeLocation
            h.controller.start()
            h.controller.cast(player)
            h.scheduler.tick(19)
            h.projectileMoves.groupBy { it.id }.values.forEach { moves ->
                moves.take(4).forEach { move ->
                    (move.at.distance(eye) > 2.0) shouldBe true
                    (kotlin.math.abs(move.direction.y) < 0.0001) shouldBe true
                }
            }
            h.projectileMoves.map { it.id }.distinct().size shouldBe 7
        }
    }

    "icicle primary shares hit de-duplication and cancels in-flight icicles on quit" {
        withStaffHarness(settings = staffSettings(range = 24.0, aimDegrees = 40.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-frost-secondary-cleanup")
            val player = h.player("staff-frost-secondary-cleanup", world, StaffSpell.ICICLE)
            val single = h.zombie(world, x = 0.5, z = 8.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(single.uniqueId)
            h.hits.size shouldBe 1
            verify(exactly = 7) { h.effects.impact(any(), any(), 0.9, 10) }

            val other = h.player("staff-frost-secondary-quit", world, StaffSpell.ICICLE)
            h.controller.cast(other)
            h.scheduler.tick(6)
            val movesBeforeQuit = h.projectileMoves.size
            h.controller.onQuit(PlayerQuitEvent(other, Component.empty()))
            h.scheduler.tick(60)

            h.projectileMoves.size shouldBe movesBeforeQuit
            h.hits.map { it.uniqueId }.shouldBe(listOf(single.uniqueId))
            verify(exactly = 1) { h.effects.cancel(other.uniqueId) }
        }
    }

    "secondary lance fans three reduced-power beams and de-duplicates their hits" {
        withStaffHarness(settings = staffSettings(range = 10.0, lanceTargets = 3)) { h ->
            val world = h.paper.addSimpleWorld("staff-lance-secondary")
            val player = h.player("staff-lance-secondary", world, StaffSpell.LANCE).apply { isSneaking = true }
            val left = h.zombie(world, x = -1.65, z = 8.0)
            val center = h.zombie(world, x = 0.5, z = 8.0)
            val right = h.zombie(world, x = 2.65, z = 8.0)

            h.controller.start()
            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(5)
            h.hits shouldBe emptyList()
            h.scheduler.tick(20)

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(left.uniqueId, center.uniqueId, right.uniqueId)
            h.hits.size shouldBe 3
            h.damageScales.map { it.first }.distinct() shouldBe listOf(0.65)
            h.damageScales.all { kotlin.math.abs(it.second - 3.9) < 0.000001 } shouldBe true
            h.visualPlays.count { it.spell == StaffSpell.LANCE && it.secondary } shouldBe 3
        }
    }

    "emerald bloom expands at the target after its warning" {
        withStaffHarness(settings = staffSettings(range = 32.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-bloom")
            val player = h.player("bloom", world, StaffSpell.NOVA).apply { isSneaking = true }
            val target = h.zombie(world, x = 0.5, z = 16.0)
            val side = h.zombie(world, x = 2.5, z = 16.0)
            val remote = h.zombie(world, x = 8.0, z = 16.0)
            h.controller.start()
            h.controller.cast(player)
            h.scheduler.tick(5)
            h.hits shouldBe emptyList()
            h.scheduler.tick(25)
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(target.uniqueId, side.uniqueId)
            remote.isValid shouldBe true
            h.hits.distinctBy { it.uniqueId }.size shouldBe h.hits.size
        }
    }

    "secondary mark stays fixed, pulses four times, and only pulls after damage lands" {
        var deniedId: UUID? = null
        withStaffHarness(
            settings = staffSettings(range = 12.0),
            hit = { _, target -> target.uniqueId != deniedId },
        ) { h ->
            val world = h.paper.addSimpleWorld("staff-mark-secondary")
            val player = h.player("staff-mark-secondary", world, StaffSpell.MARK).apply { isSneaking = true }
            val original = h.zombie(world, x = 0.5, z = 5.0)
            h.controller.start()

            h.controller.cast(player)
            original.teleport(Location(world, 0.5, 64.0, 12.0))
            val pulled = h.zombie(world, x = 1.0, z = 5.0)
            val denied = h.zombie(world, x = 1.3, z = 5.0)
            deniedId = denied.uniqueId

            h.scheduler.tick(7)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(pulled.uniqueId, denied.uniqueId)
            (pulled.velocity.lengthSquared() > 0.0) shouldBe true
            denied.velocity.lengthSquared() shouldBe 0.0
            h.damageScales.map { it.first }.distinct() shouldBe listOf(0.12)

            h.scheduler.tick(24)
            h.hits.count { it.uniqueId == pulled.uniqueId } shouldBe 4
            h.hits.count { it.uniqueId == denied.uniqueId } shouldBe 4
            h.hits.any { it.uniqueId == original.uniqueId } shouldBe false
            h.scheduler.tick(8)
            h.damageScales.last().first shouldBe 0.65
            h.visualPlays.any { it.spell == StaffSpell.MARK && it.secondary } shouldBe true
        }
    }

    "secondary ember staggers three reduced-power meteors between a floor and low ceiling" {
        withStaffHarness(settings = staffSettings(range = 12.0, aimDegrees = 30.0, maxAreaTargets = 8),
            rayStep = 0.1) { h ->
            val world = h.paper.addSimpleWorld("staff-ember-secondary")
            world.loadChunk(0, 0)
            world.isChunkLoaded(0, 0) shouldBe true
            val player = h.player("staff-ember-secondary", world, StaffSpell.EMBER).apply { isSneaking = true }
            for (x in -5..10) for (z in 6..15) {
                world.getBlockAt(x, 63, z).type = Material.STONE
                world.getBlockAt(x, 70, z).type = Material.STONE
            }
            val targets = (0 until 9).map { index ->
                world.spawn(Location(world, 4.5 + (index % 3) * 0.12, 64.0, 10.5 + (index / 3) * 0.12), Zombie::class.java)
            }
            h.controller.start()

            h.controller.cast(player)
            h.visualPlays.count { it.spell == StaffSpell.EMBER && it.secondary } shouldBe 0
            h.scheduler.tick(14)
            h.visualPlays.count { it.spell == StaffSpell.EMBER && it.secondary } shouldBe 0
            h.hits shouldBe emptyList()
            player.teleport(player.location.apply { yaw = 180f }) // The warning and destination are locked on release.
            h.scheduler.tick(1)
            h.visualPlays.count { it.spell == StaffSpell.EMBER && it.secondary } shouldBe 1
            h.scheduler.tick(6)
            h.visualPlays.count { it.spell == StaffSpell.EMBER && it.secondary } shouldBe 2
            h.scheduler.tick(6)
            h.visualPlays.count { it.spell == StaffSpell.EMBER && it.secondary } shouldBe 3
            h.visualPlays.filter { it.spell == StaffSpell.EMBER && it.secondary }
                .all { it.from.y > 64.0 && it.from.y < 70.0 } shouldBe true
            h.scheduler.tick(18)

            h.hits.size shouldBe 8
            h.hits.map { it.uniqueId }.distinct().size shouldBe 8
            h.hits.all { it in targets } shouldBe true
            h.damageScales.map { it.first }.distinct() shouldBe listOf(0.65)
        }
    }

    "secondary ember quit invalidates delayed meteor spawns" {
        withStaffHarness(settings = staffSettings(range = 10.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-ember-secondary-quit")
            val player = h.player("staff-ember-secondary-quit", world, StaffSpell.EMBER).apply { isSneaking = true }
            h.controller.start()

            h.controller.cast(player)
            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            h.scheduler.tick(20)

            h.visualPlays shouldBe emptyList()
            h.hits shouldBe emptyList()
            verify(exactly = 1) { h.effects.cancel(player.uniqueId) }
        }
    }

    "secondary cooldowns use one and a half or two times the primary duration" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-secondary-cooldown")
            val quick = h.player("staff-secondary-quick", world, StaffSpell.FROST).apply { isSneaking = true }
            h.controller.cast(quick)
            quick.inventory.setItemInMainHand(h.config.item(StaffSpell.NOVA))
            h.scheduler.tick(20)
            h.controller.cast(quick)
            h.captures.size shouldBe 1
            h.scheduler.tick(10)
            h.controller.cast(quick)
            h.captures.size shouldBe 2

            val long = h.player("staff-secondary-long", world, StaffSpell.MARK).apply { isSneaking = true }
            h.controller.cast(long)
            long.inventory.setItemInMainHand(h.config.item(StaffSpell.CHAIN))
            h.scheduler.tick(30)
            h.controller.cast(long)
            h.captures.size shouldBe 3
            h.scheduler.tick(10)
            h.controller.cast(long)
            h.captures.size shouldBe 4
        }
    }

    "frost waves stop on caster quit, death, world change, and controller close" {
        withStaffHarness(settings = staffSettings(frostRange = 7.0, frostDegrees = 50.0)) { h ->
            h.controller.start()
            val quitWorld = h.paper.addSimpleWorld("staff-frost-quit")
            val quitting = h.player("staff-frost-quit", quitWorld, StaffSpell.FROST)
            h.zombie(quitWorld, x = 0.5, z = 4.0)
            h.controller.cast(quitting)
            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))

            val deathWorld = h.paper.addSimpleWorld("staff-frost-death")
            val dying = h.player("staff-frost-death", deathWorld, StaffSpell.FROST)
            h.zombie(deathWorld, x = 0.5, z = 4.0)
            h.controller.cast(dying)
            val deathEvent = mockk<PlayerDeathEvent>()
            every { deathEvent.entity } returns dying
            h.controller.onDeath(deathEvent)

            val oldWorld = h.paper.addSimpleWorld("staff-frost-world-change")
            val newWorld = h.paper.addSimpleWorld("staff-frost-world-change-to")
            val changing = h.player("staff-frost-world-change", oldWorld, StaffSpell.FROST)
            h.zombie(oldWorld, x = 0.5, z = 4.0)
            h.controller.cast(changing)
            changing.teleport(Location(newWorld, 0.5, 64.0, 0.5))
            h.controller.onWorldChange(PlayerChangedWorldEvent(changing, oldWorld))

            verify(exactly = 1) { h.effects.cancel(quitting.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(dying.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(changing.uniqueId) }
            h.scheduler.tick(16)
            h.hits shouldBe emptyList()

            val closingWorld = h.paper.addSimpleWorld("staff-frost-close")
            val closing = h.player("staff-frost-close", closingWorld, StaffSpell.FROST)
            h.zombie(closingWorld, x = 0.5, z = 4.0)
            h.controller.cast(closing)
            h.controller.close()
            h.scheduler.tick(16)
            h.hits shouldBe emptyList()
        }
    }

    "chain reaches each mob once and stops when an impact is cancelled" {
        val cancelledTargets = mutableSetOf<UUID>()
        withStaffHarness(
            settings = staffSettings(chainTargets = 4),
            hit = { _, target -> target.uniqueId !in cancelledTargets },
        ) { h ->
            val world = h.paper.addSimpleWorld("staff-chain")
            val player = h.player("staff-chain", world, StaffSpell.CHAIN)
            val first = h.zombie(world, x = 0.5, z = 4.0)
            val second = h.zombie(world, x = 2.0, z = 5.0)
            val third = h.zombie(world, x = 4.0, z = 6.0)
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(first.uniqueId, second.uniqueId, third.uniqueId)
            h.hits.map { it.uniqueId }.distinct().size shouldBe h.hits.size

            h.hits.clear()
            val blockedWorld = h.paper.addSimpleWorld("staff-chain-cancelled")
            val cancelledPlayer = h.player("staff-chain-cancelled", blockedWorld, StaffSpell.CHAIN)
            val cancelled = h.zombie(blockedWorld, x = 0.5, z = 4.0)
            h.zombie(blockedWorld, x = 2.0, z = 5.0)
            cancelledTargets += cancelled.uniqueId

            h.controller.cast(cancelledPlayer)
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(cancelled.uniqueId)
        }
    }

    "mark melee rechecks contact and cancels for invalid, dead, death, quit, world change, and close" {
        withStaffHarness { h ->
            h.controller.start()

            val movingWorld = h.paper.addSimpleWorld("staff-mark-moving")
            val movingPlayer = h.player("mark-moving", movingWorld, StaffSpell.MARK)
            val moving = h.zombie(movingWorld, x = 0.5, z = 3.5)
            h.controller.cast(movingPlayer)
            moving.teleport(Location(movingWorld, 0.5, 64.0, 6.0))

            val invalidWorld = h.paper.addSimpleWorld("staff-mark-invalid")
            val invalidPlayer = h.player("mark-invalid", invalidWorld, StaffSpell.MARK)
            val invalidTarget = h.zombie(invalidWorld, x = 0.5, z = 3.5)
            h.controller.cast(invalidPlayer)
            invalidTarget.remove()

            val deadWorld = h.paper.addSimpleWorld("staff-mark-dead")
            val deadPlayer = h.player("mark-dead", deadWorld, StaffSpell.MARK)
            val deadTarget = h.zombie(deadWorld, x = 0.5, z = 3.5)
            h.controller.cast(deadPlayer)
            deadTarget.health = 0.0

            val deathWorld = h.paper.addSimpleWorld("staff-mark-death")
            val dyingPlayer = h.player("mark-death", deathWorld, StaffSpell.MARK)
            h.zombie(deathWorld, x = 0.5, z = 3.5)
            h.controller.cast(dyingPlayer)
            val deathEvent = mockk<PlayerDeathEvent>()
            every { deathEvent.entity } returns dyingPlayer
            h.controller.onDeath(deathEvent)

            val quitWorld = h.paper.addSimpleWorld("staff-mark-quit")
            val quitting = h.player("mark-quit", quitWorld, StaffSpell.MARK)
            h.zombie(quitWorld, x = 0.5, z = 3.5)
            h.controller.cast(quitting)
            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))

            val worldChangeOrigin = h.paper.addSimpleWorld("staff-mark-world-change")
            val otherWorld = h.paper.addSimpleWorld("staff-mark-other")
            val changingWorld = h.player("mark-world", worldChangeOrigin, StaffSpell.MARK)
            h.zombie(worldChangeOrigin, x = 0.5, z = 3.5)
            h.controller.cast(changingWorld)
            changingWorld.teleport(Location(otherWorld, 0.5, 64.0, 0.5))
            h.controller.onWorldChange(PlayerChangedWorldEvent(changingWorld, worldChangeOrigin))

            h.scheduler.tick(4)
            h.hits shouldBe emptyList()
            verify(exactly = 1) { h.effects.cancel(dyingPlayer.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(quitting.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(changingWorld.uniqueId) }

            val closeWorld = h.paper.addSimpleWorld("staff-mark-close")
            val closingPlayer = h.player("mark-close", closeWorld, StaffSpell.MARK)
            h.zombie(closeWorld, x = 0.5, z = 3.5)
            h.controller.cast(closingPlayer)
            val hitCountBeforeClose = h.hits.size
            h.controller.close()
            verify(exactly = 1) { h.effects.close() }
            h.scheduler.tick(10)
            h.hits.size shouldBe hitCountBeforeClose
        }
    }
})

internal data class StaffHarness(
    val paper: MockBukkitTestRuntime,
    val scheduler: TestTaskScheduler,
    val config: StaffSpellConfig,
    val controller: StaffSpellController,
    val effects: StaffSpellDisplayEffects,
    val movedEffects: MutableList<Pair<UUID, Location>>,
    val trailMoves: MutableList<StaffTrailMove>,
    val projectileMoves: MutableList<StaffProjectileMove>,
    val finishedTrails: MutableList<UUID?>,
    val visualPlays: MutableList<StaffVisualPlay>,
    val removedEffects: MutableList<UUID?>,
    val captures: MutableList<Player>,
    val hits: MutableList<LivingEntity>,
    val hitLocations: MutableList<Location>,
    val damageScales: MutableList<Pair<Double, Double>>,
) {
    fun player(name: String, world: World, spell: StaffSpell): Player = paper.addPlayer(name).apply {
        val spawn = Location(world, 0.5, 64.0, 0.5).apply { setDirection(Vector(0.0, 0.0, 1.0)) }
        teleport(spawn)
        inventory.setItemInMainHand(config.item(spell))
    }

    fun zombie(world: World, x: Double, z: Double): Zombie =
        world.spawn(Location(world, x, 64.0, z), Zombie::class.java)
}

internal data class StaffTrailMove(val id: UUID?, val points: List<Location>)
internal data class StaffProjectileMove(val id: UUID?, val at: Location, val direction: Vector)

internal data class StaffVisualPlay(
    val id: UUID,
    val spell: StaffSpell,
    val from: Location,
    val to: Location,
    val secondary: Boolean,
    val impact: Boolean,
)

internal fun withStaffHarness(
    settings: StaffSpellSettings = staffSettings(),
    hit: (Player, LivingEntity) -> Boolean = { _, _ -> true },
    currentCast: (Player, StaffSpellCast) -> Boolean = { _, _ -> true },
    rayStep: Double = 0.1,
    config: StaffSpellConfig = StaffSpellConfig(TestConfig()),
    summonActorFactory: ((World, Location, Boolean) -> org.bukkit.entity.AbstractSkeleton)? = null,
    block: (StaffHarness) -> Unit,
) {
    failOnUnsupportedMockBukkitOperation {
        val scheduler = TestTaskScheduler()
        mockkConstructor(WorldMock::class)
        try {
            // This fixture is a fully loaded combat map; unloaded and partial-shape rays have their own contract spec.
            every { anyConstructed<WorldMock>().isChunkLoaded(any<Int>(), any<Int>()) } returns true
            every {
                anyConstructed<WorldMock>().rayTraceBlocks(
                    any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>(),
                )
            } answers {
                val start = firstArg<Location>()
                val direction = secondArg<Vector>().clone().normalize()
                val distance = thirdArg<Double>()
                val world = requireNotNull(start.world)
                val steps = (distance / rayStep).toInt().coerceAtLeast(1)
                (1..steps).firstNotNullOfOrNull { step ->
                    val hitPosition = start.toVector().add(direction.clone().multiply(distance * step / steps))
                    val block = world.getBlockAt(hitPosition.blockX, hitPosition.blockY, hitPosition.blockZ)
                    if (block.type.isAir) null else BoundingBox(
                        block.x.toDouble(), block.y.toDouble(), block.z.toDouble(),
                        block.x + 1.0, block.y + 1.0, block.z + 1.0,
                    ).rayTrace(start.toVector(), direction, distance)
                        ?.let { hit -> RayTraceResult(hit.hitPosition, block, hit.hitBlockFace ?: BlockFace.NORTH) }
                }
            }
            MockBukkitTestRuntime.open().use { paper ->
                Tasks.withScheduler(scheduler) {
                    CooldownManager.stop()
                    CooldownManager.clearAll()
                    CooldownManager.setupTask(5, scheduler)
                    val damage = mockk<StaffSpellDamage>()
                    every { damage.summonCast = any() } returns Unit
                    val captures = mutableListOf<Player>()
                    val hits = mutableListOf<LivingEntity>()
                    val hitLocations = mutableListOf<Location>()
                    val damageScales = mutableListOf<Pair<Double, Double>>()
                    every { damage.eligible(any(), any(), any()) } answers {
                        val target = secondArg<LivingEntity>()
        target !is Player && target.isValid && !target.isDead && !target.isInvulnerable && target.health > 0.0
                    }
                    every { damage.capture(any(), any()) } answers {
                        val player = firstArg<Player>()
                        captures += player
                        StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
                    }
                    every { damage.isCurrent(any(), any()) } answers { currentCast(firstArg(), secondArg()) }
                    every { damage.hit(any(), any(), any(), any(), any()) } answers {
                        val player = firstArg<Player>()
                        val target = secondArg<LivingEntity>()
                        hits += target
                        hitLocations += target.location.clone()
                        damageScales += arg<Double>(3) to arg<Double>(4)
                        hit(player, target)
                    }
                    val effects = mockk<StaffSpellDisplayEffects>(relaxed = true)
                    val visualPlays = mutableListOf<StaffVisualPlay>()
                    every { effects.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                        val id = UUID.randomUUID()
                        if (!arg<Boolean>(13)) visualPlays += StaffVisualPlay(id, arg<StaffSpell>(1), arg<Location>(2).clone(),
                            arg<Location>(3).clone(), arg<Boolean>(7), arg<Boolean>(6))
                        id
                    }
                    every { effects.chargeOrigin(any()) } returns null
                    every { effects.releaseCharge(any(), any(), any()) } returns null
                    val movedEffects = mutableListOf<Pair<UUID, Location>>()
                    val trailMoves = mutableListOf<StaffTrailMove>()
                    val projectileMoves = mutableListOf<StaffProjectileMove>()
                    val finishedTrails = mutableListOf<UUID?>()
                    val removedEffects = mutableListOf<UUID?>()
                    every { effects.move(any(), any()) } answers {
                        movedEffects += firstArg<UUID>() to secondArg<Location>().clone()
                    }
                    every { effects.moveTrail(any(), any()) } answers {
                        trailMoves += StaffTrailMove(arg<UUID?>(0), arg<List<Location>>(1).map { it.clone() })
                    }
                    every { effects.moveProjectile(any(), any(), any()) } answers {
                        projectileMoves += StaffProjectileMove(firstArg<UUID?>(), secondArg<Location>().clone(),
                            thirdArg<Vector>().clone())
                    }
                    every { effects.finishTrail(any()) } answers { finishedTrails += arg<UUID?>(0) }
                    every { effects.remove(any()) } answers { removedEffects += firstArg<UUID?>() }
                    val controller = StaffSpellController(config, settings, damage, effects, summonActorFactory)
                    val harness = StaffHarness(paper, scheduler, config, controller, effects,
                        movedEffects, trailMoves, projectileMoves, finishedTrails, visualPlays, removedEffects,
                        captures, hits, hitLocations, damageScales)
                    try {
                        block(harness)
                    } finally {
                        controller.close()
                        CooldownManager.stop()
                        CooldownManager.clearAll()
                    }
                }
            }
        } finally {
            unmockkConstructor(WorldMock::class)
        }
    }
}

internal fun staffSettings(
    range: Double = 24.0,
    aimDegrees: Double = 12.0,
    chainTargets: Int = 1,
    markTicks: Int = 18,
    frostRange: Double = 7.0,
    frostDegrees: Double = 50.0,
    maxAreaTargets: Int = 8,
    lanceWidth: Double = 0.22,
    lanceTargets: Int = 3,
    emberSpeed: Double = 1.2,
    emberRadius: Double = 2.8,
    novaRadius: Double = 8.0,
) = StaffSpellSettings(
    range = range,
    aimDegrees = aimDegrees,
    chainRadius = 6.0,
    chainTargets = chainTargets,
    chainDecay = 0.7,
    markTicks = markTicks,
    markRadius = 3.5,
    frostRange = frostRange,
    frostDegrees = frostDegrees,
    frostSlowTicks = 40,
    maxAreaTargets = maxAreaTargets,
    lanceWidth = lanceWidth,
    lanceTargets = lanceTargets,
    emberSpeed = emberSpeed,
    emberRadius = emberRadius,
    novaRadius = novaRadius,
    tuning = StaffSpell.entries.associateWith { StaffSpellTuning(power = 1.0, vanillaDamage = 6.0, cooldownTicks = 20) },
)

private fun interact(player: Player, item: ItemStack) = PlayerInteractEvent(
    player,
    Action.LEFT_CLICK_AIR,
    item,
    null,
    BlockFace.SELF,
    EquipmentSlot.HAND,
)
