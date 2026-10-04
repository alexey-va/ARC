package ru.arc.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader

class ArcBaseSidebarConfigTest : StringSpec({
    "scoreboard hides clients below the configured minimum protocol" {
        isSidebarClientSupported(763, 774) shouldBe false
        isSidebarClientSupported(774, 774) shouldBe true
        isSidebarClientSupported(776, 774) shouldBe true
    }

    "bundled scoreboard uses the server native protocol as its minimum" {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/scoreboard.yml"))
        val config = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }

        config.getInt("minimum-client-protocol") shouldBe 774
    }

    "scoreboard keeps clients with an unknown protocol on the existing path" {
        isSidebarClientSupported(null, 774) shouldBe true
        isSidebarClientSupported(-1, 774) shouldBe true
    }

    "one sidebar keeps six independently configurable sections and valid row counts" {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/scoreboard.yml"))
        val config = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        config.contains("styles") shouldBe false
        requireNotNull(config.getConfigurationSection("sections")).getKeys(false).toList() shouldContainExactly
            SidebarSection.entries.map { it.id }
        val rows = composeSidebarSections({ true }, { config.getStringList("sections.${it.id}") }, { it })
        (rows.size in 1..15) shouldBe true
        rows.first() shouldBe "&6| &f%arcranks_rank_name% &e/rank"
        rows.last() shouldBe "&7Онлайн: &a%online% &7• &fПинг: &e%player_ping% мс"
        (1..3).forEach { rows shouldContain "?&6| %arcranks_quest_board_$it%" }
        rows shouldContain "@survival &6| &f%lands_land_name_plain_here%"
        rows.none { "&8" in it } shouldBe true
    }

    "section composition handles every selection without orphaned rewards or blank groups" {
        val sections = SidebarSection.entries
        for (mask in 0 until (1 shl sections.size)) {
            val visible = sections.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val rows = composeSidebarSections({ it in visible }, { listOf(it.id) }, { it })
            val expected = sections.filter { it in visible && (it != SidebarSection.REWARDS || SidebarSection.QUESTS in visible) }
            rows.filter(String::isNotBlank) shouldContainExactly expected.map { it.id }
            rows.firstOrNull()?.isBlank() shouldBe if (rows.isEmpty()) null else false
            rows.lastOrNull()?.isBlank() shouldBe if (rows.isEmpty()) null else false
            rows.zipWithNext().none { (a, b) -> a.isBlank() && b.isBlank() } shouldBe true
        }
        composeSidebarSections({ true }, { if (it == SidebarSection.QUESTS) listOf("?quest") else emptyList() }, { null }) shouldBe emptyList()
    }

    "legacy choices preserve visibility and section metadata defaults on" {
        (1..20).forEach { index ->
            sidebarEnabled { it == if (index == 1) "tab.scoreboard" else "tab.scoreboard$index" } shouldBe true
        }
        sidebarEnabled { false } shouldBe false
        SidebarSection.entries.forEach {
            it.enabled(null) shouldBe true
            it.enabled("false") shouldBe false
            it.enabled("FALSE") shouldBe false
        }
        SidebarSection.REWARDS.metaKey shouldBe SidebarQuestRewards.META_KEY
    }

    "optional quest rows disappear when ArcRanks publishes no text" {
        resolveOptionalSidebarLine("?&6| %arcranks_quest_board_1%") { placeholder ->
            if (placeholder == "%arcranks_quest_board_1%") "" else placeholder
        } shouldBe null

        resolveOptionalSidebarLine("?&6| %arcranks_quest_board_1%") { placeholder ->
            if (placeholder == "%arcranks_quest_board_1%") "&fСобрать пшеницу &a12/64" else placeholder
        } shouldBe "&6| %arcranks_quest_board_1%"
    }

    "Slimefun sidebar exposes only its local currency and island navigation" {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/scoreboard.yml"))
        val config = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        config.getStringList("enabled-servers") shouldContain "slimefun"
        val rows = composeSidebarSections({ true }, { config.getStringList("slimefun.sections.${it.id}") }, { it })
        (rows.size in 1..15) shouldBe true
        rows shouldContain "&6| &fСлаймы: &a%rediseco_bal_formatted_shorthand_slimes%"
        rows shouldContain "&6| &fМеню: &e/skyblock"
        rows.none { "vault%" in it || "tokens%" in it || "quest" in it || "bank" in it } shouldBe true
    }

    "world aliases cover both spawns and dungeon world families" {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/misc.yml"))
        val config = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }

        config.getString("world-names.spawn") shouldBe "&2Спавн"
        config.getString("world-names.sp11") shouldBe "&2Спавн"
        config.getString("world-names.rc_origin_spawn") shouldBe "&2Спавн"
        config.getString("world-names.em_*") shouldBe "&6Мир данжа"
        config.getString("world-names.spn_*") shouldBe "&6Мир данжа"
        config.getString("world-names.otd_dungeon") shouldBe "&6Мир данжа"
    }

    "server-scoped rows stay off spawn" {
        resolveServerSidebarLine("@survival &6| &f%lands_land_name_plain_here%", "spawn") shouldBe null
        resolveServerSidebarLine("@survival &6| &f%lands_land_name_plain_here%", "survival") shouldBe
            "&6| &f%lands_land_name_plain_here%"
        resolveServerSidebarLine("&6| &f%arc_worldname%", "spawn") shouldBe "&6| &f%arc_worldname%"
    }
})
