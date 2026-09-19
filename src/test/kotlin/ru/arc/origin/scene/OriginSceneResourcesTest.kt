package ru.arc.origin.scene

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.trait.trait.Equipment as CitizensEquipment
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Lidded
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.inventory.ItemStack
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock as mockitoMock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify as mockitoVerify
import org.mockito.kotlin.whenever
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.function.Consumer

class OriginSceneResourcesTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "empty hand is captured once, including null, and repeated equip restores it" {
        val npc = mockNpc(17)
        val equipment = mockitoMock<CitizensEquipment>()
        every { npc.getOrAddTrait(CitizensEquipment::class.java) } returns equipment
        whenever(equipment.get(CitizensEquipment.EquipmentSlot.HAND)).thenReturn(null)

        val resources = OriginSceneResources()
        resources.equip(npc, Material.IRON_SWORD)
        resources.equip(npc, Material.DIAMOND_SWORD)
        resources.cleanup() shouldBe emptyList()

        mockitoVerify(equipment, times(1)).get(CitizensEquipment.EquipmentSlot.HAND)
        mockitoVerify(equipment).set(CitizensEquipment.EquipmentSlot.HAND, null)
    }

    "normal hand state is restored exactly after cleanup" {
        val npc = mockNpc(18)
        val equipment = mockitoMock<CitizensEquipment>()
        val original = ItemStack(Material.STICK, 2)
        every { npc.getOrAddTrait(CitizensEquipment::class.java) } returns equipment
        whenever(equipment.get(CitizensEquipment.EquipmentSlot.HAND)).thenReturn(original)

        val resources = OriginSceneResources()
        resources.equip(npc, Material.IRON_SWORD)
        resources.cleanup() shouldBe emptyList()

        mockitoVerify(equipment).set(CitizensEquipment.EquipmentSlot.HAND, original)
    }

    "container opening failure remains owned and is retried by cleanup" {
        val location = mockk<Location>(relaxed = true)
        val block = mockk<Block>(relaxed = true)
        val liddedState = mockk<BlockState>(moreInterfaces = arrayOf(Lidded::class), relaxed = true)
        val lidded = liddedState as Lidded
        every { location.clone() } returns location
        every { location.block } returns block
        every { block.state } returns liddedState
        every { lidded.open() } throws IllegalStateException("opening failed")
        every { lidded.close() } just runs

        val resources = OriginSceneResources()
        shouldThrow<IllegalStateException> { resources.setContainer(location, open = true) }

        resources.cleanup() shouldBe emptyList()
        verify(exactly = 1) { lidded.close() }
    }

    "non-lidded container is rejected instead of silently skipped" {
        val location = mockk<Location>(relaxed = true)
        val block = mockk<Block>(relaxed = true)
        every { location.block } returns block
        every { block.state } returns mockk<BlockState>()

        shouldThrow<IllegalArgumentException> {
            OriginSceneResources().setContainer(location, open = true)
        }
    }

    "display removal is idempotent" {
        val display = mockDisplay()
        val world = mockWorld(display)
        val resources = OriginSceneResources()
        withBukkitBlockData {
            resources.updateDisplay("hammer", world, resolvedProp(), displayStep(), setOf("scene-test")) shouldBe true
            resources.displayCount shouldBe 1

            resources.removeDisplay("hammer") shouldBe true
            resources.removeDisplay("hammer") shouldBe false
        }

        verify(exactly = 1) { display.remove() }
    }

    "invalid non-block material does not spawn or become owned" {
        val display = mockDisplay()
        val world = mockWorld(display)
        val resources = OriginSceneResources()

        shouldThrow<IllegalArgumentException> {
            resources.updateDisplay("invalid", world, resolvedProp(), displayStep("DIAMOND"), setOf("scene-test"))
        }

        resources.displayCount shouldBe 0
        verify(exactly = 0) {
            world.spawn<BlockDisplay>(any<Location>(), BlockDisplay::class.java, any<Consumer<BlockDisplay>>())
        }
    }

    "failed cleanup removes healthy resources and retries failed ones" {
        val npc = mockNpc(19)
        val equipment = mockitoMock<CitizensEquipment>()
        every { npc.getOrAddTrait(CitizensEquipment::class.java) } returns equipment
        whenever(equipment.get(CitizensEquipment.EquipmentSlot.HAND)).thenReturn(null)
        var setCalls = 0
        doAnswer {
            setCalls++
            if (setCalls == 2) throw IllegalStateException("restore failed")
            null
        }.whenever(equipment).set(eq(CitizensEquipment.EquipmentSlot.HAND), anyOrNull())

        val display = mockDisplay()
        val world = mockWorld(display)
        val resources = OriginSceneResources()
        resources.equip(npc, Material.IRON_SWORD)
        withBukkitBlockData {
            resources.updateDisplay("hammer", world, resolvedProp(), displayStep(), setOf("scene-test")) shouldBe true
        }

        val firstFailures = resources.cleanup()
        firstFailures.map { it.resource to it.id } shouldContainExactly listOf("hand" to "19")
        resources.displayCount shouldBe 0
        verify(exactly = 1) { display.remove() }

        resources.cleanup() shouldBe emptyList()
        mockitoVerify(equipment, times(3)).set(eq(CitizensEquipment.EquipmentSlot.HAND), anyOrNull())
    }

    "display mutation failure is owned and cleaned up" {
        val display = mockDisplay()
        val world = mockWorld(display)
        every { display.block = any() } throws IllegalStateException("display mutation failed")

        val resources = OriginSceneResources()
        withBukkitBlockData {
            shouldThrow<IllegalStateException> {
                resources.updateDisplay("broken", world, resolvedProp(), displayStep(), setOf("scene-test"))
            }
        }
        resources.displayCount shouldBe 1

        resources.cleanup() shouldBe emptyList()
        verify(exactly = 1) { display.remove() }
        resources.displayCount shouldBe 0
    }

    "rejected display teleport fails with an owned resource for cleanup" {
        val display = mockDisplay()
        every { display.teleport(any<Location>()) } returns false
        val world = mockWorld(display)
        val resources = OriginSceneResources()

        withBukkitBlockData {
            shouldThrow<IllegalStateException> {
                resources.updateDisplay("teleport-failed", world, resolvedProp(), displayStep(), setOf("scene-test"))
            }
        }
        resources.displayCount shouldBe 1
        resources.cleanup() shouldBe emptyList()
        verify(exactly = 1) { display.remove() }
    }
})

