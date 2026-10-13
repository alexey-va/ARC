package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.math.abs
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector

class StaffSpellMeleeTest : FreeSpec({
    "LANCE sweep endpoints are symmetric and remain within its fixed close-range reach" {
        val forward = Vector(0.0, 0.0, 1.0)
        val left = requireNotNull(staffMeleeSweepEndpoint(forward, 0.0, 55.0, 4.5))
        val center = requireNotNull(staffMeleeSweepEndpoint(forward, 0.5, 55.0, 4.5))
        val right = requireNotNull(staffMeleeSweepEndpoint(forward, 1.0, 55.0, 4.5))

        (abs(left.x + right.x) < 1.0e-9 && abs(left.z - right.z) < 1.0e-9) shouldBe true
        (abs(center.x) < 1.0e-9 && abs(center.z - 4.5) < 1.0e-9) shouldBe true
        listOf(left, center, right).all { it.length() <= 4.5 + 1.0e-9 } shouldBe true
        staffMeleeSweepEndpoint(Vector(), 0.5, 55.0, 4.5) shouldBe null
    }

    "the eight themed primaries make one delayed close contact through cover and hit guards" {
        val variants = listOf(
            StaffSpell.MARK to 4.5,
            StaffSpell.FROST to 4.5,
            StaffSpell.NOVA to 4.5,
            StaffSpell.LUNAR to 4.5,
            StaffSpell.GALE to 4.5,
            StaffSpell.TIDE to 4.5,
            StaffSpell.PRISM to 5.0,
            StaffSpell.OSSUARY to 5.0,
        )
        val settings = staffSettings(maxAreaTargets = 3)

        withStaffHarness(settings = settings) { h ->
            variants.forEach { (spell, range) ->
                val world = h.paper.addSimpleWorld("staff-melee-primary-${spell.id}")
                val player = h.player("staff-melee-primary-${spell.id}", world, spell)
                val rejected = h.zombie(world, x = 0.5, z = 1.7)
                val covered = h.zombie(world, x = 0.5, z = 3.0)
                val clear = h.zombie(world, x = 1.5, z = 3.0)
                val outside = h.zombie(world, x = 0.5, z = range + 0.75)
                for (y in 63..67) world.getBlockAt(0, y, 2).type = Material.STONE

                val attempts = mutableListOf<UUID>()
                val damage = mockk<StaffSpellDamage>()
                every { damage.eligible(any(), any(), any()) } returns true
                every { damage.isCurrent(any(), any()) } returns true
                every { damage.hit(any(), any(), any(), any(), any()) } answers {
                    val target = secondArg<LivingEntity>()
                    attempts += target.uniqueId
                    target.uniqueId != rejected.uniqueId
                }
                val melee = StaffSpellMelee(settings, damage, h.effects,
                    mockk(relaxed = true), StaffSpellTargeting(settings, damage))

                try {
                    melee.cast(player, meleeCast(player), spell, StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                    h.scheduler.tick(3)
                    attempts shouldBe emptyList()

                    h.scheduler.tick(1)
                    attempts shouldBe listOf(rejected.uniqueId, clear.uniqueId)
                    rejected.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
                    clear.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe
                        (spell == StaffSpell.FROST || spell == StaffSpell.NOVA)
                    (covered.uniqueId in attempts) shouldBe false
                    (outside.uniqueId in attempts) shouldBe false

                    h.scheduler.tick(6)
                    attempts shouldBe listOf(rejected.uniqueId, clear.uniqueId)
                } finally {
                    melee.close()
                }
            }
        }
    }

    "LANCE waits for its three-tick blade contact before applying the captured primary tuning" {
        val settings = staffSettings()
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-melee-lance-delay")
            val player = h.player("staff-melee-lance-delay", world, StaffSpell.LANCE)
            val target = h.zombie(world, x = 0.5, z = 3.0)
            val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
            val damage = meleeDamage(hits)
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), StaffSpellTargeting(settings, damage))
            val cast = meleeCast(player)
            try {
                melee.cast(player, cast, StaffSpell.LANCE, StaffSpellTuning(1.2, 7.0, 32)) shouldBe true
                h.scheduler.tick(2)
                hits shouldBe emptyList()

                h.scheduler.tick(1)
                hits.map { it.first.uniqueId } shouldBe listOf(target.uniqueId)
                hits.single().second shouldBe (1.2 to 7.0)
            } finally {
                melee.close()
            }
        }
    }

    "LANCE excludes targets beyond range, behind the swing, outside its front arc, or behind cover" {
        val settings = staffSettings()
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-melee-lance-bounds")
            val player = h.player("staff-melee-lance-bounds", world, StaffSpell.LANCE)
            val covered = h.zombie(world, x = 0.5, z = 3.0)
            val clear = h.zombie(world, x = 2.5, z = 3.0)
            val side = h.zombie(world, x = 4.0, z = 2.5)
            val rear = h.zombie(world, x = 0.5, z = -2.0)
            val outOfRange = h.zombie(world, x = 0.5, z = 6.0)
            for (y in 64..66) world.getBlockAt(0, y, 2).type = Material.STONE

            val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
            val damage = meleeDamage(hits)
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), StaffSpellTargeting(settings, damage))
            try {
                melee.cast(player, meleeCast(player), StaffSpell.LANCE, StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                h.scheduler.tick(3)
                hits.map { it.first.uniqueId } shouldBe listOf(clear.uniqueId)
                hits.any { it.first.uniqueId in setOf(covered.uniqueId, side.uniqueId, rear.uniqueId, outOfRange.uniqueId) } shouldBe false
            } finally {
                melee.close()
            }
        }
    }

    "one cast contacts at most three targets" {
        val settings = staffSettings(lanceTargets = 8)
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-melee-target-cap")
            val player = h.player("staff-melee-target-cap", world, StaffSpell.FAULT)
            val targets = listOf(2.0, 2.6, 3.2, 3.8).map { h.zombie(world, x = 0.5, z = it) }
            val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
            val damage = meleeDamage(hits)
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), StaffSpellTargeting(settings, damage))
            try {
                melee.cast(player, meleeCast(player), StaffSpell.FAULT, StaffSpellTuning(1.0, 6.0, 44)) shouldBe true
                h.scheduler.tick(3)
                hits.map { it.first.uniqueId } shouldBe targets.take(3).map { it.uniqueId }
            } finally {
                melee.close()
            }
        }
    }

    "cancel prevents a delayed FAULT contact" {
        val settings = staffSettings()
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-melee-cancel")
            val player = h.player("staff-melee-cancel", world, StaffSpell.FAULT)
            h.zombie(world, x = 0.5, z = 3.0)
            val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
            val damage = meleeDamage(hits)
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), StaffSpellTargeting(settings, damage))
            try {
                melee.cast(player, meleeCast(player), StaffSpell.FAULT, StaffSpellTuning(1.0, 6.0, 44)) shouldBe true
                h.scheduler.tick(2)
                melee.cancel(player.uniqueId)
                h.scheduler.tick(2)
                hits shouldBe emptyList()
            } finally {
                melee.close()
            }
        }
    }

    "FAULT applies its forward impulse only after damage reports a successful hit" {
        val settings = staffSettings()
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-melee-fault-knockback")
            val player = h.player("staff-melee-fault-knockback", world, StaffSpell.FAULT)
            val denied = h.zombie(world, x = 0.5, z = 2.0)
            val accepted = h.zombie(world, x = 0.5, z = 3.0)
            val hits = mutableListOf<LivingEntity>()
            val damage = mockk<StaffSpellDamage>()
            every { damage.eligible(any(), any(), any()) } returns true
            every { damage.isCurrent(any(), any()) } returns true
            every { damage.hit(any(), any(), any(), any(), any()) } answers {
                val target = secondArg<LivingEntity>()
                hits += target
                target.uniqueId == accepted.uniqueId
            }
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), StaffSpellTargeting(settings, damage))
            try {
                melee.cast(player, meleeCast(player), StaffSpell.FAULT, StaffSpellTuning(1.0, 6.0, 44)) shouldBe true
                h.scheduler.tick(3)
                hits.map { it.uniqueId } shouldBe listOf(denied.uniqueId, accepted.uniqueId)
                abs(denied.velocity.z) shouldBe 0.0
                (abs(accepted.velocity.z - 0.45) < 1.0e-9) shouldBe true
            } finally {
                melee.close()
            }
        }
    }
})

private fun meleeDamage(hits: MutableList<Pair<LivingEntity, Pair<Double, Double>>>): StaffSpellDamage =
    mockk<StaffSpellDamage>().also { damage ->
        every { damage.eligible(any(), any(), any()) } returns true
        every { damage.isCurrent(any(), any()) } returns true
        every { damage.hit(any(), any(), any(), any(), any()) } answers {
            hits += secondArg<LivingEntity>() to (arg<Double>(3) to arg<Double>(4))
            true
        }
    }

private fun meleeCast(player: Player) = StaffSpellCast(
    player.uniqueId, UUID.randomUUID(), skillLevel = 1, criticalHit = null,
    loudStrikesBonus = null, eliteMobsEnabled = false,
)
