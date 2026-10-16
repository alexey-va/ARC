package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
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
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.staffspells.api.StaffHudMode
import ru.arc.staffspells.api.StaffHudPlacement
import java.util.UUID

class StaffSpellHudTest : FreeSpec({
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
            // Sneaking changes the eye height, so both camera-follow updates above are real.
            record.teleports.size shouldBe 2
            val afterSneak = record.teleports.size
            harness.scheduler.tick(2)
            record.teleports.size shouldBe afterSneak
            val initial = record.spawn.clone()
            player.teleport(Location(harness.world, 1.5, 64.0, 0.5))
            harness.scheduler.tick(2)
            (record.teleports.size > afterSneak) shouldBe true
            harness.scheduler.tick(12)
            record.teleports.last().distance(initial) shouldBe 1.0
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
            val distance = record.spawn.distance(player.eyeLocation)
            // The outer corners may meet a wall before the origin; the entire card must fit.
            (distance <= staffHudSafeDistance(staffSpellHudPose().distance, 1.6)) shouldBe true
            (kotlin.math.abs(record.scale - staffSpellHudPose(distance).textScale) < 1.0e-6) shouldBe true
            record.visibleByDefault shouldBe false
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
            val bottomY = bottom.spawn.y

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
            (kotlin.math.abs(harness.recorder.records.last().spawn.y - bottomY - 1.7) < 1.0e-8) shouldBe true
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

    "flight leads forward upward downward and strafing cameras with full card clearance on both chosen sides" {
        val settings = StaffSpellHudMotionSettings()
        val position = StaffSpellHudPositionSettings()
        val bounds = staffSpellHudBounds("Shift + ПКМ · Метеоры\n55% ■■■■■□□□□□\nQ готово   F готово", 220)
        for (preference in listOf(StaffHudPlacement.TOP, StaffHudPlacement.BOTTOM)) {
            for (pitch in listOf(-80f, 0f, 80f)) {
                val eye = Location(null, 0.0, 64.0, 0.0, 35f, pitch)
                val forward = eye.direction.normalize()
                val yaw = Math.toRadians(eye.yaw.toDouble())
                val right = Vector(kotlin.math.cos(yaw), 0.0, kotlin.math.sin(yaw))
                val down = right.clone().crossProduct(forward).normalize()
                for (velocity in listOf(forward.clone().multiply(1.5), Vector(0.0, 1.5, 0.0),
                        Vector(0.0, -1.5, 0.0), right.clone().multiply(1.5))) {
                    var motion = StaffSpellHudMotion()
                    repeat(3) { motion = staffSpellHudMotion(motion, velocity, 1.0, true, settings) }
                    val moving = staffHudMovingPosition(position, settings, motion,
                        forward, down, right, preference, bounds)
                    val offset = forward.clone().multiply(moving.forwardBlocks)
                        .add(down.clone().multiply(moving.verticalBlocks))
                        .add(right.clone().multiply(moving.horizontalBlocks))
                    val side = if (preference == StaffHudPlacement.BOTTOM) 1 else -1
                    for (latency in listOf(0.0, 1.0, 2.0)) {
                        val relative = offset.clone().subtract(velocity.clone().multiply(latency))
                        (relative.dot(forward) > 0.0) shouldBe true
                        (side * relative.dot(down) - bounds.verticalRadius * position.scale >
                            relative.dot(forward) * 0.13) shouldBe true
                    }
                    (moving.forwardBlocks >= position.forwardBlocks + settings.forwardBlocks) shouldBe true
                }
            }
        }
    }

    "motion is bounded settles after stopping and never preserves a stale heading through a reversal" {
        val settings = StaffSpellHudMotionSettings()
        StaffSpellConfig(StaffTestConfig(emptyMap())).hudMotion shouldBe settings
        StaffSpellConfig(StaffTestConfig(mapOf(
            "hud.motion.prediction-ticks" to 99.0, "hud.motion.max-lead-blocks" to -1.0,
            "hud.motion.forward-blocks" to 99.0, "hud.motion.vertical-blocks" to -1.0,
            "hud.motion.settle-ticks" to 0,
        ))).hudMotion shouldBe StaffSpellHudMotionSettings(4.0, 0.0, 4.0, 0.0, 1)
        StaffSpellConfig(StaffTestConfig(mapOf(
            "hud.motion.prediction-ticks" to -1.0, "hud.motion.max-lead-blocks" to 99.0,
            "hud.motion.forward-blocks" to -1.0, "hud.motion.vertical-blocks" to 99.0,
            "hud.motion.settle-ticks" to 99,
        ))).hudMotion shouldBe StaffSpellHudMotionSettings(0.0, 8.0, 0.0, 2.0, 40)
        var motion = StaffSpellHudMotion()
        repeat(4) { motion = staffSpellHudMotion(motion, Vector(0.0, 0.0, 3.0), 1.0, true, settings) }
        motion.lead.length() shouldBe settings.maxLeadBlocks
        motion.retreat shouldBe 1.0
        staffSpellHudMotion(motion, Vector(), 1.0, true, settings.copy(maxLeadBlocks = 0.0))
            .lead.length() shouldBe 0.0
        val firstStop = staffSpellHudMotion(motion, Vector(), 1.0, true, settings)
        (firstStop.lead.z in 0.1..<motion.lead.z) shouldBe true
        (firstStop.retreat in 0.1..<motion.retreat) shouldBe true
        motion = firstStop
        repeat(settings.settleTicks) { motion = staffSpellHudMotion(motion, Vector(), 1.0, true, settings) }
        motion.retreat shouldBe 0.0
        (motion.lead.length() < 1.0e-8) shouldBe true
        motion = staffSpellHudMotion(motion, Vector(0.0, 0.0, 1.5), 1.0, true, settings)
        motion = staffSpellHudMotion(motion, Vector(0.0, 0.0, -1.5), 1.0, true, settings)
        motion.lead.z shouldBe -3.0
        motion = staffSpellHudMotion(motion, Vector(), 1.0, true, settings, Math.toRadians(12.0))
        motion.turnRadiansPerTick shouldBe Math.toRadians(12.0)
        repeat(settings.settleTicks) { motion = staffSpellHudMotion(motion, Vector(), 1.0, true, settings) }
        motion.turnRadiansPerTick shouldBe 0.0
        staffSpellHudMotion(motion, Vector(0.0, 0.0, 80.0), 1.0, true, settings) shouldBe StaffSpellHudMotion()
    }

    "unsafe turn and top-bottom change snap both interpolation channels rather than cross the reticle" {
        withHudHarness { harness ->
            val player = harness.player("staff-hud-turn", StaffSpell.EMBER)
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)
            val record = harness.recorder.records.single()
            val turned = player.location.apply { pitch = 55f }
            player.teleport(turned)
            harness.scheduler.tick(1)
            record.teleportTicks shouldBe 0
            record.transformTicks shouldBe 0
            harness.scheduler.tick(12)
            record.teleportTicks shouldBe 1
            harness.preferences.value = StaffSpellHudPreferences(StaffHudMode.HOLOGRAM, StaffHudPlacement.TOP)
            harness.scheduler.tick(1)
            record.teleportTicks shouldBe 0
            record.transformTicks shouldBe 0
            harness.recorder.records.size shouldBe 1
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
                Unit
            }
            every { handle.teleportDuration = any() } answers { record.teleportTicks = firstArg<Int>() }
            every { handle.interpolationDuration = any() } answers { record.transformTicks = firstArg<Int>() }
            every { handle.text(any()) } answers {
                record.text = firstArg<Component>()
                record.textWrites += record.text
                Unit
            }
            every { handle.showTo(any()) } answers {
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
) {
    fun player(name: String, spell: StaffSpell): Player = paper.addPlayer(name).apply {
        teleport(Location(world, 0.5, 64.0, 0.5))
        val item = ItemStack(Material.STICK)
        item.editMeta { meta -> meta.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, spell.id) }
        inventory.setItemInMainHand(item)
    }
}

private data class HudPreferenceBox(var value: StaffSpellHudPreferences = StaffSpellHudPreferences())

private fun withHudHarness(block: (HudHarness) -> Unit) = failOnUnsupportedMockBukkitOperation {
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
            if (distance == null) null else {
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
                val config = StaffSpellConfig(StaffTestConfig(mapOf(
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
                )))
                val hud = StaffSpellHud(recorder.displays, config,
                    preferences = StaffSpellHudPreferencesReader { preferences.value }).also(StaffSpellHud::start)
                try {
                    block(HudHarness(scheduler, world, recorder, hud, paper, rayHitDistance, preferences))
                } finally {
                    hud.close()
                    recorder.closes shouldBe 1
                }
            }
        }
    } finally {
        unmockkConstructor(WorldMock::class)
    }
}
