package ru.arc.staffspells

import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields
import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfigFields.ContentType
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import com.magmaguy.elitemobs.dungeons.EMPackage
import com.magmaguy.elitemobs.dungeons.WorldDungeonPackage
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.instanced.MatchInstance.InstancedRegionState
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.mobconstructor.EliteEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.InstancedBossEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Mob
import org.bukkit.inventory.ItemStack

class StaffDungeonMobBridgeTest : FreeSpec({
    "ordinary elites and unscoped custom bosses never become dungeon staff actors" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("em_primis_spoof_world")
            val body = h.zombie(world, .5, .5)
            val ordinary = mockk<EliteEntity>()
            resolveStaffDungeonMob(ordinary) shouldBe null
            val custom = mockk<CustomBossEntity>()
            readyDungeonBoss(custom, body)
            resolveStaffDungeonMob(custom) shouldBe null
        }
    }

    "Primis membership uses its registered world when the native boss package back-reference is null" {
        withStaffHarness { h ->
            h.paper.createSimplePlugin("EliteMobs")
            val world = h.paper.addSimpleWorld("em_primis")
            val body = h.zombie(world, .5, .5).apply {
                customName(Component.text("Named dungeon boss"))
                setAI(false)
                equipment.setItemInMainHand(ItemStack(Material.NETHERITE_SWORD))
            }
            val boss = mockk<RegionalBossEntity>()
            readyDungeonBoss(boss, body)
            val fields = mockk<ContentPackagesConfigFields>()
            every { fields.isEnabled } returns true
            every { fields.contentType } returns ContentType.OPEN_DUNGEON
            val content = mockk<WorldDungeonPackage>()
            every { content.isInstalled } returns true
            every { content.contentPackagesConfigFields } returns fields
            every { content.customBossEntityList } returns listOf(boss)
            every { content.world } returns world
            every { content.wormholeWorld } returns null
            every { boss.emPackage } returns null
            mockkStatic(EntityTracker::class, EMPackage::class)
            try {
                every { EntityTracker.getEliteMobEntity(body) } returns boss
                every { EMPackage.getContent(any()) } returns null
                // WorldMock is constructor-mocked by the harness; keep recorded lookup arguments literal.
                every { EMPackage.getContent("em_primis") } returns content
                every { EMPackage.getEmPackages() } returns mapOf("primis_adventure.yml" to content)
                val actor = requireNotNull(resolveStaffDungeonMob(boss))
                staffMobOwnerAllowed(actor) shouldBe true
                val accepted = mutableListOf<StaffDungeonMob>()
                StaffDungeonMobBridge { accepted += it }.use { bridge ->
                    val event = mockk<com.magmaguy.elitemobs.api.EliteMobSpawnEvent>()
                    every { event.eliteMobEntity } returns boss
                    every { event.isCancelled } returns false
                    every { content.customBossEntityList } returns emptyList()
                    resolveStaffDungeonMob(boss) shouldBe null
                    bridge.onSpawn(event)
                    every { content.customBossEntityList } returns listOf(boss)
                    h.scheduler.tick(2)
                    accepted.size shouldBe 1
                    bridge.onSpawn(event)
                    h.scheduler.tick(2)
                    accepted.size shouldBe 1
                }
                every { fields.isEnabled } returns false
                actor.isCurrent() shouldBe false
                resolveStaffDungeonMob(boss) shouldBe null
                every { fields.isEnabled } returns true
                actor.isCurrent() shouldBe true
                every { EMPackage.getContent("em_primis") } returns null
                resolveStaffDungeonMob(boss) shouldBe null
                every { EMPackage.getContent("em_primis") } returns content
                every { boss.isReinforcementOrMount } returns true
                resolveStaffDungeonMob(boss) shouldBe null
                every { boss.isReinforcementOrMount } returns false
                every { content.customBossEntityList } returns emptyList()
                actor.isCurrent() shouldBe false
                resolveStaffDungeonMob(boss) shouldBe null
                every { content.customBossEntityList } returns listOf(boss)
                every { EMPackage.getEmPackages() } returns emptyMap()
                actor.isCurrent() shouldBe false
                every { EMPackage.getEmPackages() } returns mapOf("primis_adventure.yml" to content)
                every { content.isInstalled } returns false
                resolveStaffDungeonMob(boss) shouldBe null
                every { content.isInstalled } returns true
                every { fields.contentType } returns ContentType.INSTANCED_DUNGEON
                resolveStaffDungeonMob(boss) shouldBe null
                every { fields.contentType } returns ContentType.OPEN_DUNGEON
                val entrance = h.paper.addSimpleWorld("em_primis_wormhole")
                every { content.wormholeWorld } returns entrance
                every { EMPackage.getContent("em_primis_wormhole") } returns content
                body.teleport(Location(entrance, .5, 64.0, .5))
                actor.isCurrent() shouldBe false
                resolveStaffDungeonMob(boss) shouldBe null
                body.teleport(Location(h.paper.addSimpleWorld("em_primis_fake_copy"), .5, 64.0, .5))
                actor.isCurrent() shouldBe false
                resolveStaffDungeonMob(boss) shouldBe null
            } finally {
                unmockkStatic(EntityTracker::class, EMPackage::class)
            }
        }
    }

    "Oasis native regional actor gets one enrollment per body across respawns" {
        withStaffHarness { h ->
            h.paper.createSimplePlugin("EliteMobs")
            val world = h.paper.addSimpleWorld("em_oasis")
            val original = h.zombie(world, .5, .5)
            val boss = mockk<RegionalBossEntity>()
            readyDungeonBoss(boss, original)
            every { boss.emPackage } returns null
            val fields = mockk<ContentPackagesConfigFields>()
            every { fields.isEnabled } returns true
            every { fields.contentType } returns ContentType.OPEN_DUNGEON
            val content = mockk<WorldDungeonPackage>()
            every { content.isInstalled } returns true
            every { content.contentPackagesConfigFields } returns fields
            every { content.customBossEntityList } returns listOf(boss)
            every { content.world } returns world
            mockkStatic(EntityTracker::class, EMPackage::class)
            try {
                every { EntityTracker.getEliteMobEntity(original) } returns boss
                every { EMPackage.getContent("em_oasis") } returns content
                every { EMPackage.getEmPackages() } returns mapOf("oasis_adventure.yml" to content)
                val accepted = mutableListOf<StaffDungeonMob>()
                StaffDungeonMobBridge { accepted += it }.use { bridge ->
                    val event = mockk<com.magmaguy.elitemobs.api.EliteMobSpawnEvent>()
                    every { event.eliteMobEntity } returns boss
                    every { event.isCancelled } returns false
                    bridge.onSpawn(event)
                    h.scheduler.tick(2)
                    bridge.onSpawn(event)
                    h.scheduler.tick(2)
                    accepted.map { it.body.uniqueId } shouldBe listOf(original.uniqueId)

                    val respawned = h.zombie(world, 1.5, .5)
                    every { boss.livingEntity } returns respawned
                    every { EntityTracker.getEliteMobEntity(original) } returns null
                    every { EntityTracker.getEliteMobEntity(respawned) } returns boss
                    accepted.single().isCurrent() shouldBe false
                    bridge.onSpawn(event)
                    h.scheduler.tick(2)
                    bridge.onSpawn(event)
                    h.scheduler.tick(2)
                    accepted.map { it.body.uniqueId } shouldBe listOf(original.uniqueId, respawned.uniqueId)
                    accepted.last().isCurrent() shouldBe true
                }
            } finally {
                unmockkStatic(EntityTracker::class, EMPackage::class)
            }
        }
    }

    "instanced actors recheck the active run exact clone roster lockout and native combat target" {
        withStaffHarness { h ->
            h.paper.createSimplePlugin("EliteMobs")
            val world = h.solidCollisionWorld("em_dungeon_current_clone")
            val body = h.zombie(world, .5, .5).apply { setAI(false) }
            val member = h.paper.addPlayer("dungeon-native-member").apply {
                teleport(Location(world, .5, 64.0, 4.5))
            }
            val outsider = h.paper.addPlayer("dungeon-native-outsider").apply { teleport(member.location) }
            body.target = member
            val boss = mockk<InstancedBossEntity>()
            readyDungeonBoss(boss, body)
            val instance = mockk<DungeonInstance>()
            every { boss.dungeonInstance } returns instance
            every { boss.lockoutPlayers } returns emptySet()
            every { instance.world } returns world
            every { instance.isCancelled } returns false
            every { instance.state } returns InstancedRegionState.ONGOING
            every { instance.players } returns hashSetOf(member)
            mockkStatic(EntityTracker::class, DungeonInstance::class)
            try {
                every { EntityTracker.getEliteMobEntity(body) } returns boss
                every { DungeonInstance.getDungeonInstances() } returns setOf(instance)
                val actor = requireNotNull(resolveStaffDungeonMob(boss))
                actor.isCurrent() shouldBe true
                actor.currentTarget() shouldBe member
                actor.permitsTarget(member) shouldBe true
                actor.permitsTarget(outsider) shouldBe false
                every { boss.lockoutPlayers } returns setOf(member)
                actor.permitsTarget(member) shouldBe false
                every { boss.lockoutPlayers } returns emptySet()
                every { instance.state } returns InstancedRegionState.WAITING
                actor.isCurrent() shouldBe true
                actor.permitsTarget(member) shouldBe false
                every { instance.state } returns InstancedRegionState.ONGOING
                every { instance.players } returns hashSetOf(outsider)
                actor.permitsTarget(member) shouldBe false
                every { instance.players } returns hashSetOf(member)
                body.target = outsider
                actor.currentTarget() shouldBe outsider
                staffMobTargetAllowed(actor, member, 16.0) shouldBe false
                body.target = member
                every { boss.isTriggeredAntiExploit } returns true
                actor.currentTarget() shouldBe null
                every { boss.isTriggeredAntiExploit } returns false
                every { instance.world } returns h.paper.addSimpleWorld("em_dungeon_different_clone")
                actor.isCurrent() shouldBe false
                every { instance.world } returns world
                every { instance.isCancelled } returns true
                actor.isCurrent() shouldBe false
                every { instance.isCancelled } returns false
                every { DungeonInstance.getDungeonInstances() } returns emptySet()
                actor.isCurrent() shouldBe false
            } finally {
                unmockkStatic(EntityTracker::class, DungeonInstance::class)
            }
        }
    }
})

private fun readyDungeonBoss(boss: CustomBossEntity, body: Mob) {
    val fields = mockk<CustomBossesConfigFields>()
    every { fields.isReinforcement } returns false
    every { boss.livingEntity } returns body
    every { boss.customBossesConfigFields } returns fields
    every { boss.isReinforcementOrMount } returns false
    every { boss.isDying } returns false
    every { boss.isAIActive } returns true
    every { boss.isTriggeredAntiExploit } returns false
    every { boss.isInAntiExploitCooldown } returns false
    every { boss.isInCombatGracePeriod } returns false
}
