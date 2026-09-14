package ru.arc.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
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
})
