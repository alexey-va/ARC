package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.atan2
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.staffspells.api.StaffHudMode
import ru.arc.staffspells.api.StaffHudPlacement
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.util.UUID

class StaffSpellHudTest : FreeSpec({
    "quest guidance shares the staff card, requires permission and preserves source and weapon priority" {
        withHudHarness(ownDisplays = false) { h ->
            val player = h.player("quest-hud", StaffSpell.EMBER)
            player.inventory.setItemInMainHand(ItemStack(Material.STONE))
            val permission = player.addAttachment(h.paper.createSimplePlugin("QuestHudTest"),
                QuestGuidanceHudService.PERMISSION, false)
            val optional = QuestGuidanceHudService.Source.OPTIONAL
            val home = QuestGuidanceHudService.Source.FIRST_HOME
            val hint = Component.text("Сыграйте в слоты\nБесплатная проба готова")
            h.hud.showGuidance(player, optional, hint) shouldBe false
            h.recorder.records.size shouldBe 0
            permission.setPermission(QuestGuidanceHudService.PERMISSION, true)
            h.hud.showGuidance(player, optional, hint) shouldBe true
            val card = h.recorder.records.single()
            card.attachedPlayerId shouldBe player.uniqueId
            card.visibleViewers shouldBe setOf(player.uniqueId)
            card.visibleByDefault shouldBe false
            h.hud.clear(player.uniqueId) // The staff loop clears its state after unequip.
            h.scheduler.tick(2)
            card.removals shouldBe 0
            h.hud.showGuidance(player, home, Component.text("Сохраните точку дома")) shouldBe true
            h.hud.showGuidance(player, optional, hint) shouldBe false
            h.hud.clearGuidance(player.uniqueId, optional)
            card.removals shouldBe 0
            text(card.text) shouldBe "Сохраните точку дома"
            player.inventory.setItemInMainHand(staffItem(StaffSpell.EMBER))
            h.hud.ready(player, StaffSpell.EMBER, false)
            h.recorder.records.size shouldBe 1
            text(card.text) shouldBe "ПКМ · Комета"
            h.hud.showGuidance(player, home, hint) shouldBe false
            card.removals shouldBe 0
            player.inventory.setItemInMainHand(ItemStack(Material.STONE))
            h.hud.clear(player.uniqueId)
            h.hud.showGuidance(player, optional, hint) shouldBe true
            val resumed = h.recorder.records.last()
            permission.setPermission(QuestGuidanceHudService.PERMISSION, false)
            h.scheduler.tick(1)
            resumed.removals shouldBe 1
            h.recorder.closes shouldBe 0
        }
    }

    "quest card follows native movement and respawns in the destination world before cleanup" {
        withHudHarness { h ->
            val player = h.player("quest-hud-travel", StaffSpell.EMBER)
            player.inventory.setItemInMainHand(ItemStack(Material.STONE))
            player.addAttachment(h.paper.createSimplePlugin("QuestTravelTest"), QuestGuidanceHudService.PERMISSION, true)
            val source = QuestGuidanceHudService.Source.FIRST_HOME
            val hint = Component.text("/sethome — сохранить точку дома")
            h.hud.showGuidance(player, source, hint) shouldBe true
            val first = h.recorder.records.single()
            player.teleport(Location(h.world, 4.5, 64.0, 4.5, 90f, 45f))
            h.scheduler.tick(1)
            first.teleports.size shouldBe 0
            first.removals shouldBe 0
            val other = h.paper.addSimpleWorld("quest-hud-other")
            player.teleport(Location(other, 0.5, 64.0, 0.5))
            h.hud.showGuidance(player, source, hint) shouldBe true
            first.removals shouldBe 1
            val next = h.recorder.records.last()
            next.spawn.world shouldBe other
            next.attachedPlayerId shouldBe player.uniqueId
            player.gameMode = org.bukkit.GameMode.SPECTATOR
            h.scheduler.tick(1)
            next.removals shouldBe 1
        }
    }

    "unchanged HUD follows without reparsing text and changed state refreshes both surfaces" {
        withHudHarness { h ->
            val player = h.player("staff-hud-unchanged", StaffSpell.EMBER)
            h.hud.ready(player, StaffSpell.EMBER, secondary = false)
            h.scheduler.tick(20)
            verify(exactly = 1) { h.config.staffHudReady(StaffSpell.EMBER, false) }

            player.isSneaking = true
            h.scheduler.tick(1)
            verify(exactly = 1) { h.config.staffHudReady(StaffSpell.EMBER, true) }

            h.preferences.value = StaffSpellHudPreferences(StaffHudMode.BOSSBAR, StaffHudPlacement.BOTTOM)
            h.scheduler.tick(20)
            verify(exactly = 1) { h.config.staffHudBossbar(any()) }

            h.hud.hotkeyState = { _, _ -> StaffHotkeyHudState(80, 20) }
            h.scheduler.tick(20)
            verify(exactly = 2) { h.config.staffHudBossbar(any()) }
            text(player.activeBossBars().single().name()).endsWith("Q 4.0с   F 1.0с") shouldBe true
        }
    }

    "HUD remains in front of a wall even when the player stands against it" {
        staffHudSafeDistance(staffHudBaseDistance(), 0.30) shouldBe 0.12
    }

    "attack mode helper matches native hold-release for charged primary spells" {
        staffAttackInputMode(StaffSpell.EMBER, secondary = false) shouldBe StaffAttackInputMode.HOLD_RELEASE
        staffAttackInputMode(StaffSpell.ICICLE, secondary = false) shouldBe StaffAttackInputMode.HOLD_RELEASE
        staffAttackInputMode(StaffSpell.EMBER, secondary = true) shouldBe StaffAttackInputMode.INSTANT
        staffAttackInputMode(StaffSpell.ICICLE, secondary = true) shouldBe StaffAttackInputMode.INSTANT
        staffAttackInputMode(StaffSpell.GRAVITY, secondary = false) shouldBe StaffAttackInputMode.HOLD_RELEASE
        staffAttackInputMode(StaffSpell.GRAVITY, secondary = true) shouldBe StaffAttackInputMode.INSTANT
        staffAttackInputMode(StaffSpell.LANCE, secondary = false) shouldBe StaffAttackInputMode.INSTANT
        staffSpellHudPreferencesFromStored("bossbar", "top") shouldBe
            StaffSpellHudPreferences(StaffHudMode.BOSSBAR, StaffHudPlacement.TOP)
        staffSpellHudPreferencesFromStored("unknown", "unknown") shouldBe StaffSpellHudPreferences()
    }

    "private hologram reuses one card, sends only state text deltas, follows camera, and clears after unequip" {
        withHudHarness { harness ->
            val player = harness.player("staff-hud", StaffSpell.EMBER)
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)
            harness.recorder.records.size shouldBe 1
            val record = harness.recorder.records.single()
            record.visibleViewers shouldBe setOf(player.uniqueId)
            record.visibleByDefault shouldBe false
            text(record.text) shouldBe "ПКМ · Комета"

            player.isSneaking = true
            harness.scheduler.tick(2)
            text(record.text) shouldBe "Shift + ПКМ · Метеоры"
            player.isSneaking = false
            harness.scheduler.tick(2)
            text(record.text) shouldBe "ПКМ · Комета"

            harness.hud.charging(player, StaffSpell.EMBER, secondary = false, progress = 0.55)
            harness.hud.charging(player, StaffSpell.EMBER, secondary = false, progress = 0.55)
            harness.recorder.records.size shouldBe 1
            record.textWrites.size shouldBe 3
            text(record.text) shouldBe "ПКМ · Комета\n55% ■■■■■□□□□□"

            harness.scheduler.tick(2)
            record.attachedPlayerId shouldBe player.uniqueId
            record.teleports.size shouldBe 0
            val initialTranslation = Vector3f(record.translation)
            val initialWrites = record.transformWrites
            harness.scheduler.tick(2)
            record.transformWrites shouldBe initialWrites
            player.teleport(Location(harness.world, 1.5, 64.0, 0.5))
            harness.scheduler.tick(2)
            record.transformWrites shouldBe initialWrites
            // Native passenger movement carries the card without a second server-driven pose.
            record.teleports.size shouldBe 0
            harness.scheduler.tick(12)
            (record.translation.distance(initialTranslation) < 1.0e-6) shouldBe true
            record.textWrites.size shouldBe 3

            player.inventory.setItemInMainHand(ItemStack(Material.STONE))
            harness.scheduler.tick(2)
            record.removals shouldBe 1
            harness.recorder.records.size shouldBe 1
        }
    }

    "Q continuation and independent F cooldown update the existing chosen HUD without creating extra cards" {
        withHudHarness { harness ->
            val player = harness.player("staff-hotkeys", StaffSpell.EMBER)
            var state = StaffHotkeyHudState(80, 20, "Поджечь")
            harness.hud.hotkeyState = { _, _ -> state }
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)
            val record = harness.recorder.records.single()
            text(record.text).endsWith("Q Поджечь   F 1.0с") shouldBe true
            state = StaffHotkeyHudState(76, 0)
            harness.scheduler.tick(2)
            harness.recorder.records.size shouldBe 1
            text(record.text).endsWith("Q 3.8с   F готово") shouldBe true
        }
    }

    "necromancer army count follows live changes on both HUD surfaces and disappears on another staff" {
        withHudHarness { h ->
            val player = h.player("staff-army-hud", StaffSpell.NECROMANCER)
            h.hud.ready(player, StaffSpell.NECROMANCER, secondary = false)
            val card = h.recorder.records.single()
            text(card.text).endsWith("Войско 0/10") shouldBe true

            var count = 4
            h.hud.armyCount = { count }
            h.scheduler.tick(2)
            text(card.text).endsWith("Войско 4/10") shouldBe true
            count = 3
            h.scheduler.tick(2)
            text(card.text).endsWith("Войско 3/10") shouldBe true
            h.recorder.records.size shouldBe 1

            h.preferences.value = StaffSpellHudPreferences(StaffHudMode.BOSSBAR, StaffHudPlacement.BOTTOM)
            h.scheduler.tick(2)
            val bar = player.activeBossBars().single()
            text(bar.name()).endsWith("Войско 3/10") shouldBe true
            text(bar.name()).contains('\n') shouldBe false
            count = 2
            h.scheduler.tick(2)
            text(bar.name()).endsWith("Войско 2/10") shouldBe true

            player.inventory.setItemInMainHand(staffItem(StaffSpell.EMBER))
            h.scheduler.tick(2)
            text(bar.name()).contains("Войско") shouldBe false
        }
    }

    "nearby solid blocks move the HUD forward and keep its apparent scale" {
        withHudHarness { harness ->
            val player = harness.player("staff-hud-wall", StaffSpell.EMBER)
            harness.rayHitDistance[0] = 1.6
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)

            val record = harness.recorder.records.single()
            val bounds = staffSpellHudBounds(text(record.text), 220)
            val center = hudRenderedCenter(player.eyeLocation, record.translation, record.scale, bounds,
                player.height - player.eyeHeight)
            val distance = center.distance(player.eyeLocation.toVector())
            // The outer corners may meet a wall before the origin; the entire card must fit.
            val moving = staffHudPosition(StaffSpellHudPositionSettings(),
                StaffHudPlacement.BOTTOM, bounds, player.height - player.eyeHeight)
            (distance <= staffHudSafeDistance(staffSpellHudPose(moving).distance, 1.6) + 1.0e-6) shouldBe true
            (kotlin.math.abs(record.scale - staffSpellHudPose(distance, moving).textScale) < 1.0e-6) shouldBe true
            record.visibleByDefault shouldBe false

            harness.rayHitDistance[0] = 0.7
            harness.scheduler.tick(1)
            record.transformTicks shouldBe 0
            val pulledIn = hudRenderedCenter(player.eyeLocation, record.translation, record.scale, bounds,
                player.height - player.eyeHeight).subtract(player.eyeLocation.toVector())
            (pulledIn.length() < distance) shouldBe true
            // The mock obstructs every sampled ray at 0.7 blocks, so all corners retain 0.18 clearance.
            staffHudCardOffsets(pulledIn, Vector(-1.0, 0.0, 0.0), Vector(0.0, -1.0, 0.0),
                bounds, record.scale).all { it.length() <= 0.7 - STAFF_HUD_BLOCK_CLEARANCE + 1.0e-6 }
                .shouldBe(true)
        }
    }

    "ready and cooldown cards follow staff swaps without respawning" {
        withHudHarness { harness ->
            val player = harness.player("staff-hud-swap", StaffSpell.EMBER)
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)
            val record = harness.recorder.records.single()

            player.inventory.setItemInMainHand(staffItem(StaffSpell.GRAVITY))
            harness.scheduler.tick(2)
            harness.recorder.records.size shouldBe 1
            record.removals shouldBe 0
            text(record.text) shouldBe "ПКМ · Тяготение"

            harness.hud.cooldown(player, StaffSpell.GRAVITY, secondary = false, remainingTicks = 18, totalTicks = 44)
            player.inventory.setItemInMainHand(staffItem(StaffSpell.EMBER))
            harness.scheduler.tick(2)
            harness.recorder.records.size shouldBe 1
            record.removals shouldBe 0
            text(record.text) shouldBe "ПКМ · Комета\n0.9 с ■■■■■□□□□□"
        }
    }

    "ready ability and held gravity use a compact selected key and state" {
        withHudHarness { harness ->
            val player = harness.player("gravity-hud", StaffSpell.GRAVITY)
            player.isSneaking = true
            harness.hud.ready(player, StaffSpell.GRAVITY, secondary = true)
            text(harness.recorder.records.single().text) shouldBe
                "Shift + ПКМ · Тяготение"

            player.isSneaking = false
            harness.scheduler.tick(2)
            harness.hud.channel(player, StaffSpell.GRAVITY, secondary = false)
            text(harness.recorder.records.single().text) shouldBe
                "ПКМ · Тяготение\nГлыба готова"

            harness.hud.cooldown(player, StaffSpell.GRAVITY, secondary = false, remainingTicks = 9, totalTicks = 44)
            text(harness.recorder.records.single().text) shouldBe
                "ПКМ · Тяготение\n0.5 с ■■■■■■■□□□"
            player.isSneaking = true
            harness.scheduler.tick(2)
            text(harness.recorder.records.single().text) shouldBe
                "Shift + ПКМ · Тяготение\n0.5 с ■■■■■■■□□□"
        }
    }

    "stored mode switches remove the old surface and top placement flips the camera-plane offset" {
        withHudHarness { harness ->
            val player = harness.player("staff-hud-mode", StaffSpell.EMBER)
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)
            val bottom = harness.recorder.records.single()
            val bottomY = bottom.translation.y

            harness.preferences.value = StaffSpellHudPreferences(StaffHudMode.BOSSBAR, StaffHudPlacement.TOP)
            harness.scheduler.tick(2)
            bottom.removals shouldBe 1
            player.activeBossBars().count() shouldBe 1
            val bar = player.activeBossBars().single()
            text(bar.name()) shouldBe "ПКМ · Комета"
            text(bar.name()).contains('\n') shouldBe false

            harness.hud.charging(player, StaffSpell.EMBER, secondary = false, progress = 0.4)
            text(bar.name()) shouldBe "ПКМ · Комета 40% ■■■■□□□□□□"
            bar.progress() shouldBe 0.4f

            harness.preferences.value = StaffSpellHudPreferences(StaffHudMode.OFF, StaffHudPlacement.TOP)
            harness.scheduler.tick(2)
            player.activeBossBars().count() shouldBe 0
            harness.recorder.records.size shouldBe 1

            harness.preferences.value = StaffSpellHudPreferences(StaffHudMode.HOLOGRAM, StaffHudPlacement.TOP)
            harness.scheduler.tick(2)
            harness.recorder.records.size shouldBe 2
            (harness.recorder.records.last().translation.y > bottomY + 1.7) shouldBe true
            harness.recorder.records.last().attachedPlayerId shouldBe player.uniqueId
        }
    }

    "hologram is removed instead of teleported across worlds" {
        withHudHarness { harness ->
            val player = harness.player("staff-hud-world", StaffSpell.EMBER)
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)
            val record = harness.recorder.records.single()

            val otherWorld = harness.paper.addSimpleWorld("staff-hud-other-world")
            player.teleport(Location(otherWorld, 0.5, 64.0, 0.5))
            harness.scheduler.tick(2)

            record.removals shouldBe 1
            record.teleports.size shouldBe 0
        }
    }

    "mounted camera keeps the full card clear through delayed ascent reversal yaw pitch and pose transitions" {
        val config = StaffSpellConfig(StaffTestConfig(emptyMap()))
        val bounds = staffSpellHudBounds("Shift + ПКМ · Метеоры\n55% ■■■■■□□□□□\nQ готово   F готово", 220)
        for (preference in listOf(StaffHudPlacement.TOP, StaffHudPlacement.BOTTOM)) {
            val frames = staffHudMotionPreviewFrames(config, bounds, preference)
            val side = if (preference == StaffHudPlacement.BOTTOM) 1 else -1
            // Sample the whole route, including the old failures 40.5, 75, 91 and their next frames.
            for (sample in 0 until frames.lastIndex * 4) {
                val time = sample / 4.0
                val i = time.toInt()
                val partial = (time - i).toFloat()
                val eye = frames[i].eye.clone().apply {
                    x += (frames[i + 1].eye.x - x) * partial
                    y += (frames[i + 1].eye.y - y) * partial
                    z += (frames[i + 1].eye.z - z) * partial
                    yaw += (frames[i + 1].eye.yaw - yaw) * partial
                    pitch += (frames[i + 1].eye.pitch - pitch) * partial
                }
                val gap = frames[i].passengerGap +
                    (frames[i + 1].passengerGap - frames[i].passengerGap) * partial
                // One tick until the owner's metadata flush, then one tick of native interpolation.
                // Player passenger position and billboard orientation use the CURRENT client frame.
                val from = frames[(i - 2).coerceAtLeast(0)]
                val to = frames[(i - 1).coerceAtLeast(0)]
                val translation = Vector3f(from.transform.translation).lerp(to.transform.translation, partial)
                val scale = from.pose.textScale + (to.pose.textScale - from.pose.textScale) * partial
                for (view in listOf("first", "back", "front")) {
                    val camera = eye.clone()
                    if (view != "first") camera.add(eye.direction.multiply(if (view == "back") -4.0 else 4.0))
                    if (view == "front") { camera.yaw += 180f; camera.pitch = -camera.pitch }
                    val center = hudRenderedCenter(eye, translation, scale, bounds, gap, camera)
                    val relative = center.subtract(camera.toVector())
                    val forward = camera.direction
                    val right = Vector(-kotlin.math.cos(Math.toRadians(camera.yaw.toDouble())), 0.0,
                        -kotlin.math.sin(Math.toRadians(camera.yaw.toDouble())))
                    val down = forward.clone().crossProduct(right).normalize()
                    val depth = relative.dot(forward)
                    val near = atan2(side * relative.dot(down) - bounds.verticalRadius * scale, depth)
                    val far = atan2(side * relative.dot(down) + bounds.verticalRadius * scale, depth)
                    check(depth > 0 && near > Math.toRadians(if (view == "first") 7.9 else 0.0) &&
                        far < Math.toRadians(35.0)) {
                        "$preference $view t=$time depth=$depth near=${Math.toDegrees(near)} far=${Math.toDegrees(far)}"
                    }
                    (abs(relative.dot(right)) + bounds.halfWidth * scale < depth * 1.12) shouldBe true
                }
            }
        }
    }

    "billboard local translation follows a fresh camera turn without a new server pose" {
        val bounds = staffSpellHudBounds("ПКМ · Комета\n55% ■■■■■□□□□□\nQ готово   F готово", 220)
        val eye = Location(null, 0.0, 64.0, 0.0)
        for (preference in listOf(StaffHudPlacement.TOP, StaffHudPlacement.BOTTOM)) {
            val position = staffHudPosition(StaffSpellHudPositionSettings(), preference, bounds)
            val transform = staffHudBillboardTransformation(staffSpellHudPose(position), bounds, eye, 0.18)
            for (yaw in listOf(0f, 90f, 180f)) for (pitch in listOf(-85f, 0f, 85f)) {
                val camera = eye.clone().apply { this.yaw = yaw; this.pitch = pitch }
                val local = hudRenderedCenter(eye, transform.translation, transform.scale.x, bounds, 0.18, camera)
                    .subtract(camera.toVector())
                val down = camera.direction.clone().crossProduct(Vector(-kotlin.math.cos(Math.toRadians(yaw.toDouble())),
                    0.0, -kotlin.math.sin(Math.toRadians(yaw.toDouble())))).normalize()
                val side = if (preference == StaffHudPlacement.BOTTOM) 1 else -1
                (side * local.dot(down) - bounds.verticalRadius * transform.scale.x > local.dot(camera.direction) * 0.13)
                    .shouldBe(true)
            }
        }
    }

    "continuous and batched movement and yaw never resize or reposition the mounted card" {
        withHudHarness { h ->
            val player = h.player("staff-hud-steady", StaffSpell.EMBER)
            h.hud.cooldown(player, StaffSpell.EMBER, secondary = false, remainingTicks = 40, totalTicks = 40)
            val card = h.recorder.records.single()
            val translation = Vector3f(card.translation)
            val scale = card.scale
            val writes = card.transformWrites
            val movement = List(8) { Vector(0.0, 0.0, 0.28) } + listOf(
                Vector(), Vector(), Vector(0.0, 0.0, 0.84), // Three received movement ticks in one server tick.
                Vector(0.0, 0.42, 0.28), Vector(0.0, -0.4, 0.28),
                Vector(1.5, 1.2, 0.0), Vector(-1.5, -1.2, 0.0), Vector(),
            )
            movement.forEachIndexed { index, delta ->
                player.teleport(player.location.add(delta).apply { yaw += 13f })
                h.hud.cooldown(player, StaffSpell.EMBER, secondary = false,
                    remainingTicks = 39L - index, totalTicks = 40)
                h.scheduler.tick(1)
                card.translation shouldBe translation
                card.scale shouldBe scale
                card.transformWrites shouldBe writes
                card.teleports.size shouldBe 0
            }
            h.recorder.records.size shouldBe 1
            card.textWrites.isNotEmpty() shouldBe true
            card.attachedPlayerId shouldBe player.uniqueId
        }
    }

    "slow pitch corrections accumulate against the last applied pose" {
        withHudHarness { h ->
            val player = h.player("staff-hud-slow-pitch", StaffSpell.EMBER)
            h.hud.ready(player, StaffSpell.EMBER, secondary = false)
            val card = h.recorder.records.single()
            val writes = card.transformWrites
            val bounds = staffSpellHudBounds(text(card.text), staffSpellHudVisualSpec().lineWidth)
            val gap = player.height - player.eyeHeight
            val pose = staffSpellHudPose(staffHudPosition(StaffSpellHudPositionSettings(),
                StaffHudPlacement.BOTTOM, bounds, gap))
            repeat(40) { tick ->
                player.teleport(player.location.apply { pitch = (tick + 1) * 0.1f })
                h.scheduler.tick(1)
                if (tick == 0) card.transformWrites shouldBe writes
                val expected = staffHudBillboardTransformation(pose, bounds, player.eyeLocation, gap)
                (card.translation.distance(expected.translation) < 0.00101f) shouldBe true
            }
            (card.transformWrites > writes) shouldBe true
            card.teleports.size shouldBe 0
        }
    }

    "camera turns reuse the mounted card and top-bottom changes snap only the local pose" {
        withHudHarness { harness ->
            val player = harness.player("staff-hud-turn", StaffSpell.EMBER)
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)
            val record = harness.recorder.records.single()
            player.teleport(player.location.apply { pitch = 55f })
            harness.scheduler.tick(1)
            record.teleportTicks shouldBe 0
            record.transformTicks shouldBe 1
            record.teleports.size shouldBe 0
            harness.preferences.value = StaffSpellHudPreferences(StaffHudMode.HOLOGRAM, StaffHudPlacement.TOP)
            harness.scheduler.tick(1)
            record.transformTicks shouldBe 0
            harness.recorder.records.size shouldBe 1
        }
    }

    "closing HUD with a shared passenger owner removes only its card" {
        withHudHarness(ownDisplays = false) { harness ->
            val player = harness.player("staff-hud-shared", StaffSpell.EMBER)
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)
            harness.hud.close()
            harness.recorder.records.single().removals shouldBe 1
            harness.recorder.closes shouldBe 0
        }
    }

})

