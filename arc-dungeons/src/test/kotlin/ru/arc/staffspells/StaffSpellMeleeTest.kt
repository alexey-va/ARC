package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.math.abs
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector

class StaffSpellMeleeTest : FreeSpec({
    "empty-air frontal casts have bounded launch contact and tail cues with no per-frame sound" {
        withStaffHarness { h ->
            listOf(StaffSpell.FROST, StaffSpell.FAULT, StaffSpell.LUNAR).forEach { spell ->
                val world = h.paper.addSimpleWorld("staff-frontal-audio-${spell.id}")
                val player = h.player("staff-frontal-audio-${spell.id}", world, spell)
                val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
                val damage = meleeDamage(hits)
                val sounds = mutableListOf<Sound>()
                val contactLayers = 2
                val visuals = mockk<StaffSpellVisuals>(relaxed = true)
                every { visuals.withOwner(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
                every { visuals.cue(any(), any(), any(), any()) } answers { sounds += secondArg<Sound>() }
                val melee = StaffSpellMelee(staffSettings(), damage, h.effects, visuals,
                    StaffSpellTargeting(staffSettings(), damage))
                try {
                    melee.cast(player, meleeCast(player), spell, StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                    sounds.size shouldBe 1
                    h.scheduler.tick(STAFF_FRONTAL_CONTACT_TICK - 1)
                    sounds.size shouldBe 1
                    h.scheduler.tick(1)
                    sounds.size shouldBe 1 + contactLayers
                    h.scheduler.tick((staffMeleeDuration(spell) - 2) - STAFF_FRONTAL_CONTACT_TICK)
                    sounds.size shouldBe 2 + contactLayers
                    h.scheduler.tick(30)
                    sounds.size shouldBe 2 + contactLayers
                    hits shouldBe emptyList()
                } finally { melee.close() }
            }
        }
    }

    "only FROST, FAULT, and LUNAR own the broad primary contact" {
        val frontal = setOf(StaffSpell.FROST, StaffSpell.FAULT, StaffSpell.LUNAR)

        frontal.forEach { staffMeleeReach(it) shouldBe 7.0 }
        StaffSpell.entries.filterNot { it in frontal }.forEach { staffMeleeReach(it) shouldBe null }
        STAFF_FRONTAL_CONTACT_TICK shouldBe 6L
    }

    "the three frontal primaries reach seven blocks and a wide arc, contact at tick six, and hit once" {
        val spells = listOf(StaffSpell.FROST, StaffSpell.FAULT, StaffSpell.LUNAR)
        val settings = staffSettings(maxAreaTargets = 8)

        withStaffHarness(settings = settings) { h ->
            spells.forEach { spell ->
                val world = h.paper.addSimpleWorld("staff-frontal-area-${spell.id}")
                val player = h.player("staff-frontal-area-${spell.id}", world, spell)
                val nearReachLimit = h.zombie(world, x = 0.5, z = 7.0)
                val wideArc = h.zombie(world, x = 4.5, z = 5.0)
                val outsideArc = h.zombie(world, x = 6.5, z = 4.0)
                val outsideRange = h.zombie(world, x = 0.5, z = 7.8)
                val verticalEdge = h.zombie(world, x = 0.5, z = 4.0).apply {
                    teleport(Location(world, 0.5, 67.0, 4.0))
                }
                val outsideVertical = h.zombie(world, x = 0.5, z = 4.0).apply {
                    teleport(Location(world, 0.5, 68.0, 4.0))
                }
                val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
                val damage = meleeDamage(hits)
                val melee = StaffSpellMelee(settings, damage, h.effects,
                    mockk(relaxed = true), StaffSpellTargeting(settings, damage))

                try {
                    melee.cast(player, meleeCast(player), spell, StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                    h.scheduler.tick(5)
                    hits shouldBe emptyList()

                    h.scheduler.tick(1)
                    hits.map { it.first.uniqueId }.toSet() shouldBe
                        setOf(nearReachLimit.uniqueId, wideArc.uniqueId, verticalEdge.uniqueId)
                    hits.any { it.first.uniqueId in setOf(outsideArc.uniqueId, outsideRange.uniqueId, outsideVertical.uniqueId) } shouldBe false

                    h.scheduler.tick(STAFF_FRONTAL_DURATION_TICKS.toLong())
                    hits.map { it.first.uniqueId }.distinct().size shouldBe 3
                } finally {
                    melee.close()
                }
            }
        }
    }

    "contact uses the captured origin and yaw, live arrivals, and current-player cover" {
        val settings = staffSettings()
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-frontal-live-contact")
            val player = h.player("staff-frontal-live-contact", world, StaffSpell.FROST)
            val coveredFromCurrentPosition = h.zombie(world, x = 0.5, z = 6.0)
            val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
            val damage = meleeDamage(hits)
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), StaffSpellTargeting(settings, damage))

            try {
                melee.cast(player, meleeCast(player), StaffSpell.FROST, StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                h.scheduler.tick(5)

                player.teleport(Location(world, 12.5, 64.0, 0.5).apply {
                    setDirection(Vector(0.0, 0.0, -1.0))
                })
                val lateArrival = h.zombie(world, x = 4.0, z = 6.0)
                for (y in 64..66) world.getBlockAt(5, y, 3).type = Material.STONE
                h.scheduler.tick(1)

                hits.map { it.first.uniqueId } shouldBe listOf(lateArrival.uniqueId)
                (coveredFromCurrentPosition.uniqueId in hits.map { it.first.uniqueId }) shouldBe false
            } finally {
                melee.close()
            }
        }
    }

    "a frontal cast attempts at most three nearest targets even when its setting is higher" {
        val settings = staffSettings(maxAreaTargets = 8)
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-frontal-target-cap")
            val player = h.player("staff-frontal-target-cap", world, StaffSpell.FAULT)
            val targets = listOf(2.0, 3.0, 4.0, 5.0).map { h.zombie(world, x = 0.5, z = it) }
            val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
            val damage = meleeDamage(hits)
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), StaffSpellTargeting(settings, damage))

            try {
                melee.cast(player, meleeCast(player), StaffSpell.FAULT, StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                h.scheduler.tick(STAFF_FRONTAL_CONTACT_TICK)

                hits.map { it.first.uniqueId } shouldBe targets.take(3).map { it.uniqueId }
            } finally {
                melee.close()
            }
        }
    }

    "FROST and FAULT apply crowd control only after accepted damage; LUNAR has none" {
        val expectedPush = mapOf(StaffSpell.FAULT to 0.42)
        listOf(StaffSpell.FROST, StaffSpell.FAULT, StaffSpell.LUNAR).forEach { spell ->
            val settings = staffSettings()
            withStaffHarness(settings = settings) { h ->
                val world = h.paper.addSimpleWorld("staff-frontal-hit-effects-${spell.id}")
                val player = h.player("staff-frontal-hit-effects-${spell.id}", world, spell)
                val denied = h.zombie(world, x = 0.5, z = 2.0)
                val accepted = h.zombie(world, x = 0.5, z = 3.0)
                val attempts = mutableListOf<UUID>()
                val damage = mockk<StaffSpellDamage>()
                every { damage.eligible(any(), any(), any()) } returns true
                every { damage.isCurrent(any(), any()) } returns true
                every { damage.hit(any(), any(), any(), any(), any()) } answers {
                    val target = secondArg<LivingEntity>()
                    attempts += target.uniqueId
                    target.uniqueId == accepted.uniqueId
                }
                val melee = StaffSpellMelee(settings, damage, h.effects,
                    mockk(relaxed = true), StaffSpellTargeting(settings, damage))

                try {
                    melee.cast(player, meleeCast(player), spell, StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                    h.scheduler.tick(STAFF_FRONTAL_CONTACT_TICK)

                    attempts shouldBe listOf(denied.uniqueId, accepted.uniqueId)
                    when (spell) {
                        StaffSpell.FROST -> {
                            denied.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
                            val slow = requireNotNull(accepted.getPotionEffect(PotionEffectType.SLOWNESS))
                            slow.duration shouldBe settings.frostSlowTicks
                            slow.amplifier shouldBe 1
                        }
                        StaffSpell.FAULT -> {
                            abs(denied.velocity.z) shouldBe 0.0
                            (abs(accepted.velocity.z - requireNotNull(expectedPush[spell])) < 1.0e-9) shouldBe true
                        }
                        StaffSpell.LUNAR -> {
                            denied.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
                            accepted.hasPotionEffect(PotionEffectType.SLOWNESS) shouldBe false
                            abs(denied.velocity.z) shouldBe 0.0
                            abs(accepted.velocity.z) shouldBe 0.0
                        }
                        else -> error("Unexpected frontal spell: $spell")
                    }
                } finally {
                    melee.close()
                }
            }
        }
    }

    "cancel prevents delayed frontal contact" {
        val settings = staffSettings()
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-frontal-cancel")
            val player = h.player("staff-frontal-cancel", world, StaffSpell.FAULT)
            h.zombie(world, x = 0.5, z = 3.0)
            val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
            val damage = meleeDamage(hits)
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), StaffSpellTargeting(settings, damage))

            try {
                melee.cast(player, meleeCast(player), StaffSpell.FAULT, StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                h.scheduler.tick(STAFF_FRONTAL_CONTACT_TICK - 1)
                melee.cancel(player.uniqueId)
                h.scheduler.tick(2)
                hits shouldBe emptyList()
            } finally {
                melee.close()
            }
        }
    }

    "a melee frontal scene takes the bounded horizontal launch nudge" {
        val settings = staffSettings().copy(aimAssist = StaffAimAssistSettings(
            enabled = true, halfAngleDegrees = 6.0, maxMiss = 2.0, strength = .35))
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-frontal-aim-assist")
            val player = h.player("staff-frontal-aim-assist", world, StaffSpell.FAULT)
            // Keep the target outside the direct .05-block line width, but inside six degrees
            // after accounting for the vertical difference between eye and mob centers.
            player.teleport(player.location.apply { pitch = 4f })
            val target = h.zombie(world, x = 0.98, z = 6.5)
            var sceneEnd: Location? = null
            every { h.effects.melee(any(), any(), any(), any(), any()) } answers {
                sceneEnd = arg<Location>(3).clone()
                UUID.randomUUID()
            }
            val damage = meleeDamage(mutableListOf())
            // Production eligibility excludes the caster; this fixture's broad mock must do so too,
            // or the ray starts inside the player's own bounding box and its self-hit masks the nudge.
            every { damage.eligible(player, player, any()) } returns false
            val targeting = StaffSpellTargeting(settings, damage)
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), targeting)
            try {
                val cast = meleeCast(player)
                melee.cast(player, cast, StaffSpell.FAULT,
                    StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                val eye = player.eyeLocation.clone()
                val look = eye.direction
                val directTargets = targeting.lineTargets(player, eye,
                    eye.clone().add(look.clone().multiply(7.0)), 0.05, cast)
                val assistedDirection = targeting.assistedDirection(player, StaffSpell.FAULT, cast,
                    direction = look, from = eye, range = 7.0)
                val origin = player.eyeLocation.clone().subtract(0.0, .25, 0.0)
                val endpoint = requireNotNull(sceneEnd)
                withClue("target=${target.location} center=${center(target)} direct=${target in directTargets} " +
                    "look=$look assisted=$assistedDirection origin=$origin endpoint=$endpoint " +
                    "horizontalDelta=${endpoint.x - origin.x}") {
                    (endpoint.x - origin.x > .05) shouldBe true
                }
                (endpoint.z - origin.z > 6.9) shouldBe true
                (kotlin.math.abs(endpoint.y - origin.y) < .001) shouldBe true
            } finally {
                melee.close()
            }
        }
    }

    "an expired captured combat context suppresses frontal contact" {
        var current = true
        val settings = staffSettings()
        withStaffHarness(settings = settings) { h ->
            val world = h.paper.addSimpleWorld("staff-frontal-stale-context")
            val player = h.player("staff-frontal-stale-context", world, StaffSpell.LUNAR)
            h.zombie(world, x = 0.5, z = 3.0)
            val hits = mutableListOf<Pair<LivingEntity, Pair<Double, Double>>>()
            val damage = mockk<StaffSpellDamage>()
            every { damage.eligible(any(), any(), any()) } returns true
            every { damage.isCurrent(any(), any()) } answers { current }
            every { damage.hit(any(), any(), any(), any(), any()) } answers {
                hits += secondArg<LivingEntity>() to (arg<Double>(3) to arg<Double>(4))
                true
            }
            val melee = StaffSpellMelee(settings, damage, h.effects,
                mockk(relaxed = true), StaffSpellTargeting(settings, damage))

            try {
                melee.cast(player, meleeCast(player), StaffSpell.LUNAR, StaffSpellTuning(1.0, 6.0, 32)) shouldBe true
                h.scheduler.tick(STAFF_FRONTAL_CONTACT_TICK - 1)
                current = false
                h.scheduler.tick(1)
                hits shouldBe emptyList()
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
