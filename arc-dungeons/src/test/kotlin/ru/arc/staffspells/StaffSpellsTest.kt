package ru.arc.staffspells

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import org.mockbukkit.mockbukkit.attribute.AttributesMock
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.particle.PaperViewerParticles
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class StaffSpellsTest : FreeSpec({
    "duel staff is native, marked, keeps spell instructions, and replaces PvE prototype lore" {
        val config = StaffSpellConfig(StaffTestConfig(mapOf(
            "chain.skin" to "missing:appearance_should_not_be_loaded",
            "chain.duel-appearance.item-model" to "minecraft:crossbow",
            "chain.duel-appearance.custom-model-data" to 10009,
            "chain.description" to "Направленный разряд",
            "chain.aim" to "Прицельтесь во врага",
            "chain.secondary" to "<attack-secondary> — цепная молния",
            "chain.quick" to "<defense> — фронтальный щит",
            "chain.quick-secondary" to "<defense-secondary> — поглощение",
            "messages.duel-only" to "Только в активной дуэли",
            "messages.prototype" to "Только прототип",
            "messages.use" to "<attack> — применить",
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
            lore.contains("Shift + ПКМ — цепная молния") shouldBe true
            lore.contains("ЛКМ — фронтальный щит") shouldBe true
            lore.contains("Shift + ЛКМ — поглощение") shouldBe true
            lore.contains("Только в активной дуэли") shouldBe true
            lore.contains("ПКМ — применить") shouldBe true
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
                    StaffSpellConfig(StaffTestConfig(values)).duelItem(StaffSpell.CHAIN)
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
                    h.controller.canCharge(player, spell) shouldBe false
                    h.scheduler.tick(4)
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
            if (spell == StaffSpell.ICICLE) player.teleport(player.location.apply { yaw = 73f; pitch = -31f })
            h.controller.start()
            h.controller.charge(player, spell, 1.0)
            val held = h.visualPlays.toList()
            held.size shouldBe if (spell == StaffSpell.ICICLE) 11 else 1
            if (spell == StaffSpell.ICICLE) {
                val eye = player.eyeLocation
                held.forEachIndexed { index, scene ->
                    val direction = eye.direction
                    val rotation = staffChargeOrientation(spell, eye.yaw,
                        org.joml.Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()))
                    val offset = rotation.transform(staffIcicleChargeAnchorOffset(index))
                    val expected = eye.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
                    (scene.from.distance(expected) < 1e-5) shouldBe true
                    (scene.from.toVector().subtract(eye.toVector()).dot(direction) > 3.2) shouldBe true
                }
            }
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
        val config = spyk(StaffSpellConfig(StaffTestConfig()))
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

    "primary lightning corrects a near miss without acquiring broad or out-of-range targets" {
        for (nearMiss in listOf(true, false)) {
            val settings = staffSettings(range = 56.0, aimDegrees = 20.0).copy(
                aimAssist = StaffAimAssistSettings(halfAngleDegrees = 8.0, maxMiss = 2.0, strength = 0.70),
            )
            withStaffHarness(settings = settings) { h ->
                val world = h.paper.addSimpleWorld("staff-chain-assist-$nearMiss")
                val player = h.player("staff-chain-assist-$nearMiss", world, StaffSpell.CHAIN)
                val target = h.zombie(world, x = if (nearMiss) 2.0 else 8.0, z = 40.0)
                h.zombie(world, x = 0.5, z = -3.0)
                h.zombie(world, x = 0.5, z = 60.0)
                val origin = player.eyeLocation.clone()
                h.controller.start()

                h.controller.cast(player)
                h.scheduler.tick(60)

                h.hits.map { it.uniqueId } shouldBe if (nearMiss) listOf(target.uniqueId) else emptyList()
                val firstStep = h.trailMoves.first().points.last().toVector().subtract(origin.toVector())
                (firstStep.x > 0.0) shouldBe nearMiss
                if (!nearMiss) (h.trailMoves.last().points.last().distance(origin) <= 56.001) shouldBe true
            }
        }
    }

    "primary lightning keeps its launch heading when a directly aimed target dodges" {
        withStaffHarness(settings = staffSettings(range = 56.0, aimDegrees = 20.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-chain-dodge")
            val player = h.player("staff-chain-dodge", world, StaffSpell.CHAIN)
            val moving = h.zombie(world, x = 0.5, z = 40.0)
            h.zombie(world, x = 0.5, z = -3.0)
            h.zombie(world, x = 0.5, z = 60.0)
            val castOrigin = player.eyeLocation.clone()
            val forward = castOrigin.direction.normalize()
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            moving.teleport(Location(world, 3.0, 64.0, 41.0))
            h.scheduler.tick(60)

            h.hits shouldBe emptyList()
            h.trailMoves.all { move ->
                val heading = move.points.last().toVector().subtract(castOrigin.toVector()).normalize()
                heading.dot(forward) > 0.9999
            } shouldBe true
            h.trailMoves.all { it.points.size <= 41 } shouldBe true
            (h.trailMoves.last().points.size > 8) shouldBe true
            (h.trailMoves.last().points.last().distance(castOrigin) <= 56.001) shouldBe true
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

    "frost frontal primary waits for contact and slows only after successful damage" {
        val cancelledTargets = mutableSetOf<UUID>()
        withStaffHarness(
            settings = staffSettings(),
            hit = { _, target -> target.uniqueId !in cancelledTargets },
        ) { h ->
            val world = h.paper.addSimpleWorld("staff-frost")
            val player = h.player("staff-frost", world, StaffSpell.FROST)
            val cancelled = h.zombie(world, x = 0.5, z = 2.0)
            val landed = h.zombie(world, x = 4.5, z = 5.0)
            cancelledTargets += cancelled.uniqueId
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(5)

            cancelled.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            landed.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            h.hits shouldBe emptyList()

            h.scheduler.tick(1)
            landed.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe true
            h.hits.map { it.uniqueId } shouldBe listOf(cancelled.uniqueId, landed.uniqueId)
            h.scheduler.tick(STAFF_FRONTAL_DURATION_TICKS.toLong())
            h.hits.map { it.uniqueId }.distinct().size shouldBe h.hits.size
        }
    }

    "frost frontal contact uses captured direction, late arrivals, and current-player cover" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-frost-wall")
            val player = h.player("staff-frost-wall", world, StaffSpell.FROST)
            val hiddenByCurrentCover = h.zombie(world, x = 0.5, z = 6.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(5)
            player.teleport(Location(world, 12.5, 64.0, 0.5).apply {
                setDirection(Vector(0.0, 0.0, -1.0))
            })
            val lateArrival = h.zombie(world, x = 4.0, z = 6.0)
            for (y in 64..66) world.getBlockAt(5, y, 3).type = Material.STONE
            h.scheduler.tick(1)

            h.hits.map { it.uniqueId } shouldBe listOf(lateArrival.uniqueId)
            (hiddenByCurrentCover.uniqueId in h.hits.map { it.uniqueId }) shouldBe false
            lateArrival.isValid shouldBe true
        }
    }

    "frost frontal primary accepts a target inside its broad front arc and contacts it once" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-frost-side")
            val player = h.player("staff-frost-side", world, StaffSpell.FROST)
            val diagonal = h.zombie(world, x = 4.5, z = 5.0)
            h.controller.start()

            h.controller.cast(player)
            h.scheduler.tick(5)
            h.hits shouldBe emptyList()

            h.scheduler.tick(1)
            h.hits.map { it.uniqueId } shouldBe listOf(diagonal.uniqueId)
            h.scheduler.tick(STAFF_FRONTAL_DURATION_TICKS.toLong())
            h.hits.map { it.uniqueId }.distinct().size shouldBe 1
        }
    }

    "changing to Frost still waits for its own visible contact before damage" {
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

    "staff cooldown HUD tracks selection and clears on item removal quit and shutdown" {
        val config = StaffSpellConfig(StaffTestConfig(mapOf(
            "icicle.name" to "Ледяной венец", "icicle.secondary-name" to "Ледяной дождь",
            "fault.name" to "Обсидиановый раскол", "fault.secondary-name" to "Каменный выброс",
            "messages.staff-hud.ready-charge" to "<input> · <ability>",
            "messages.staff-hud.ready-instant" to "<input> · <ability>",
            "messages.staff-hud.cooldown" to "<input> · <ability><newline><seconds> с <bar>",
        )))
        val displays = StaffHudDisplayRecorder()
        withStaffHarness(config = config, hudFactory = { StaffSpellHud(displays.displays, config) }) { h ->
            val world = h.paper.addSimpleWorld("staff-hud-lifecycle")
            val player = h.player("staff-hud-lifecycle", world, StaffSpell.ICICLE)
            h.controller.start()
            h.scheduler.tick(4)
            displays.records.size shouldBe 1
            displays.text() shouldBe "ПКМ · Ледяной венец\nQ готово   F готово"

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.5)
            h.cooldowns.remainingTicks(player.uniqueId) shouldBe 4L
            h.controller.skillRemaining(player.uniqueId) shouldBe 20L
            displays.text().startsWith("ПКМ · Ледяной венец\n1.0 с ") shouldBe true
            h.scheduler.tick(8)
            h.controller.skillRemaining(player.uniqueId) shouldBe 12L
            displays.text().startsWith("ПКМ · Ледяной венец\n0.6 с ") shouldBe true

            player.isSneaking = true
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.FAULT))
            h.scheduler.tick(4)
            displays.records.size shouldBe 1
            h.captures.size shouldBe 1
            h.controller.skillRemaining(player.uniqueId) shouldBe 8L
            h.controller.skillRemaining(player.uniqueId, secondary = true) shouldBe 0L
            displays.text().startsWith("Shift + ПКМ · Каменный выброс\nQ готово") shouldBe true

            player.inventory.setItemInMainHand(ItemStack(Material.AIR))
            h.scheduler.tick(4)
            displays.records.last().removals shouldBe 1

            player.inventory.setItemInMainHand(h.config.item(StaffSpell.ICICLE))
            h.scheduler.tick(4)
            displays.records.size shouldBe 2
            displays.text().startsWith("Shift + ПКМ · Ледяной дождь") shouldBe true

            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            displays.records.last().removals shouldBe 1
            h.scheduler.tick(4)
            displays.records.size shouldBe 3 // Mock quit leaves the player online, so its timer may recreate the card.
            h.controller.close()
            displays.records.last().removals shouldBe 1
        }
    }

    "quitting retains cooldown duration and selected HUD input label" {
        val config = StaffSpellConfig(StaffTestConfig(mapOf(
            "icicle.name" to "Венец", "icicle.secondary-name" to "Дождь",
            "messages.staff-hud.cooldown" to "<ability> · <input><newline>Восстановление <seconds> с<newline><bar>",
        )))
        val displays = StaffHudDisplayRecorder()
        withStaffHarness(config = config, hudFactory = { StaffSpellHud(displays.displays, config) }) { h ->
            val world = h.paper.addSimpleWorld("staff-cooldown-return")
            val player = h.player("staff-cooldown-return", world, StaffSpell.ICICLE)
            player.isSneaking = true
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            h.controller.skillRemaining(player.uniqueId, secondary = true) shouldBe 30L
            h.scheduler.tick(8)
            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            h.scheduler.tick(4) // Mock quit leaves the player online, modelling the returning HUD.
            val remaining = h.controller.skillRemaining(player.uniqueId, secondary = true)
            remaining shouldBe 18L
            displays.text().startsWith("Дождь · Shift + ПКМ\nВосстановление 0.9 с\n") shouldBe true
            displays.text().substringBefore("\nQ ").endsWith(staffSpellHudBar(staffCooldownProgress(remaining, 30).toDouble())) shouldBe true
            h.controller.cast(player)
            h.captures.size shouldBe 1
            h.controller.skillRemaining(player.uniqueId, secondary = true) shouldBe remaining
        }
    }

    "travelling lance and quick gesture stop when the captured combat context expires" {
        listOf(StaffSpell.LANCE to StaffCastInput.PRIMARY, StaffSpell.MARK to StaffCastInput.QUICK).forEach { (spell, input) ->
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

    "all spells cast into empty space and respect the short global cooldown" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-empty-casts")
            // Ground spells require support even when the arena contains no targets.
            for (x in -12..12) for (z in -2..28) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("staff-empty-casts", world, StaffSpell.CHAIN)
            val spells = StaffSpell.entries.filterNot { it in setOf(StaffSpell.NECROMANCER, StaffSpell.INFERNO, StaffSpell.GRAVITY) }
            h.controller.start()

            spells.forEachIndexed { index, spell ->
                player.inventory.setItemInMainHand(h.config.item(spell))
                h.controller.cast(player, StaffCastInput.PRIMARY, if (StaffSpell.from(player.inventory.itemInMainHand) == StaffSpell.ICICLE) 0.5 else 0.0)
                h.captures.size shouldBe index + 1

                player.inventory.setItemInMainHand(h.config.item(spells[(index + 1) % spells.size]))
                h.controller.cast(player, StaffCastInput.PRIMARY, if (StaffSpell.from(player.inventory.itemInMainHand) == StaffSpell.ICICLE) 0.5 else 0.0)
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

    "manual lance primary and ember do not acquire an off-axis mob" {
        withStaffHarness(settings = staffSettings(range = 10.0)) { h ->
            h.controller.start()
            for (spell in listOf(StaffSpell.LANCE, StaffSpell.EMBER)) {
                val world = h.paper.addSimpleWorld("staff-manual-${spell.id}")
                val player = h.player("staff-manual-${spell.id}", world, spell)
                // Within the 12-degree soft aim cone, but outside the manual beam/projectile path.
                h.zombie(world, x = 1.5, z = 7.0)

                h.controller.cast(player)
                if (spell == StaffSpell.EMBER) h.scheduler.tick(12)

                h.hits shouldBe emptyList()
            }
        }
    }

    "lance primary pierces up to its target limit" {
        withStaffHarness(settings = staffSettings(lanceTargets = 3)) { h ->
            val world = h.paper.addSimpleWorld("staff-lance-pierce")
            val player = h.player("staff-lance-pierce", world, StaffSpell.LANCE)
            val first = h.zombie(world, x = 0.5, z = 4.0)
            val second = h.zombie(world, x = 0.5, z = 6.0)
            val third = h.zombie(world, x = 0.5, z = 8.5)
            val beyondTargetLimit = h.zombie(world, x = 0.5, z = 11.0)

            h.controller.start()
            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(5)
            h.hits shouldBe emptyList()
            h.scheduler.tick(20)

            h.hits.map { it.uniqueId } shouldBe listOf(first.uniqueId, second.uniqueId, third.uniqueId)
            beyondTargetLimit.isValid shouldBe true
        }
    }

    "lance primary wall collision stops the beam while target budget remains" {
        withStaffHarness(settings = staffSettings(lanceTargets = 8)) { h ->
            val world = h.paper.addSimpleWorld("staff-lance-wall")
            val player = h.player("staff-lance-wall", world, StaffSpell.LANCE)
            val inFront = h.zombie(world, x = 0.5, z = 4.0)
            val behindWall = h.zombie(world, x = 0.5, z = 12.0)
            for (x in -1..1) for (y in 64..67) world.getBlockAt(x, y, 10).type = Material.STONE

            h.controller.start()
            h.controller.cast(player)
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

    "shift right-click chain is a radial nova with the extended cooldown" {
        withStaffHarness(settings = staffSettings(range = 12.0, chainTargets = 4, aimDegrees = 20.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-chain-secondary")
            val player = h.player("staff-chain-secondary", world, StaffSpell.CHAIN).apply { isSneaking = true }
            val behind = h.zombie(world, x = 0.5, z = -2.5)
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.projectileMoves shouldBe emptyList()
            h.controller.skillRemaining(player.uniqueId, secondary = true) shouldBe 80L
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

    "icicle primary keeps one live focus through staggered launches and nearby reticle turns" {
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-frost-secondary")
            val player = h.player("staff-frost-secondary", world, StaffSpell.ICICLE)
            val focus = h.zombie(world, x = 0.5, z = 12.0)
            val neighbor = h.zombie(world, x = 1.5, z = 14.0)
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
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
            aimAt(focus)
            h.scheduler.tick(3)
            h.projectileMoves shouldBe emptyList()
            h.scheduler.tick(1)
            h.projectileMoves.map { it.id } shouldBe listOf(icicles[0].id)
            aimAt(neighbor)
            h.scheduler.tick(2)
            h.projectileMoves.count { it.id == icicles[1].id } shouldBe 1
            h.scheduler.tick(2)
            h.projectileMoves.count { it.id == icicles[2].id } shouldBe 1
            h.scheduler.tick(50)

            h.hits.map { it.uniqueId } shouldBe listOf(focus.uniqueId)
            h.damageScales shouldBe listOf(1.45 to 8.7)
            focus.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe true
            neighbor.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
            verify(exactly = 7) { h.effects.impact(any(), any(), 0.9, 10) }
        }
    }

    "icicle fallback release retains each scattered charge slot" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-icicle-fallback-slots")
            val player = h.player("staff-icicle-fallback-slots", world, StaffSpell.ICICLE)
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)

            val fallbackCharges = h.visualPlays.filter { it.spell == StaffSpell.ICICLE && it.charging }
            fallbackCharges.size shouldBe 7
            fallbackCharges.map { it.chargeSlot } shouldBe (0..6).toList()
            fallbackCharges.forEach { scene ->
                verify(exactly = 1) { h.effects.releaseCharge(scene.id, 80, any()) }
            }
        }
    }

    "icicle aim assist nudges a near miss without granting a perfect target lock" {
        val baseline = firstIcicleHeadingAtAgeFive()
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-icicle-near-miss")
            val player = h.player("staff-icicle-near-miss", world, StaffSpell.ICICLE)
            val target = h.zombie(world, x = 1.3, z = 12.0)
            val eye = player.eyeLocation
            val look = eye.direction.normalize()
            val offset = center(target).toVector().subtract(eye.toVector())
            (staffAimScore(offset.x, offset.y, offset.z, look.x, look.y, look.z, 24.0, 8.0) != null) shouldBe true
            target.boundingBox.expand(0.25).rayTrace(eye.toVector(), look, 24.0) shouldBe null
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
            h.scheduler.tick(8)

            val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE && it.charging }.id
            val assisted = h.projectileMoves.filter { it.id == firstShot }.getOrNull(4)?.direction
            val heading = requireNotNull(assisted)
            // The default 70% correction still leaves the initial miss partially uncorrected.
            (heading.x > baseline.x) shouldBe true
            (heading.angle(baseline) < Math.toRadians(8.0 * 0.70)) shouldBe true
        }
    }

    "icicle aim assist does not steer to targets outside its narrow cone" {
        val baseline = firstIcicleHeadingAtAgeFive()
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-icicle-outside-cone")
            val player = h.player("staff-icicle-outside-cone", world, StaffSpell.ICICLE)
            val target = h.zombie(world, x = 3.0, z = 12.0)
            val eye = player.eyeLocation
            val look = eye.direction.normalize()
            val offset = center(target).toVector().subtract(eye.toVector())
            (staffAimScore(offset.x, offset.y, offset.z, look.x, look.y, look.z, 24.0, 8.0) == null) shouldBe true
            target.boundingBox.expand(0.25).rayTrace(eye.toVector(), look, 24.0) shouldBe null
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
            h.scheduler.tick(8)

            val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE && it.charging }.id
            val unassisted = h.projectileMoves.filter { it.id == firstShot }.getOrNull(4)?.direction
            (requireNotNull(unassisted).distanceSquared(baseline) < 1e-8) shouldBe true
        }
    }

    "icicle aim assist does not acquire a near miss behind cover" {
        val baseline = firstIcicleHeadingAtAgeFive(cover = true)
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-icicle-aim-cover")
            val player = h.player("staff-icicle-aim-cover", world, StaffSpell.ICICLE)
            val target = h.zombie(world, x = 1.3, z = 12.0)
            for (x in -3..3) for (y in 64..66) world.getBlockAt(x, y, 11).type = Material.STONE
            visible(player.eyeLocation, center(target)) shouldBe false
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
            h.scheduler.tick(12)

            val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE && it.charging }.id
            val unassisted = h.projectileMoves.filter { it.id == firstShot }.getOrNull(4)?.direction
            (requireNotNull(unassisted).distanceSquared(baseline) < 1e-8) shouldBe true
            h.hits shouldBe emptyList()
        }
    }

    "icicle aim assist requires line of sight from each release point" {
        val baseline = firstIcicleHeadingAtAgeFive()
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-icicle-release-cover")
            val player = h.player("staff-icicle-release-cover", world, StaffSpell.ICICLE)
            val target = h.zombie(world, x = 1.3, z = 12.0)
            world.getBlockAt(-1, 65, 7).type = Material.STONE
            val eye = player.eyeLocation
            visible(eye, center(target)) shouldBe true
            val rotation = staffChargeOrientation(StaffSpell.ICICLE, eye.yaw,
                org.joml.Vector3f(eye.direction.x.toFloat(), eye.direction.y.toFloat(), eye.direction.z.toFloat()))
            val offset = rotation.transform(staffIcicleChargeAnchorOffset(0))
            val release = eye.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
            visible(release, center(target)) shouldBe false
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
            val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE && it.charging }.id
            h.scheduler.tick(8)

            val unassisted = h.projectileMoves.filter { it.id == firstShot }.getOrNull(4)?.direction
            (requireNotNull(unassisted).distanceSquared(baseline) < 1e-8) shouldBe true
        }
    }

    "icicle stops steering when a selected target becomes occluded" {
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-icicle-lost-cover")
            val player = h.player("staff-icicle-lost-cover", world, StaffSpell.ICICLE)
            val target = h.zombie(world, x = 0.5, z = 12.0)
            visible(player.eyeLocation, center(target)) shouldBe true
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
            val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE && it.charging }.id
            h.scheduler.tick(4) // The first bolt acquires the directly aimed target.
            val launchHeading = h.projectileMoves.single { it.id == firstShot }.direction
            for (x in -3..3) for (y in 64..66) world.getBlockAt(x, y, 11).type = Material.STONE
            visible(player.eyeLocation, center(target)) shouldBe false
            h.scheduler.tick(4)

            val firstShotMoves = h.projectileMoves.filter { it.id == firstShot }
            firstShotMoves.size shouldBe 5
            (firstShotMoves[4].direction.distanceSquared(launchHeading) < 1e-8) shouldBe true
        }
    }

    "icicle volley follows reticle turns in empty space without redirecting the first airborne shot" {
        withStaffHarness(settings = staffSettings(range = 32.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-icicle-live-reticle")
            val player = h.player("live-reticle", world, StaffSpell.ICICLE)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
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

    "icicle primary departs away from the camera along a downward aim" {
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-frost-camera")
            val player = h.player("staff-frost-camera", world, StaffSpell.ICICLE).apply {
                teleport(location.apply { pitch = 62.45f })
            }
            val eye = player.eyeLocation
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
            h.scheduler.tick(19)
            h.projectileMoves.groupBy { it.id }.values.forEach { moves ->
                moves.take(4).forEach { move ->
                    (move.at.distance(eye) > 2.0) shouldBe true
                    (move.direction.dot(eye.direction) > 0.999) shouldBe true
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

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(single.uniqueId)
            h.hits.size shouldBe 1
            verify(exactly = 7) { h.effects.impact(any(), any(), 0.9, 10) }

            val other = h.player("staff-frost-secondary-quit", world, StaffSpell.ICICLE)
            h.controller.cast(other, StaffCastInput.PRIMARY, 0.6)
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

    "NOVA ground claws contact the aimed area after their warning" {
        withStaffHarness(settings = staffSettings(range = 32.0)) { h ->
            val world = h.paper.addSimpleWorld("staff-bloom")
            for (x in -6..6) for (z in 0..22) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("bloom", world, StaffSpell.NOVA).apply { isSneaking = true }
            val target = h.zombie(world, x = 0.5, z = 16.0)
            val side = h.zombie(world, x = 2.5, z = 16.0)
            val remote = h.zombie(world, x = 8.0, z = 16.0)
            h.controller.start()
            h.controller.cast(player)
            h.scheduler.tick(12)
            h.hits shouldBe emptyList()
            h.scheduler.tick(2)
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
            val world = h.solidCollisionWorld("staff-mark-secondary")
            val player = h.player("staff-mark-secondary", world, StaffSpell.MARK).apply { isSneaking = true }
            val original = h.zombie(world, x = 0.5, z = 5.0)
            h.controller.start()

            h.controller.cast(player)
            original.teleport(Location(world, 0.5, 64.0, 12.0))
            val pulled = h.zombie(world, x = 1.0, z = 5.0)
            val denied = h.zombie(world, x = 1.3, z = 5.0)
            deniedId = denied.uniqueId

            h.scheduler.tick(1)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(pulled.uniqueId, denied.uniqueId)
            (pulled.velocity.lengthSquared() > 0.0) shouldBe true
            denied.velocity.lengthSquared() shouldBe 0.0
            h.damageScales.map { it.first }.distinct() shouldBe listOf(0.12)

            h.scheduler.tick(30)
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

    "short GCD separates primary secondary Q and F while staves share each input cooldown" {
        withStaffHarness { h ->
            every { h.effects.custom(any(), any(), any(), any(), any()) } returns UUID.randomUUID()
            every { h.effects.isActive(any()) } returns true
            val world = h.paper.addSimpleWorld("staff-specific-cooldown")
            val player = h.player("staff-specific-cooldown", world, StaffSpell.CHAIN)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            h.controller.skillRemaining(player.uniqueId, secondary = true) shouldBe 80L
            h.controller.canCharge(player, StaffSpell.CHAIN) shouldBe false
            h.controller.canHotkey(player, StaffSpell.CHAIN, StaffHotkey.Q) shouldBe false
            h.scheduler.tick(4)
            h.controller.canCharge(player, StaffSpell.CHAIN) shouldBe true
            h.controller.canCharge(player, StaffSpell.CHAIN, secondary = true) shouldBe false
            h.controller.canHotkey(player, StaffSpell.CHAIN, StaffHotkey.Q) shouldBe true
            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            h.scheduler.tick(4)
            // The second press detonates the existing orb; it is not a fresh Q cast.
            h.controller.canHotkey(player, StaffSpell.CHAIN, StaffHotkey.Q) shouldBe true
            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            h.scheduler.tick(4)
            h.controller.canHotkey(player, StaffSpell.CHAIN, StaffHotkey.Q) shouldBe false
            h.controller.canHotkey(player, StaffSpell.CHAIN, StaffHotkey.F) shouldBe true
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.FROST))
            h.controller.canCharge(player, StaffSpell.FROST, secondary = true) shouldBe false
            h.controller.canHotkey(player, StaffSpell.FROST, StaffHotkey.Q) shouldBe false
            h.controller.canCharge(player, StaffSpell.FROST) shouldBe true
            player.inventory.setItemInMainHand(h.config.item(StaffSpell.CHAIN))
            h.controller.canHotkey(player, StaffSpell.CHAIN, StaffHotkey.Q) shouldBe false
            h.controller.canCharge(player, StaffSpell.CHAIN, secondary = true) shouldBe false
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

    "chain bounces after a real hit with decay while cover and cancelled impacts stop the chain" {
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
            val covered = h.zombie(world, x = 0.5, z = 9.0)
            for (x in -1..1) for (y in 63..67) world.getBlockAt(x, y, 7).type = Material.STONE
            visible(player.eyeLocation, center(covered)) shouldBe false
            h.controller.start()

            h.controller.cast(player)
            h.hits shouldBe emptyList()
            h.scheduler.tick(60)

            h.hits.map { it.uniqueId } shouldBe listOf(first.uniqueId, second.uniqueId, third.uniqueId)
            h.hits.map { it.uniqueId }.distinct().size shouldBe h.hits.size
            h.damageScales shouldBe listOf(1.0, 0.7, 0.7 * 0.7).map { it to 6.0 * it }

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

    "marks follow a moved target and cancel for invalid, dead, quit, world change, and close" {
        withStaffHarness(settings = staffSettings(markTicks = 6)) { h ->
            val world = h.paper.addSimpleWorld("staff-mark")
            val otherWorld = h.paper.addSimpleWorld("staff-mark-other")
            h.controller.start()

            val follower = h.player("mark-follow", world, StaffSpell.MARK)
            val moving = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(follower)
            follower.inventory.setItemInMainHand(ItemStack(Material.AIR))
            moving.teleport(Location(world, 0.5, 64.0, 6.0))
            h.scheduler.tick(6)
            h.hitLocations.single().z shouldBe 6.0
            h.movedEffects.any { it.second.z == 6.0 } shouldBe true
            verify(exactly = 1) { h.effects.impact(any(), any(), any(), any()) }

            val invalidPlayer = h.player("mark-invalid", world, StaffSpell.MARK)
            val invalidTarget = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(invalidPlayer)
            invalidPlayer.inventory.setItemInMainHand(ItemStack(Material.AIR))
            invalidTarget.remove()

            val deadPlayer = h.player("mark-dead", world, StaffSpell.MARK)
            val deadTarget = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(deadPlayer)
            deadPlayer.inventory.setItemInMainHand(ItemStack(Material.AIR))
            deadTarget.health = 0.0

            val dyingPlayer = h.player("mark-death", world, StaffSpell.MARK)
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(dyingPlayer)
            dyingPlayer.inventory.setItemInMainHand(ItemStack(Material.AIR))
            val deathEvent = mockk<PlayerDeathEvent>()
            every { deathEvent.entity } returns dyingPlayer
            h.controller.onDeath(deathEvent)

            val quitting = h.player("mark-quit", world, StaffSpell.MARK)
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(quitting)
            quitting.inventory.setItemInMainHand(ItemStack(Material.AIR))
            h.controller.onQuit(PlayerQuitEvent(quitting, Component.empty()))

            val changingWorld = h.player("mark-world", world, StaffSpell.MARK)
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(changingWorld)
            changingWorld.inventory.setItemInMainHand(ItemStack(Material.AIR))
            changingWorld.teleport(Location(otherWorld, 0.5, 64.0, 0.5))
            h.controller.onWorldChange(PlayerChangedWorldEvent(changingWorld, world))

            verify(exactly = 1) { h.effects.cancel(dyingPlayer.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(quitting.uniqueId) }
            verify(exactly = 1) { h.effects.cancel(changingWorld.uniqueId) }

            val hitCountBeforeCancelledMarks = h.hits.size
            h.scheduler.tick(6)
            h.hits.size shouldBe hitCountBeforeCancelledMarks

            val closingPlayer = h.player("mark-close", world, StaffSpell.MARK)
            h.zombie(world, x = 0.5, z = 4.0)
            h.controller.cast(closingPlayer)
            closingPlayer.inventory.setItemInMainHand(ItemStack(Material.AIR))
            val hitCountBeforeClose = h.hits.size
            h.controller.close()
            verify(exactly = 1) { h.effects.close() }
            h.scheduler.tick(10)
            h.hits.size shouldBe hitCountBeforeClose
        }
    }

})

private fun firstIcicleHeadingAtAgeFive(cover: Boolean = false): Vector {
    var heading: Vector? = null
    withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
        val world = h.paper.addSimpleWorld("staff-icicle-heading-probe")
        val player = h.player("staff-icicle-heading-probe", world, StaffSpell.ICICLE)
        if (cover) for (x in -3..3) for (y in 64..66) world.getBlockAt(x, y, 11).type = Material.STONE
        h.controller.start()

        h.controller.cast(player, StaffCastInput.PRIMARY, 0.6)
        val firstShot = h.visualPlays.first { it.spell == StaffSpell.ICICLE && it.charging }.id
        h.scheduler.tick(8)
        heading = h.projectileMoves.filter { it.id == firstShot }.getOrNull(4)?.direction
    }
    return requireNotNull(heading) { "The first icicle did not reach its age-five keyframe" }
}

internal data class StaffHarness(
    val paper: MockBukkitTestRuntime,
    val scheduler: TestTaskScheduler,
    val config: StaffSpellConfig,
    val controller: StaffSpellController,
    val cooldowns: StaffTestCooldowns,
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
    /** Pinned MockBukkit lacks this Paper query; these tests use full solid cubes only. */
    fun solidCollisionWorld(name: String, rayStep: Double = .1): WorldMock = object : WorldMock() {
        override fun isChunkLoaded(x: Int, z: Int) = true

        override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
            fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? =
            staffTestBlockRay(start, direction, maxDistance, ignorePassableBlocks, rayStep) { true }

        override fun rayTraceBlocks(start: io.papermc.paper.math.Position, direction: Vector, maxDistance: Double,
            fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean,
            canCollide: java.util.function.Predicate<in org.bukkit.block.Block>?): RayTraceResult? =
            staffTestBlockRay(start.toLocation(this), direction, maxDistance, ignorePassableBlocks, rayStep) {
                canCollide?.test(it) ?: true
            }

        override fun hasCollisionsIn(box: BoundingBox): Boolean =
            (kotlin.math.floor(box.minX).toInt()..kotlin.math.floor(box.maxX).toInt()).any { x ->
                (kotlin.math.floor(box.minY).toInt()..kotlin.math.floor(box.maxY).toInt()).any { y ->
                    (kotlin.math.floor(box.minZ).toInt()..kotlin.math.floor(box.maxZ).toInt()).any { z ->
                        getBlockAt(x, y, z).type.isSolid && box.overlaps(BoundingBox(
                            x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0))
                    }
                }
            }
    }.apply {
        setName(name)
        paper.server.addWorld(this)
    }

    fun player(name: String, world: World, spell: StaffSpell): Player = paper.addPlayer(name).apply {
        val spawn = Location(world, 0.5, 64.0, 0.5).apply { setDirection(Vector(0.0, 0.0, 1.0)) }
        teleport(spawn)
        inventory.setItemInMainHand(config.item(spell))
    }

    fun zombie(world: World, x: Double, z: Double): Zombie =
        world.spawn(Location(world, x, 64.0, z), Zombie::class.java).apply {
            // Native zombies have SCALE; MockBukkit 4.116.3 omits it from its default attributes.
            if (getAttribute(org.bukkit.attribute.Attribute.SCALE) == null)
                registerAttribute(org.bukkit.attribute.Attribute.SCALE)
        }
}

/** One full-cube ray model shared by the standard and collision-aware fixture worlds. */
private fun staffTestBlockRay(start: Location, directionInput: Vector, distance: Double,
    ignorePassable: Boolean, rayStep: Double, canCollide: (org.bukkit.block.Block) -> Boolean): RayTraceResult? {
    val direction = directionInput.clone().normalize()
    val world = requireNotNull(start.world)
    val steps = (distance / rayStep).toInt().coerceAtLeast(1)
    return (1..steps).firstNotNullOfOrNull { step ->
        val hitPosition = start.toVector().add(direction.clone().multiply(distance * step / steps))
        val block = world.getBlockAt(hitPosition.blockX, hitPosition.blockY, hitPosition.blockZ)
        if (block.type.isAir || ignorePassable && block.isPassable || !canCollide(block)) null else BoundingBox(
            block.x.toDouble(), block.y.toDouble(), block.z.toDouble(),
            block.x + 1.0, block.y + 1.0, block.z + 1.0,
        ).rayTrace(start.toVector(), direction, distance)
            ?.let { hit -> RayTraceResult(hit.hitPosition, block, hit.hitBlockFace ?: BlockFace.NORTH) }
    }
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
    val charging: Boolean,
    val chargeSlot: Int?,
)

private data class StaffHudDisplayRecord(
    val handle: PacketTextDisplay,
    var text: Component,
    var removals: Int = 0,
)

private class StaffHudDisplayRecorder {
    val records = mutableListOf<StaffHudDisplayRecord>()

    val displays = mockk<PaperPacketDisplays>(relaxed = true).also { owner ->
        every { owner.spawnText(any(), any()) } answers {
            val record = StaffHudDisplayRecord(
                mockk<PacketTextDisplay>(relaxed = true), secondArg<Component>(),
            )
            every { record.handle.isValid } returns true
            every { record.handle.text() } answers { record.text }
            every { record.handle.text(any()) } answers {
                record.text = firstArg()
                Unit
            }
            every { record.handle.remove() } answers {
                record.removals++
                Unit
            }
            records += record
            record.handle
        }
    }

    fun text(): String = PlainTextComponentSerializer.plainText().serialize(records.last().text)
}

internal fun withStaffHarness(
    settings: StaffSpellSettings = staffSettings(),
    hit: (Player, LivingEntity) -> Boolean = { _, _ -> true },
    currentCast: (Player, StaffSpellCast) -> Boolean = { _, _ -> true },
    rayStep: Double = 0.1,
    config: StaffSpellConfig = StaffSpellConfig(StaffTestConfig()),
    summonActorFactory: ((World, Location, Boolean) -> org.bukkit.entity.AbstractSkeleton)? = null,
    hudFactory: (() -> StaffSpellHud)? = null,
    targetEligible: (Player, LivingEntity) -> Boolean = { _, target -> target !is Player },
    deniedTarget: (Player, LivingEntity, StaffSpellCast) -> Boolean = { _, _, _ -> false },
    damageBridge: StaffSpellDamage? = null,
    block: (StaffHarness) -> Unit,
) {
    failOnUnsupportedMockBukkitOperation {
        val scheduler = TestTaskScheduler()
        mockkConstructor(WorldMock::class)
        try {
            // This fixture is a fully loaded combat map; unloaded and partial-shape rays have their own contract spec.
            every { anyConstructed<WorldMock>().isChunkLoaded(any<Int>(), any<Int>()) } returns true
            every { anyConstructed<WorldMock>().strikeLightningEffect(any()) } answers {
                mockk<org.bukkit.entity.LightningStrike>(relaxed = true) {
                    every { isEffect } returns true
                }
            }
            every {
                anyConstructed<WorldMock>().rayTraceBlocks(
                    any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>(),
                )
            } answers { staffTestBlockRay(firstArg(), secondArg(), thirdArg(), arg(4), rayStep) { true } }
            every {
                anyConstructed<WorldMock>().rayTraceBlocks(
                    any<io.papermc.paper.math.Position>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(),
                    any<Boolean>(), any<java.util.function.Predicate<org.bukkit.block.Block>>(),
                )
            } answers {
                staffTestBlockRay(firstArg<io.papermc.paper.math.Position>() as Location, secondArg(), thirdArg(), arg(4), rayStep) {
                    arg<java.util.function.Predicate<org.bukkit.block.Block>>(5).test(it)
                }
            }
            MockBukkitTestRuntime.open().use { paper ->
                // 4.116.3 implements AttributeInstanceMock fully, but its default-value table omits SCALE.
                // Supply only that native default; registerAttribute and modifier bookkeeping stay real.
                mockkStatic(AttributesMock::class)
                every { AttributesMock.getDefaultValue(org.bukkit.attribute.Attribute.SCALE) } returns 1.0
                try {
                    Tasks.withScheduler(scheduler) {
                        val cooldowns = StaffTestCooldowns(scheduler)
                        val damage = mockk<StaffSpellDamage>()
                        every { damage.summonCast = any() } returns Unit
                        every { damage.reportDeniedTarget(any(), any(), any()) } answers {
                            damageBridge?.reportDeniedTarget(firstArg(), secondArg(), thirdArg()) ?:
                                deniedTarget(firstArg(), secondArg(), thirdArg())
                        }
                        every { damage.inputAllowed(any()) } returns true
                        val captures = mutableListOf<Player>()
                        val hits = mutableListOf<LivingEntity>()
                        val hitLocations = mutableListOf<Location>()
                        val damageScales = mutableListOf<Pair<Double, Double>>()
                        every { damage.eligible(any(), any(), any()) } answers {
                            val target = secondArg<LivingEntity>()
                            (damageBridge?.eligible(firstArg(), target, thirdArg()) ?: targetEligible(firstArg(), target)) &&
                                target.isValid && !target.isDead &&
                                !target.isInvulnerable && target.health > 0.0
                        }
                        every { damage.capture(any(), any()) } answers {
                            val player = firstArg<Player>()
                            captures += player
                            if (damageBridge != null) damageBridge.capture(player, secondArg())
                            else StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
                        }
                        every { damage.isCurrent(any(), any()) } answers {
                            currentCast(firstArg(), secondArg()) &&
                                (damageBridge?.isCurrent(firstArg(), secondArg()) ?: true)
                        }
                        fun recordHit(player: Player, target: LivingEntity, cast: StaffSpellCast,
                            power: Double, vanillaDamage: Double): Boolean {
                            hits += target
                            hitLocations += target.location.clone()
                            damageScales += power to vanillaDamage
                            return damageBridge?.hit(player, target, cast, power, vanillaDamage) ?: hit(player, target)
                        }
                        every { damage.hit(any(), any(), any(), any(), any()) } answers {
                            recordHit(firstArg(), secondArg(), thirdArg(), arg(3), arg(4))
                        }
                        // Summoned actors explicitly omit a primary weapon strike; reject any non-null tag.
                        every { damage.hit(any(), any(), any(), any(), any(), null) } answers {
                            recordHit(firstArg(), secondArg(), thirdArg(), arg(3), arg(4))
                        }
                        val effects = mockk<StaffSpellDisplayEffects>(relaxed = true)
                        val visualPlays = mutableListOf<StaffVisualPlay>()
                        every { effects.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                            val id = UUID.randomUUID()
                            if (!arg<Boolean>(13)) visualPlays += StaffVisualPlay(id, arg<StaffSpell>(1), arg<Location>(2).clone(),
                                arg<Location>(3).clone(), arg<Boolean>(7), arg<Boolean>(6), arg<Boolean>(9), arg<Int?>(12))
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
                        var hotkeyTick = 0L
                        val hotkeyClock = scheduler.runTimer(1, 1) { hotkeyTick++ }
                        val particles = mockk<PaperViewerParticles>(relaxed = true)
                        val controller = StaffSpellController(config, settings, damage, effects, cooldowns,
                            particles, summonActorFactory, hud = hudFactory?.invoke(), hotkeys = StaffHotkeyCooldowns { hotkeyTick })
                        val harness = StaffHarness(paper, scheduler, config, controller, cooldowns, effects,
                            movedEffects, trailMoves, projectileMoves, finishedTrails, visualPlays, removedEffects,
                            captures, hits, hitLocations, damageScales)
                        try {
                            block(harness)
                        } finally {
                            controller.close()
                            hotkeyClock.cancel()
                            cooldowns.close()
                            // MockK retains call stacks and captured fixture graphs until these owned mocks are cleared.
                            clearMocks(damage, effects, particles)
                        }
                    }
                } finally {
                    unmockkStatic(AttributesMock::class)
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
