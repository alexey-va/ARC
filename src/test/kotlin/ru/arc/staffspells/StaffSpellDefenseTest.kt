package ru.arc.staffspells

import com.google.common.base.Function
import io.mockk.every
import io.mockk.mockk
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier
import ru.arc.util.CooldownManager
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

@Suppress("DEPRECATION")
class StaffSpellDefenseTest : FreeSpec({
    "normal LMB guard blocks frontal attacks within its hit budget, leaves the rear open, and keeps its cooldown" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-arc")
            val player = h.player("staff-defense-arc", world, StaffSpell.FAULT)
            val front = h.zombie(world, x = 0.5, z = 3.0)
            val rear = h.zombie(world, x = 0.5, z = -2.0)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            var closed = false
            try {
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe true
                CooldownManager.isOnCooldown(player.uniqueId, "staff-spells") shouldBe false
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe false

                val rearHit = attack(rear, player)
                defense.onDamage(rearHit)
                rearHit.isCancelled shouldBe false

                repeat(3) {
                    val frontalHit = attack(front, player)
                    defense.onDamage(frontalHit)
                    frontalHit.isCancelled shouldBe true
                }
                val overBudgetHit = attack(front, player)
                defense.onDamage(overBudgetHit)
                overBudgetHit.isCancelled shouldBe false

                h.scheduler.tick(80)
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe true
                h.scheduler.tick(32)
                val expiredHit = attack(front, player)
                defense.onDamage(expiredHit)
                expiredHit.isCancelled shouldBe false

                h.scheduler.tick(80)
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe true
                defense.close()
                closed = true
                val closedHit = attack(front, player)
                defense.onDamage(closedHit)
                closedHit.isCancelled shouldBe false
            } finally {
                if (!closed) defense.close()
            }
        }
    }

    "Shift+LMB shield spends pre-absorption damage up to its budget and passes partial overflow" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-shield")
            val player = h.player("staff-defense-shield", world, StaffSpell.FAULT)
            val rear = h.zombie(world, x = 0.5, z = -2.0)
            val front = h.zombie(world, x = 0.5, z = 3.0)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            try {
                player.absorptionAmount = 3.0
                defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe true
                defense.activate(player, StaffSpell.EMBER, cast, allAround = false) shouldBe false

                val cancelled = attack(rear, player, rawDamage = 20.0, armorReduction = .5)
                cancelled.isCancelled = true
                defense.onDamage(cancelled)
                cancelled.finalDamage shouldBe 10.0

                val overflow = attack(rear, player, rawDamage = 20.0, armorReduction = .5)
                defense.onDamage(overflow)
                overflow.isCancelled shouldBe false
                abs(overflow.finalDamage - 2.0) shouldBeLessThan .00001
                player.absorptionAmount shouldBe 3.0

                val afterDepletion = attack(front, player, rawDamage = 20.0, armorReduction = .5)
                defense.onDamage(afterDepletion)
                afterDepletion.isCancelled shouldBe false
                afterDepletion.finalDamage shouldBe 10.0

                h.scheduler.tick(158)
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe false
                h.scheduler.tick(2)
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe true
            } finally {
                defense.close()
            }
        }
    }

    "Shift+LMB shield absorbs environmental damage and attacks from non-living sources" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-shield-generic-damage")
            val player = h.player("staff-defense-shield-generic-damage", world, StaffSpell.FAULT)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            try {
                defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe true

                val environmentalHit = EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 6.0)
                defense.onDamage(environmentalHit)
                environmentalHit.isCancelled shouldBe true

                val tnt = mockk<TNTPrimed>()
                val explosion = EntityDamageByEntityEvent(
                    tnt, player, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, 4.0,
                )
                defense.onDamage(explosion)
                explosion.isCancelled shouldBe false
                abs(explosion.finalDamage - 2.0) shouldBeLessThan .00001

                val afterDepletion = EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, 1.0)
                defense.onDamage(afterDepletion)
                afterDepletion.isCancelled shouldBe false
                afterDepletion.finalDamage shouldBe 1.0
            } finally {
                defense.close()
            }
        }
    }

    "Shift+LMB shield expires after 60 ticks and its spherical arcs leave the forward view open" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-shield-expiry")
            val player = h.player("staff-defense-shield-expiry", world, StaffSpell.FROST)
            val front = h.zombie(world, x = 0.5, z = 3.0)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            try {
                defense.activate(player, StaffSpell.FROST, cast, allAround = true) shouldBe true
                val shell = staffDefenseParticles(StaffSpell.FROST, age = 0, allAround = true)
                shell.isNotEmpty() shouldBe true
                shell.all {
                    abs(sqrt(it.offset.x * it.offset.x + it.offset.z * it.offset.z +
                        (it.offset.y - .95) * (it.offset.y - .95)) - 1.48) < .000001
                } shouldBe true
                shell.none { it.offset.z > 1.48 * .78 && abs(it.offset.y - .95) < .7 } shouldBe true
                val impactSparks = staffDefenseParticles(StaffSpell.FROST, 0, true, impact = true, depleted = true)
                    .count { it.particle == Particle.ELECTRIC_SPARK }
                val activeSparks = staffDefenseParticles(StaffSpell.FROST, 0, true)
                    .count { it.particle == Particle.ELECTRIC_SPARK }
                (impactSparks > activeSparks) shouldBe true

                h.scheduler.tick(58)
                val activeHit = attack(front, player, rawDamage = 1.0)
                defense.onDamage(activeHit)
                activeHit.isCancelled shouldBe true

                h.scheduler.tick(2)
                val expiredHit = attack(front, player, rawDamage = 4.0)
                defense.onDamage(expiredHit)
                expiredHit.isCancelled shouldBe false
                expiredHit.finalDamage shouldBe 4.0
                defense.activate(player, StaffSpell.FROST, cast, allAround = true) shouldBe false
                h.scheduler.tick(100)
                defense.activate(player, StaffSpell.FROST, cast, allAround = true) shouldBe true
            } finally {
                defense.close()
            }
        }
    }

    "partial shield overflow is still reduced by vanilla absorption" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-shield-native-absorption-partial")
            val player = h.player("staff-defense-shield-native-absorption-partial", world, StaffSpell.FAULT)
            val enemy = h.zombie(world, x = 0.5, z = -2.0)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            try {
                player.absorptionAmount = 4.0
                defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe true

                // Post-armor damage is 10: the shield takes 8, then vanilla absorption takes the 2 overflow.
                val hit = attack(enemy, player, rawDamage = 20.0, armorReduction = .5, vanillaAbsorption = 4.0)
                defense.onDamage(hit)

                hit.isCancelled shouldBe false
                abs(hit.finalDamage) shouldBeLessThan .00001
                abs(hit.getDamage(DamageModifier.ABSORPTION) + 2.0) shouldBeLessThan .00001
                abs(hit.finalDamage - hit.getDamage(DamageModifier.ABSORPTION) - 2.0) shouldBeLessThan .00001
                player.absorptionAmount shouldBe 4.0
                val depleted = attack(enemy, player, rawDamage = 1.0)
                defense.onDamage(depleted)
                depleted.isCancelled shouldBe false
                depleted.finalDamage shouldBe 1.0
            } finally {
                defense.close()
            }
        }
    }

    "a natively absorbed hit spends its full pre-absorption amount from shield capacity" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-shield-native-absorption-full")
            val player = h.player("staff-defense-shield-native-absorption-full", world, StaffSpell.FAULT)
            val enemy = h.zombie(world, x = 0.5, z = -2.0)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            try {
                player.absorptionAmount = 4.0
                defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe true

                // Post-armor damage is 6, of which native absorption covers 4. The cancelled hit must still cost 6.
                val fullyShielded = attack(enemy, player, rawDamage = 12.0, armorReduction = .5,
                    vanillaAbsorption = 4.0)
                defense.onDamage(fullyShielded)
                fullyShielded.isCancelled shouldBe true
                abs(fullyShielded.finalDamage - 2.0) shouldBeLessThan .00001
                player.absorptionAmount shouldBe 4.0

                // Only 2 shield capacity remains, so 1 of this 3-damage hit passes through.
                val overflow = attack(enemy, player, rawDamage = 3.0)
                defense.onDamage(overflow)
                overflow.isCancelled shouldBe false
                abs(overflow.finalDamage - 1.0) shouldBeLessThan .00001
            } finally {
                defense.close()
            }
        }
    }
})

