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

class GravesAdminMenuTest : FunSpec({
    test("network admin list shows current graves from all owners with owner and aliased world only") {
        val f = GravesAdminFixture()
        val current = (1L..7L).map(f::record)
        f.menu.open(f.player, current + listOf(f.record(20).copy(phase = NativeGravePhase.CLAIMED),
            f.record(21).copy(phase = NativeGravePhase.REVIEW), f.record(22).copy(expiresAt = 1)))
        f.screen.buttons.count { it.id.value.startsWith("admin_grave_") } shouldBe 6
        f.text() shouldContain "Owner7 · Ванильный мир"
        f.text() shouldNotContain "survival"
        f.text() shouldNotContain "vanilla"
        f.screen.buttons.none { "claim" in it.id.value || "teleport" in it.id.value } shouldBe true
        PlainTextComponentSerializer.plainText().serialize(f.screen.exitButton!!.label) shouldBe "Закрыть"
        f.click("next_page")
        f.requests shouldBe listOf(1 to null)
        f.menu.open(f.player, current, page = 1)
        f.click("admin_grave_0")
        f.opened shouldBe listOf(f.record(1).id)
    }

    test("revoked admin access prevents stale contents page and refresh callbacks") {
        val f = GravesAdminFixture()
        f.menu.open(f.player, (1L..7L).map(f::record))
        val reopen = f.reopen!!
        f.admin = false
        f.click("admin_grave_0")
        f.click("next_page")
        reopen()
        f.opened shouldBe emptyList()
        f.requests shouldBe emptyList()
        f.closed shouldBe 3
    }

    test("optional server filter and unavailable empty and loading states remain read only") {
        val f = GravesAdminFixture()
        var dismissals = 0
        f.menu.loading(f.player) { dismissals++ }
        f.text() shouldContain "Загружаем"
        f.onDismiss()
        dismissals shouldBe 1
        f.menu.open(f.player, null)
        f.text() shouldContain "Не удалось загрузить"
        f.menu.open(f.player, emptyList())
        f.text() shouldContain "Активных могил по этому запросу нет"
        f.menu.open(f.player, listOf(f.record(1), f.record(2).copy(server = "parkour")), serverFilter = "parkour")
        f.screen.buttons.size shouldBe 1
        f.text() shouldContain "Owner2"
        f.text() shouldNotContain "Owner1"
    }
})

private class GravesAdminFixture {
    val player = mockk<Player>(relaxed = true)
    var admin = true
    var closed = 0
    lateinit var screen: PaperDialogScreen
    var reopen: (() -> Unit)? = null
    var onDismiss: () -> Unit = {}
    val opened = mutableListOf<UUID>()
    val requests = mutableListOf<Pair<Int, String?>>()
    val menu: GravesAdminMenu

    init {
        every { player.isOnline } returns true
        every { player.hasPermission(GRAVES_ADMIN_PERMISSION) } answers { admin }
        menu = GravesAdminMenu(GravesConfig(ConfigManager.empty()).snapshot(), { Component.text("Ванильный мир") },
            { _, page, filter -> requests += page to filter }, { _, id -> opened += id },
            { _, value, callback, dismissed -> screen = value; reopen = callback; onDismiss = dismissed }, { closed++ })
    }

    fun record(id: Long) = NativeGraveRecord(UUID(0, id), UUID(1, id), "Owner$id", "survival", "vanilla", 0, 64, 0, id, -1, 0, emptyList())
    fun click(id: String) {
        val context = mockk<PaperDialogClickContext>()
        every { context.player } returns player
        screen.buttons.single { it.id.value == id }.onClick.handle(context)
    }
    fun text() = PlainTextComponentSerializer.plainText().serialize(screen.buttons.fold(
        screen.body.fold(Component.empty()) { a, b -> a.append(b.text) },
    ) { a, b -> a.append(b.label) })
}
