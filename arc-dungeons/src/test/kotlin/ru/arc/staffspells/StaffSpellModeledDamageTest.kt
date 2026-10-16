package ru.arc.staffspells

import com.magmaguy.elitemobs.MetadataHandler
import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatEnemyAuthorization
import com.magmaguy.elitemobs.advancedcombat.damage.AdvancedDamageScaling
import com.magmaguy.elitemobs.api.EliteMobDamagedByPlayerEventFilter
import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.combatsystem.CombatDamageContext
import com.magmaguy.elitemobs.config.SkillsConfig
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.items.GearRestrictionHandler
import com.magmaguy.elitemobs.items.ItemDurability
import com.magmaguy.elitemobs.items.customenchantments.SoulbindEnchantment
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.playerdata.ElitePlayerInventory
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.SkillType
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import com.magmaguy.elitemobs.thirdparty.custommodels.CustomModel
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.UnsafeValues
import org.bukkit.World
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.staffspells.api.StaffSpellDuelPolicy
import ru.arc.staffspells.api.StaffSpellDuelContext
import java.util.UUID

class StaffSpellModeledDamageTest : FreeSpec({
    val unsafeValues = mockk<UnsafeValues>()
    val damageSourceBuilder = mockk<DamageSource.Builder>()
    val magicDamageSource = mockk<DamageSource>()
    val pluginManager = mockk<PluginManager>()
    val eliteMobsMain = mockk<JavaPlugin>(relaxed = true)
    var originalEliteMobsMain: JavaPlugin? = null

    beforeTest {
        clearMocks(unsafeValues, damageSourceBuilder, magicDamageSource, pluginManager, eliteMobsMain)
        originalEliteMobsMain = MetadataHandler.PLUGIN
        every { eliteMobsMain.name } returns "EliteMobs"
        MetadataHandler.PLUGIN = eliteMobsMain
        mockkStatic(
            Bukkit::class,
            AdvancedCombatEnemyAuthorization::class,
            AdvancedDamageScaling::class,
            EliteMobDamagedByPlayerEventFilter::class,
            EliteItemManager::class,
            EntityTracker::class,
            GearRestrictionHandler::class,
            ItemDurability::class,
            PlayerData::class,
            SkillsConfig::class,
            SoulbindEnchantment::class,
            ElitePlayerInventory::class,
            WeaponIdentityResolver::class,
        )
        every { Bukkit.getPluginManager() } returns pluginManager
        every { pluginManager.isPluginEnabled("EliteMobs") } returns true
        every { pluginManager.callEvent(any()) } returns Unit
        every { Bukkit.getUnsafe() } returns unsafeValues
        every { unsafeValues.createDamageSourceBuilder(DamageType.MAGIC) } returns damageSourceBuilder
        every { damageSourceBuilder.withDirectEntity(any()) } returns damageSourceBuilder
        every { damageSourceBuilder.withCausingEntity(any()) } returns damageSourceBuilder
        every { damageSourceBuilder.build() } returns magicDamageSource
    }

    afterTest {
        unmockkStatic(
            Bukkit::class,
            AdvancedCombatEnemyAuthorization::class,
            AdvancedDamageScaling::class,
            EliteMobDamagedByPlayerEventFilter::class,
            EliteItemManager::class,
            EntityTracker::class,
            GearRestrictionHandler::class,
            ItemDurability::class,
            PlayerData::class,
            SkillsConfig::class,
            SoulbindEnchantment::class,
            ElitePlayerInventory::class,
            WeaponIdentityResolver::class,
        )
        MetadataHandler.PLUGIN = originalEliteMobsMain
    }

    fun readyEntities(player: Player, target: LivingEntity, world: World) {
        every { player.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000001")
        every { player.isOnline } returns true
        every { player.isValid } returns true
        every { player.isDead } returns false
        every { player.world } returns world
        every { player.eyeLocation } returns org.bukkit.Location(world, .5, 65.6, .5)
        every { target.location } returns org.bukkit.Location(world, .5, 64.0, 4.5)
        every { target.height } returns 1.8
        every { target.boundingBox } returns org.bukkit.util.BoundingBox(.2, 64.0, 4.2, .8, 65.8, 4.8)
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { world.rayTraceBlocks(any<io.papermc.paper.math.Position>(), any<org.bukkit.util.Vector>(), any<Double>(),
            any<org.bukkit.FluidCollisionMode>(), any<Boolean>(), any<java.util.function.Predicate<org.bukkit.block.Block>>()) } returns null
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

    "the final damage boundary rejects a target behind cover even when authorization allows it" {
        val world = mockk<World>()
        val player = mockk<Player>()
        val target = mockk<LivingEntity>()
        readyEntities(player, target, world)
        every { world.rayTraceBlocks(any<io.papermc.paper.math.Position>(), any<org.bukkit.util.Vector>(), any<Double>(),
            any<org.bukkit.FluidCollisionMode>(), true, any<java.util.function.Predicate<org.bukkit.block.Block>>()) } returns
            org.bukkit.util.RayTraceResult(org.bukkit.util.Vector(.5, 65.0, 2.0))
        StaffSpellDamage().hit(player, target, cast(player), 1.0, 6.0) shouldBe false
        verify(exactly = 0) { target.damage(any<Double>(), player) }
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
        every { access.inputAllowed(any()) } returns true
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

    "duel spells can destroy only summons registered to a current opposing participant" {
        val world = mockk<World>()
        val attacker = mockk<Player>()
        val owner = mockk<Player>()
        val summon = mockk<LivingEntity>()
        val unrelated = mockk<LivingEntity>()
        readyPlayers(attacker, owner, world)
        readyEntities(attacker, summon, world)
        readyEntities(attacker, unrelated, world)
        every { Bukkit.getPlayer(owner.uniqueId) } returns owner
        every { summon.absorptionAmount } returns 0.0
        var health = 8.0
        every { summon.health } answers { health }
        every { summon.damage(any<Double>(), attacker) } answers { health -= firstArg<Double>(); Unit }
        val context = StaffSpellDuelContext(UUID.randomUUID(), 1, .4)
        val authorization = DuelSpellAuthorization(UUID.randomUUID(), context)
        val access = mockk<StaffSpellDuelAccess>()
        every { access.inputAllowed(any()) } returns true
        var ownerCurrent = true
        every { access.context(any()) } returns authorization
        every { access.isCurrent(authorization, any()) } answers { secondArg<Player>() !== owner || ownerCurrent }
        every { access.canHit(authorization, attacker, any()) } answers { thirdArg<Player>() === owner }
        val damage = StaffSpellDamage(access)
        val attack = requireNotNull(damage.capture(attacker, duelOnly = true))
        val summonCast = requireNotNull(damage.capture(owner, duelOnly = true))
        damage.summonCast = { if (it === summon) summonCast else null }

        damage.eligible(attacker, summon, attack) shouldBe true
        damage.eligible(attacker, unrelated, attack) shouldBe false
        damage.hit(attacker, summon, attack, 1.0, 10.0) shouldBe true
        health shouldBe 4.0
        ownerCurrent = false
        damage.eligible(attacker, summon, attack) shouldBe false
        damage.hit(attacker, summon, attack, 1.0, 10.0) shouldBe false
        health shouldBe 4.0
    }

    "duel-only spell capture is denied outside an active duel" {
        val world = mockk<World>()
        val caster = mockk<Player>()
        val target = mockk<Player>()
        readyPlayers(caster, target, world)
        val access = mockk<StaffSpellDuelAccess>()
        every { access.inputAllowed(any()) } returns true
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
        var allowsInput = true
        var allowsMobility = true
        var teleports = 0
        val destination = org.bukkit.Location(world, .5, 64.0, 4.5)
        val policy = object : StaffSpellDuelPolicy {
            override fun context(casterPlayer: Player) = active.takeIf { casterPlayer === caster }
            override fun canHit(casterPlayer: Player, targetPlayer: Player, context: StaffSpellDuelContext) =
                context == active && casterPlayer === caster && targetPlayer === opponent
            override fun permitsInput(player: Player) = allowsInput && player === caster
            override fun permitsMobility(player: Player, target: org.bukkit.Location) =
                allowsMobility && player === caster && target == destination
            override fun teleportMobility(player: Player, target: org.bukkit.Location): Boolean {
                teleports++
                return player === caster && target == destination
            }
        }
        val registration = provider.registerDuelPolicy(owner, policy)
        try {
            provider.isAvailable() shouldBe false
            provider.inputAllowed(caster) shouldBe true
            allowsInput = false
            provider.inputAllowed(caster) shouldBe false
            allowsInput = true
            val damage = StaffSpellDamage(provider)
            val cast = requireNotNull(damage.capture(caster, duelOnly = true))
            val authorization = requireNotNull(cast.duelAuthorization)
            provider.mobilityAllowed(caster, authorization, destination) shouldBe true
            provider.teleportMobility(caster, authorization, destination) shouldBe true
            teleports shouldBe 1
            allowsMobility = false
            provider.mobilityAllowed(caster, authorization, destination) shouldBe false
            provider.teleportMobility(caster, authorization, destination) shouldBe false
            teleports shouldBe 1
            allowsMobility = true
            damage.eligible(caster, unrelated, cast) shouldBe false
            damage.eligible(caster, opponent, cast) shouldBe true

            active = StaffSpellDuelContext(initial.matchId(), 1, 2.0)
            provider.mobilityAllowed(caster, authorization, destination) shouldBe false
            provider.teleportMobility(caster, authorization, destination) shouldBe false
            teleports shouldBe 1
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

    "older duel policies keep input enabled but cannot authorize staff mobility by default" {
        val world = mockk<World>()
        val caster = mockk<Player>()
        val target = mockk<Player>()
        readyPlayers(caster, target, world)
        every { Bukkit.isPrimaryThread() } returns true
        every { Bukkit.getPluginManager().registerEvents(any(), any()) } returns Unit

        val arc = mockk<Plugin>(relaxed = true)
        val owner = mockk<Plugin>(relaxed = true)
        every { owner.isEnabled } returns true
        val provider = ArcStaffSpellServiceProvider(arc) { null }
        val context = StaffSpellDuelContext(UUID.fromString("00000000-0000-0000-0000-000000000030"), 1, 1.0)
        val policy = object : StaffSpellDuelPolicy {
            override fun context(casterPlayer: Player) = context.takeIf { casterPlayer === caster }
            override fun canHit(casterPlayer: Player, targetPlayer: Player, duel: StaffSpellDuelContext) = false
        }
        val registration = provider.registerDuelPolicy(owner, policy)
        val destination = org.bukkit.Location(world, .5, 64.0, 4.5)
        try {
            val authorization = requireNotNull(provider.context(caster))
            provider.inputAllowed(caster) shouldBe true
            policy.permitsMobility(caster, destination) shouldBe false
            policy.teleportMobility(caster, destination) shouldBe false
            provider.mobilityAllowed(caster, authorization, destination) shouldBe false
            provider.teleportMobility(caster, authorization, destination) shouldBe false
        } finally {
            registration.close()
            provider.close()
        }
    }

    "mapped EliteMobs casts snapshot the native weapon family, level, carrier and enchantment power" {
        mockkObject(StaffEliteItems, StaffEliteEnchantments.Companion)
        try {
            val world = mockk<World>()
            val player = mockk<Player>()
            val target = mockk<LivingEntity>()
            val boss = mockk<CustomBossEntity>()
            val model = mockk<CustomModel>()
            val weapon = mockk<ItemStack>()
            val itemMeta = mockk<ItemMeta>()
            val playerInventory = mockk<PlayerInventory>()
            val eliteInventory = mockk<ElitePlayerInventory>()
            readyEntities(player, target, world)
            every { player.inventory } returns playerInventory
            every { playerInventory.itemInMainHand } returns weapon
            every { weapon.itemMeta } returns itemMeta
            every { weapon.type } returns Material.STICK
            every { StaffEliteItems.resolve(weapon) } returns StaffSpell.CHAIN
            every { ItemDurability.prepareMagicWeapon(weapon) } returns Unit
            every { EliteItemManager.isOnLastDamage(weapon) } returns false
            every { GearRestrictionHandler.canEquip(player, weapon) } returns true
            every { SoulbindEnchantment.isValidSoulbindUser(itemMeta, player) } returns true
            every { WeaponIdentityResolver.progressionSkill(weapon) } returns SkillType.WANDS
            every { SkillsConfig.isWorldExcludedFromSkills(player) } returns false
            every { PlayerData.isDataLoaded(player.uniqueId) } returns true
            every { PlayerData.getSkillLevel(player.uniqueId, SkillType.WANDS) } returns 6
            every { ElitePlayerInventory.getPlayer(player) } returns eliteInventory
            every { eliteInventory.getLoudStrikesBonusMultiplier(true) } returns 1.2
            every { EliteItemManager.getItemLevel(weapon) } returns 12.0
            every { EliteItemManager.getEliteDamageEnchantmentMultiplier(weapon) } returns 1.5
            every { EliteMobDamagedByPlayerEventFilter.captureCriticalHit(player) } returns true
            every { StaffEliteEnchantments.capture(player, weapon, SkillType.WANDS) } returns
                StaffEliteEnchantments(damageMultiplier = 3.0, radiusMultiplier = 1.4, ignitionTicks = 40)

            val damage = StaffSpellDamage()
            val cast = requireNotNull(damage.capture(player))
            cast.progressionSkill shouldBe StaffWeaponFamily.WANDS
            cast.skillLevel shouldBe 6
            cast.itemLevel shouldBe 12.0
            cast.carrierMaterial shouldBe Material.STICK
            cast.eliteDamageEnchantmentMultiplier shouldBe 1.5
            cast.enchantments shouldBe StaffEliteEnchantments(3.0, 1.4, 40)
            cast.nativeEliteWeapon shouldBe true

            every { target.absorptionAmount } returns 0.0
            every { target.fireTicks } returns 0
            every { target.fireTicks = any() } returns Unit
            var health = 20.0
            every { target.health } answers { health }
            every { EntityTracker.getEliteMobEntity(target) } returns boss
            every { boss.customModel } returns model
            every { AdvancedDamageScaling.magicWeapon(boss, 6, 12.0, 6.0, Material.STICK) } returns 8.0
            var combatSource: CombatDamageContext.PlayerDamageSource? = null
            every { target.damage(any<Double>(), any<DamageSource>()) } answers {
                combatSource = CombatDamageContext.currentPlayerToEliteSource().orElse(null)
                health -= firstArg<Double>()
                Unit
            }

            damage.hit(player, target, cast, power = 2.0, vanillaDamage = 2.0) shouldBe true
            verify(exactly = 1) {
                AdvancedDamageScaling.magicWeapon(boss, 6, 12.0, 6.0, Material.STICK)
            }
            verify(exactly = 1) { target.damage(12.0, magicDamageSource) }
            verify(exactly = 1) { damageSourceBuilder.withDirectEntity(player) }
            verify(exactly = 1) { damageSourceBuilder.withCausingEntity(player) }
            verify(exactly = 1) { unsafeValues.createDamageSourceBuilder(DamageType.MAGIC) }
            verify(exactly = 1) { target.fireTicks = 40 }
            combatSource?.progressionSkill() shouldBe SkillType.WANDS
        } finally {
            unmockkObject(StaffEliteItems, StaffEliteEnchantments.Companion)
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
        every { target.damage(any<Double>(), any<DamageSource>()) } answers {
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
        verify(exactly = 1) { target.damage(6.25, magicDamageSource) }
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
        every { unmodeledBossTarget.damage(any<Double>(), any<DamageSource>()) } answers {
            unmodeledDamageInsideCombatBypass = CombatDamageContext.isPlayerToEliteBypassActive()
            unmodeledHealth -= firstArg<Double>()
            Unit
        }
        every { ordinaryTarget.health } answers { ordinaryHealth }
        every { ordinaryTarget.absorptionAmount } returns 0.0
        every { ordinaryTarget.damage(any<Double>(), any<DamageSource>()) } answers {
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
        verify(exactly = 1) { unmodeledBossTarget.damage(5.0, magicDamageSource) }
        verify(exactly = 1) { ordinaryTarget.damage(2.0, magicDamageSource) }
        CombatDamageContext.isPlayerToEliteBypassActive() shouldBe false
        CombatDamageContext.currentPlayerToEliteSource().isEmpty shouldBe true
    }
})
