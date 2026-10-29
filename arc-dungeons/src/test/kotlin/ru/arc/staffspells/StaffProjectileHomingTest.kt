package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import ru.arc.staffspells.api.StaffSpellDuelContext
import java.util.UUID

class StaffProjectileHomingTest : FreeSpec({
    listOf(StaffSpell.CHAIN, StaffSpell.EMBER, StaffSpell.ICICLE, StaffSpell.TIDE,
        StaffSpell.GALE, StaffSpell.GRAVITY).forEach { spell ->
        "$spell acquires a near-missed sprinting player while the caster keeps the launch aim" {
            val damage = StaffSpellDamage(canAttackPlayer = { _, _ -> true }, notifyDeniedPlayer = { _, _ -> })
            withStaffHarness(settings = staffSettings(range = 56.0), damageBridge = damage) { h ->
                val world = h.paper.addSimpleWorld("homing-${spell.id}").apply { pvp = true }
                val caster = h.player("caster", world, spell)
                val target = h.player("runner", world, StaffSpell.CHAIN).apply {
                    teleport(Location(world, 1.5, 64.0, 26.0))
                    isSprinting = true
                }
                if (spell == StaffSpell.GRAVITY)
                    for (x in -1..1) for (z in 0..28) world.getBlockAt(x, 63, z).type = Material.STONE
                val aim = caster.eyeLocation.direction.clone()
                h.controller.start()
                launchHomingSpell(h, caster, spell)

                repeat(60) {
                    target.teleport(target.location.add(.28, 0.0, 0.0))
                    h.scheduler.tick(1)
                }

                h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
                caster.eyeLocation.direction shouldBe aim
                val expected = if (spell == StaffSpell.GRAVITY) 1.35 to 6.0 * 1.35 else 1.45 to 8.7
                h.damageScales shouldBe listOf(expected)
                target.health shouldBe 20.0 - expected.second
            }
        }
    }

    "a directly aimed player wins over a nearer soft candidate" {
        withStaffHarness(targetEligible = { caster, target -> caster.uniqueId != target.uniqueId }) { h ->
            val world = h.paper.addSimpleWorld("homing-direct")
            val caster = h.player("caster", world, StaffSpell.CHAIN)
            val direct = h.player("direct", world, StaffSpell.CHAIN).apply {
                teleport(Location(world, .5, 64.0, 20.0))
            }
            h.player("nearby", world, StaffSpell.CHAIN).teleport(Location(world, 1.6, 64.0, 12.0))
            h.controller.start()
            launchHomingSpell(h, caster, StaffSpell.CHAIN)
            h.scheduler.tick(30)
            h.hits.map { it.uniqueId } shouldBe listOf(direct.uniqueId)
        }
    }

    "a directly aimed mob keeps priority and moving mobs keep their original unguided lightning" {
        withStaffHarness(settings = staffSettings(range = 56.0),
            targetEligible = { caster, target -> caster.uniqueId != target.uniqueId }) { h ->
            val world = h.paper.addSimpleWorld("homing-pve")
            val caster = h.player("caster", world, StaffSpell.CHAIN)
            val mob = h.zombie(world, .5, 26.0)
            h.player("neighbor", world, StaffSpell.CHAIN).teleport(Location(world, 1.6, 64.0, 26.0))
            h.controller.start()
            launchHomingSpell(h, caster, StaffSpell.CHAIN)
            repeat(60) {
                mob.teleport(mob.location.add(.28, 0.0, 0.0))
                h.scheduler.tick(1)
            }
            h.hits shouldBe emptyList()
            h.trailMoves.flatMap { it.points }.all { kotlin.math.abs(it.x - .5) < 1e-8 } shouldBe true
        }
    }

    "a better soft mob candidate is never replaced by a worse player lock" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("homing-mixed-soft").apply { pvp = true }
            val caster = h.player("caster", world, StaffSpell.CHAIN)
            h.zombie(world, 1.3, 26.0)
            h.player("neighbor", world, StaffSpell.CHAIN).teleport(Location(world, 2.0, 64.0, 26.0))
            val targeting = StaffSpellTargeting(staffSettings(range = 56.0),
                StaffSpellDamage(canAttackPlayer = { _, _ -> true }, notifyDeniedPlayer = { _, _ -> }))
            targeting.homingTarget(caster, StaffSpell.CHAIN, null) shouldBe null
            val assisted = targeting.assistedDirection(caster, StaffSpell.CHAIN, null)
            (assisted.x > 0.0 && assisted.x / assisted.z < .8 / 25.5) shouldBe true
        }
    }

    "disabled zero-strength and excluded aim assist leave moving player shots unguided" {
        listOf(StaffAimAssistSettings(enabled = false), StaffAimAssistSettings(strength = 0.0),
            StaffAimAssistSettings(excludedSpells = setOf(StaffSpell.CHAIN.id))).forEach { assist ->
            withStaffHarness(settings = staffSettings(range = 56.0).copy(aimAssist = assist),
                targetEligible = { caster, target -> caster.uniqueId != target.uniqueId }) { h ->
                val world = h.paper.addSimpleWorld("homing-disabled")
                val caster = h.player("caster", world, StaffSpell.CHAIN)
                val target = h.player("runner", world, StaffSpell.CHAIN).apply {
                    teleport(Location(world, .5, 64.0, 26.0))
                }
                h.controller.start()
                launchHomingSpell(h, caster, StaffSpell.CHAIN)
                repeat(30) {
                    target.teleport(target.location.add(.28, 0.0, 0.0))
                    h.scheduler.tick(1)
                }
                h.hits shouldBe emptyList()
                h.trailMoves.flatMap { it.points }.all { kotlin.math.abs(it.x - .5) < 1e-8 } shouldBe true
            }
        }
    }

    "an empty-space launch never acquires a later nearby or behind-caster player" {
        withStaffHarness(settings = staffSettings(range = 32.0),
            targetEligible = { caster, target -> caster.uniqueId != target.uniqueId }) { h ->
            val world = h.paper.addSimpleWorld("homing-empty")
            val caster = h.player("caster", world, StaffSpell.CHAIN)
            val target = h.player("runner", world, StaffSpell.CHAIN).apply {
                teleport(Location(world, .5, 64.0, -8.0))
            }
            h.controller.start()
            launchHomingSpell(h, caster, StaffSpell.CHAIN)
            h.scheduler.tick(2)
            target.teleport(Location(world, 1.8, 64.0, 26.0))
            h.scheduler.tick(60)
            h.hits shouldBe emptyList()
            h.trailMoves.flatMap { it.points }.all { kotlin.math.abs(it.x - .5) < 1e-8 } shouldBe true
            h.trailMoves.last().points.zipWithNext().sumOf { (from, to) -> from.distance(to) } shouldBe 32.0
            h.finishedTrails.size shouldBe 1
        }
    }

    "lost player locks never turn back or exceed their original distance" {
        for (destination in listOf(-8.0, 80.0)) withStaffHarness(settings = staffSettings(range = 32.0),
            targetEligible = { caster, target -> caster.uniqueId != target.uniqueId }) { h ->
            val world = h.paper.addSimpleWorld("homing-budget")
            val caster = h.player("caster", world, StaffSpell.CHAIN)
            val target = h.player("runner", world, StaffSpell.CHAIN).apply {
                teleport(Location(world, .5, 64.0, 26.0))
            }
            h.controller.start()
            launchHomingSpell(h, caster, StaffSpell.CHAIN)
            h.scheduler.tick(1)
            target.teleport(Location(world, .5, 64.0, destination))
            h.scheduler.tick(60)
            h.hits shouldBe emptyList()
            val path = h.trailMoves.last().points
            path.zipWithNext().all { (from, to) -> to.z > from.z } shouldBe true
            (path.zipWithNext().sumOf { (from, to) -> from.distance(to) } <= 32.000001) shouldBe true
            h.finishedTrails.size shouldBe 1
        }
    }

    "new cover or protection during flight prevents player damage and clears the lock" {
        for (loss in listOf("wall", "shield", "observer", "protection", "stale")) {
            var allowed = true
            var current = true
            val damage = StaffSpellDamage(canAttackPlayer = { _, _ -> allowed }, notifyDeniedPlayer = { _, _ -> })
            withStaffHarness(settings = staffSettings(range = 56.0), damageBridge = damage,
                currentCast = { _, _ -> current }) { h ->
                val world = h.paper.addSimpleWorld("homing-$loss").apply { pvp = true }
                val caster = h.player("caster", world, StaffSpell.EMBER)
                val target = h.player("runner", world, StaffSpell.CHAIN).apply {
                    teleport(Location(world, .5, 64.0, 26.0))
                }
                h.controller.start()
                launchHomingSpell(h, caster, StaffSpell.EMBER)
                h.scheduler.tick(3)
                when (loss) {
                    "protection" -> allowed = false
                    "stale" -> current = false
                    "shield" -> (StaffMagicBarriers.register(java.util.UUID.randomUUID(),
                        Location(world, .5, 63.0, 10.0), Vector(0.0, 0.0, 1.0), 4.0, 4.0) != null) shouldBe true
                    else -> for (x in -5..5) for (y in 63..69)
                        world.getBlockAt(x, y, if (loss == "observer") 2 else 10).type = Material.STONE
                }
                repeat(50) {
                    target.teleport(target.location.add(.28, 0.0, 0.0))
                    h.scheduler.tick(1)
                }
                h.hits shouldBe emptyList()
                target.health shouldBe 20.0
                h.movedEffects.size.let { count ->
                    h.scheduler.tick(60)
                    h.movedEffects.size shouldBe count
                }
            }
        }
    }

    "duel locks track only the authorized moving opponent and stop when the captured round changes" {
        for (nextRound in listOf(false, true)) {
            val authorization = DuelSpellAuthorization(UUID.randomUUID(), StaffSpellDuelContext(UUID.randomUUID(), 1, 1.25))
            var currentAuthorization = authorization
            var casterId: UUID? = null
            var opponentId: UUID? = null
            val access = mockk<StaffSpellDuelAccess>()
            every { access.inputAllowed(any()) } returns true
            every { access.context(any()) } answers {
                currentAuthorization.takeIf { firstArg<Player>().uniqueId == casterId }
            }
            every { access.isCurrent(any(), any()) } answers {
                firstArg<DuelSpellAuthorization>() == currentAuthorization && secondArg<Player>().uniqueId == casterId
            }
            every { access.canHit(any(), any(), any()) } answers {
                firstArg<DuelSpellAuthorization>() == currentAuthorization && thirdArg<Player>().uniqueId == opponentId
            }
            val settings = staffSettings(range = 56.0)
            withStaffHarness(settings = settings, damageBridge = StaffSpellDamage(access)) { h ->
                val world = h.paper.addSimpleWorld("homing-duel")
                val caster = h.player("caster", world, StaffSpell.CHAIN).also {
                    casterId = it.uniqueId
                    it.inventory.setItemInMainHand(h.config.duelItem(StaffSpell.CHAIN))
                }
                val opponent = h.player("opponent", world, StaffSpell.CHAIN).apply {
                    teleport(Location(world, 1.5, 64.0, 26.0))
                    opponentId = uniqueId
                }
                val teammate = h.player("teammate", world, StaffSpell.CHAIN).apply {
                    teleport(Location(world, .5, 64.0, 12.0))
                }
                h.controller.start()
                launchHomingSpell(h, caster, StaffSpell.CHAIN)
                h.scheduler.tick(1)
                if (nextRound) currentAuthorization = authorization.copy(context = StaffSpellDuelContext(
                    authorization.context.matchId(), 2, authorization.context.damageMultiplier()))
                repeat(40) {
                    opponent.teleport(opponent.location.add(.28, 0.0, 0.0))
                    h.scheduler.tick(1)
                }

                teammate.health shouldBe 20.0
                if (nextRound) {
                    h.hits shouldBe emptyList()
                    opponent.health shouldBe 20.0
                } else {
                    h.hits.map { it.uniqueId } shouldBe listOf(opponent.uniqueId)
                    opponent.health shouldBe 20.0 - settings.tuningFor(StaffSpell.CHAIN, duel = true).vanillaDamage *
                        1.45 * authorization.context.damageMultiplier()
                }
            }
        }
    }
})

private fun launchHomingSpell(h: StaffHarness, caster: Player, spell: StaffSpell) {
    if (spell == StaffSpell.GRAVITY) {
        h.controller.beginChannel(caster, spell) shouldBe true
        h.controller.releaseChannel(caster)
    } else h.controller.cast(caster, if (spell == StaffSpell.GALE) StaffCastInput.SECONDARY
        else StaffCastInput.PRIMARY, .6)
}
