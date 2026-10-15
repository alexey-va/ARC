package ru.arc.hooks.elitemobs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class DungeonSkillProgressionMenusTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "overview shows current XP and named next benefit before opening a skill" {
        val fixture = ProgressionFixture(paper.addPlayer("overview"))
        fixture.menus.open(fixture.player)
        fixture.last.id shouldBe "dungeon.progression"
        fixture.last.buttons.count { it.id.value.startsWith("progression_") } shouldBe 4
        fixture.bodyText().contains("Пассивные бонусы навыков действуют только в данжах") shouldBe true
        fixture.bodyText().contains("3 250 / 6 000 опыта") shouldBe true
        fixture.bodyText().replace(Regex("[\\p{Co}\\s]+"), " ").contains("Ур. 25 · Expose Weakness") shouldBe true
        fixture.last.buttons.single { it.id.value == "books" }.label.plain().contains("1") shouldBe true
        val tooltip = fixture.last.buttons.first().tooltip.plain()
        tooltip.startsWith('\n') shouldBe true
        tooltip.endsWith('\n') shouldBe true
        tooltip.contains("\n\n") shouldBe true
        tooltip.lines().none { it.startsWith(' ') } shouldBe true
        tooltip.contains("+2%") shouldBe true
        tooltip.contains("Только в данжах") shouldBe true
        tooltip.contains("бой") shouldBe true
        fixture.click("next")
        fixture.click("next")
        fixture.last.buttons.count { it.id.value.startsWith("progression_") } shouldBe 3
        fixture.last.buttons.indexOfFirst { it.id.value == "previous" } % 2 shouldBe 0
        fixture.click("next")
        fixture.last.title.plain().contains("1 / 3") shouldBe true
    }

    "skill detail keeps existing perks and shows only supported passive bonuses" {
        val fixture = ProgressionFixture(paper.addPlayer("detail"))
        fixture.menus.detail(fixture.player, "SWORDS", 1)
        fixture.bodyText().contains("Скорость ближних атак") shouldBe true
        fixture.bodyText().contains("+2%") shouldBe true
        fixture.bodyText().replace(Regex("[\\p{Co}\\s]+"), " ").contains("+2% только в данжах") shouldBe true
        fixture.bodyText().contains("Expose Weakness") shouldBe true
        fixture.click("perks")
        fixture.openedPerks shouldBe listOf("SWORDS" to 1)
        fixture.menus.detail(fixture.player, "BOWS")
        fixture.bodyText().contains("Скорость") shouldBe false
        fixture.last.buttons.none { it.id.value == "books" } shouldBe true
        fixture.last.buttons.any { it.id.value == "perks" } shouldBe true
    }

    "loading disabled empty and capped states never invent XP or spend controls" {
        val fixture = ProgressionFixture(paper.addPlayer("states"))
        fixture.view = DungeonProgressionView(DungeonProgressionAvailability.LOADING)
        fixture.menus.open(fixture.player)
        fixture.last.buttons.map { it.id.value } shouldBe listOf("refresh")
        fixture.bodyText().contains("0 /") shouldBe false
        fixture.view = DungeonProgressionView(DungeonProgressionAvailability.DISABLED)
        fixture.menus.open(fixture.player)
        fixture.last.buttons.isEmpty() shouldBe true
        fixture.view = DungeonProgressionView(DungeonProgressionAvailability.READY)
        fixture.menus.books(fixture.player)
        fixture.bodyText().contains("Книг пока нет") shouldBe true
        fixture.bodyText().contains("10% заработанного опыта") shouldBe true
        fixture.last.buttons.none { it.id.value.startsWith("book_") } shouldBe true
        val capped = progressionSkills().first().copy(maxLevel = 24)
        fixture.view = DungeonProgressionView(DungeonProgressionAvailability.READY, listOf(capped), listOf(progressionBook()))
        fixture.menus.detail(fixture.player, "SWORDS")
        fixture.bodyText().contains("Максимальный уровень достигнут") shouldBe true
        fixture.last.buttons.none { it.id.value == "books" } shouldBe true
    }

    "one book previews every earned branch and duplicate confirmation cannot submit twice" {
        val fixture = ProgressionFixture(paper.addPlayer("book"))
        fixture.menus.books(fixture.player, "SWORDS")
        fixture.last.title.plain() shouldBe "Книги прохождений · Мечи"
        fixture.bodyText().contains("10% опыта, заработанного каждым навыком за завершённый инстанс") shouldBe true
        fixture.click("book_0")
        fixture.bodyText().contains("Мечи") shouldBe true
        fixture.bodyText().contains("Топоры") shouldBe true
        fixture.bodyText().contains("Броня") shouldBe true
        fixture.bodyText().contains("1 200") shouldBe true
        fixture.bodyText().contains("24 → 25") shouldBe true
        val apply = fixture.last.buttons.single { it.id.value == "apply_book" }
        apply.closeDialogBeforeAction shouldBe false
        apply.onClick.handle(mockk())
        fixture.last.id shouldBe "dungeon.progression.book.confirm"
        fixture.last.buttons.isEmpty() shouldBe true
        apply.onClick.handle(mockk())
        fixture.consumed shouldBe listOf("book-1")
        fixture.view = fixture.view.copy(books = emptyList())
        fixture.complete!!(DungeonProgressionBookResult.APPLIED)
        fixture.last.id shouldBe "dungeon.progression.swords"
        fixture.bodyText().contains("+2 200 опыта") shouldBe true
    }

    "changed preview requires another confirmation and vanished book is reported inline" {
        val fixture = ProgressionFixture(paper.addPlayer("changed"))
        fixture.menus.books(fixture.player)
        fixture.click("book_0")
        fixture.view = fixture.view.copy(books = listOf(progressionBook().copy(gains = listOf(
            DungeonProgressionBookGain("SWORDS", "Мечи", 1200, 25, 26),
        ))))
        fixture.click("apply_book")
        fixture.consumed.isEmpty() shouldBe true
        fixture.bodyText().contains("Прогресс навыков изменился") shouldBe true
        fixture.bodyText().contains("25 → 26") shouldBe true
        fixture.view = fixture.view.copy(books = emptyList())
        fixture.click("apply_book")
        fixture.consumed.isEmpty() shouldBe true
        fixture.last.id shouldBe "dungeon.progression.books"
        fixture.bodyText().contains("Этой книги больше нет") shouldBe true
    }

    "dismissal fences a late result and unknown save never promises a refund" {
        val fixture = ProgressionFixture(paper.addPlayer("late"))
        fixture.menus.books(fixture.player)
        fixture.click("book_0")
        fixture.click("apply_book")
        val count = fixture.screens.size
        fixture.dismiss!!()
        fixture.complete!!(DungeonProgressionBookResult.APPLIED)
        fixture.screens.size shouldBe count
        fixture.menus.books(fixture.player)
        fixture.click("book_0")
        fixture.click("apply_book")
        fixture.complete!!(DungeonProgressionBookResult.FAILED)
        fixture.bodyText().contains("Сохранение ещё не подтверждено") shouldBe true
        fixture.bodyText().contains("Повторно книга не списывается") shouldBe true
        fixture.bodyText().contains("вернули") shouldBe false
    }

    "profile loading during confirmation is distinct from a missing book" {
        val fixture = ProgressionFixture(paper.addPlayer("loading-book"))
        fixture.menus.books(fixture.player)
        fixture.click("book_0")
        fixture.view = fixture.view.copy(availability = DungeonProgressionAvailability.LOADING)
        fixture.click("apply_book")
        fixture.consumed.isEmpty() shouldBe true
        fixture.bodyText().contains("Данные навыков загружаются") shouldBe true
        fixture.bodyText().contains("Этой книги больше нет") shouldBe false
    }

    "a book covering all native skills paginates the preview and still submits the entire book once" {
        val fixture = ProgressionFixture(paper.addPlayer("all-branches"))
        fixture.view = fixture.view.copy(books = listOf(allBranchesBook()))
        fixture.menus.books(fixture.player)
        fixture.click("book_0")
        fixture.bodyText().contains("1–4 из 11 навыков") shouldBe true
        fixture.bodyText().contains("Применится вся книга") shouldBe true
        fixture.click("next")
        fixture.bodyText().contains("5–8 из 11 навыков") shouldBe true
        fixture.click("next")
        fixture.bodyText().contains("9–11 из 11 навыков") shouldBe true
        fixture.bodyText().contains("Жезлы") shouldBe true
        fixture.click("apply_book")
        fixture.consumed shouldBe listOf("book-1")
    }

    "repeated book cycles reuse screen identities without closing between native screens" {
        val fixture = ProgressionFixture(paper.addPlayer("cycles"))
        repeat(3) { cycle ->
            fixture.view = fixture.view.copy(books = listOf(progressionBook().copy(id = "book-$cycle")))
            fixture.menus.open(fixture.player)
            fixture.click("progression_swords")
            fixture.click("books")
            fixture.click("book_0")
            fixture.click("apply_book")
            fixture.view = fixture.view.copy(books = emptyList())
            fixture.complete!!(DungeonProgressionBookResult.APPLIED)
            fixture.last.id shouldBe "dungeon.progression.swords"
            fixture.last.exitButton!!.onClick.handle(mockk())
            fixture.last.id shouldBe "dungeon.progression"
        }
        fixture.screens.flatMap { it.buttons }.none { it.closeDialogBeforeAction } shouldBe true
        fixture.screens.map { it.id }.toSet() shouldBe setOf(
            "dungeon.progression", "dungeon.progression.swords", "dungeon.progression.books.swords", "dungeon.progression.book.confirm",
        )
    }

    "production components export readable overview detail library and confirmation with actual pack frames" {
        val fixture = ProgressionFixture(paper.addPlayer("preview"))
        val selected = mutableListOf<PaperDialogScreen>()
        fixture.menus.open(fixture.player); selected += fixture.last
        fixture.menus.detail(fixture.player, "SWORDS"); selected += fixture.last
        fixture.menus.detail(fixture.player, "STAVES"); selected += fixture.last
        fixture.menus.books(fixture.player); selected += fixture.last
        fixture.click("book_0"); selected += fixture.last
        fixture.click("apply_book"); selected += fixture.last
        fixture.dismiss!!()
        fixture.view = fixture.view.copy(books = emptyList())
        fixture.menus.books(fixture.player); selected += fixture.last
        fixture.view = fixture.view.copy(books = listOf(allBranchesBook()))
        fixture.menus.books(fixture.player)
        fixture.click("book_0"); selected += fixture.last
        fixture.view = fixture.view.copy(books = (1..5).map { run ->
            // Longest enabled INSTANCED_DUNGEON name in the tracked package catalog.
            progressionBook().copy(id = "long-name-run-$run", name = "[lvl 000-020] Primis - Gladius Invasion")
        })
        fixture.menus.books(fixture.player); selected += fixture.last
        fixture.last.buttons.count { it.id.value.startsWith("book_") } shouldBe 3
        fixture.last.buttons.any { it.id.value == "next" } shouldBe true
        fixture.click("book_0"); selected += fixture.last
        fixture.menus.books(fixture.player, "SWORDS")
        fixture.click("book_0")
        fixture.click("apply_book")
        fixture.complete!!(DungeonProgressionBookResult.PENDING)
        selected += fixture.last
        fixture.last.title.plain() shouldBe "Книги прохождений · Мечи"
        fixture.last.buttons.count { it.id.value.startsWith("book_") } shouldBe 3
        fixture.last.buttons.any { it.id.value == "all_books" } shouldBe true
        fixture.bodyText().contains("Книга уже применяется") shouldBe true
        val components = selected.flatMap { screen -> listOf(screen.title) + screen.body.map { it.text } +
            screen.buttons.flatMap { listOf(it.label, it.tooltip) } + listOfNotNull(screen.exitButton?.label) }
        val darkNeutral = setOf(0xaaaaaa, 0x555555, 0x666666, 0x808080, 0x8c8c8c, 0x969696)
        fun inspect(component: Component) {
            (component.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE) shouldBe false
            if ((component as? TextComponent)?.content()?.any { it.isLetterOrDigit() } == true) {
                (component.color()?.value() in darkNeutral) shouldBe false
            }
            component.children().forEach(::inspect)
        }
        components.forEach(::inspect)
        exportProgressionScreens(selected)
    }
})