private fun text(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

private fun staffItem(spell: StaffSpell) = ItemStack(Material.STICK).apply {
    editMeta { meta -> meta.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, spell.id) }
}

private data class HudDisplayRecord(
    val handle: PacketTextDisplay,
    val spawn: Location,
    var text: Component,
    val textWrites: MutableList<Component> = mutableListOf(),
    val teleports: MutableList<Location> = mutableListOf(),
    val visibleViewers: MutableSet<UUID> = mutableSetOf(),
    var visibleByDefault: Boolean = true,
    var scale: Float = 0.0f,
    var translation: Vector3f = Vector3f(),
    var transformWrites: Int = 0,
    var attachedPlayerId: UUID? = null,
    var teleportTicks: Int = 0,
    var transformTicks: Int = 0,
    var removals: Int = 0,
)

private class HudDisplayRecorder {
    val records = mutableListOf<HudDisplayRecord>()
    var closes = 0

    val displays = mockk<PaperPacketDisplays>(relaxed = true).also { owner ->
        every { owner.spawnText(any(), any()) } answers {
            val at = firstArg<Location>().clone()
            val initialText = secondArg<Component>()
            val handle = mockk<PacketTextDisplay>(relaxed = true)
            val record = HudDisplayRecord(handle, at, initialText)
            every { handle.isValid } returns true
            every { handle.text() } answers { record.text }
            every { handle.isVisibleByDefault } answers { record.visibleByDefault }
            every { handle.isVisibleByDefault = any() } answers {
                record.visibleByDefault = firstArg<Boolean>()
                Unit
            }
            every { handle.transformation = any() } answers {
                record.scale = firstArg<org.bukkit.util.Transformation>().scale.x
                record.translation = Vector3f(firstArg<org.bukkit.util.Transformation>().translation)
                record.transformWrites++
                Unit
            }
            every { handle.attachTo(any<Player>()) } answers { record.attachedPlayerId = firstArg<Player>().uniqueId }
            every { handle.teleportDuration = any() } answers { record.teleportTicks = firstArg<Int>() }
            every { handle.interpolationDuration = any() } answers { record.transformTicks = firstArg<Int>() }
            every { handle.text(any()) } answers {
                record.text = firstArg<Component>()
                record.textWrites += record.text
                Unit
            }
            every { handle.showTo(any<Player>()) } answers {
                record.visibleViewers += firstArg<Player>().uniqueId
                Unit
            }
            every { handle.teleport(any()) } answers { record.teleports += firstArg<Location>().clone() }
            every { handle.remove() } answers { record.removals++; Unit }
            records += record
            handle
        }
        every { owner.close() } answers { closes++; Unit }
    }
}

