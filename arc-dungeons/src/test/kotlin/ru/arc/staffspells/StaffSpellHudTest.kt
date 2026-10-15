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

    "attack mode helper matches native hold-release and Gravity's primary-only channel" {
        staffAttackInputMode(StaffSpell.EMBER, secondary = false) shouldBe StaffAttackInputMode.HOLD_RELEASE
        staffAttackInputMode(StaffSpell.ICICLE, secondary = false) shouldBe StaffAttackInputMode.HOLD_RELEASE
        staffAttackInputMode(StaffSpell.EMBER, secondary = true) shouldBe StaffAttackInputMode.INSTANT
        staffAttackInputMode(StaffSpell.ICICLE, secondary = true) shouldBe StaffAttackInputMode.INSTANT
        staffAttackInputMode(StaffSpell.GRAVITY, secondary = false) shouldBe StaffAttackInputMode.CHANNEL_CLICK_TOGGLE
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
            text(record.text) shouldBe "Комета\nУдерживайте ПКМ для зарядки\nОтпустите, чтобы выпустить"

            player.isSneaking = true
            harness.scheduler.tick(2)
            text(record.text) shouldBe "Метеоры\nГотово · без зарядки\nShift + ПКМ — применить"
            player.isSneaking = false
            harness.scheduler.tick(2)
            text(record.text) shouldBe "Комета\nУдерживайте ПКМ для зарядки\nОтпустите, чтобы выпустить"

            harness.hud.charging(player, StaffSpell.EMBER, secondary = false, progress = 0.55)
            harness.hud.charging(player, StaffSpell.EMBER, secondary = false, progress = 0.55)
            harness.recorder.records.size shouldBe 1
            record.textWrites.size shouldBe 3
            text(record.text) shouldBe "Комета\nСила 55% ■■■■■□□□□□\nОтпустите ПКМ — выпустить"

            harness.scheduler.tick(2)
            // Sneaking changes the eye height, so both camera-follow updates above are real.
            record.teleports.size shouldBe 2
            val afterSneak = record.teleports.size
            harness.scheduler.tick(2)
            record.teleports.size shouldBe afterSneak
            val initial = record.spawn.clone()
            player.teleport(Location(harness.world, 1.5, 64.0, 0.5))
            harness.scheduler.tick(2)
            record.teleports.size shouldBe afterSneak + 1
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
            text(record.text).endsWith("Q · Поджечь    F · 1.0с") shouldBe true
            state = StaffHotkeyHudState(76, 0)
            harness.scheduler.tick(2)
            harness.recorder.records.size shouldBe 1
            text(record.text).endsWith("Q · 3.8с    F · готово") shouldBe true
        }
    }

    "nearby solid blocks move the HUD forward and keep its apparent scale" {
        withHudHarness { harness ->
            val player = harness.player("staff-hud-wall", StaffSpell.EMBER)
            harness.rayHitDistance[0] = 1.6
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)

            val record = harness.recorder.records.single()
            val distance = record.spawn.distance(player.eyeLocation)
            (kotlin.math.abs(distance - staffHudSafeDistance(staffSpellHudPose().distance, 1.6)) < 1.0e-8) shouldBe true
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
            text(record.text) shouldBe "Тяготение\nПКМ — захватить\nВедите прицел · нажмите ещё раз, чтобы бросить"

            harness.hud.cooldown(player, StaffSpell.GRAVITY, secondary = false, remainingTicks = 18, totalTicks = 44)
            player.inventory.setItemInMainHand(staffItem(StaffSpell.EMBER))
            harness.scheduler.tick(2)
            harness.recorder.records.size shouldBe 1
            record.removals shouldBe 0
            text(record.text) shouldBe "Комета · ПКМ\nВосстановление 0.9 с\n■■■■■□□□□□"
        }
    }

    "ready ability and gravity channel use selected key and truthful click-toggle language" {
        withHudHarness { harness ->
            val player = harness.player("gravity-hud", StaffSpell.GRAVITY)
            player.isSneaking = true
            harness.hud.ready(player, StaffSpell.GRAVITY, secondary = true)
            text(harness.recorder.records.single().text) shouldBe
                "Тяготение\nГотово · без зарядки\nShift + ПКМ — применить"

            player.isSneaking = false
            harness.scheduler.tick(2)
            harness.hud.channel(player, StaffSpell.GRAVITY, secondary = false)
            text(harness.recorder.records.single().text) shouldBe
                "Тяготение\nГлыба в захвате\nПКМ — бросить"

            harness.hud.cooldown(player, StaffSpell.GRAVITY, secondary = false, remainingTicks = 9, totalTicks = 44)
            text(harness.recorder.records.single().text) shouldBe
                "Тяготение · ПКМ\nВосстановление 0.5 с\n■■■■■■■□□□"
            player.isSneaking = true
            harness.scheduler.tick(2)
            text(harness.recorder.records.single().text) shouldBe
                "Тяготение · Shift + ПКМ\nВосстановление 0.5 с\n■■■■■■■□□□"
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
            text(bar.name()) shouldBe "Комета · удерживайте ПКМ · отпустите для выпуска"
            text(bar.name()).contains('\n') shouldBe false

            harness.hud.charging(player, StaffSpell.EMBER, secondary = false, progress = 0.4)
            text(bar.name()) shouldBe "Комета · сила 40% ■■■■□□□□□□ · отпустите ПКМ"
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
                    "messages.staff-hud.ready-instant" to "<ability><newline>Готово · без зарядки<newline><input> — применить",
                    "messages.staff-hud.ready-charge" to "<ability><newline>Удерживайте <input> для зарядки<newline>Отпустите, чтобы выпустить",
                    "messages.staff-hud.ready-channel" to "<ability><newline><input> — захватить<newline>Ведите прицел · нажмите ещё раз, чтобы бросить",
                    "messages.staff-hud.charging" to "<ability><newline>Сила <percent>% <bar><newline>Отпустите <input> — выпустить",
                    "messages.staff-hud.cooldown" to "<ability> · <input><newline>Восстановление <seconds> с<newline><bar>",
                    "messages.staff-hud.channel" to "<ability><newline>Глыба в захвате<newline><input> — бросить",
                    "messages.staff-hud.bossbar-ready-instant" to "<ability> · <input> — применить",
                    "messages.staff-hud.bossbar-ready-charge" to "<ability> · удерживайте <input> · отпустите для выпуска",
                    "messages.staff-hud.bossbar-ready-channel" to "<ability> · <input> — захватить · ведите прицел · нажмите ещё раз для броска",
                    "messages.staff-hud.bossbar-charging" to "<ability> · сила <percent>% <bar> · отпустите <input>",
                    "messages.staff-hud.bossbar-cooldown" to "<ability> · <input> · <seconds> с <bar>",
                    "messages.staff-hud.bossbar-channel" to "<ability> · глыба в захвате · <input> — бросить",
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
