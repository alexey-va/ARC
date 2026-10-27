package ru.arc.pvp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.FlagRegistry
import me.angeschossen.lands.api.flags.enums.FlagTarget
import me.angeschossen.lands.api.flags.type.NaturalFlag
import me.angeschossen.lands.api.land.Area
import me.angeschossen.lands.api.land.Land
import org.bukkit.Location
import org.bukkit.plugin.Plugin

class LandsPvpFlagTest : StringSpec({
    "the native factory registers once while an absent ban stays disabled during war" {
        val plugin = mockk<Plugin> { every { name } returns "ARC" }
        val lands = mockk<LandsIntegration>()
        val registry = mockk<FlagRegistry>()
        val flag = mockk<NaturalFlag>(relaxed = true)
        val area = mockk<Area>()
        val land = mockk<Land>()
        val at = mockk<Location>()
        var activeInWar = true
        var stored = false
        var registered: NaturalFlag? = null
        every { lands.onLoad(any()) } answers { firstArg<Runnable>().run() }
        every { lands.flagRegistry } returns registry
        every { registry.getNatural("arc_no_pvp") } answers { registered }
        every { registry.register(any()) } throws IllegalStateException("Native factory already registered arc_no_pvp")
        every { flag.setDefaultState(any()) } returns flag
        every { flag.setApplyInSubareas(any()) } returns flag
        every { flag.setDisplayName(any()) } returns flag
        every { flag.setActiveInWar(any()) } answers { activeInWar = firstArg(); flag }
        every { lands.getArea(at) } returns area
        every { area.land } returns land
        every { land.defaultArea } returns area
        every { land.isInWar } returns true
        // Exact Lands 8.9.6 fallback: a war-active flag is true even when absent from the stored set.
        every { area.hasNaturalFlag(flag) } answers { stored || activeInWar && land.isInWar }
        mockkStatic(LandsIntegration::class, NaturalFlag::class)
        try {
            every { LandsIntegration.of(plugin) } returns lands
            // Exact Lands 8.9.6 factory publishes this same object before returning it.
            every { NaturalFlag.of(lands, FlagTarget.PLAYER, "arc_no_pvp") } answers { registered = flag; flag }
            LandsPvpFlag.install(plugin)
            registered shouldBe flag
            LandsPvpFlag.isRegistered() shouldBe true
            verify(exactly = 1) { flag.setDefaultState(false) }
            verify(exactly = 1) { flag.setApplyInSubareas(false) }
            verify(exactly = 1) { flag.setActiveInWar(false) }
            verify(exactly = 1) { NaturalFlag.of(lands, FlagTarget.PLAYER, "arc_no_pvp") }
            verify(exactly = 0) { registry.register(any()) }
            LandsPvpFlag.prohibits(at) shouldBe false
            stored = true
            LandsPvpFlag.prohibits(at) shouldBe true
            verify(exactly = 0) { area.toggleNaturalFlag(any()) }
        } finally {
            unmockkStatic(LandsIntegration::class, NaturalFlag::class)
        }
    }

    "the persisted main-region ban protects subareas without inspecting membership or migrating existing areas" {
        val plugin = mockk<Plugin> { every { name } returns "ARC" }
        val lands = mockk<LandsIntegration>()
        val registry = mockk<FlagRegistry>()
        val flag = mockk<NaturalFlag>()
        val main = mockk<Area>()
        val subarea = mockk<Area>()
        val land = mockk<Land>()
        val at = mockk<Location>()
        var banned = false
        every { lands.onLoad(any()) } answers { firstArg<Runnable>().run() }
        every { lands.flagRegistry } returns registry
        every { registry.getNatural("arc_no_pvp") } returns flag
        every { flag.plugin } returns plugin
        every { lands.getArea(at) } returns subarea
        every { subarea.land } returns land
        every { land.defaultArea } returns main
        every { main.hasNaturalFlag(flag) } answers { banned }
        mockkStatic(LandsIntegration::class)
        try {
            every { LandsIntegration.of(plugin) } returns lands
            LandsPvpFlag.install(plugin)
            LandsPvpFlag.prohibits(at) shouldBe false
            banned = true
            LandsPvpFlag.prohibits(at) shouldBe true
            every { lands.getArea(at) } returns null
            LandsPvpFlag.prohibits(at) shouldBe false
            verify(exactly = 0) { registry.register(any()) }
            verify(exactly = 0) { subarea.hasNaturalFlag(any()) }
            verify(exactly = 0) { lands.lands }
        } finally {
            unmockkStatic(LandsIntegration::class)
        }
    }
})
