package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Mob
import org.bukkit.event.entity.CreatureSpawnEvent
import java.util.UUID

class MountSessionControllerTest : StringSpec({
    "mount mobs stay physics-active while vanilla goals are disabled" {
        val mob = mockk<Mob>(relaxed = true)

        configureMountMob(mob)

        verify(exactly = 1) { mob.setAware(false) }
        verify(exactly = 0) { mob.setAI(any()) }
    }

    "only a tagged ARC custom mount may bypass a cancelled spawn" {
        val owner = UUID.randomUUID().toString()

        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.CUSTOM, owner, "bee") shouldBe true
        shouldAllowCancelledMountSpawn(false, CreatureSpawnEvent.SpawnReason.CUSTOM, owner, "bee") shouldBe false
        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.NATURAL, owner, "bee") shouldBe false
        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.CUSTOM, "invalid", "bee") shouldBe false
        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.CUSTOM, owner, "") shouldBe false
        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.CUSTOM, null, "bee") shouldBe false
    }
})