private data class HudHarness(
    val scheduler: TestTaskScheduler,
    val world: org.bukkit.World,
    val recorder: HudDisplayRecorder,
    val hud: StaffSpellHud,
    val paper: MockBukkitTestRuntime,
    val rayHitDistance: MutableList<Double?>,
    val preferences: HudPreferenceBox,
    val config: StaffSpellConfig,
) {
    fun player(name: String, spell: StaffSpell): Player = paper.addPlayer(name).apply {
        teleport(Location(world, 0.5, 64.0, 0.5))
        val item = ItemStack(Material.STICK)
        item.editMeta { meta -> meta.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, spell.id) }
        inventory.setItemInMainHand(item)
    }
}

private data class HudPreferenceBox(var value: StaffSpellHudPreferences = StaffSpellHudPreferences())

private fun withHudHarness(ownDisplays: Boolean = true, block: (HudHarness) -> Unit) = failOnUnsupportedMockBukkitOperation {
    val scheduler = TestTaskScheduler()
    val rayHitDistance = mutableListOf<Double?>(null)
    mockkConstructor(WorldMock::class)
    try {
        every { anyConstructed<WorldMock>().isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every {
            anyConstructed<WorldMock>().rayTraceBlocks(
                any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>(),
            )
        } answers {
            val distance = rayHitDistance.firstOrNull()
            if (distance == null || distance > thirdArg<Double>()) null else {
                val start = firstArg<Location>()
                val direction = secondArg<Vector>().clone().normalize()
                RayTraceResult(start.toVector().add(direction.multiply(distance)))
            }
        }
        MockBukkitTestRuntime.open().use { paper ->
            Tasks.withScheduler(scheduler) {
                val world = paper.addSimpleWorld("staff-spell-hud")
                val recorder = HudDisplayRecorder()
                val preferences = HudPreferenceBox()
                val config = spyk(StaffSpellConfig(StaffTestConfig(mapOf(
                    "ember.name" to "Комета",
                    "ember.secondary-name" to "Метеоры",
                    "gravity.name" to "Тяготение",
                    "gravity.secondary-name" to "Тяготение",
                    "messages.staff-hud.ready-instant" to "<input> · <ability>",
                    "messages.staff-hud.ready-charge" to "<input> · <ability>",
                    "messages.staff-hud.charging" to "<input> · <ability><newline><percent>% <bar>",
                    "messages.staff-hud.cooldown" to "<input> · <ability><newline><seconds> с <bar>",
                    "messages.staff-hud.channel" to "<input> · <ability><newline>Глыба готова",
                    "messages.staff-hud.army" to "Войско <count>/<limit>",
                    "messages.staff-hud.bossbar-ready-instant" to "<input> · <ability>",
                    "messages.staff-hud.bossbar-ready-charge" to "<input> · <ability>",
                    "messages.staff-hud.bossbar-charging" to "<input> · <ability> <percent>% <bar>",
                    "messages.staff-hud.bossbar-cooldown" to "<input> · <ability> <seconds> с",
                    "messages.staff-hud.bossbar-channel" to "<input> · <ability> · Глыба готова",
                ))))
                val hud = StaffSpellHud(recorder.displays, config,
                    preferences = StaffSpellHudPreferencesReader { preferences.value }, ownDisplays = ownDisplays).also(StaffSpellHud::start)
                try {
                    block(HudHarness(scheduler, world, recorder, hud, paper, rayHitDistance, preferences, config))
                } finally {
                    hud.close()
                    recorder.closes shouldBe if (ownDisplays) 1 else 0
                }
            }
        }
    } finally {
        unmockkConstructor(WorldMock::class)
    }
}

/** Native DisplayRenderer applies camera billboard rotation BEFORE the transformation matrix. */
private fun hudRenderedCenter(
    playerEye: Location,
    translation: Vector3f,
    scale: Float,
    bounds: StaffSpellHudBounds,
    passengerGap: Double,
    camera: Location = playerEye,
): Vector {
    val local = Vector3f(translation).add(0f, (bounds.originUp * scale).toFloat(), 0f)
    Quaternionf().rotationYXZ(Math.toRadians(180.0 - camera.yaw).toFloat(),
        Math.toRadians(-camera.pitch.toDouble()).toFloat(), 0f).transform(local)
    return playerEye.toVector().add(Vector(0.0, passengerGap, 0.0))
        .add(Vector(local.x.toDouble(), local.y.toDouble(), local.z.toDouble()))
}
