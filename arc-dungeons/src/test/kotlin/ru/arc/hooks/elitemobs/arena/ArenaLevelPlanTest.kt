package ru.arc.hooks.elitemobs.arena

import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfigFields
import com.magmaguy.elitemobs.config.custombosses.CustomBossesConfig
import com.magmaguy.elitemobs.config.powers.PowersConfig
import com.magmaguy.elitemobs.config.powers.PowersConfigFields
import com.magmaguy.elitemobs.config.powers.LuaPowerConfigFields
import com.magmaguy.elitemobs.config.luapowers.premade.AttackFireballLuaConfig
import com.magmaguy.elitemobs.config.luapowers.premade.ZombieFriendsLuaConfig
import com.magmaguy.elitemobs.magmacore.scripting.ScriptDefinition
import com.magmaguy.elitemobs.instanced.arena.ArenaInstance
import com.magmaguy.elitemobs.instanced.arena.ArenaWaves
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.File

class ArenaLevelPlanTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach {
        paper = MockBukkitTestRuntime.open()
        mockkStatic(PowersConfig::class, CustomBossesConfig::class)
        every { PowersConfig.getPower("custom_summon.yml") } returns mockk<PowersConfigFields>(relaxed = true) {
            every { filename } returns "custom_summon.yml"
        }
    }
    afterEach {
        unmockkStatic(PowersConfig::class, CustomBossesConfig::class)
        paper.close()
    }

    fun boss(name: String, level: Int, children: List<Any> = emptyList(), phases: List<String> = emptyList()) =
        mockk<CustomBossesConfigFields>(relaxed = true) {
            every { filename } returns name
            every { getLevel() } returns level
            every { powers } returns children
            every { getPhases() } returns phases
        }

    fun inspect(definitions: Map<String, CustomBossesConfigFields>, roots: List<String>): ArenaLevelPlan {
        every { CustomBossesConfig.getCustomBoss(any()) } answers { definitions[firstArg()] }
        val arena = mockk<ArenaInstance>(relaxed = true)
        every { arena.customArenasConfigFields.waveCount } returns roots.size
        every { arena.arenaWaves } returns ArenaWaves(roots.mapIndexed { index, name ->
            "wave=${index + 1}:spawnpoint=gate:boss=$name"
        })
        return ArenaLevelPlan.inspect(arena, 200, definitions::get)
    }

    "first-wave baseline and whole-run range preserve every authored wave" {
        val definitions = mapOf("first.yml" to boss("first.yml", 51), "last.yml" to boss("last.yml", 100))
        val plan = inspect(definitions, listOf("first.yml", "last.yml"))
        plan.supported shouldBe true
        plan.baseline shouldBe 51
        plan.maximum shouldBe 151
        plan.range(1, 81) shouldBe 81..81
        plan.range(2, 81) shouldBe 130..130
        plan.waves.keys shouldBe setOf(1, 2)
        plan.level(250, 0) shouldBe 250
        plan.level(190, 30) shouldBe 200
    }

    "native summon parser distinguishes inherited child from explicit child and closes phases" {
        val definitions = mapOf(
            "iron.yml" to boss("iron.yml", 50, listOf(
                mapOf("summonType" to "ONCE", "filename" to "child.yml", "inheritLevel" to true),
                "summon:onhit:0.2:explicit.yml",
            ), listOf("phase.yml:0.5")),
            "child.yml" to boss("child.yml", 100),
            "explicit.yml" to boss("explicit.yml", 25),
            "phase.yml" to boss("phase.yml", 70),
        )
        val plan = inspect(definitions, listOf("iron.yml"))
        plan.supported shouldBe true
        plan.bosses.keys shouldBe definitions.keys
        plan.bosses.getValue("iron.yml").summons shouldBe mapOf("child.yml" to true, "explicit.yml" to false)
    }

    "dynamic phase and ambiguous child declarations fall back to original difficulty" {
        val dynamic = mapOf(
            "root.yml" to boss("root.yml", 50, phases = listOf("dynamic.yml:0.5")),
            "dynamic.yml" to boss("dynamic.yml", -1),
        )
        inspect(dynamic, listOf("root.yml")).supported shouldBe false
        val ambiguous = mapOf(
            "root.yml" to boss("root.yml", 50, listOf(true, false).map {
                mapOf("summonType" to "ONCE", "filename" to "child.yml", "inheritLevel" to it)
            }),
            "child.yml" to boss("child.yml", 100),
        )
        inspect(ambiguous, listOf("root.yml")).supported shouldBe false
        inspect(emptyMap(), listOf("missing.yml")).supported shouldBe false
    }

    "only audited unchanged Lua can enable raised mode" {
        fun power(filename: String, source: String) = LuaPowerConfigFields(filename, File(filename),
            ScriptDefinition(filename, File(filename), source, 0, emptySet()))
        val stock = AttackFireballLuaConfig()
        ArenaPowerCapabilities.scripts(power(stock.filename, stock.source)) shouldBe emptyList()
        shouldThrow<IllegalArgumentException> {
            ArenaPowerCapabilities.scripts(power(stock.filename, stock.source + "\n-- modified"))
        }
        val summoner = ZombieFriendsLuaConfig()
        shouldThrow<IllegalArgumentException> {
            ArenaPowerCapabilities.scripts(power(summoner.filename, summoner.source))
        }
    }

    "failed and reentrant admissions cannot lock or replace the successful lobby choice" {
        val lobby = ArenaLevelLobby(ArenaLevelPlan(50, 150, 200, emptyMap(), emptyMap()))
        lobby.admit(90) {
            lobby.admit(100) { error("Reentrant admission must not reach native match") } shouldBe false
            false
        } shouldBe false
        lobby.locked shouldBe false
        lobby.selected shouldBe 50
        lobby.admit(80) { lobby.observePlayer(); true } shouldBe true
        lobby.selected shouldBe 80
        lobby.locked shouldBe true
        lobby.admit(90) { error("Locked choice must not reach native match") } shouldBe false
        lobby.admit(80) { true } shouldBe true
        lobby.reset()
        lobby.locked shouldBe false
        lobby.selected shouldBe 50
        lobby.generation shouldBe 1
    }

    "external player fixes baseline while an automatic menu refresh preserves stale acknowledgement" {
        val lobby = ArenaLevelLobby(ArenaLevelPlan(50, 150, 200, emptyMap(), emptyMap()))
        val view = ArenaMenuSelection(lobby, true)
        view.step(30, lobby)
        lobby.observePlayer()
        view.reconcile(lobby, false)
        view.level shouldBe 50
        view.reconcile(lobby, false)
        view.acknowledge() shouldBe true
        view.acknowledge() shouldBe false
        lobby.reset()
        view.reconcile(lobby, true)
        view.acknowledge() shouldBe true
        view.step(1000, lobby)
        view.level shouldBe 150
        view.step(-1000, lobby)
        view.level shouldBe 50
    }
})
