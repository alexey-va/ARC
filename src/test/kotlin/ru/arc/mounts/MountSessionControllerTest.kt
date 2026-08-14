package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageEvent
import java.util.UUID

class MountSessionControllerTest : StringSpec({
    "mount mobs stay physics-active while vanilla goals are disabled" {
        val mob = mockk<Mob>(relaxed = true)

        configureMountMob(mob)

        verify(exactly = 1) { mob.setAware(false) }
        verify(exactly = 0) { mob.setAI(any()) }
    }

    "temporary mount entities are invulnerable" {
        val entity = mockk<LivingEntity>(relaxed = true)

        configureMountDurability(entity)

        verify(exactly = 1) { entity.setInvulnerable(true) }
    }

    "active mount damage policy cancels every damage cause" {
        EntityDamageEvent.DamageCause.entries.forEach { cause ->
            shouldCancelMountDamage(MountDamageTarget.MOUNT, cause) shouldBe true
        }
    }

    "mounted rider is protected only from hitbox suffocation" {
        shouldCancelMountDamage(MountDamageTarget.RIDER, EntityDamageEvent.DamageCause.SUFFOCATION) shouldBe true
        shouldCancelMountDamage(MountDamageTarget.RIDER, EntityDamageEvent.DamageCause.ENTITY_ATTACK) shouldBe false
        shouldCancelMountDamage(MountDamageTarget.RIDER, EntityDamageEvent.DamageCause.FALL) shouldBe false
    }

    "vanilla dismount is blocked until double sneak authorizes it" {
        shouldCancelUnauthorizedDismount(allowDismount = false, cancellable = true) shouldBe true
        shouldCancelUnauthorizedDismount(allowDismount = true, cancellable = true) shouldBe false
        shouldCancelUnauthorizedDismount(allowDismount = false, cancellable = false) shouldBe false
    }

    "a mounted rider is knocked off only by one hit reaching the configured final damage" {
        shouldKnockRiderOff(finalDamage = 5.99, threshold = 6.0) shouldBe false
        shouldKnockRiderOff(finalDamage = 6.0, threshold = 6.0) shouldBe true
        shouldKnockRiderOff(finalDamage = 12.0, threshold = 6.0) shouldBe true
        shouldKnockRiderOff(finalDamage = Double.NaN, threshold = 6.0) shouldBe false
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