@Suppress("DEPRECATION")
private fun attack(
    damager: LivingEntity,
    player: org.bukkit.entity.Player,
    rawDamage: Double = 4.0,
    armorReduction: Double = 0.0,
    vanillaAbsorption: Double = 0.0,
): EntityDamageByEntityEvent {
    if (armorReduction == 0.0 && vanillaAbsorption == 0.0) return EntityDamageByEntityEvent(
        damager, player, EntityDamageEvent.DamageCause.ENTITY_ATTACK, rawDamage)
    val modifiers = linkedMapOf(DamageModifier.BASE to rawDamage)
    val functions = linkedMapOf(DamageModifier.BASE to Function<Double, Double> { 0.0 })
    if (armorReduction != 0.0) {
        modifiers[DamageModifier.ARMOR] = -rawDamage * armorReduction
        functions[DamageModifier.ARMOR] = Function<Double, Double> { base -> -base * armorReduction }
    }
    if (vanillaAbsorption != 0.0) {
        modifiers[DamageModifier.ABSORPTION] = -min(vanillaAbsorption, rawDamage * (1.0 - armorReduction))
        functions[DamageModifier.ABSORPTION] = Function<Double, Double> { base -> -min(vanillaAbsorption, base) }
    }
    return EntityDamageByEntityEvent(damager, player, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
        modifiers, functions)
}
