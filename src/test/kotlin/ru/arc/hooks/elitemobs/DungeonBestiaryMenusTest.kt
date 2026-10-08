package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DungeonBestiaryMenusTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    val scopes = mutableListOf<LifecycleTaskScope>()
    beforeEach {
        paper = MockBukkitTestRuntime.open()
        scopes.clear()
    }
    afterEach {
        scopes.forEach { it.close() }
        paper.close()
    }

    data class Shown(
        val screen: PaperDialogScreen,
        val reopen: (() -> Unit)?,
        val dismiss: () -> Unit,
    )

    data class Harness(
        val menus: DungeonBestiaryMenus,
        val scheduler: TestTaskScheduler,
        val shown: MutableList<Shown>,
    )

    fun harness(
        entries: (String) -> List<BestiaryMob>,
        load: (UUID) -> CompletableFuture<Set<String>>,
    ): Harness {
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.text(any(), any(), *anyVararg()) } answers {
            val values = thirdArg<Array<out Pair<String, Component>>>()
            MiniMessage.miniMessage().deserialize(secondArg<String>(), TagResolver.resolver(values.map { (name, value) ->
                Placeholder.component(name, value)
            }))
        }
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        scopes += tasks
        val shown = mutableListOf<Shown>()
        val menus = DungeonBestiaryMenus(dungeon, entries, load, tasks) { _, screen, reopen, dismiss ->
            shown += Shown(screen, reopen, dismiss)
        }
        return Harness(menus, scheduler, shown)
    }

    fun mob(
        id: String,
        name: String,
        abilities: List<BestiaryFact> = emptyList(),
        loot: List<BestiaryFact> = emptyList(),
        notes: List<String> = emptyList(),
    ) = BestiaryMob(id, name, "Босс", "42", abilities, loot, notes)

    fun settle(harness: Harness, future: CompletableFuture<Set<String>>, values: Set<String>) {
        future.complete(values)
        harness.scheduler.executeImmediate()
    }

    val plain = PlainTextComponentSerializer.plainText()

    "studied entries sort first by normalized name and then by id" {
        val roster = listOf(
            mob("locked.yml", "Аардварк"),
            mob("b.yml", "Бета"),
            mob("z.yml", "Ёж"),
            mob("a.yml", "Еж"),
        )

        orderDungeonBestiaryEntries(roster, setOf("a.yml", "z.yml", "b.yml")).map(BestiaryMob::id) shouldBe
            listOf("b.yml", "a.yml", "z.yml", "locked.yml")
    }

    "locked cards reveal no names or bestiary facts and cannot be opened" {
        val hidden = mob("hidden.yml", "Безымянный страж", listOf(BestiaryFact("Секретный удар", "Смертельная волна")),
            listOf(BestiaryFact("Секретная руна", "Шанс выпадения: 17%")))
        val future = CompletableFuture<Set<String>>()
        val harness = harness({ listOf(hidden) }, { future })
        val player = paper.addPlayer("locked-bestiary")

        harness.menus.open(player, "crypt.yml", "Крипта") {}
        settle(harness, future, emptySet())

        val screen = harness.shown.last().screen
        val locked = screen.buttons.single { it.id.value == "mob_0" }
        plain.serialize(locked.label) shouldBe "[Недоступно] Неизученный противник"
        val visible = (screen.body.map { plain.serialize(it.text) } + screen.buttons.map { plain.serialize(it.label) + plain.serialize(it.tooltip) })
            .joinToString("\n")
        listOf(hidden.name, "Секретный удар", "Смертельная волна", "Секретная руна", "17%").forEach { visible.contains(it) shouldBe false }
        locked.onClick.handle(mockk<PaperDialogClickContext>())
        harness.shown.size shouldBe 2
    }

    "unlocked detail separates abilities, loot chances, and notes into readable tabs" {
        val opened = mob("opened.yml", "Пепельный голем",
            abilities = listOf(BestiaryFact("Кулак гранита", "Наносит тяжёлый урон и отбрасывает цель.")),
            loot = listOf(BestiaryFact("Осколок ядра", "Шанс выпадения: 12% после победы.")),
            notes = listOf("Уязвим к холоду."))
        val future = CompletableFuture<Set<String>>()
        val harness = harness({ listOf(opened) }, { future })
        val player = paper.addPlayer("opened-bestiary")
        val click = mockk<PaperDialogClickContext>()

        harness.menus.open(player, "crypt.yml", "Крипта") {}
        settle(harness, future, setOf(opened.id))
        harness.shown.last().screen.buttons.single { it.id.value == "mob_0" }.onClick.handle(click)
        harness.shown.last().screen.id shouldBe "dungeon.bestiary.detail"
        var body = harness.shown.last().screen.body.joinToString("\n") { plain.serialize(it.text) }
        body.contains("Кулак гранита") shouldBe true
        body.contains("отбрасывает цель") shouldBe true
        body.contains("Осколок ядра") shouldBe false

        harness.shown.last().screen.buttons.single { it.id.value == "section_loot" }.onClick.handle(click)
        body = harness.shown.last().screen.body.joinToString("\n") { plain.serialize(it.text) }
        body.contains("Осколок ядра") shouldBe true
        body.contains("12%") shouldBe true
        body.contains("Кулак гранита") shouldBe false

        harness.shown.last().screen.buttons.single { it.id.value == "section_notes" }.onClick.handle(click)
        body = harness.shown.last().screen.body.joinToString("\n") { plain.serialize(it.text) }
        body.contains("Уязвим к холоду") shouldBe true
    }

    "unlocked cards and details retain the full name when labels are shortened" {
        val name = "Пробуждённый древний страж Затонувшего храма, хранитель семи печатей и последнего прохода к цитадели"
        val description = "Описывает сложный многоэтапный эффект, который постепенно меняет поведение противника и требует от группы своевременно менять позицию. ".repeat(4)
        val opened = mob("long-name.yml", name, abilities = listOf(BestiaryFact("Испытание печатей", description)))
        val future = CompletableFuture<Set<String>>()
        val harness = harness({ listOf(opened) }, { future })
        val player = paper.addPlayer("long-name-bestiary")

        harness.menus.open(player, "crypt.yml", "Крипта") {}
        settle(harness, future, setOf(opened.id))

        val card = harness.shown.last().screen.buttons.single { it.id.value == "mob_0" }
        plain.serialize(card.label).contains(name) shouldBe false
        plain.serialize(card.tooltip).contains(name) shouldBe true
        card.onClick.handle(mockk<PaperDialogClickContext>())

        val detail = harness.shown.last().screen
        plain.serialize(detail.title).contains(name) shouldBe false
        detail.body.any { it.width == 468 && plain.serialize(it.text).contains(name) } shouldBe true
        detail.body.any { it.width == 468 && plain.serialize(it.text).contains(description) } shouldBe true
    }

    "catalog and detail pagination keep control rows complete on short pages" {
        val mobs = (0 until 13).map { index ->
            mob("mob_$index.yml", "Противник $index", abilities = if (index == 0) {
                (1..3).map { BestiaryFact("Приём $it", "Описание приёма $it.") }
            } else emptyList())
        }
        val future = CompletableFuture<Set<String>>()
        val harness = harness({ mobs }, { future })
        val player = paper.addPlayer("paged-bestiary")
        val click = mockk<PaperDialogClickContext>()

        harness.menus.open(player, "crypt.yml", "Крипта") {}
        settle(harness, future, mobs.map(BestiaryMob::id).toSet())
        var screen = harness.shown.last().screen
        screen.buttons.count { it.id.value.startsWith("mob_") } shouldBe 6
        screen.buttons.size % screen.columns shouldBe 0
        screen.buttons.indexOfFirst { it.id.value == "previous" } / screen.columns shouldBe
            screen.buttons.indexOfFirst { it.id.value == "next" } / screen.columns

        screen.buttons.single { it.id.value == "next" }.onClick.handle(click)
        screen = harness.shown.last().screen
        screen.buttons.count { it.id.value.startsWith("mob_") } shouldBe 6
        screen.buttons.size % screen.columns shouldBe 0

        screen.buttons.single { it.id.value == "next" }.onClick.handle(click)
        screen = harness.shown.last().screen
        screen.buttons.map { it.id.value } shouldBe listOf("mob_12", "catalog_padding", "previous", "next")
        screen.buttons.size % screen.columns shouldBe 0
        screen.buttons.indexOfFirst { it.id.value == "previous" } % screen.columns shouldBe 0

        screen.buttons.single { it.id.value == "mob_12" }.onClick.handle(click)
        harness.shown.last().screen.exitButton!!.onClick.handle(click)
        screen = harness.shown.last().screen
        screen.buttons.map { it.id.value } shouldBe listOf("mob_12", "catalog_padding", "previous", "next")

        screen.buttons.single { it.id.value == "next" }.onClick.handle(click)
        harness.shown.last().screen.buttons.count { it.id.value.startsWith("mob_") } shouldBe 6

        harness.shown.last().screen.buttons.first { it.id.value == "mob_0" }.onClick.handle(click)
        val detail = harness.shown.last().screen
        detail.buttons.size % detail.columns shouldBe 0
        detail.buttons.map { it.id.value }.take(3) shouldBe listOf("section_abilities", "section_loot", "section_notes")
        detail.buttons.indexOfFirst { it.id.value == "previous" } / detail.columns shouldBe
            detail.buttons.indexOfFirst { it.id.value == "next" } / detail.columns
    }

    "dismissal fences a late progress reply" {
        val mob = mob("one.yml", "Страж")
        val future = CompletableFuture<Set<String>>()
        val harness = harness({ listOf(mob) }, { future })
        val player = paper.addPlayer("dismissed-bestiary")

        harness.menus.open(player, "crypt.yml", "Крипта") {}
        harness.shown.last().dismiss()
        settle(harness, future, setOf(mob.id))

        harness.shown.size shouldBe 1
        harness.shown.last().screen.id shouldBe "dungeon.bestiary.catalog"
    }

    "a newer open wins when progress requests complete out of order" {
        val firstMob = mob("first.yml", "Первый страж")
        val secondMob = mob("second.yml", "Второй страж")
        val first = CompletableFuture<Set<String>>()
        val second = CompletableFuture<Set<String>>()
        var request = 0
        val harness = harness({ id -> if (id == "first.yml") listOf(firstMob) else listOf(secondMob) }, {
            if (request++ == 0) first else second
        })
        val player = paper.addPlayer("reordered-bestiary")

        harness.menus.open(player, "first.yml", "Первый данж") {}
        harness.menus.open(player, "second.yml", "Второй данж") {}
        settle(harness, second, setOf(secondMob.id))
        settle(harness, first, setOf(firstMob.id))

        harness.shown.size shouldBe 3
        val visible = harness.shown.last().screen.body.joinToString("\n") { plain.serialize(it.text) } +
            harness.shown.last().screen.buttons.joinToString("\n") { plain.serialize(it.label) }
        visible.contains(secondMob.name) shouldBe true
        visible.contains(firstMob.name) shouldBe false
    }
})
