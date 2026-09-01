package ru.arc.mounts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeExactly
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
import org.bukkit.potion.PotionEffectType
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

    "live size growth keeps feet anchored while expanding the full hitbox" {
        val scaled = scaledMountBoundingBox(BoundingBox(-0.5, 10.0, -1.0, 0.5, 12.0, 1.0), 1.5)

        scaled.minY shouldBeExactly 10.0
        scaled.maxY shouldBeExactly 13.0
        scaled.minX shouldBeExactly -0.75
        scaled.maxX shouldBeExactly 0.75
        scaled.minZ shouldBeExactly -1.5
        scaled.maxZ shouldBeExactly 1.5
    }

    "appearance growth includes an age transition from baby to adult" {
        mountAppearanceMayGrow(MountAppearance(baby = true), MountAppearance(baby = false), supportsAge = true) shouldBe true
        mountAppearanceMayGrow(MountAppearance(baby = true), MountAppearance(baby = false), supportsAge = false) shouldBe false
        mountAppearanceMayGrow(MountAppearance(scale = 0.9), MountAppearance(scale = 1.1), supportsAge = false) shouldBe true
        mountAppearanceMayGrow(MountAppearance(scale = 1.1), MountAppearance(scale = 0.9), supportsAge = true) shouldBe false
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
        nativeHorseMovementAttribute(0.0) shouldBeExactly 0.0
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

    "fire-resistant mount protection covers every vanilla fire and lava damage cause" {
        setOf(
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.HOT_FLOOR,
            EntityDamageEvent.DamageCause.CAMPFIRE,
        ).all(::isRiderFireDamageCause) shouldBe true
        isRiderFireDamageCause(EntityDamageEvent.DamageCause.ENTITY_ATTACK) shouldBe false
        isRiderFireDamageCause(EntityDamageEvent.DamageCause.FALL) shouldBe false
        shouldCancelMountDamage(
            MountDamageTarget.RIDER,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            riderFireProtected = true,
        ) shouldBe true
        shouldCancelMountDamage(
            MountDamageTarget.RIDER,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            riderFireProtected = false,
        ) shouldBe false
    }

    "fire-resistant mount extinguishes the rider instead of leaving the fire overlay" {
        val protectedRider = mockk<Player>(relaxed = true)
        every { protectedRider.fireTicks } returns 80
        val ordinaryRider = mockk<Player>(relaxed = true)
        every { ordinaryRider.fireTicks } returns 80

        extinguishRiderFire(protectedRider, fireProtected = true)
        extinguishRiderFire(ordinaryRider, fireProtected = false)

        verify(exactly = 1) { protectedRider.fireTicks = 0 }
        verify(exactly = 0) { ordinaryRider.fireTicks = any() }
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

    "rider-selected auto-hide uses hysteresis and can be disabled" {
        nextRiderMountHidden(true, false, 34.9f, 35.0f, 20.0f) shouldBe false
        nextRiderMountHidden(true, false, 35.0f, 35.0f, 20.0f) shouldBe true
        nextRiderMountHidden(true, true, 20.1f, 35.0f, 20.0f) shouldBe true
        nextRiderMountHidden(true, true, 20.0f, 35.0f, 20.0f) shouldBe false
        nextRiderMountHidden(false, true, 90.0f, 35.0f, 20.0f) shouldBe false
    }

    "giant mounts leave the rider view sooner than regular mounts" {
        riderViewPitchThresholds(1.0, 35.0f, 20.0f) shouldBe (35.0f to 20.0f)
        riderViewPitchThresholds(2.0, 35.0f, 20.0f) shouldBe (10.0f to -5.0f)
        riderViewPitchThresholds(10.0, 35.0f, 20.0f) shouldBe (-10.0f to -25.0f)
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

    "every passive mount effect resolves to its exact Paper potion type" {
        MountAbilityEffect.RESISTANCE.potionEffectType() shouldBe PotionEffectType.RESISTANCE
        MountAbilityEffect.REGENERATION.potionEffectType() shouldBe PotionEffectType.REGENERATION
        MountAbilityEffect.SPEED.potionEffectType() shouldBe PotionEffectType.SPEED
        MountAbilityEffect.SLOW_FALLING.potionEffectType() shouldBe PotionEffectType.SLOW_FALLING
        MountAbilityEffect.STRENGTH.potionEffectType() shouldBe PotionEffectType.STRENGTH
    }

    "trample requires movement and enforces a per-target cooldown" {
        trampleSpeedEligible(currentSpeed = 0.19, maximumSpeed = 1.0, minimumFraction = 0.2) shouldBe false
        trampleSpeedEligible(currentSpeed = 0.2, maximumSpeed = 1.0, minimumFraction = 0.2) shouldBe true
        trampleSpeedEligible(currentSpeed = 1.0, maximumSpeed = 0.0, minimumFraction = 0.2) shouldBe false
        trampleCooldownReady(lastDamageAtMillis = null, nowMillis = 1_000, cooldownMillis = 1_000) shouldBe true
        trampleCooldownReady(lastDamageAtMillis = 500, nowMillis = 1_499, cooldownMillis = 1_000) shouldBe false
        trampleCooldownReady(lastDamageAtMillis = 500, nowMillis = 1_500, cooldownMillis = 1_000) shouldBe true
    }
})
