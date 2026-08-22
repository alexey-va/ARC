package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Bat
import org.bukkit.entity.Horse
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Vex
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.util.BoundingBox
import java.util.UUID

class MountSessionControllerTest : StringSpec({
    "mount mobs stay physics-active while vanilla goals are disabled" {
        val mob = mockk<Mob>(relaxed = true)

        configureMountMob(mob)

        verify(exactly = 1) { mob.setAware(false) }
        verify(exactly = 0) { mob.setAI(any()) }
    }

    "bat mounts are forced awake at spawn and during every movement tick" {
        val bat = mockk<Bat>(relaxed = true)

        configureMountMob(bat)
        maintainMountMobState(bat)

        verify(exactly = 2) { bat.setAwake(true) }
    }

    "vex mount sweeps its full path and slides along a wall instead of phasing through it" {
        val vex = mockk<Vex>(relaxed = true)
        every { vex.boundingBox } returns BoundingBox(0.0, 0.0, 0.0, 0.4, 0.8, 0.4)
        every { vex.wouldCollideUsing(any()) } answers {
            firstArg<BoundingBox>().maxX > 1.0
        }

        constrainPhasingVelocity(vex, MotionVector(1.2, 0.0, 0.4)) shouldBe MotionVector(0.0, 0.0, 0.4)
    }

    "walking mounts step over a full block without a manual jump" {
        val entity = mockk<LivingEntity>(relaxed = true)
        val stepHeight = mockk<AttributeInstance>(relaxed = true)
        every { entity.getAttribute(Attribute.STEP_HEIGHT) } returns stepHeight

        configureWalkingStepHeight(entity, 1.1)

        verify(exactly = 1) { stepHeight.baseValue = 1.1 }
    }

    "horses use native ridden physics with ARC speed and jump values" {
        val horse = mockk<Horse>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val movementSpeed = mockk<AttributeInstance>(relaxed = true)
        val stepHeight = mockk<AttributeInstance>(relaxed = true)
        every { horse.getAttribute(Attribute.MOVEMENT_SPEED) } returns movementSpeed
        every { horse.getAttribute(Attribute.STEP_HEIGHT) } returns stepHeight

        configureNativeHorse(horse, player)
        configureNativeHorseMotion(horse, maximumSpeedBlocksPerTick = 0.42, jumpVelocity = 0.5, stepHeight = 1.1)

        verify(exactly = 1) { horse.setAware(true) }
        verify(exactly = 1) { horse.isTamed = true }
        verify(exactly = 1) { horse.owner = player }
        verify(exactly = 1) { movementSpeed.baseValue = nativeHorseMovementAttribute(0.42) }
        verify(exactly = 1) { horse.jumpStrength = 0.5 }
        verify(exactly = 1) { stepHeight.baseValue = 1.1 }
        nativeHorseMovementAttribute(1.05) shouldBe (0.5 plusOrMinus 1.0e-9)
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

    "kelp and bubble columns keep an aquatic mount session alive" {
        isAquaticEnvironment(inWaterOrBubbleColumn = true, blockIsLiquid = false) shouldBe true
        isAquaticEnvironment(inWaterOrBubbleColumn = false, blockIsLiquid = true) shouldBe true
        isAquaticEnvironment(inWaterOrBubbleColumn = false, blockIsLiquid = false) shouldBe false
    }

    "only a flying mount is hidden after the rider looks clearly downward" {
        nextRiderMountHidden(MountMovement.FLYING, false, 34.9f, 35.0f, 20.0f) shouldBe false
        nextRiderMountHidden(MountMovement.FLYING, false, 35.0f, 35.0f, 20.0f) shouldBe true
        nextRiderMountHidden(MountMovement.FLYING, true, 20.1f, 35.0f, 20.0f) shouldBe true
        nextRiderMountHidden(MountMovement.FLYING, true, 20.0f, 35.0f, 20.0f) shouldBe false
        nextRiderMountHidden(MountMovement.WALKING, true, 90.0f, 35.0f, 20.0f) shouldBe false
        nextRiderMountHidden(MountMovement.SWIMMING, true, 90.0f, 35.0f, 20.0f) shouldBe false
    }

    "flying mount mining compensation is transient and exactly cancels the airborne penalty" {
        val player = mockk<Player>(relaxed = true)
        val blockBreakSpeed = mockk<AttributeInstance>(relaxed = true)
        val modifier = mockk<AttributeModifier>(relaxed = true)
        every { player.getAttribute(Attribute.BLOCK_BREAK_SPEED) } returns blockBreakSpeed

        setAirborneMiningCompensation(player, modifier, enabled = true)
        setAirborneMiningCompensation(player, modifier, enabled = false)

        verify(exactly = 1) { blockBreakSpeed.addTransientModifier(modifier) }
        verify(exactly = 2) { blockBreakSpeed.removeModifier(modifier.key) }
        airborneMiningCompensationAmount() shouldBe 4.0
    }
})