private class ProgressionFixture(val player: Player) {
    var view = DungeonProgressionView(DungeonProgressionAvailability.READY, progressionSkills(), listOf(progressionBook()))
    val screens = mutableListOf<PaperDialogScreen>()
    val last get() = screens.last()
    val consumed = mutableListOf<String>()
    val openedPerks = mutableListOf<Pair<String, Int>>()
    var complete: ((DungeonProgressionBookResult) -> Unit)? = null
    var dismiss: (() -> Unit)? = null
    private val dungeon = mockk<EMDungeonQol>(relaxed = true)
    init {
        val defaults = YamlConfiguration.loadConfiguration(File("src/main/resources/modules/elitemobs.yml"))
        every { dungeon.text(any(), any(), *anyVararg()) } answers {
            val values = thirdArg<Array<out Pair<String, Component>>>()
            val template = defaults.getString("dungeon-qol.${firstArg<String>()}") ?: secondArg<String>()
            MiniMessage.miniMessage().deserialize(template, TagResolver.resolver(values.map { (name, value) -> Placeholder.component(name, value) }))
        }
    }
    val menus = DungeonSkillProgressionMenus(
        dungeon = dungeon,
        read = { view },
        preview = { _, id -> if (view.availability == DungeonProgressionAvailability.READY) view.books.firstOrNull { it.id == id } else null },
        consume = { _, id, callback -> consumed += id; complete = callback },
        openPerks = { _, skill, page -> openedPerks += skill to page },
        returnTo = {},
        show = { _, screen, _ -> screens += screen },
        showConfirmation = { _, screen, _, onDismiss -> screens += screen; dismiss = onDismiss },
    )
    fun click(id: String) = last.buttons.single { it.id.value == id }.onClick.handle(mockk())
    fun bodyText() = last.body.joinToString("\n") { it.text.plain() }
}

