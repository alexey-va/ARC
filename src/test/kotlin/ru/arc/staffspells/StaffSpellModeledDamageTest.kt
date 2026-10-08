package ru.arc.staffspells

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatEnemyAuthorization
import com.magmaguy.elitemobs.advancedcombat.damage.AdvancedDamageScaling
import com.magmaguy.elitemobs.combatsystem.CombatDamageContext
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.thirdparty.custommodels.CustomModel
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.PluginManager
import java.util.UUID

class StaffSpellModeledDamageTest : FreeSpec({
    beforeTest {
        mockkStatic(
            Bukkit::class,
            AdvancedCombatEnemyAuthorization::class,
            AdvancedDamageScaling::class,
            EntityTracker::class,
        )
        val pluginManager = mockk<PluginManager>()
        every { Bukkit.getPluginManager() } returns pluginManager
        every { pluginManager.isPluginEnabled("EliteMobs") } returns true
    }

    afterTest {
        unmockkStatic(
            Bukkit::class,
            AdvancedCombatEnemyAuthorization::class,
            AdvancedDamageScaling::class,
            EntityTracker::class,
        )
    }

    fun readyEntities(player: Player, target: LivingEntity, world: World) {
        every { player.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000001")
        every { player.isOnline } returns true
        every { player.isValid } returns true
        every { player.isDead } returns false
        every { player.world } returns world
        every { target.isValid } returns true
        every { target.isDead } returns false
        every { target.isInvulnerable } returns false
        every { target.world } returns world
        every { target.hasMetadata("NPC") } returns false
        every { AdvancedCombatEnemyAuthorization.canTargetWithMagicWeapon(player, target) } returns true
    }

    fun cast(player: Player) = StaffSpellCast(
        casterId = player.uniqueId,
        attackId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        skillLevel = 4,
        criticalHit = true,
        loudStrikesBonus = 1.2,
        eliteMobsEnabled = true,
    )

    "modeled EliteMobs damage applies one attributed hit inside the combat context" {
        val world = mockk<World>()
        val player = mockk<Player>()
        val target = mockk<LivingEntity>()
        val boss = mockk<CustomBossEntity>()
        val model = mockk<CustomModel>()
        readyEntities(player, target, world)

        every { target.absorptionAmount } returns 0.0
        var health = 20.0
        every { target.health } answers { health }
        var damageInsideCombatBypass = false
        var damageSource: CombatDamageContext.PlayerDamageSource? = null
        every { boss.customModel } returns model
        every { EntityTracker.getEliteMobEntity(target) } returns boss
        every { AdvancedDamageScaling.magicWeapon(boss, 4, 4.0, 1.5, Material.BLAZE_ROD) } returns 6.25
        every { target.damage(any<Double>(), player) } answers {
            damageInsideCombatBypass = CombatDamageContext.isPlayerToEliteBypassActive()
            damageSource = CombatDamageContext.currentPlayerToEliteSource().orElse(null)
            health -= firstArg<Double>()
            Unit
        }

        val attack = cast(player)
        StaffSpellDamage().hit(player, target, attack, power = 1.5, vanillaDamage = 2.0) shouldBe true

        damageInsideCombatBypass shouldBe true
        damageSource?.attackId() shouldBe attack.attackId
        damageSource?.progressionSkill() shouldBe SkillType.STAVES
        damageSource?.criticalHit() shouldBe true
        damageSource?.loudStrikesBonus() shouldBe 1.2
        verify(exactly = 1) { boss.customModel }
        verify(exactly = 1) { target.damage(6.25, player) }
        CombatDamageContext.isPlayerToEliteBypassActive() shouldBe false
        CombatDamageContext.currentPlayerToEliteSource().isEmpty shouldBe true
    }

    "bosses without a custom model and untracked ordinary entities keep the fallback path" {
        val world = mockk<World>()
        val player = mockk<Player>()
        val unmodeledBossTarget = mockk<LivingEntity>()
        val ordinaryTarget = mockk<LivingEntity>()
        val unmodeledBoss = mockk<CustomBossEntity>()
        readyEntities(player, unmodeledBossTarget, world)
        readyEntities(player, ordinaryTarget, world)
        every { unmodeledBoss.customModel } returns null
        every { EntityTracker.getEliteMobEntity(unmodeledBossTarget) } returns unmodeledBoss
        every { EntityTracker.getEliteMobEntity(ordinaryTarget) } returns null
        every { AdvancedDamageScaling.magicWeapon(unmodeledBoss, 4, 4.0, 1.5, Material.BLAZE_ROD) } returns 5.0

        var unmodeledHealth = 20.0
        var ordinaryHealth = 20.0
        var unmodeledDamageInsideCombatBypass = false
        var ordinaryDamageInsideCombatBypass = true
        var ordinaryDamageSource: CombatDamageContext.PlayerDamageSource? = null
        every { unmodeledBossTarget.health } answers { unmodeledHealth }
        every { unmodeledBossTarget.absorptionAmount } returns 0.0
        every { unmodeledBossTarget.damage(any<Double>(), player) } answers {
            unmodeledDamageInsideCombatBypass = CombatDamageContext.isPlayerToEliteBypassActive()
            unmodeledHealth -= firstArg<Double>()
            Unit
        }
        every { ordinaryTarget.health } answers { ordinaryHealth }
        every { ordinaryTarget.absorptionAmount } returns 0.0
        every { ordinaryTarget.damage(any<Double>(), player) } answers {
            ordinaryDamageInsideCombatBypass = CombatDamageContext.isPlayerToEliteBypassActive()
            ordinaryDamageSource = CombatDamageContext.currentPlayerToEliteSource().orElse(null)
            ordinaryHealth -= firstArg<Double>()
            Unit
        }

        val attack = cast(player)
        val damage = StaffSpellDamage()
        damage.hit(player, unmodeledBossTarget, attack, power = 1.5, vanillaDamage = 2.0) shouldBe true
        damage.hit(player, ordinaryTarget, attack, power = 1.5, vanillaDamage = 2.0) shouldBe true

        unmodeledDamageInsideCombatBypass shouldBe true
        ordinaryDamageInsideCombatBypass shouldBe false
        ordinaryDamageSource shouldBe null
        verify(exactly = 1) { unmodeledBoss.customModel }
        verify(exactly = 0) { ordinaryTarget.damage(5.0, player) }
        verify(exactly = 1) { unmodeledBossTarget.damage(5.0, player) }
        verify(exactly = 1) { ordinaryTarget.damage(2.0, player) }
        CombatDamageContext.isPlayerToEliteBypassActive() shouldBe false
        CombatDamageContext.currentPlayerToEliteSource().isEmpty shouldBe true
    }
})
