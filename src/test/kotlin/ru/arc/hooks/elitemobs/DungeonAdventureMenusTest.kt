package ru.arc.hooks.elitemobs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.io.File

class DungeonAdventureMenusTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    fun entry(number: Int, type: String = "OPEN_DUNGEON", name: String = "Пещера $number") = DungeonCatalogEntry(
        id = "dungeon_$number.yml", name = name, type = type, level = number * 5, lowestLevel = number * 5,
        highestLevel = number * 5, description = listOf("Лесные приключения"), difficulties = emptyList(),
        permissionGranted = true, maxPlayers = 5, dynamic = false, availableLevels = emptyList(),
    )

    fun fixture(player: Player, entries: List<DungeonCatalogEntry>): Triple<DungeonSaveMenus, DungeonAdventureService, MutableList<PaperDialogScreen>> {
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.panelView(player) } returns null
        every { dungeon.lastReturn(player) } returns null
        val defaults = YamlConfiguration.loadConfiguration(File("src/main/resources/modules/elitemobs.yml"))
        every { dungeon.text(any(), any(), *anyVararg()) } answers {
            val values = thirdArg<Array<out Pair<String, Component>>>()
            val template = defaults.getString("dungeon-qol.${firstArg<String>()}") ?: secondArg<String>()
            MiniMessage.miniMessage().deserialize(template, TagResolver.resolver(values.map { (name, value) -> Placeholder.component(name, value) }))
        }
        val data = mockk<DungeonAdventureService>(relaxed = true)
        every { data.catalog(player) } returns entries
        every { data.stats(player) } returns listOf(DungeonAdventureValue("combatLevel", "40"), DungeonAdventureValue("score", "1234"), DungeonAdventureValue("activeQuests", "2"))
        every { data.skills(player) } returns listOf("Мечи", "Топоры", "Луки", "Арбалеты", "Трезубцы", "Мотыги", "Броня", "Булавы", "Копья", "Посохи", "Жезлы")
            .mapIndexed { index, name -> DungeonSkillInfo("SKILL_$index", name, 40, 1250, 4000, 0.3125) }
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon, crystals = { "27" }, adventure = data) { _, screen, _ -> screens += screen }
        return Triple(menus, data, screens)
    }

    "catalog searches literal words and combines them with type filters" {
        val entries = listOf(entry(2, name = "Тёмный лес"), entry(1, "INSTANCED_DUNGEON", "Замок"), entry(3, name = "Ёж и пещера"))
        filterDungeonCatalog(entries, DungeonCatalogQuery("темный лес")).map { it.id } shouldBe listOf("dungeon_2.yml")
        filterDungeonCatalog(entries, DungeonCatalogQuery("еж")).map { it.id } shouldBe listOf("dungeon_3.yml")
        filterDungeonCatalog(entries, DungeonCatalogQuery(kind = "instanced")).map { it.id } shouldBe listOf("dungeon_1.yml")
        filterDungeonCatalog(entries, DungeonCatalogQuery("лесные", "open")).map { it.id } shouldBe listOf("dungeon_2.yml", "dungeon_3.yml")
    }

    "search filter page and detail return preserve the catalog state" {
        val player = paper.addPlayer("searcher")
        val (menus, _, screens) = fixture(player, (1..23).map { entry(it) })
        menus.catalog(player)
        screens.last().columns shouldBe 2
        screens.last().buttons.count { it.id.value.startsWith("dungeon_") } shouldBe 10
        val input = mockk<PaperDialogClickContext>()
        every { input.text(any()) } returns "Пещера"
        screens.last().buttons.single { it.id.value == "search" }.onClick.handle(input)
        screens.last().inputs.single().initial shouldBe "Пещера"
        screens.last().buttons.single { it.id.value == "next" }.onClick.handle(input)
        screens.last().buttons.single { it.id.value == "next" }.onClick.handle(input)
        screens.last().buttons.map { it.id.value }.takeLast(3) shouldBe listOf("refresh", "previous", "next")
        screens.last().buttons.size % screens.last().columns shouldBe 0
        screens.last().buttons.indexOfFirst { it.id.value == "previous" } % screens.last().columns shouldBe 0
        val beforeDetail = screens.last().buttons.filter { it.id.value.startsWith("dungeon_") }.map { it.label }
        screens.last().buttons.first { it.id.value.startsWith("dungeon_") }.onClick.handle(input)
        screens.last().id shouldBe "dungeon.catalog.detail"
        screens.last().exitButton!!.onClick.handle(input)
        screens.last().inputs.single().initial shouldBe "Пещера"
        screens.last().buttons.filter { it.id.value.startsWith("dungeon_") }.map { it.label } shouldBe beforeDetail
        every { input.text(any()) } returns "нет такого данжа"
        screens.last().buttons.single { it.id.value == "search" }.onClick.handle(input)
        screens.last().buttons.none { it.id.value.startsWith("dungeon_") } shouldBe true
        screens.last().inputs.single().initial shouldBe "нет такого данжа"
        screens.last().buttons.map { it.id.value } shouldBe listOf("filter_all", "filter_open", "filter_instanced", "search")
    }

    "instance panel isolates its final global button and returns to the context without closing" {
        val player = paper.addPlayer("runner")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("dungeon-run")
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", instanced = true), null)
        every { dungeon.insideInstance(player) } returns true
        every { dungeon.continuation(player) } returns null
        every { dungeon.text(any(), any(), *anyVararg()) } answers { MiniMessage.miniMessage().deserialize(secondArg<String>()) }
        val data = mockk<DungeonAdventureService>(relaxed = true)
        every { data.stats(player) } returns emptyList()
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon, adventure = data) { _, screen, _ -> screens += screen }
        menus.panel(player)
        val panel = screens.last()
        panel.columns shouldBe 2
        panel.buttons.last().id.value shouldBe "global"
        panel.buttons.dropLast(1).size % panel.columns shouldBe 0
        panel.buttons.last().closeDialogBeforeAction shouldBe false
        panel.buttons.last().onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.main"
        screens.last().buttons.single { it.id.value == "current" }.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.panel"
    }

    "catalog and travel remain readable inside an instance but cannot leave it" {
        val player = paper.addPlayer("locked-runner")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("locked-world")
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", instanced = true), null)
        every { dungeon.insideInstance(player) } returns true
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val data = mockk<DungeonAdventureService>(relaxed = true)
        every { data.catalog(player) } returns listOf(entry(1))
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon, adventure = data) { _, screen, _ -> screens += screen }
        menus.catalog(player)
        screens.last().buttons.single { it.id.value == "dungeon_0" }.onClick.handle(mockk())
        screens.last().buttons.single { it.id.value == "enter_0" }.also {
            it.closeDialogBeforeAction shouldBe false
            it.onClick.handle(mockk())
        }
        verify(exactly = 0) { data.enter(any(), any(), any(), any()) }
        menus.travelMenu(player)
        screens.last().buttons.single { it.id.value == "spawn" }.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.travel"
        screens.last().buttons.single { it.id.value == "spawn" }.closeDialogBeforeAction shouldBe false
        verify(exactly = 0) { dungeon.action(any(), any(), any()) }
    }

    "skills paginate all types and refresh native perk selections without losing the page" {
        val player = paper.addPlayer("perk-selection")
        val (menus, data, screens) = fixture(player, emptyList())
        val skill = DungeonSkillInfo("SKILL_6", "Броня", 40, 1250, 4000, 0.3125)
        val perk = DungeonSkillPerkInfo("armor_guard", "Стойкость", 1, 10, false, listOf("Снижает входящий урон"), "5%")
        every { data.perks(player, skill.id) } returns DungeonSkillPerkView(skill, 3, listOf(perk))
        every { data.togglePerk(player, skill.id, perk.id) } returns DungeonAdventureActionResult.PERK_ACTIVATED
        menus.skills(player)
        screens.last().buttons.count { it.id.value.startsWith("skill_") } shouldBe 6
        screens.last().buttons.single { it.id.value == "next" }.onClick.handle(mockk())
        val lastSkillsPage = screens.last()
        lastSkillsPage.buttons.count { it.id.value.startsWith("skill_") } shouldBe 5
        lastSkillsPage.buttons.map { it.id.value }.takeLast(3) shouldBe listOf("refresh", "previous", "next")
        lastSkillsPage.buttons.size % lastSkillsPage.columns shouldBe 0
        lastSkillsPage.buttons.indexOfFirst { it.id.value == "previous" } % lastSkillsPage.columns shouldBe 0
        lastSkillsPage.buttons.single { it.id.value == "refresh" }.onClick.handle(mockk())
        screens.last().buttons.count { it.id.value.startsWith("skill_") } shouldBe 5
        screens.last().buttons.map { it.id.value }.takeLast(2) shouldBe listOf("previous", "next")
        screens.last().buttons.single { it.id.value == "skill_0" }.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.skill.perks"
        every { data.perks(player, skill.id) } returns DungeonSkillPerkView(skill, 3, listOf(perk.copy(active = true)))
        screens.last().buttons.single { it.id.value == "perk_0" }.onClick.handle(mockk())
        verify(exactly = 1) { data.togglePerk(player, skill.id, perk.id) }
        PlainTextComponentSerializer.plainText().serialize(screens.last().buttons.single().label).contains("✔") shouldBe true
        screens.last().exitButton!!.onClick.handle(mockk())
        screens.last().buttons.count { it.id.value.startsWith("skill_") } shouldBe 5
    }

    "dynamic entry forwards the selected native level and stable difficulty id" {
        val player = paper.addPlayer("dynamic-selection")
        val dungeon = entry(8, "DYNAMIC_DUNGEON").copy(dynamic = true, availableLevels = listOf(35, 40, 45),
            difficulties = listOf(DungeonDifficultyInfo("hard_id", "Сложная", 45)))
        val (menus, data, screens) = fixture(player, listOf(dungeon))
        every { data.enter(player, dungeon.id, "hard_id", 45) } returns DungeonAdventureActionResult.REQUESTED
        menus.catalog(player)
        screens.last().buttons.single { it.id.value == "dungeon_0" }.onClick.handle(mockk())
        screens.last().numberInputs.single().step shouldBe 5f
        val selected = mockk<PaperDialogClickContext>()
        every { selected.number(any()) } returns 45f
        screens.last().buttons.single { it.id.value == "enter_0" }.onClick.handle(selected)
        verify(exactly = 1) { data.enter(player, dungeon.id, "hard_id", 45) }
    }

    "resolved production components export representative layouts with real frame glyphs" {
        val player = paper.addPlayer("preview")
        val (menus, data, screens) = fixture(player, (1..13).map { entry(it, if (it % 2 == 0) "INSTANCED_DUNGEON" else "OPEN_DUNGEON") })
        val selected = mutableListOf<PaperDialogScreen>()
        menus.main(player); selected += screens.last()
        menus.catalog(player); selected += screens.last()
        screens.last().buttons.single { it.id.value == "dungeon_0" }.onClick.handle(mockk()); selected += screens.last()
        menus.skills(player); selected += screens.last()
        screens.last().buttons.single { it.id.value == "next" }.onClick.handle(mockk()); selected += screens.last()
        menus.statistics(player); selected += screens.last()
        every { data.gear(player) } returns DungeonGearView(
            listOf(DungeonAdventureValue("combatLevel", "40"), DungeonAdventureValue("weaponLevel", "42"),
                DungeonAdventureValue("armorLevel", "38"), DungeonAdventureValue("weaponFactor", "× 1.08"),
                DungeonAdventureValue("critChance", "15%"), DungeonAdventureValue("health", "20"), DungeonAdventureValue("defenseMatch", "23%")),
            listOf(DungeonEquipmentInfo("helmet", "Шлем искателя", 40, listOf("§fУровень: 40")),
                DungeonEquipmentInfo("mainhand", "Клинок лесного стража", 42, listOf("§fУровень: 42"))), emptyList(), emptyList())
        menus.gear(player); selected += screens.last()
        val current = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("preview-instance")
        every { current.panelView(player) } returns DungeonPanelView(world.uid,
            DungeonVisit("run", name = "Королевство гоблинов", instanced = true,
                stats = DungeonVisitStats(level = 40, difficulty = "hard", playerCount = 3)), null)
        every { current.continuation(player) } returns null
        every { current.text(any(), any(), *anyVararg()) } answers {
            val values = thirdArg<Array<out Pair<String, Component>>>()
            val template = YamlConfiguration.loadConfiguration(File("src/main/resources/modules/elitemobs.yml"))
                .getString("dungeon-qol.${firstArg<String>()}") ?: secondArg<String>()
            MiniMessage.miniMessage().deserialize(template, TagResolver.resolver(values.map { (name, value) -> Placeholder.component(name, value) }))
        }
        DungeonSaveMenus(current, crystals = { "27" }, adventure = data) { _, screen, _ -> selected += screen }.panel(player)
        every { data.catalog(player) } returns emptyList()
        menus.catalog(player); selected += screens.last()
        val legacy = LegacyComponentSerializer.builder().character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
        val content = linkedMapOf<String, Map<String, String>>()
        val glyphs = sortedSetOf<Int>()
        fun encoded(component: Component): String {
            fun visit(node: Component) {
                (node as? TextComponent)?.content()?.codePoints()?.forEach { point ->
                    if (point in 0xE000..0xF8FF || point in 0xF0000..0xFFFFD) glyphs += point
                }
                node.children().forEach(::visit)
            }
            visit(component)
            return legacy.serialize(component)
        }
        val meta = selected.mapIndexed { index, screen ->
            val group = "screen_$index"
            content[group] = buildMap {
                put("title", encoded(screen.title))
                screen.body.forEachIndexed { i, body -> put("body_$i", encoded(body.text)) }
                screen.inputs.forEachIndexed { i, input -> put("input_$i", encoded(input.label)); put("initial_$i", input.initial) }
                screen.buttons.forEachIndexed { i, button -> put("button_$i", encoded(button.label)); put("tip_$i", encoded(button.tooltip)) }
                screen.exitButton?.let { put("exit", encoded(it.label)) }
            }
            mapOf("source" to group, "id" to "${screen.id}-$index", "columns" to screen.columns,
                "body" to screen.body.mapIndexed { i, body -> mapOf("key" to "body_$i", "width" to body.width) },
                "buttons" to screen.buttons.mapIndexed { i, button -> mapOf("id" to button.id.value, "key" to "button_$i", "tip" to "tip_$i", "width" to button.width) },
                "inputs" to screen.inputs.mapIndexed { i, input -> mapOf("key" to "input_$i", "initial" to "initial_$i", "width" to input.width, "maxLength" to input.maxLength) },
                "exit" to screen.exitButton?.let { mapOf("width" to it.width) })
        }
        glyphs.any { it in 0xE570..0xE59E } shouldBe true
        val path = Path.of("build/reports/dungeon-adventure/content.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(mapOf("content" to content, "screens" to meta,
            "glyphs" to glyphs.map { mapOf("character" to String(Character.toChars(it)), "provider" to "minecraft:default") })))
        val plain = PlainTextComponentSerializer.plainText()
        selected.first().body.any { plain.serialize(it.text).contains("27") } shouldBe true
        selected.flatMap { it.body }.none { plain.serialize(it.text).contains("Параметр") || plain.serialize(it.text).contains("Значение") } shouldBe true
        val panel = selected.single { it.id == "dungeon.panel" }
        panel.buttons.none { it.id.value == "lost_loot" } shouldBe true
        plain.serialize(panel.buttons.last().label) shouldBe "Главное меню ›"
    }
})