private fun progressionSkills(): List<DungeonProgressionSkill> = listOf(
    "SWORDS" to "Мечи", "AXES" to "Топоры", "ARMOR" to "Броня", "BOWS" to "Луки",
    "CROSSBOWS" to "Арбалеты", "TRIDENTS" to "Трезубцы", "HOES" to "Косы", "MACES" to "Булавы",
    "SPEARS" to "Копья", "STAVES" to "Посохи", "WANDS" to "Жезлы",
).map { (id, name) ->
    val passive = when (id) {
        "ARMOR" -> "Защита от элитных мобов"
        "STAVES", "WANDS" -> "Скорость заклинаний"
        "BOWS", "CROSSBOWS" -> null
        else -> "Скорость ближних атак"
    }
    DungeonProgressionSkill(
        DungeonSkillInfo(id, name, 24, 3250, 6000, 3250.0 / 6000), maxLevel = null,
        gainDescription = if (id in setOf("STAVES", "WANDS"))
            "Ускоряет обычные заклинания посохов и жезлов; отдельные способности сохраняют свои перезарядки."
        else "Опыт за бой с элитными мобами. Оружейная пара развивается автоматически.",
        passive = passive?.let { DungeonProgressionPassive(it, 2.0, 30, 3.0) },
        nextUnlock = nativeNextUnlock(id),
    )
}

