package ru.arc.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader

class ArcBaseSidebarConfigTest : StringSpec({
    "bundled scoreboard preserves every legacy selection with valid dynamic row counts" {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/scoreboard.yml"))
        val config = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        val styles = requireNotNull(config.getConfigurationSection("styles"))

        styles.getKeys(false).sorted() shouldContainExactly (1..20).map { "style${it.toString().padStart(2, '0')}" }
        styles.getKeys(false).forEach { id ->
            val rows = config.getStringList("styles.$id.lines")
            (rows.size in 1..15) shouldBe true
            rows.first().isNotEmpty() shouldBe true
            rows.last().isNotEmpty() shouldBe true
        }
    }

    "default sidebar keeps ArcRanks rank and tracked quest content after the TAB migration" {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/scoreboard.yml"))
        val config = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        val defaultRows = config.getStringList("styles.style01.lines")
        val profileRows = config.getStringList("styles.style04.lines")

        defaultRows shouldContain "&6| &f%arcranks_rank_name%"
        defaultRows shouldContain "?%arcranks_quest_line_1%"
        defaultRows shouldContain "?&6| %arcranks_quest_line_4%"
        profileRows shouldContain "&6| &fРанг: &e%arcranks_rank_name%"
        profileRows shouldContain "&6| &fСледующий: &e%arcranks_next_rank%"
        (defaultRows + profileRows) shouldNotContain "&6| &f%cmi_user_rank_displayname%"
    }

    "optional quest rows disappear when ArcRanks publishes no text" {
        resolveOptionalSidebarLine("?&6| %arcranks_quest_line_2%") { placeholder ->
            if (placeholder == "%arcranks_quest_line_2%") "" else placeholder
        } shouldBe null

        resolveOptionalSidebarLine("?&6| %arcranks_quest_line_2%") { placeholder ->
            if (placeholder == "%arcranks_quest_line_2%") "&fСобрать пшеницу &a12/64" else placeholder
        } shouldBe "&6| %arcranks_quest_line_2%"
    }

    "default sidebar is compact and ends with the server footer" {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/scoreboard.yml"))
        val config = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        val rows = config.getStringList("styles.style01.lines")

        rows.first() shouldBe "&6| &f%arcranks_rank_name%"
        rows.last() shouldBe "&7Онлайн: &a%online% &7• &fПинг: &e%player_ping% мс"
        rows shouldContain "@survival &6| &f%lands_land_name_plain_here%"
        rows shouldNotContain "&6| &f%player%"
        rows shouldNotContain "&6| &fНаиграно: &e%cmi_user_playtime_hoursf% ч"
        rows shouldNotContain "&6Сервер"
        rows[rows.indexOf("?%arcranks_quest_line_1%") - 1] shouldBe ""
        (rows.indexOf("?&6| %arcranks_quest_line_4%") < rows.lastIndex) shouldBe true
    }

    "scoreboard never uses dark gray text" {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("modules/scoreboard.yml"))
        val config = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        val styles = requireNotNull(config.getConfigurationSection("styles"))

        styles.getKeys(false).flatMap { config.getStringList("styles.$it.lines") }.none { "&8" in it } shouldBe true
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
