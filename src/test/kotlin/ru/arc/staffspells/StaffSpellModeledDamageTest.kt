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
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import ru.arc.staffspells.api.StaffSpellDuelPolicy
import ru.arc.staffspells.api.StaffSpellDuelContext
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

    fun readyPlayers(caster: Player, target: Player, world: World) {
        readyEntities(caster, target, world)
        every { target.isOnline } returns true
        every { target.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000003")
    }

    "duel damage uses native configured multiplier and rechecks exact round and opponent policy" {
        val world = mockk<World>()
        val caster = mockk<Player>()
        val opponent = mockk<Player>()
        val teammate = mockk<Player>()
        readyPlayers(caster, opponent, world)
        readyPlayers(caster, teammate, world)
        every { teammate.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000004")
        every { opponent.absorptionAmount } returns 0.0
        var health = 20.0
        every { opponent.health } answers { health }
        every { opponent.damage(any<Double>(), caster) } answers {
            health -= firstArg<Double>()
            Unit
        }

        val context = StaffSpellDuelContext(UUID.fromString("00000000-0000-0000-0000-000000000010"), 2, 1.5)
        var currentContext: StaffSpellDuelContext? = context
        val registrationId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val access = mockk<StaffSpellDuelAccess>()
        every { access.context(caster) } answers {
            currentContext?.let { DuelSpellAuthorization(registrationId, it) }
        }
        every { access.isCurrent(any(), caster) } answers { firstArg<DuelSpellAuthorization>().context == currentContext }
        every { access.canHit(any(), caster, any()) } answers {
            firstArg<DuelSpellAuthorization>().context == currentContext && thirdArg<Player>() === opponent
        }

        val damage = StaffSpellDamage(access)
        val cast = requireNotNull(damage.capture(caster, duelOnly = true))
        damage.capture(caster, duelOnly = false) shouldBe null
        damage.eligible(caster, teammate, cast) shouldBe false
        damage.hit(caster, opponent, cast, power = 4.0, vanillaDamage = 3.0) shouldBe true
        health shouldBe 15.5
        verify(exactly = 1) { opponent.damage(4.5, caster) }

        currentContext = StaffSpellDuelContext(context.matchId(), 3, context.damageMultiplier())
        damage.hit(caster, opponent, cast, power = 4.0, vanillaDamage = 3.0) shouldBe false
        verify(exactly = 1) { opponent.damage(any<Double>(), caster) }
        verify(exactly = 0) { Bukkit.getPluginManager().isPluginEnabled("EliteMobs") }
    }

    "duel-only spell capture is denied outside an active duel" {
        val world = mockk<World>()
        val caster = mockk<Player>()
        val target = mockk<Player>()
        readyPlayers(caster, target, world)
        val access = mockk<StaffSpellDuelAccess>()
        every { access.context(caster) } returns null

        StaffSpellDamage(access).capture(caster, duelOnly = true) shouldBe null
        verify(exactly = 0) { Bukkit.getPluginManager().isPluginEnabled(any<String>()) }
    }

    "registered duel policy binds player hits to its exact round and rejects unrelated targets" {
        val world = mockk<World>()
        val caster = mockk<Player>()
        val opponent = mockk<Player>()
        val unrelated = mockk<Player>()
        readyPlayers(caster, opponent, world)
        readyPlayers(caster, unrelated, world)
        every { unrelated.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000004")
        every { Bukkit.isPrimaryThread() } returns true
        every { Bukkit.getPluginManager().registerEvents(any(), any()) } returns Unit

        val arc = mockk<Plugin>(relaxed = true)
        val owner = mockk<Plugin>(relaxed = true)
        every { owner.isEnabled } returns true
        val provider = ArcStaffSpellServiceProvider(arc) { null }
        val initial = StaffSpellDuelContext(UUID.fromString("00000000-0000-0000-0000-000000000020"), 0, 2.0)
        var active: StaffSpellDuelContext? = initial
        val policy = object : StaffSpellDuelPolicy {
            override fun context(casterPlayer: Player) = active.takeIf { casterPlayer === caster }
            override fun canHit(casterPlayer: Player, targetPlayer: Player, context: StaffSpellDuelContext) =
                context == active && casterPlayer === caster && targetPlayer === opponent
        }
        val registration = provider.registerDuelPolicy(owner, policy)
        try {
            provider.isAvailable() shouldBe false
            val damage = StaffSpellDamage(provider)
            val cast = requireNotNull(damage.capture(caster, duelOnly = true))
            damage.eligible(caster, unrelated, cast) shouldBe false
            damage.eligible(caster, opponent, cast) shouldBe true

            active = StaffSpellDuelContext(initial.matchId(), 1, 2.0)
            damage.eligible(caster, opponent, cast) shouldBe false
            damage.capture(caster, duelOnly = true)?.duelAuthorization?.context shouldBe active

            active = null
            damage.capture(caster, duelOnly = true) shouldBe null
            active = initial
            registration.close()
            provider.context(caster) shouldBe null
        } finally {
            registration.close()
            provider.close()
        }
    }

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