/** Existing enabled tier-two definitions from the tracked EliteMobs catalog, not new abilities. */
private fun nativeNextUnlock(skill: String): DungeonProgressionUnlock {
    val (name, description) = when (skill) {
        "SWORDS" -> "Expose Weakness" to "Attacks have a chance to reduce the target's defense."
        "AXES" -> "Executioner" to "Deal massive bonus damage to enemies below 40% health."
        "ARMOR" -> "Adrenaline Surge" to "Gain combat buffs when health drops below 30%. 45s CD."
        "BOWS" -> "Hunter's Mark" to "Mark enemies with your arrows. Marked targets take bonus damage."
        "CROSSBOWS" -> "Explosive Tip" to "Chance for bolts to explode on impact, damaging nearby enemies."
        "TRIDENTS" -> "Poseidon's Favor" to "Gain aquatic buffs when hitting enemies with trident. (Best in water environments)"
        "HOES" -> "Death Mark" to "Mark enemies for death. Marked targets take bonus damage."
        "MACES" -> "Divine Shield" to "When taking fatal damage, become invulnerable briefly. 120s CD."
        "SPEARS" -> "Impaling Strike" to "Chance to cause bleed, dealing damage over time."
        "STAVES" -> "Repel" to "Staff strikes can knock enemies back."
        else -> "Arcing Bolt" to "Missiles can arc to another nearby enemy."
    }
    return DungeonProgressionUnlock(name, 25, description)
}

