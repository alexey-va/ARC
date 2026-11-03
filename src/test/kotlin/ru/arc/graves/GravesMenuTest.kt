package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import java.util.UUID

class GravesMenuTest : FunSpec({
    test("owner list paginates fresh data with Russian world names and no server or tracking controls") {
        val f = GravesMenuFixture()
        f.views = (1L..7L).map(f::grave) + f.grave(99).copy(ownerId = UUID(2, 3))
        f.menu.open(f.player)
        f.screen.buttons.count { it.id.value.startsWith("grave_") } shouldBe 6
        f.text() shouldContain "Ванильный мир"
        f.text() shouldNotContain "survival"
        f.text() shouldNotContain "vanilla"
        f.text() shouldNotContain "Компас"
        f.screen.buttons.any { "track" in it.id.value } shouldBe false
        f.click("next_page")
        f.screen.buttons.count { it.id.value.startsWith("grave_") } shouldBe 1
        f.views = listOf(f.grave(8))
        f.reopen!!.invoke()
        f.screen.buttons.count { it.id.value.startsWith("grave_") } shouldBe 1
        f.text() shouldContain "8, 64, 8"
        f.literalClose()
    }

    test("command detail opens the chest callback and has no direct claim or tracking action") {
        val f = GravesMenuFixture()
        f.views = listOf(f.grave(1))
        f.menu.openDetail(f.player, f.views.single().id)
        f.screen.id shouldBe "graves.detail"
        f.screen.buttons.map { it.id.value }.toSet() shouldBe setOf("contents", "teleport_grave", "back_to_list")
        f.text() shouldContain "Ванильный мир"
        f.text() shouldNotContain "survival"
        f.literalClose()
        f.click("contents")
        f.openedContents shouldBe f.views.single().id
        f.closes shouldBe 1
        f.teleported shouldBe null
    }

    test("three confirm and return cycles keep explicit literal back and close without closing between dialogs") {
        val f = GravesMenuFixture()
        f.views = listOf(f.grave(1))
        f.menu.open(f.player)
        repeat(3) {
            f.click("grave_0")
            f.literalClose()
            f.click("teleport_grave")
            f.screen.id shouldBe "graves.teleport-confirmation"
            f.literalClose()
            f.click("back_to_detail")
            f.screen.id shouldBe "graves.detail"
            f.click("back_to_list")
            f.screen.id shouldBe "graves.list"
        }
        f.closes shouldBe 0
    }

    test("confirmation keeps its quote but supplies the current owner record to the backend") {
        val f = GravesMenuFixture()
        f.views = listOf(f.grave(1))
        f.menu.openDetail(f.player, f.views.single().id)
        f.click("teleport_grave")
        f.quote = 300.0
        f.views = listOf(f.views.single().copy(x = 55))
        f.reopen!!.invoke()
        f.text() shouldContain "100"
        f.text() shouldContain "55, 64, 1"
        f.click("confirm_teleport")
        f.teleported shouldBe (f.views.single() to 100.0)
        f.closes shouldBe 1
    }

    test("expired or removed records cannot open contents or teleport") {
        val f = GravesMenuFixture()
        f.views = listOf(f.grave(1))
        f.menu.openDetail(f.player, f.views.single().id)
        f.views = emptyList()
        f.click("contents")
        f.screen.id shouldBe "graves.list"
        f.text() shouldContain "больше недоступна"
        f.openedContents shouldBe null
        f.views = listOf(f.grave(2))
        f.menu.openDetail(f.player, f.views.single().id)
        f.click("teleport_grave")
        f.views = listOf(f.views.single().copy(expiresAt = 1))
        f.click("confirm_teleport")
        f.teleported shouldBe null
        f.closes shouldBe 0
    }

    test("unavailable data and empty graves stay distinct and invalid prices do not show confirmation") {
        val f = GravesMenuFixture()
        f.available = false
        f.menu.open(f.player)
        f.text() shouldContain "Не удалось загрузить"
        f.literalClose()
        f.available = true
        f.menu.open(f.player)
        f.text() shouldContain "Активных могил сейчас нет"
        f.views = listOf(f.grave(1))
        f.quote = Double.NaN
        f.menu.openDetail(f.player, f.views.single().id)
        f.click("teleport_grave")
        f.screen.id shouldBe "graves.detail"
        f.text() shouldContain "Стоимость перехода сейчас недоступна"
        f.screen.buttons.any { it.id.value == "teleport_grave" } shouldBe false
    }
})

private class GravesMenuFixture {
    val owner: UUID = UUID(1, 1)
    val player = mockk<Player>(relaxed = true)
    var views = emptyList<GraveView>()
    var available = true
    var quote = 100.0
    var closes = 0
    var openedContents: UUID? = null
    var teleported: Pair<GraveView, Double>? = null
    lateinit var screen: PaperDialogScreen
    var reopen: (() -> Unit)? = null
    val menu: GravesMenu

    init {
        every { player.uniqueId } returns owner
        menu = GravesMenu(
            GravesConfig(ConfigManager.empty()).snapshot(), { if (available) views else null },
            { quote }, { _, grave, price -> teleported = grave to price },
            { Component.text("Ванильный мир") }, { _, id -> openedContents = id },
            showDialog = { _, value, callback -> screen = value; reopen = callback },
            closeDialog = { closes++ },
        )
    }

    fun grave(id: Long) = GraveView(UUID(0, id), owner, "survival", "vanilla", id.toInt(), 64, id.toInt(), id, -1, 50, emptyList())
    fun click(id: String) {
        val context = mockk<PaperDialogClickContext>()
        every { context.player } returns player
        screen.buttons.single { it.id.value == id }.onClick.handle(context)
    }
    fun text(): String = PlainTextComponentSerializer.plainText().serialize(
        Component.empty().append(screen.title).append(screen.body.fold(Component.empty()) { a, b -> a.append(b.text) })
            .append(screen.buttons.fold(Component.empty()) { a, b -> a.append(b.label) }),
    )
    fun literalClose() {
        PlainTextComponentSerializer.plainText().serialize(checkNotNull(screen.exitButton).label) shouldBe "Закрыть"
        checkNotNull(screen.exitButton).closeDialogBeforeAction shouldBe true
    }
}
