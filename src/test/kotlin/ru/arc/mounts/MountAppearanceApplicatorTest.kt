package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Zombie
import org.bukkit.inventory.EntityEquipment

class MountAppearanceApplicatorTest : StringSpec({
    "base zombie is always adult and stripped of random equipment" {
        val equipment = mockk<EntityEquipment>(relaxed = true)
        val zombie = mockk<Zombie>(relaxed = true) { every { this@mockk.equipment } returns equipment }

        MountAppearanceApplicator.apply(zombie, MountAppearance(baby = false))

        verify(exactly = 1) { zombie.setAdult() }
        verify(exactly = 0) { zombie.setBaby() }
        verify(exactly = 1) { equipment.clear() }
        verify(exactly = 0) { equipment.setItem(any(), any(), any()) }
        verify(exactly = 1) { zombie.setShouldBurnInDay(false) }
    }
})
