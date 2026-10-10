package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.BlockData
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import kotlin.math.roundToInt

class StaffSpellGesturesTest : FreeSpec({
    "each spell has a distinct native quick frame with a bounded particle budget" {
        fun signature(samples: List<StaffParticleSample>) = samples.joinToString("|") { sample ->
            listOf(sample.particle.name, sample.count,
                (sample.offset.x * 100).roundToInt(), (sample.offset.y * 100).roundToInt(),
                (sample.offset.z * 100).roundToInt()).joinToString(":")
        }

        MockBukkitTestRuntime.open().use {
            StaffSpell.entries.forEach { spell ->
                val primary = staffGestureFrame(spell, StaffCastInput.QUICK, ageTicks = 10, reach = 6.0)
                val secondary = staffGestureFrame(spell, StaffCastInput.QUICK_SECONDARY, ageTicks = 10, reach = 6.0)

                primary.isNotEmpty() shouldBe true
                secondary.isNotEmpty() shouldBe true
                (primary.size <= 192 && secondary.size <= 192) shouldBe true
                (primary.sumOf { it.count } in 40..100) shouldBe true
                (secondary.sumOf { it.count } in 40..100) shouldBe true
                (signature(primary) != signature(secondary)) shouldBe true
                (primary + secondary).all { sample ->
                    listOf(sample.offset.x, sample.offset.y, sample.offset.z,
                        sample.spread.x, sample.spread.y, sample.spread.z).all(Double::isFinite) &&
                        (sample.particle != Particle.BLOCK || sample.data is BlockData)
                } shouldBe true
                if (spell == StaffSpell.TIDE) secondary.none { it.particle == Particle.BUBBLE } shouldBe true
            }
        }
    }

    "chain contact advances only through the rendered fork envelope" {
        val profile = staffGestureProfile(StaffSpell.CHAIN, StaffCastInput.QUICK)
        val point = staffGestureFrame(StaffSpell.CHAIN, StaffCastInput.QUICK, ageTicks = 10, reach = 6.0)
            .first { it.particle == Particle.ELECTRIC_SPARK && it.offset.z > 2.5 }

        staffGestureContact(profile, ageTicks = 10, previousAgeTicks = 8, local = point.offset, reach = 6.0) shouldBe true
        staffGestureContact(profile, ageTicks = 8, previousAgeTicks = 6, local = point.offset, reach = 6.0) shouldBe false
    }

    "lowered forward sweeps reach standing zombies where their visible arcs pass" {
        withStaffHarness { h ->
            val settings = staffSettings()
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            every { damage.eligible(any(), any(), any()) } answers {
                val target = secondArg<org.bukkit.entity.LivingEntity>()
                target !is org.bukkit.entity.Player && target.isValid && !target.isDead
            }
            val hits = mutableSetOf<UUID>()
            every { damage.hit(any(), any(), any(), any(), any()) } answers {
                hits += secondArg<org.bukkit.entity.LivingEntity>().uniqueId
                true
            }
            val visuals = mockk<StaffSpellVisuals>(relaxed = true)
            every { visuals.withOwner(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
            val gestures = StaffSpellGestures(settings, damage, visuals, StaffSpellTargeting(settings, damage))
            gestures.start()

            val lanceWorld = h.paper.addSimpleWorld("gesture-lance-torso")
            val lanceCaster = h.player("gesture-lance-torso", lanceWorld, StaffSpell.LANCE)
            val lanceTarget = h.zombie(lanceWorld, 0.5, 3.5)
            val lunarWorld = h.paper.addSimpleWorld("gesture-lunar-torso")
            val lunarCaster = h.player("gesture-lunar-torso", lunarWorld, StaffSpell.LUNAR)
            val lunarTarget = h.zombie(lunarWorld, 2.5, 3.3)
            val tideWorld = h.paper.addSimpleWorld("gesture-tide-torso")
            val tideCaster = h.player("gesture-tide-torso", tideWorld, StaffSpell.TIDE)
            val tideTarget = h.zombie(tideWorld, 1.0, 3.5)

            fun cast(player: org.bukkit.entity.Player, spell: StaffSpell) = gestures.cast(
                player, spell,
                StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false),
                StaffSpellTuning(1.0, 6.0, 20), StaffCastInput.QUICK,
            )
            cast(lanceCaster, StaffSpell.LANCE)
            cast(lunarCaster, StaffSpell.LUNAR)
            cast(tideCaster, StaffSpell.TIDE)

            h.scheduler.tick(6)
            hits.isEmpty() shouldBe true
            h.scheduler.tick(2)
            hits shouldBe setOf(lanceTarget.uniqueId)
            h.scheduler.tick(2)
            hits shouldBe setOf(lanceTarget.uniqueId, lunarTarget.uniqueId)
            h.scheduler.tick(2)
            hits shouldBe setOf(lanceTarget.uniqueId, lunarTarget.uniqueId, tideTarget.uniqueId)
            gestures.close()
        }
    }

    "owner cancellation clears a windup and leaves the next cast independent of cooldown" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("gesture-lifecycle")
            val caster = h.player("gesture-caster", world, StaffSpell.MARK)
            val firstTarget = h.zombie(world, 0.5, 3.5)
            h.zombie(world, 1.0, 3.5)
            val settings = staffSettings()
            val damage = mockk<StaffSpellDamage>()
            every { damage.isCurrent(any(), any()) } returns true
            every { damage.eligible(any(), any(), any()) } answers {
                val target = secondArg<org.bukkit.entity.LivingEntity>()
                target !is org.bukkit.entity.Player && target.isValid && !target.isDead
            }
            val hits = mutableListOf<UUID>()
            val cues = mutableListOf<Sound>()
            lateinit var gestures: StaffSpellGestures
            every { damage.hit(any(), any(), any(), any(), any()) } answers {
                val player = firstArg<org.bukkit.entity.Player>()
                val target = secondArg<org.bukkit.entity.LivingEntity>()
                hits += target.uniqueId
                gestures.cancel(player.uniqueId)
                true
            }
            val visuals = mockk<StaffSpellVisuals>(relaxed = true)
            every { visuals.cue(any(), any(), any(), any()) } answers {
                cues += secondArg<Sound>()
                Unit
            }
            every { visuals.withOwner(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
            gestures = StaffSpellGestures(settings, damage, visuals, StaffSpellTargeting(settings, damage))
            gestures.start()

            val cast = StaffSpellCast(caster.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val tuning = StaffSpellTuning(1.0, 6.0, 20)
            gestures.cast(caster, StaffSpell.MARK, cast, tuning, StaffCastInput.QUICK)
            gestures.cancel(caster.uniqueId)
            h.scheduler.tick(30)
            hits.size shouldBe 0
            cues shouldBe listOf(Sound.BLOCK_AMETHYST_BLOCK_CHIME)

            gestures.cast(caster, StaffSpell.MARK, cast, tuning, StaffCastInput.QUICK)
            h.scheduler.tick(20)
            hits shouldBe listOf(firstTarget.uniqueId)
            cues shouldBe listOf(
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
            )
            gestures.close()
        }
    }
})
