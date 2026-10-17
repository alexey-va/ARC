package ru.arc.hooks.elitemobs.arena

import com.magmaguy.elitemobs.api.EliteMobSpawnEvent
import com.magmaguy.elitemobs.api.PlayerJoinArenaEvent
import com.magmaguy.elitemobs.api.instanced.MatchLeaveEvent
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfig
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import com.magmaguy.elitemobs.config.menus.premade.ArenaMenuConfig
import com.magmaguy.elitemobs.config.powers.PowersConfig
import com.magmaguy.elitemobs.config.powers.PowersConfigFields
import com.magmaguy.elitemobs.config.npcs.NPCsConfig
import com.magmaguy.elitemobs.config.npcs.NPCsConfigFields
import com.magmaguy.elitemobs.commands.ArenaCommand
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.magmacore.command.CommandManager
import com.magmaguy.elitemobs.npcs.NPCInteractions.NPCInteractionType
import com.magmaguy.elitemobs.instanced.MatchInstance.InstancedRegionState
import com.magmaguy.elitemobs.instanced.arena.ArenaInstance
import com.magmaguy.elitemobs.instanced.arena.ArenaWaves
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.command.PluginCommand
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import ru.arc.config.Config
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID

class NativeArenaLevelsTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var addon: NativeArenaLevels
    lateinit var point: Location
    val definitions = mutableMapOf<String, CustomBossesConfigFields>()
    beforeEach {
        paper = MockBukkitTestRuntime.open()
        point = Location(paper.addSimpleWorld("arena"), 10.0, 64.0, 20.0)
        definitions.clear()
        mockkStatic(ArenaMenuConfig::class, CustomBossesConfig::class, PowersConfig::class)
        every { ArenaMenuConfig.getPlayerItemSlot() } returns 6
        every { ArenaMenuConfig.getSpectatorItemSlot() } returns 2
        every { ArenaMenuConfig.getInvalidArenaMessage() } returns "Invalid arena"
        every { CustomBossesConfig.getCustomBoss(any()) } answers { definitions[firstArg()] }
        every { PowersConfig.getPower("custom_summon.yml") } returns mockk<PowersConfigFields>(relaxed = true) {
            every { filename } returns "custom_summon.yml"
        }
        val plugin = paper.createSimplePlugin("ArenaLevels")
        addon = NativeArenaLevels(plugin, Config(Files.createTempDirectory("arena-levels-test"), "arena.yml"), TestTaskScheduler())
        paper.server.pluginManager.registerEvents(addon, plugin)
    }
    afterEach {
        addon.close()
        unmockkStatic(ArenaMenuConfig::class, CustomBossesConfig::class, PowersConfig::class)
        paper.close()
    }

    fun definition(name: String, level: Int, children: List<Any> = emptyList(), phases: List<String> = emptyList()): CustomBossesConfigFields {
        val fields = mockk<CustomBossesConfigFields>(relaxed = true) {
            every { filename } returns name
            every { getLevel() } returns level
            every { getName() } returns "Boss \$level"
            every { powers } returns children
            every { getPhases() } returns phases
        }
        definitions[name] = fields
        return fields
    }

    fun arena(root: String): ArenaInstance = mockk<ArenaInstance>(relaxed = true) {
        every { customArenasConfigFields.waveCount } returns 1
        every { arenaWaves } returns ArenaWaves(listOf("wave=1:spawnpoint=gate:boss=$root"))
        every { container.spawnPoint("gate") } returns point
        every { currentWave } returns 1
        every { state } returns InstancedRegionState.ONGOING
        every { players } returns hashSetOf()
    }

    fun boss(fields: CustomBossesConfigFields, location: Location = point, parent: CustomBossEntity? = null): CustomBossEntity {
        var currentLevel = fields.level
        val data = mutableMapOf<NamespacedKey, Any>()
        return mockk<CustomBossEntity>(relaxed = true) {
            every { customBossesConfigFields } returns fields
            every { spawnLocation } returns location
            every { summoningEntity } returns parent
            every { livingEntity } returns mockk<LivingEntity>(relaxed = true) {
                every { uniqueId } returns UUID.randomUUID()
            }
            every { isNormalizedCombat } returns true
            every { getLevel() } answers { currentLevel }
            every { setLevel(any()) } answers { currentLevel = firstArg() }
            every { maxHealth } returns 400.0
            every { customModel } returns null
            every { getCustomData(any()) } answers { data[firstArg()] }
            every { addCustomData(any(), any()) } answers { data[firstArg()] = secondArg(); Unit }
        }
    }

    "exact root correlation raises before MONITOR and only once for each materialized body" {
        val fields = definition("root.yml", 50)
        val arena = arena(fields.filename)
        addon.lobby(arena).admit(80) { true }
        val actor = boss(fields)
        val observed = mutableListOf<Int>()
        paper.server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.MONITOR)
            fun onSpawn(event: EliteMobSpawnEvent) { observed += event.eliteMobEntity.level }
        }, paper.createSimplePlugin("SpawnObserver"))
        paper.callEvent(EliteMobSpawnEvent(actor))
        paper.callEvent(EliteMobSpawnEvent(actor))
        observed shouldBe listOf(80, 80)
        verify(exactly = 1) { actor.setNormalizedMaxHealth() }
        verify { actor.setHealth(400.0); actor.setName("Boss 80", true) }

        val unrelated = boss(fields, point.clone().add(0.0, 0.0, 1.0))
        paper.callEvent(EliteMobSpawnEvent(unrelated))
        verify(exactly = 0) { unrelated.setLevel(any()) }
        val wrongBoss = boss(definition("outside.yml", 50))
        paper.callEvent(EliteMobSpawnEvent(wrongBoss))
        verify(exactly = 0) { wrongBoss.setLevel(any()) }
    }

    "phase body retains scope and derives its own authored level while children inherit once" {
        val rootFields = definition("root.yml", 50, listOf(
            mapOf("summonType" to "ONCE", "filename" to "inherited.yml", "inheritLevel" to true),
            mapOf("summonType" to "ONCE", "filename" to "explicit.yml"),
        ), listOf("phase.yml:0.5"))
        val inheritedFields = definition("inherited.yml", 100)
        val explicitFields = definition("explicit.yml", 25)
        val phaseFields = definition("phase.yml", 190)
        val arena = arena(rootFields.filename)
        addon.lobby(arena).admit(100) { true }
        val root = boss(rootFields)
        paper.callEvent(EliteMobSpawnEvent(root))
        val child = boss(inheritedFields, point.clone().add(2.0, 0.0, 0.0), root)
        // Stock CustomSummonPower already assigns the effective parent level before spawn.
        child.level = root.level
        paper.callEvent(EliteMobSpawnEvent(child))
        child.level shouldBe 100
        val explicit = boss(explicitFields, point.clone().add(3.0, 0.0, 0.0), root)
        paper.callEvent(EliteMobSpawnEvent(explicit))
        explicit.level shouldBe 75
        every { root.customBossesConfigFields } returns phaseFields
        every { root.livingEntity } returns mockk<LivingEntity>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
        }
        root.level = phaseFields.level
        paper.callEvent(EliteMobSpawnEvent(root))
        root.level shouldBe 200
        verify(exactly = 2) { root.setNormalizedMaxHealth() }
    }

    "original mode and expired runs never rewrite native mob state" {
        val fields = definition("root.yml", 50)
        val arena = arena(fields.filename)
        addon.lobby(arena).admit(50) { true }
        val original = boss(fields)
        paper.callEvent(EliteMobSpawnEvent(original))
        verify(exactly = 0) { original.setLevel(any()); original.setNormalizedMaxHealth(); original.setName(any<String>(), any()) }
        addon.lobby(arena).reset()
        addon.lobby(arena).admit(80) { true }
        val raised = boss(fields)
        paper.callEvent(EliteMobSpawnEvent(raised))
        raised.level shouldBe 80
        addon.onLeave(MatchLeaveEvent(arena, paper.addPlayer("Departed")))
        addon.lobby(arena).locked shouldBe false
        every { raised.livingEntity } returns mockk<LivingEntity>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
        }
        paper.callEvent(EliteMobSpawnEvent(raised))
        verify(exactly = 1) { raised.setLevel(any()) }
    }

    "spectator notifications do not own the lobby and external player fixes baseline" {
        val fields = definition("root.yml", 50)
        val arena = arena(fields.filename)
        val spectator = paper.addPlayer("Spectator")
        every { arena.isSpectator(spectator) } returns true
        addon.onJoin(PlayerJoinArenaEvent(arena, spectator))
        addon.lobby(arena).locked shouldBe false
        val player = paper.addPlayer("Player")
        addon.onJoin(PlayerJoinArenaEvent(arena, player))
        addon.lobby(arena).locked shouldBe true
        addon.lobby(arena).selected shouldBe 50
    }

    "stock command and NPC routes restore only the exact owned replacement" {
        val native = paper.createSimplePlugin("EliteMobs")
        // Bukkit exposes no public PluginCommand constructor; this is only the mocked plugin descriptor fixture.
        val command = PluginCommand::class.java.getDeclaredConstructor(String::class.java, Plugin::class.java)
            .apply { isAccessible = true }.newInstance("elitemobs", native)
        paper.server.commandMap.register("elitemobs", command)
        val original = ArenaCommand()
        val manager = CommandManager(native, "elitemobs").also { it.commands.add(original) }
        val npc = NPCsConfigFields("master.yml", true).apply {
            interactionType = NPCInteractionType.ARENA_MASTER
            arenaFilename = "test.yml"
            setCommand("previous")
        }
        val changedNpc = NPCsConfigFields("other.yml", true).apply {
            interactionType = NPCInteractionType.ARENA_MASTER
            arenaFilename = "test.yml"
        }
        mockkStatic(NPCsConfig::class, EntityTracker::class)
        try {
            every { NPCsConfig.getNpcEntities() } returns hashMapOf("master.yml" to npc, "other.yml" to changedNpc)
            every { EntityTracker.getNpcEntities() } returns hashMapOf()
            addon.install()
            manager.commands.size shouldBe 2
            manager.commands.first().permission shouldBe original.permission
            manager.commands.first().argumentsMap shouldBe original.argumentsMap
            npc.interactionType shouldBe NPCInteractionType.COMMAND
            npc.command shouldBe "em arcarenalevels test.yml"
            val npcVisitor = paper.addPlayer("NpcVisitor")
            manager.onCommand(npcVisitor, command, "em", arrayOf("arcarenalevels", "missing.yml")) shouldBe true
            manager.onTabComplete(npcVisitor, command, "em", arrayOf("arca")) shouldBe listOf("arcarenalevels")
            changedNpc.command = "new-owner-command"
            addon.close()
            (manager.commands.single() === original) shouldBe true
            npc.interactionType shouldBe NPCInteractionType.ARENA_MASTER
            npc.command shouldBe "previous"
            changedNpc.interactionType shouldBe NPCInteractionType.COMMAND
            changedNpc.command shouldBe "new-owner-command"
        } finally {
            unmockkStatic(NPCsConfig::class, EntityTracker::class)
        }
    }
})