private fun progressionBook() = DungeonProgressionBook(
    id = "book-1", name = "Катакомбы ледяного короля", earnedAt = 1792080000000,
    gains = listOf(
        DungeonProgressionBookGain("SWORDS", "Мечи", 1200, 24, 25),
        DungeonProgressionBookGain("AXES", "Топоры", 600, 18, 18),
        DungeonProgressionBookGain("ARMOR", "Броня", 400, 22, 22),
    ),
)

private fun allBranchesBook() = progressionBook().copy(gains = progressionSkills().map { entry ->
    DungeonProgressionBookGain(entry.skill.id, entry.skill.name, 100, entry.skill.level, entry.skill.level)
})

private fun Component.plain() = PlainTextComponentSerializer.plainText().serialize(this)

private fun exportProgressionScreens(screens: List<PaperDialogScreen>) {
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
    val metadata = screens.mapIndexed { index, screen ->
        val source = "screen_$index"
        content[source] = buildMap {
            put("title", encoded(screen.title))
            screen.body.forEachIndexed { i, body -> put("body_$i", encoded(body.text)) }
            screen.buttons.forEachIndexed { i, button -> put("button_$i", encoded(button.label)); put("tip_$i", encoded(button.tooltip)) }
            screen.exitButton?.let { put("exit", encoded(it.label)) }
        }
        mapOf("source" to source, "id" to "${screen.id}-$index", "columns" to screen.columns,
            "body" to screen.body.mapIndexed { i, body -> mapOf("key" to "body_$i", "width" to body.width) },
            "buttons" to screen.buttons.mapIndexed { i, button -> mapOf("id" to button.id.value, "key" to "button_$i", "tip" to "tip_$i", "width" to button.width) },
            "exit" to screen.exitButton?.let { mapOf("width" to it.width) })
    }
    glyphs.any { it in 0xE570..0xE59E } shouldBe true
    val path = Path.of("build/reports/dungeon-progression/content.json")
    Files.createDirectories(path.parent)
    Files.writeString(path, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(mapOf(
        "content" to content, "screens" to metadata,
        "glyphs" to glyphs.map { mapOf("character" to String(Character.toChars(it)), "provider" to "minecraft:default") },
    )))
}
