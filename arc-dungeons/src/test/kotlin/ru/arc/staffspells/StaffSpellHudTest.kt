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
import java.util.UUID

class StaffSpellHudTest : FreeSpec({
    "HUD remains in front of a wall even when the player stands against it" {
        staffHudSafeDistance(staffHudBaseDistance(), 0.30) shouldBe 0.12
    }

    "attack mode helper describes two-click charges without changing the instant routes" {
        staffAttackInputMode(StaffSpell.EMBER, secondary = false) shouldBe StaffAttackInputMode.CHARGE_CLICK_TOGGLE
        staffAttackInputMode(StaffSpell.ICICLE, secondary = false) shouldBe StaffAttackInputMode.CHARGE_CLICK_TOGGLE
        staffAttackInputMode(StaffSpell.EMBER, secondary = true) shouldBe StaffAttackInputMode.INSTANT
        staffAttackInputMode(StaffSpell.ICICLE, secondary = true) shouldBe StaffAttackInputMode.INSTANT
        staffAttackInputMode(StaffSpell.GRAVITY, secondary = false) shouldBe StaffAttackInputMode.CHANNEL_CLICK_TOGGLE
        staffAttackInputMode(StaffSpell.GRAVITY, secondary = true) shouldBe StaffAttackInputMode.CHANNEL_CLICK_TOGGLE
        staffAttackInputMode(StaffSpell.LANCE, secondary = false) shouldBe StaffAttackInputMode.INSTANT
    }

    "private hologram reuses one card, sends only state text deltas, follows camera, and clears after unequip" {
        withHudHarness { harness ->
            val player = harness.player("staff-hud", StaffSpell.EMBER)
            harness.hud.ready(player, StaffSpell.EMBER, secondary = false)
            harness.recorder.records.size shouldBe 1
            val record = harness.recorder.records.single()
            record.visibleViewers shouldBe setOf(player.uniqueId)
            record.visibleByDefault shouldBe false
            text(record.text) shouldBe "Комета\nЛКМ — начать заряд\nЕщё один клик — выпустить"

            player.isSneaking = true
            harness.scheduler.tick(2)
            text(record.text) shouldBe "Метеоры\nГотово · без зарядки\nShift + ЛКМ — применить"
            player.isSneaking = false
            harness.scheduler.tick(2)
            text(record.text) shouldBe "Комета\nЛКМ — начать заряд\nЕщё один клик — выпустить"

            harness.hud.charging(player, StaffSpell.EMBER, secondary = false, progress = 0.55)
            harness.hud.charging(player, StaffSpell.EMBER, secondary = false, progress = 0.55)
            harness.recorder.records.size shouldBe 1
            record.textWrites.size shouldBe 3
            text(record.text) shouldBe "Комета\nСила 55% ■■■■■□□□□□\nЛКМ — выпустить"

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
            text(record.text) shouldBe "Тяготение\nЛКМ — захватить\nВедите прицел · ещё клик — бросить"

            harness.hud.cooldown(player, StaffSpell.GRAVITY, secondary = false, remainingTicks = 18, totalTicks = 44)
            player.inventory.setItemInMainHand(staffItem(StaffSpell.EMBER))
            harness.scheduler.tick(2)
            harness.recorder.records.size shouldBe 1
            record.removals shouldBe 0
            text(record.text) shouldBe "Комета · ЛКМ\nВосстановление 0.9 с\n■■■■■□□□□□"
        }
    }

    "ready ability and gravity channel use selected key and truthful click-toggle language" {
        withHudHarness { harness ->
            val player = harness.player("gravity-hud", StaffSpell.GRAVITY)
            player.isSneaking = true
            harness.hud.ready(player, StaffSpell.GRAVITY, secondary = true)
            text(harness.recorder.records.single().text) shouldBe
                "Тяготение\nShift + ЛКМ — захватить\nВедите прицел · ещё клик — бросить"

            harness.hud.channel(player, StaffSpell.GRAVITY, secondary = true)
            text(harness.recorder.records.single().text) shouldBe
                "Тяготение\nГлыба в захвате\nShift + ЛКМ — бросить"

            harness.hud.cooldown(player, StaffSpell.GRAVITY, secondary = true, remainingTicks = 9, totalTicks = 44)
            text(harness.recorder.records.single().text) shouldBe
                "Тяготение · Shift + ЛКМ\nВосстановление 0.5 с\n■■■■■■■□□□"
            player.isSneaking = false
            harness.scheduler.tick(2)
            text(harness.recorder.records.single().text) shouldBe
                "Тяготение · ЛКМ\nВосстановление 0.5 с\n■■■■■■■□□□"
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
) {
    fun player(name: String, spell: StaffSpell): Player = paper.addPlayer(name).apply {
        teleport(Location(world, 0.5, 64.0, 0.5))
        val item = ItemStack(Material.STICK)
        item.editMeta { meta -> meta.persistentDataContainer.set(StaffSpell.itemKey, PersistentDataType.STRING, spell.id) }
        inventory.setItemInMainHand(item)
    }
}

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
                val config = StaffSpellConfig(StaffTestConfig(mapOf(
                    "ember.name" to "Комета",
                    "ember.secondary-name" to "Метеоры",
                    "gravity.name" to "Тяготение",
                    "gravity.secondary-name" to "Тяготение",
                    "messages.staff-hud.ready-instant" to "<ability><newline>Готово · без зарядки<newline><input> — применить",
                    "messages.staff-hud.ready-charge" to "<ability><newline><input> — начать заряд<newline>Ещё один клик — выпустить",
                    "messages.staff-hud.ready-channel" to "<ability><newline><input> — захватить<newline>Ведите прицел · ещё клик — бросить",
                    "messages.staff-hud.charging" to "<ability><newline>Сила <percent>% <bar><newline><input> — выпустить",
                    "messages.staff-hud.cooldown" to "<ability> · <input><newline>Восстановление <seconds> с<newline><bar>",
                    "messages.staff-hud.channel" to "<ability><newline>Глыба в захвате<newline><input> — бросить",
                )))
                val hud = StaffSpellHud(recorder.displays, config).also(StaffSpellHud::start)
                try {
                    block(HudHarness(scheduler, world, recorder, hud, paper, rayHitDistance))
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
