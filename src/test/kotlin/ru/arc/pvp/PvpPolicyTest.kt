package ru.arc.pvp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.ThrownPotion
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionEffectTypeCategory
import ru.arc.dungeons.api.ArcPvpService.Result
import ru.arc.paper.testing.MockBukkitTestRuntime

class PvpPolicyTest : StringSpec({
    "either settlement blocks the same attack for every identity and a query stays silent" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("pvp")
            val attacker = paper.server.addPlayer()
            val victim = paper.server.addPlayer()
            attacker.teleport(Location(world, 0.0, 64.0, 0.0))
            victim.teleport(Location(world, 20.0, 64.0, 0.0))
            var protectedX: Double? = null
            var teamChecks = 0
            PvpPolicy({ it.x == protectedX }, { _, _, _ -> teamChecks++; Result.ALLOWED }, { Component.text(it.name) }).use { policy ->
                policy.check(attacker, victim, attacker) shouldBe Result.ALLOWED
                protectedX = 0.0
                policy.check(attacker, victim, attacker) shouldBe Result.SETTLEMENT
                protectedX = 20.0
                policy.check(attacker, victim, attacker) shouldBe Result.SETTLEMENT
                teamChecks shouldBe 1
                attacker.nextMessage() shouldBe null
            }
        }
    }

    "native damage uses the indirect owner and never uncancels another plugin's veto" {
        MockBukkitTestRuntime.open().use { paper ->
            val attacker = paper.server.addPlayer()
            val victim = paper.server.addPlayer()
            val projectile = mockk<Projectile>()
            val source = mockk<DamageSource>()
            val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
            every { event.entity } returns victim
            every { event.damager } returns projectile
            every { event.damageSource } returns source
            every { source.causingEntity } returns attacker
            every { event.isCancelled } returns true
            var result = Result.ALLOWED
            PvpPolicy({ false }, { owner, target, direct ->
                owner shouldBe attacker
                target shouldBe victim
                direct shouldBe projectile
                result
            }, { Component.text(it.name) }).use { policy ->
                policy.onDamage(event)
                verify(exactly = 0) { event.isCancelled = any() }
                result = Result.TEAM
                policy.onDamage(event)
                verify(exactly = 1) { event.isCancelled = true }
                verify(exactly = 0) { event.isCancelled = false }
                attacker.nextMessage() shouldBe "TEAM"
            }
        }
    }

    "feedback is shared across reasons and bounded to one message every two seconds" {
        MockBukkitTestRuntime.open().use { paper ->
            val attacker = paper.server.addPlayer()
            var now = 0L
            PvpPolicy({ false }, { _, _, _ -> Result.ALLOWED }, { Component.text(it.name) }, { now }).use { policy ->
                policy.notifyDenied(attacker, Result.ALLOWED)
                policy.notifyDenied(attacker, Result.SETTLEMENT)
                now = 1_999
                policy.notifyDenied(attacker, Result.TEAM)
                now = 2_000
                policy.notifyDenied(attacker, Result.ALLY)
                attacker.nextMessage() shouldBe "SETTLEMENT"
                attacker.nextMessage() shouldBe "ALLY"
                attacker.nextMessage() shouldBe null
            }
        }
    }

    "harmful splash and remaining cloud targets share the policy without inventing native removed targets" {
        MockBukkitTestRuntime.open().use { paper ->
            val attacker = paper.server.addPlayer()
            val victim = paper.server.addPlayer()
            val effectType = mockk<PotionEffectType>()
            every { effectType.category } returns PotionEffectTypeCategory.HARMFUL
            val effect = PotionEffect(effectType, 40, 0)
            val potion = mockk<ThrownPotion>()
            every { potion.shooter } returns attacker
            every { potion.effects } returns listOf(effect)
            val splash = mockk<PotionSplashEvent>(relaxed = true)
            every { splash.potion } returns potion
            every { splash.affectedEntities } returns listOf(victim)
            val cloud = mockk<AreaEffectCloud>()
            every { cloud.source } returns attacker
            every { cloud.customEffects } returns listOf(effect)
            val remaining = mutableListOf<org.bukkit.entity.LivingEntity>(victim)
            val apply = mockk<AreaEffectCloudApplyEvent>()
            every { apply.entity } returns cloud
            every { apply.affectedEntities } returns remaining
            PvpPolicy({ false }, { _, _, _ -> Result.TEAM }, { Component.text(it.name) }).use { policy ->
                policy.onSplash(splash)
                verify(exactly = 1) { splash.setIntensity(victim, 0.0) }
                policy.onCloud(apply)
                remaining shouldBe emptyList()
                attacker.nextMessage() shouldBe "TEAM"
                attacker.nextMessage() shouldBe null
                policy.onCloud(apply)
                attacker.nextMessage() shouldBe null
            }
        }
    }
})
