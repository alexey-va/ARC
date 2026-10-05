package ru.arc.tablist

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.StringSpec
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader

class TablistSectionTest : StringSpec({
    val brand = listOf("", "Logo", "", "")
    val config = requireNotNull(TablistSectionTest::class.java.classLoader.getResourceAsStream("modules/tablist.yml"))
        .use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }

    "defaults preserve the compact tab and expose all sections independently" {
        config.getConfigurationSection("sections")!!.getKeys(false).toList() shouldContainExactly TablistSection.entries.map { it.id }
        TablistSection.entries.filter { it.defaultEnabled }.map { it.id } shouldContainExactly listOf("profile", "balance", "online", "technical")
        TablistSection.entries.forEach {
            it.enabled(null) shouldBe it.defaultEnabled
            it.enabled("invalid") shouldBe it.defaultEnabled
            it.enabled("TRUE") shouldBe true
            it.enabled("false") shouldBe false
        }
        config.getStringList("slimefun.sections.balance") shouldContainExactly
            listOf("&#F4F6FAСлаймы: &#9BD48D%rediseco_bal_short_slimes% &#FFFFFF")
        tablistEnabled { it == "tab.tablist20" } shouldBe true
        tablistEnabled { false } shouldBe false
    }

    "every section combination keeps complete sections and the visual row budget" {
        for (mask in 0 until (1 shl TablistSection.entries.size)) {
            val selected = TablistSection.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val rows = selected.associateWith { listOf("${it.id}:title", "${it.id}:body") }
            val frame = composeTablistSections(brand, rows, 22)
            val all = (frame.header + "\n" + frame.footer).split('\n')
            val total = frame.header.split('\n').size + if (frame.footer.isEmpty()) 0 else frame.footer.split('\n').size
            (total <= 22) shouldBe true
            selected.forEach { ("${it.id}:title" in all) shouldBe ("${it.id}:body" in all) }
            (frame.header.startsWith("\nLogo\n\n")) shouldBe true
            (frame.footer.contains("\n\n\n")) shouldBe false
        }
    }

    "overflow drops lower priority sections and returns them after another section is hidden" {
        val selected = linkedMapOf(TablistSection.SKILLS to listOf("Навыки", "Рыбалка", "Добыча"),
            TablistSection.TECHNICAL to listOf("Пинг"))
        val crowded = composeTablistSections(brand, selected, 9)
        crowded.footer shouldBe "\nНавыки\nРыбалка\nДобыча\n"
        composeTablistSections(brand, selected - TablistSection.SKILLS, 9).footer shouldBe "\nПинг\n"
        composeTablistSections(brand, mapOf(TablistSection.SKILLS to emptyList()), 22).footer shouldBe ""
    }

    "capacity uses the same spacing as rendering and reserves optional future data" {
        val sections = linkedMapOf(TablistSection.PROFILE to listOf("Player"),
            TablistSection.QUESTS to listOf("?Title", "?Quest 1", "?Quest 2", "?Quest 3"),
            TablistSection.TECHNICAL to listOf("Ping Tps"))
        val capacity = tablistCapacity(brand, sections, 14)
        capacity shouldBe TablistCapacity(3, 14, 14)
        capacity.fits shouldBe true
        capacity.availableRows shouldBe 0
        tablistCapacity(brand, sections + (TablistSection.ONLINE to listOf("Online")), 14).fits shouldBe false
        tablistCapacity(brand, sections - TablistSection.QUESTS, 14).availableRows shouldBe 5
        val frame = composeTablistSections(brand, sections, 14)
        frame.header.split('\n').size + frame.footer.split('\n').size shouldBe capacity.usedRows
        // Optional rows count even while their placeholders are empty; no later overflow.
        tablistCapacity(brand, sections, 13).fits shouldBe false
    }

    "every admitted selection fits even when all reserved rows become visible" {
        listOf("spawn", "survival", "slimefun").forEach { server ->
            val layout = if (server == "slimefun") "slimefun.sections" else "sections"
            for (mask in 0 until (1 shl TablistSection.entries.size)) {
                val sections = TablistSection.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                    .associateWith { section -> config.getStringList("$layout.${section.id}").mapNotNull { ru.arc.sidebar.resolveServerSidebarLine(it, server) } }
                val capacity = tablistCapacity(config.getStringList("brand"), sections, config.getInt("maximum-rows"))
                if (capacity.fits) {
                    val frame = composeTablistSections(config.getStringList("brand"), sections, capacity.maximumRows)
                    val expectedRows = sections.values.flatten()
                    expectedRows.forEach { row -> (row in frame.header || row in frame.footer) shouldBe true }
                }
            }
        }
    }

    "short data is grouped in the established small caps paired style" {
        listOf("coordinates", "rank-progress", "activity", "online", "technical").forEach { id ->
            val rows = config.getStringList("sections.$id")
            rows.size shouldBe 1
            rows.single().contains("<font:arc:small_caps>") shouldBe true
            rows.single().contains("  ") shouldBe true
        }
    }

    "placeholder cleanup preserves literal percent text inside resolved values" {
        resolveTablistTemplate("Место: %region% • %unknown%") { token ->
            if (token == "%region%") "Земля %foo% • 50%" else token
        } shouldBe "Место: Земля %foo% • 50% • "
        resolveTablistTemplate("%arc_worldname% / %lands_land_name_plain_here%") { it } shouldBe "Сервер / Серверная"
    }

    "skills use a Russian heading and two separate rows without a sidebar rail" {
        listOf("sections", "slimefun.sections").forEach { layout ->
            val rows = config.getStringList("$layout.skills")
            rows.size shouldBe 3
            rows.first() shouldBe "&#F6C453Навыки"
            rows.drop(1) shouldContainExactly listOf("?&#F4F6FA%arc_sidebar_skill_1%", "?&#F4F6FA%arc_sidebar_skill_2%")
            rows.none { "|" in it || "&l" in it } shouldBe true
        }
        val sections = TablistSection.entries.filter { it.defaultEnabled }.associateWith { config.getStringList("sections.${it.id}") }
        val frame = composeTablistSections(config.getStringList("brand"), sections, config.getInt("maximum-rows"))
        (frame.header.split('\n').size + frame.footer.split('\n').size <= 22) shouldBe true
    }
})
