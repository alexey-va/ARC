package ru.arc.graves

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Skeleton
import org.bukkit.entity.Wither
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.PlayerDeathEvent
import java.util.UUID

class GraveCustomizationTest : FunSpec({
    test("every personal style and text mode roundtrips through one bounded meta value") {
        GraveStyle.entries.forEach { style ->
            GraveTextMode.entries.forEach { mode ->
                val preference = GravePreferences(style, mode, "Ведро было. Реакции не было. | Да.")
                GravePreferences.fromStored(preference.stored()) shouldBe preference
                val choice = preference.appearance { "Гравитация читов не использовала." }
                choice.modelId shouldBe style.modelId
                choice.engraved shouldBe style.engraved
                choice.epitaph shouldBe when (mode) {
                    GraveTextMode.AUTO -> "Гравитация читов не использовала."
                    GraveTextMode.CUSTOM -> preference.customText
                    GraveTextMode.NONE -> ""
                }
                choice.validate()
            }
        }
        listOf(null, "", "bad", "x".repeat(513), "SLATE|CUSTOM|", "SLATE|AUTO|!bad", "UNKNOWN|AUTO|")
            .forEach { GravePreferences.fromStored(it) shouldBe GravePreferences() }
    }

    test("custom text rejects formatting controls bidi hidden glyphs and excess length") {
        listOf("line\nbreak", "\u00a7cRed", "<red>Text", "A\u202eB", "A\u0000B", "\ue000", "🙂", "я".repeat(81))
            .forEach { text ->
                GravePreferences.validText(text) shouldBe false
                shouldThrow<IllegalArgumentException> { GravePreferences(customText = text) }
            }
        GravePreferences.validText("я".repeat(80)) shouldBe true
        shouldThrow<IllegalArgumentException> { GravePreferences(textMode = GraveTextMode.CUSTOM, customText = " ") }
    }

    test("the bundled catalog provides hundreds of unique supported short phrases and stable cause-specific selection") {
        val catalog = GraveEpitaphs.load { javaClass.classLoader.getResourceAsStream(it) }
        val all = GraveDeathKind.entries.flatMap { cause ->
            (0L until 2048L).map { catalog.choose(UUID(11L, it), cause) }.toSet().also {
                it.size shouldBeGreaterThanOrEqual 9
                it.forEach { phrase ->
                    GravePreferences.validText(phrase) shouldBe true
                    (phrase.length <= 80) shouldBe true
                    GraveModels.originals.forEach { model ->
                        model.inscription.placements(phrase, ".LongestNick123456").isNotEmpty() shouldBe true
                    }
                }
            }
        }
        all.size shouldBeGreaterThanOrEqual 200
        all.distinct().size shouldBe all.size
        val id = UUID(9, 123)
        GraveDeathKind.entries.forEach { catalog.choose(id, it) shouldBe catalog.choose(id, it) }
    }

    test("death causes include all common hazards and distinguish PvP projectiles and bosses") {
        fun classify(cause: DamageCause, attacker: org.bukkit.entity.Entity? = null,
                     attributed: org.bukkit.entity.Entity? = null): GraveDeathKind {
            val damage = if (attacker == null) mockk<EntityDamageEvent>() else mockk<EntityDamageByEntityEvent> {
                every { damager } returns attacker
            }
            every { damage.cause } returns cause
            val player = mockk<Player> { every { lastDamageCause } returns damage }
            val event = mockk<PlayerDeathEvent> {
                every { entity } returns player
                every { damageSource } returns mockk { every { causingEntity } returns attributed }
            }
            return GraveDeathKind.of(event)
        }
        mapOf(
            DamageCause.FALL to GraveDeathKind.FALL, DamageCause.LAVA to GraveDeathKind.LAVA,
            DamageCause.FIRE_TICK to GraveDeathKind.FIRE, DamageCause.CAMPFIRE to GraveDeathKind.FIRE,
            DamageCause.DROWNING to GraveDeathKind.DROWNING, DamageCause.BLOCK_EXPLOSION to GraveDeathKind.EXPLOSION,
            DamageCause.PROJECTILE to GraveDeathKind.PROJECTILE, DamageCause.VOID to GraveDeathKind.VOID,
            DamageCause.SUFFOCATION to GraveDeathKind.SUFFOCATION, DamageCause.CRAMMING to GraveDeathKind.SUFFOCATION,
            DamageCause.STARVATION to GraveDeathKind.STARVATION, DamageCause.FREEZE to GraveDeathKind.FREEZING,
            DamageCause.MAGIC to GraveDeathKind.MAGIC, DamageCause.LIGHTNING to GraveDeathKind.LIGHTNING,
            DamageCause.THORNS to GraveDeathKind.THORNS, DamageCause.FALLING_BLOCK to GraveDeathKind.FALLING_BLOCK,
            DamageCause.FLY_INTO_WALL to GraveDeathKind.ELYTRA, DamageCause.WITHER to GraveDeathKind.WITHER,
            DamageCause.SONIC_BOOM to GraveDeathKind.MOB, DamageCause.KILL to GraveDeathKind.GENERIC,
        ).forEach { (cause, expected) -> classify(cause) shouldBe expected }
        classify(DamageCause.ENTITY_ATTACK, mockk<Player>()) shouldBe GraveDeathKind.PVP
        val arrow = mockk<Projectile> { every { shooter } returns mockk<Player>() }
        classify(DamageCause.PROJECTILE, arrow) shouldBe GraveDeathKind.PVP
        val skeletonArrow = mockk<Projectile> { every { shooter } returns mockk<Skeleton>() }
        classify(DamageCause.PROJECTILE, skeletonArrow) shouldBe GraveDeathKind.PROJECTILE
        classify(DamageCause.ENTITY_ATTACK, mockk<EnderDragon>()) shouldBe GraveDeathKind.DRAGON
        classify(DamageCause.ENTITY_ATTACK, attributed = mockk<EnderDragon>()) shouldBe GraveDeathKind.DRAGON
        classify(DamageCause.MAGIC, attributed = mockk<Player>()) shouldBe GraveDeathKind.PVP
        classify(DamageCause.ENTITY_ATTACK, mockk<Wither>()) shouldBe GraveDeathKind.WITHER
    }
})
