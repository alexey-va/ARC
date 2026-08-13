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

    "only the exact pending ARC spawn token may bypass a cancelled spawn" {
        val owner = UUID.randomUUID().toString()
        val pendingToken = UUID.randomUUID()
        val otherToken = UUID.randomUUID()
        val mountIds = setOf("bee", "zombie")

        shouldAllowCancelledMountSpawn(
            true,
            CreatureSpawnEvent.SpawnReason.CUSTOM,
            owner,
            "bee",
            pendingToken.toString(),
            mountIds,
            setOf(pendingToken),
        ) shouldBe true
        shouldAllowCancelledMountSpawn(false, CreatureSpawnEvent.SpawnReason.CUSTOM, owner, "bee", pendingToken.toString(), mountIds, setOf(pendingToken)) shouldBe false
        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.NATURAL, owner, "bee", pendingToken.toString(), mountIds, setOf(pendingToken)) shouldBe false
        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.CUSTOM, "invalid", "bee", pendingToken.toString(), mountIds, setOf(pendingToken)) shouldBe false
        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.CUSTOM, owner, "unknown", pendingToken.toString(), mountIds, setOf(pendingToken)) shouldBe false
        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.CUSTOM, owner, "bee", otherToken.toString(), mountIds, setOf(pendingToken)) shouldBe false
        shouldAllowCancelledMountSpawn(true, CreatureSpawnEvent.SpawnReason.CUSTOM, owner, "bee", null, mountIds, setOf(pendingToken)) shouldBe false
    }
})
