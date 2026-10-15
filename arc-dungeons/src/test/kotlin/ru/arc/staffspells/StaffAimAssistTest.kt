package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector

class StaffAimAssistTest : FreeSpec({
    "near misses receive fractional correction and disabled or excluded spells preserve aim" {
        val fixture = AimFixture()
        val settings = staffSettings()
        val corrected = fixture.targeting(settings).assistedDirection(fixture.player, StaffSpell.EMBER, null)
        (corrected.x > .01 && corrected.x < .04) shouldBe true
        (corrected.z > .99) shouldBe true
        val direct = Vector(0.0, 0.0, 1.0)
        fixture.targeting(settings.copy(aimAssist = settings.aimAssist.copy(enabled = false)))
            .assistedDirection(fixture.player, StaffSpell.EMBER, null) shouldBe direct
        fixture.targeting(settings.copy(aimAssist = settings.aimAssist.copy(excludedSpells = setOf("ember"))))
            .assistedDirection(fixture.player, StaffSpell.EMBER, null) shouldBe direct
    }

    "cover and out-of-cone targets never pull the launch direction" {
        val fixture = AimFixture()
        val targeting = fixture.targeting(staffSettings())
        every { fixture.world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(),
            any<FluidCollisionMode>(), any<Boolean>()) } returns RayTraceResult(Vector(0.0, 1.6, 4.0))
        targeting.assistedDirection(fixture.player, StaffSpell.EMBER, null) shouldBe Vector(0.0, 0.0, 1.0)
        every { fixture.world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(),
            any<FluidCollisionMode>(), any<Boolean>()) } returns null
        every { fixture.victim.location } returns Location(fixture.world, 4.0, .6, 15.0)
        targeting.assistedDirection(fixture.player, StaffSpell.EMBER, null) shouldBe Vector(0.0, 0.0, 1.0)
    }

    "exact hits keep their original direction and ground attacks never acquire pitch" {
        val fixture = AimFixture()
        every { fixture.victim.boundingBox } returns BoundingBox(-.3, .6, 14.7, .3, 2.6, 15.3)
        fixture.targeting(staffSettings()).assistedDirection(fixture.player, StaffSpell.LANCE, null)
            .shouldBe(Vector(0.0, 0.0, 1.0))
        every { fixture.victim.boundingBox } returns BoundingBox(.7, .6, 14.7, 1.3, 2.6, 15.3)
        every { fixture.victim.location } returns Location(fixture.world, 1.0, 2.6, 15.0)
        fixture.targeting(staffSettings()).assistedDirection(fixture.player, StaffSpell.TIDE, null,
            direction = Vector(0.0, 0.0, 1.0)).y shouldBe 0.0
    }

    "assist configuration is bounded and refreshed with each settings generation" {
        val raw = StaffTestConfig()
        val config = StaffSpellConfig(raw)
        val first = config.settings
        raw["targeting.aim-assist.enabled"] = false
        raw["targeting.aim-assist.strength"] = 5.0
        raw["targeting.aim-assist.excluded-spells"] = listOf("ember")
        val next = config.settings
        first.aimAssist.enabled shouldBe true
        next.aimAssist.enabled shouldBe false
        next.aimAssist.strength shouldBe .8
        next.aimAssist.excludedSpells shouldBe setOf("ember")
        (first.fingerprint("test") != next.fingerprint("test")) shouldBe true
    }
})

private class AimFixture {
    val world = mockk<World>()
    val player = mockk<Player>()
    val victim = mockk<LivingEntity>()
    private val damage = mockk<StaffSpellDamage>()
    init {
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(),
            any<FluidCollisionMode>(), any<Boolean>()) } returns null
        every { world.getNearbyLivingEntities(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns listOf(victim)
        every { world.getNearbyLivingEntities(any<Location>(), any<Double>()) } returns listOf(victim)
        every { player.eyeLocation } returns Location(world, 0.0, 1.6, 0.0)
        every { victim.location } answers { Location(world, 1.0, .6, 15.0) }
        every { victim.height } returns 2.0
        every { victim.boundingBox } returns BoundingBox(.7, .6, 14.7, 1.3, 2.6, 15.3)
        every { damage.eligible(player, victim, any()) } returns true
    }
    fun targeting(settings: StaffSpellSettings) = StaffSpellTargeting(settings, damage)
}