private fun mockNpc(id: Int): NPC = mockk {
    every { this@mockk.id } returns id
}

private fun mockDisplay(): BlockDisplay = mockk(relaxed = true) {
    every { isValid } returns true
    every { teleport(any<Location>()) } returns true
}

private fun mockWorld(display: BlockDisplay): World = mockk(relaxed = true) {
    every {
        spawn<BlockDisplay>(any<Location>(), BlockDisplay::class.java, any<Consumer<BlockDisplay>>())
    } answers {
        thirdArg<Consumer<BlockDisplay>>().accept(display)
        display
    }
}

private fun resolvedProp() = OriginSceneResolvedProp(
    x = 12.0,
    y = 64.0,
    z = -4.0,
    translationX = -0.25f,
    translationY = 0f,
    translationZ = -0.15f,
)

private fun displayStep(material: String = "IRON_BLOCK") = OriginSceneStep.BlockDisplay(
    key = "hammer",
    surface = null,
    anchor = "forge",
    material = material,
    origin = OriginScenePropOrigin.BOTTOM_CENTER,
    offset = OriginSceneVector.ZERO,
    scale = OriginSceneVector(0.5, 0.5, 0.5),
    rotationYDegrees = 90f,
    interpolationTicks = 4,
)

private fun <T> withBukkitBlockData(block: () -> T): T {
    val blockData = mockk<BlockData>()
    mockkStatic(Bukkit::class)
    return try {
        every { Bukkit.createBlockData(Material.IRON_BLOCK) } returns blockData
        block()
    } finally {
        unmockkStatic(Bukkit::class)
    }
}
