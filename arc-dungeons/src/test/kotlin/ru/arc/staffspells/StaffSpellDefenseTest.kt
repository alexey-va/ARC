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
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

@Suppress("DEPRECATION")
class StaffSpellDefenseTest : FreeSpec({
    "airborne NOVA left clicks activate both defenses through the ordinary input route" {
        for (secondary in listOf(false, true)) withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-air-defense-$secondary")
            val player = h.player("staff-air-defense-$secondary", world, StaffSpell.NOVA)
            player.teleport(player.location.add(0.0, 30.0, 0.0))
            player.allowFlight = true
            player.isFlying = true
            player.isSneaking = secondary
            h.controller.start()
            val input = org.bukkit.event.player.PlayerInteractEvent(player,
                org.bukkit.event.block.Action.LEFT_CLICK_AIR, player.inventory.itemInMainHand,
                null, org.bukkit.block.BlockFace.SELF, org.bukkit.inventory.EquipmentSlot.HAND)

            h.controller.onInteract(input)

            io.mockk.verify(exactly = 1) { h.effects.defend(player, StaffSpell.NOVA, any(), secondary) }
            input.useItemInHand() shouldBe org.bukkit.event.Event.Result.DENY
            val attacker = h.zombie(world, .5, 3.0).apply { teleport(location.add(0.0, 30.0, 0.0)) }
            val incoming = attack(attacker, player)
            h.controller.defense.onDamage(incoming)
            incoming.isCancelled shouldBe true
        }
    }

    "normal LMB guard blocks frontal attacks within its hit budget, leaves the rear open, and keeps its cooldown" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-arc")
            val player = h.player("staff-defense-arc", world, StaffSpell.FAULT)
            val front = h.zombie(world, x = 0.5, z = 3.0)
            val rear = h.zombie(world, x = 0.5, z = -2.0)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true), mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            var closed = false
            try {
                defense.activate(player, StaffSpell.FAULT, cast, allAround = false) shouldBe true
                h.cooldowns.remainingTicks(player.uniqueId) shouldBe 0L
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
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true), mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            try {
                player.absorptionAmount = 3.0
                defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe true
                defense.activate(player, StaffSpell.EMBER, cast, allAround = true) shouldBe false

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

                h.scheduler.tick(498)
                defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe false
                h.scheduler.tick(2)
                defense.activate(player, StaffSpell.FAULT, cast, allAround = true) shouldBe true
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
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true), mockk(relaxed = true))
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

    "defense displays stay bounded, finite, stable, and transparent at the center" {
        StaffSpell.entries.forEach { spell ->
            val heavy = spell in setOf(StaffSpell.FAULT, StaffSpell.GRAVITY, StaffSpell.OSSUARY,
                StaffSpell.NOVA, StaffSpell.NECROMANCER)
            listOf(false, true).forEach { allAround ->
                val duration = if (allAround) 60 else if (heavy) 32 else 24
                val expectedParts = if (allAround) 40 else 12
                val expectedPanes = if (allAround) 12 else 3
                val initial = staffDefenseParts(spell, 0, duration, allAround)
                val active = staffDefenseParts(spell, 4, duration, allAround)
                val impact = staffDefenseParts(spell, 4, duration, allAround, impactAge = 0)

                initial.size shouldBe expectedParts
                (initial.size <= StaffSpellDisplayEffects.MAX_PARTS) shouldBe true
                initial.all { part ->
                    listOf(part.center.x, part.center.y, part.center.z,
                        part.scale.x, part.scale.y, part.scale.z,
                        part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
                        part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
                } shouldBe true

                val identities = initial.map { it.identity }
                identities.all { !it.isNullOrBlank() } shouldBe true
                identities.toSet().size shouldBe expectedParts
                active.map { it.identity } shouldBe identities
                impact.map { it.identity } shouldBe identities

                val panes = initial.filter { it.identity?.contains("-pane-") == true }
                panes.size shouldBe expectedPanes
                panes.all { it.material.name.endsWith("_STAINED_GLASS") } shouldBe true
                initial.all { it.center.x * it.center.x + it.center.z * it.center.z > 1.0f } shouldBe true
                (0 until duration).all { staffDefenseParticles(spell, it, allAround).isEmpty() } shouldBe true
            }
        }
    }

    "normal guards keep every opaque part out of standing and sneak reticles for their full lifetime" {
        StaffSpell.entries.forEach { spell ->
            val heavy = spell in setOf(StaffSpell.FAULT, StaffSpell.GRAVITY, StaffSpell.OSSUARY,
                StaffSpell.NOVA, StaffSpell.NECROMANCER)
            val duration = if (heavy) 32 else 24
            (0 until duration).forEach { age ->
                val parts = staffDefenseParts(spell, age, duration, allAround = false,
                    strength = if (age < 8) 1.0 else .5, impactAge = age - 8)
                listOf(1.62f, 1.27f).forEach { eyeHeight ->
                    parts.filterNot { it.material.name.endsWith("_STAINED_GLASS") }
                        .none { staffPartIntersectsReticle(it, eyeHeight) } shouldBe true
                }
            }
        }
    }

    "Shift+LMB shield expires after 400 ticks and recovery blocks refresh until 500" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("staff-defense-shield-expiry")
            val player = h.player("staff-defense-shield-expiry", world, StaffSpell.FROST)
            val front = h.zombie(world, x = 0.5, z = 3.0)
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true), mockk(relaxed = true))
            defense.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            try {
                defense.activate(player, StaffSpell.FROST, cast, allAround = true) shouldBe true
                staffDefenseParticles(StaffSpell.FROST, age = 0, allAround = true).isEmpty() shouldBe true
                val impactSparks = staffDefenseParticles(StaffSpell.FROST, 0, true, impact = true, depleted = true)
                    .count { it.particle == Particle.ELECTRIC_SPARK }
                (impactSparks > 0) shouldBe true

                h.scheduler.tick(200)
                defense.activate(player, StaffSpell.FROST, cast, allAround = true) shouldBe false
                h.scheduler.tick(198)
                val activeHit = attack(front, player, rawDamage = 1.0)
                defense.onDamage(activeHit)
                activeHit.isCancelled shouldBe true

                h.scheduler.tick(2)
                val expiredHit = attack(front, player, rawDamage = 4.0)
                defense.onDamage(expiredHit)
                expiredHit.isCancelled shouldBe false
                expiredHit.finalDamage shouldBe 4.0
                defense.activate(player, StaffSpell.FROST, cast, allAround = true) shouldBe false
                player.inventory.setItemInMainHand(h.config.item(StaffSpell.EMBER))
                defense.activate(player, StaffSpell.EMBER, cast, allAround = true) shouldBe false
                player.inventory.setItemInMainHand(h.config.item(StaffSpell.FROST))
                h.scheduler.tick(98)
                defense.activate(player, StaffSpell.FROST, cast, allAround = true) shouldBe false
                h.scheduler.tick(2)
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
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true), mockk(relaxed = true))
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
            val defense = StaffSpellDefense(h.config, damage, mockk(relaxed = true), mockk(relaxed = true))
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

private fun staffPartIntersectsReticle(part: StaffDisplayPart, eyeHeight: Float): Boolean {
    val inverse = Quaternionf(part.rotation).invert()
    val origin = inverse.transform(Vector3f(-part.center.x, eyeHeight - part.center.y, -part.center.z))
    val direction = inverse.transform(Vector3f(0f, 0f, 1f))
    val rayOrigin = floatArrayOf(origin.x, origin.y, origin.z)
    val rayDirection = floatArrayOf(direction.x, direction.y, direction.z)
    val halfExtent = floatArrayOf(part.scale.x / 2f, part.scale.y / 2f, part.scale.z / 2f)
    var near = 0.0
    var far = Double.POSITIVE_INFINITY
    for (axis in 0..2) {
        val component = rayDirection[axis].toDouble()
        val offset = rayOrigin[axis].toDouble()
        val extent = halfExtent[axis].toDouble()
        if (abs(component) < 1e-6) {
            if (abs(offset) > extent) return false
        } else {
            val first = (-extent - offset) / component
            val second = (extent - offset) / component
            near = maxOf(near, minOf(first, second))
            far = minOf(far, maxOf(first, second))
            if (near > far) return false
        }
    }
    return far >= 0.0
}

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
