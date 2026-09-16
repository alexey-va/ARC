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
})
