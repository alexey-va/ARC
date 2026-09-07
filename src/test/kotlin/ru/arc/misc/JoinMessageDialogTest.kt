package ru.arc.misc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.commands.arc.subcommands.JoinMessageSubCommand
import ru.arc.commands.arc.subcommands.QuitMessageSubCommand
import ru.arc.config.Config
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class JoinMessageDialogTest : FreeSpec({
    val directory = Files.createTempDirectory("join-message-dialog")
    val config = Config(directory, "modules/join-message-dialog.yml")
    val runOnMain: (() -> Unit) -> Unit = { it() }
    val dialogs = JoinMessageDialogs(config, runOnMain)
    val player = mockk<Player>(relaxed = true)
    var screen: PaperDialogScreen? = null
    var dismiss: (() -> Unit)? = null
    var data = JoinMessagesData("Viewer")
    val permissions = mutableSetOf<String>()
    fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    fun click(id: String, input: String = "") {
        val context = mockk<PaperDialogClickContext>()
        every { context.player } returns player
        every { context.text(any()) } returns input
        val current = checkNotNull(screen)
        val button = (current.buttons + listOfNotNull(current.exitButton)).single { it.id.value == id }
        // Closing between screens returns mouse control to the game and recenters the cursor.
        button.closeDialogBeforeAction shouldBe false
        button.onClick.handle(context)
    }

    beforeEach {
        screen = null
        dismiss = null
        data = JoinMessagesData("Viewer")
        permissions.clear()
        permissions += "arc.join.message.gui"
        mockkObject(ArcMenus, JoinMessagesManager, JoinMessageCatalogManager, JoinMessageSubCommand, QuitMessageSubCommand)
        mockkStatic(JoinMessagesManager::class, JoinMessageCatalogManager::class)
        every { JoinMessageSubCommand.permission } returns "arc.join.message.gui"
        every { QuitMessageSubCommand.permission } returns "arc.join.message.gui"
        every { player.name } returns "Viewer"
        every { player.uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000001")
        every { player.isOnline } returns true
        every { player.hasPermission(any<String>()) } answers { firstArg<String>() in permissions }
        every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers {
            screen = secondArg()
            dismiss = arg(4)
        }
        every { JoinMessagesManager.getOrCreateAsync("Viewer") } answers { CompletableFuture.completedFuture(data) }
        every { JoinMessagesManager.updateMessageAsync("Viewer", any(), any(), any()) } answers {
            data.updateMessage(secondArg(), thirdArg(), arg(3))
            CompletableFuture.completedFuture(Unit)
        }
        every { JoinMessagesManager.removeMessagesAsync("Viewer", any(), any()) } answers {
            data.removeMessages(secondArg(), thirdArg())
            CompletableFuture.completedFuture(Unit)
        }
        every { JoinMessagesManager.addCustomMessageAsync("Viewer", any(), any()) } answers {
            data.addCustomMessage(secondArg(), thirdArg())
            CompletableFuture.completedFuture(Unit)
        }
        every { JoinMessagesManager.editCustomMessageAsync("Viewer", any(), any(), any()) } answers {
            runCatching { data.editCustomMessage(secondArg(), thirdArg(), arg(3)); Unit }
                .fold({ CompletableFuture.completedFuture(it) }, { CompletableFuture.failedFuture(it) })
        }
        every { JoinMessagesManager.selectCustomMessageAsync("Viewer", any(), any(), any()) } answers {
            data.updateMessage(CustomJoinMessage.selectionKey(secondArg()), thirdArg(), arg(3))
            CompletableFuture.completedFuture(Unit)
        }
        every { JoinMessagesManager.deleteCustomMessageAsync("Viewer", any(), any()) } answers {
            data.deleteCustomMessage(secondArg(), thirdArg())
            CompletableFuture.completedFuture(Unit)
        }
        val entries = (1..13).map {
            JoinMessageCatalogEntry(id = "join-$it", message = "<gold>%player_name% <gray>принёс уют $it",
                permission = if (it == 2) "rank.vip" else null)
        }
        every { JoinMessageCatalogManager.currentAsync() } returns CompletableFuture.completedFuture(
            JoinMessageCatalog(revision = "test", joinPrefix = "<green>● ", leavePrefix = "<red>◆ ", join = entries, leave = listOf(JoinMessageCatalogEntry(id = "leave-1", message = "%player_name% ушёл"))),
        )
        val scheduler = mockk<TaskScheduler>()
        every { scheduler.runSync(any()) } answers { firstArg<Runnable>().run(); mockk<ScheduledTask>(relaxed = true) }
        every { scheduler.cancelAll() } just Runs
        every { scheduler.close() } just Runs
        Tasks.install(scheduler)
    }
    afterEach {
        Tasks.reset()
        unmockkAll()
    }
    afterSpec { directory.toFile().deleteRecursively() }

    "pending catalog keeps loading screen and ignores completion after dismiss" {
        val catalog = CompletableFuture<JoinMessageCatalog>()
        val preferences = CompletableFuture<JoinMessagesData>()
        every { JoinMessageCatalogManager.currentAsync() } returns catalog
        every { JoinMessagesManager.getOrCreateAsync("Viewer") } returns preferences

        dialogs.show(player)

        screen!!.id shouldBe "messages.catalog.join"
        dismiss!!.invoke()
        catalog.complete(JoinMessageCatalog(revision = "late"))
        preferences.complete(data)
        screen!!.body.single().text shouldBe Component.text("…")
    }

    "upgrading bundled dialog text preserves operator overrides and adds editor defaults" {
        val legacyDirectory = Files.createTempDirectory(directory, "legacy")
        val legacyFile = legacyDirectory.resolve("modules/join-message-dialog.yml")
        Files.createDirectories(legacyFile.parent)
        javaClass.getResourceAsStream("/modules/join-message-dialog-legacy.yml")!!.use {
            Files.copy(it, legacyFile)
        }
        val old = Config(legacyDirectory, "modules/join-message-dialog.yml")
        old.setString("text.join-title", "Наш заголовок")
        val upgraded = JoinMessageDialogs(old, runOnMain)
        old.string("text.join-title") shouldBe "Наш заголовок"
        old.string("text.custom-list") shouldBe "<#c4a7e7>Мои фразы ›"
        old.string("text.editor-help") shouldContain "%player_name%"
        upgraded.show(player)
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        upgraded.show(player)
        click("custom")
        click("create")
        screen!!.inputs.single().initial shouldContain "%player_name%"
    }

    "full-template defaults migrate utilities without replacing operator labels" {
        val dir = Files.createTempDirectory(directory, "v2")
        val file = dir.resolve("modules/join-message-dialog.yml")
        Files.createDirectories(file.parent)
        javaClass.getResourceAsStream("/modules/join-message-dialog-v2.yml")!!.use { Files.copy(it, file) }
        val old = Config(dir, "modules/join-message-dialog.yml")
        old.setString("text.create", "Моя кнопка")
        JoinMessageDialogs(old, runOnMain)
        old.string("text.create") shouldBe "Моя кнопка"
        old.string("text.custom-list") shouldBe "<#c4a7e7>Мои фразы ›"
        old.string("text.editor-help") shouldContain "точку"
    }

    "content state colors stay separate from utilities even for colored custom templates" {
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        data.addCustomMessage("<#aaa49a>● %player_name% дома", true)
        dialogs.show(player)
        val buttons = screen!!.buttons.associateBy { it.id.value }
        fun colors(c: Component): List<String> = listOfNotNull(c.color()?.asHexString()?.lowercase()) + c.children().flatMap(::colors)
        colors(buttons.getValue("own_0").label).toSet() shouldBe setOf("#9bd48d")
        colors(buttons.getValue("phrase_0").label).toSet() shouldBe setOf("#ffffff")
        mapOf("custom" to "#c4a7e7", "switch" to "#e5ba73", "next" to "#92bed8", "previous" to "#92bed8").forEach { (id, color) ->
            colors(buttons.getValue(id).label).toSet() shouldBe setOf(color)
        }
        plain(buttons.getValue("own_0").tooltip) shouldContain "● Viewer дома"
    }

    "pagination is always last and wraps both ways including a single page" {
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        dialogs.show(player)
        screen!!.buttons.takeLast(4).map { it.id.value } shouldBe listOf("custom", "switch", "previous", "next")
        click("previous")
        plain(screen!!.body[1].text) shouldContain "2/2"
        click("next")
        plain(screen!!.body[1].text) shouldContain "1/2"
        click("switch")
        repeat(2) {
            click("next")
            plain(screen!!.body[1].text) shouldContain "1/1"
            click("previous")
            plain(screen!!.body[1].text) shouldContain "1/1"
        }
    }

    "short pages preserve complete utility and paging rows" {
        for (hasCustom in listOf(false, true)) {
            if (hasCustom) permissions += JoinMessageDialogs.CUSTOM_PERMISSION
            dialogs.show(player, startPage = 1)
            val ids = screen!!.buttons.map { it.id.value }
            (ids.indexOf("previous") % 2) shouldBe 0
            (ids.indexOf("switch") % 2) shouldBe 1
            ids.takeLast(2) shouldBe listOf("previous", "next")
            click("empty_phrase")
            plain(screen!!.body[1].text) shouldContain "2/2"
        }
    }

    "two-column pages toggle selections and keep page while switching join and leave" {
        dialogs.show(player)
        screen!!.columns shouldBe 2
        screen!!.buttons.filter { it.id.value.startsWith("phrase_") }.size shouldBe 12
        screen!!.buttons.all { it.width == 299 } shouldBe true
        screen!!.buttons.none { it.id.value == "custom" } shouldBe true
        plain(screen!!.buttons.first().label) shouldBe "○ Viewer принёс уют 1"
        click("phrase_0")
        plain(screen!!.buttons.first().label) shouldContain "✔ Viewer"
        click("phrase_0")
        data.selectedMessages(true) shouldBe emptySet()
        click("next")
        plain(screen!!.body[1].text) shouldContain "2/2"
        click("previous")
        plain(screen!!.body[1].text) shouldContain "1/2"
        click("next")
        click("phrase_0")
        plain(screen!!.body[1].text) shouldContain "2/2"
        screen!!.buttons.count { it.id.value.startsWith("phrase_") } shouldBe 1
        click("next")
        click("switch")
        plain(screen!!.title) shouldBe "Сообщения при выходе"
        click("phrase_0")
        data.selectedMessages(false) shouldBe setOf("%player_name% ушёл")
    }

    "personal and preset phrases share pagination and personal toggles keep their page" {
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        (1..10).forEach { data.addCustomMessage("своя фраза $it", true) }
        dialogs.show(player)
        plain(screen!!.body[1].text) shouldContain "1/2"
        plain(screen!!.buttons.first().label) shouldBe "★ ✔ ● Viewer своя фраза 1"
        plain(screen!!.buttons.first().tooltip) shouldContain "Своя фраза"
        val phrases = mutableListOf<String>()
        repeat(2) { page ->
            phrases += screen!!.buttons.filter { it.id.value.startsWith("own_") || it.id.value.startsWith("phrase_") }
                .map { plain(it.label) }
            if (page < 1) click("next")
        }
        phrases.size shouldBe 23
        phrases.distinct().size shouldBe 23
        phrases.take(10).all { it.startsWith("★") } shouldBe true
        dialogs.show(player, startPage = 0)
        screen!!.buttons.count { it.id.value.startsWith("own_") } shouldBe 10
        screen!!.buttons.count { it.id.value.startsWith("phrase_") } shouldBe 2
        click("own_0")
        plain(screen!!.body[1].text) shouldContain "1/2"
        plain(screen!!.buttons.first().label) shouldBe "★ ○ ● Viewer своя фраза 1"
        (CustomJoinMessage.selectionKey("своя фраза 1") in data.selectedMessages(true)) shouldBe false
        click("own_0")
        (CustomJoinMessage.selectionKey("своя фраза 1") in data.selectedMessages(true)) shouldBe true
        data.customMessages(true).size shouldBe 10
        click("switch")
        screen!!.buttons.none { it.id.value.startsWith("own_") } shouldBe true
    }

    "personal-only catalog stays visible and revoked custom permission blocks toggles" {
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        data.addCustomMessage("принёс чай", true)
        every { JoinMessageCatalogManager.currentAsync() } returns CompletableFuture.completedFuture(JoinMessageCatalog())
        dialogs.show(player)
        screen!!.body.size shouldBe 2
        screen!!.buttons.first().id.value shouldBe "own_0"
        permissions -= JoinMessageDialogs.CUSTOM_PERMISSION
        click("own_0")
        verify(exactly = 0) { JoinMessagesManager.selectCustomMessageAsync(any(), any(), any(), any()) }
        data.selectedMessages(true) shouldBe setOf("%player_name% принёс чай")
        data.updateMessage("%player_name% принёс чай", true, false)
        dialogs.show(player)
        plain(screen!!.buttons.first().label) shouldBe "★ [Недоступно] ● Viewer принёс чай"
        screen!!.buttons.none { it.id.value == "custom" } shouldBe true
    }

    "permissions are rechecked on every callback including grants revoked after opening" {
        dialogs.show(player)
        click("phrase_1")
        data.selectedMessages(true) shouldBe emptySet()
        permissions.clear()
        click("phrase_0")
        data.selectedMessages(true) shouldBe emptySet()
        verify(exactly = 0) { JoinMessagesManager.updateMessageAsync(any(), any(), any(), any()) }
    }

    "opening prunes retired catalog selections without deleting saved custom phrases" {
        data.updateMessage("retired", true, true)
        data.addCustomMessage("принёс чай", true)
        dialogs.show(player)
        data.selectedMessages(true) shouldBe setOf("%player_name% принёс чай")
        data.customMessages(true) shouldBe setOf("принёс чай")
    }

    "a retired or newly restricted catalog entry cannot be selected from an older screen" {
        dialogs.show(player)
        every { JoinMessageCatalogManager.currentAsync() } returns CompletableFuture.completedFuture(
            JoinMessageCatalog(revision = "changed", join = listOf(JoinMessageCatalogEntry(
                id = "join-1", message = "<gold>%player_name% <gray>принёс уют 1", permission = "rank.vip",
            ))),
        )
        click("phrase_0")
        data.selectedMessages(true) shouldBe emptySet()
        every { JoinMessageCatalogManager.currentAsync() } returns CompletableFuture.completedFuture(JoinMessageCatalog(revision = "removed"))
        click("phrase_0")
        data.selectedMessages(true) shouldBe emptySet()
    }

    "custom editor validates previews saves toggles and deletes with separate permission" {
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        dialogs.show(player)
        click("custom")
        click("create")
        screen!!.inputs.single().maxLength shouldBe 512
        click("preview", "<red>инъекция")
        screen!!.body.size shouldBe 2
        click("preview", "%player_name% принёс чай")
        plain(screen!!.body.first().text) shouldBe "Viewer принёс чай"
        data.customMessages(true) shouldBe emptySet()
        click("save")
        data.customMessages(true) shouldBe setOf("%player_name% принёс чай")
        click("custom_0")
        click("toggle")
        data.selectedMessages(true) shouldBe emptySet()
        data.customMessages(true) shouldBe setOf("%player_name% принёс чай")
        click("custom_0")
        click("delete")
        data.customMessages(true) shouldBe emptySet()
        data.selectedMessages(true) shouldBe emptySet()
    }

    "edit replaces enabled and disabled templates and formatting help retains the draft for both kinds" {
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        for (isJoin in listOf(true, false)) {
            data.addCustomMessage("старая фраза", isJoin)
            dialogs.show(player, isJoin)
            click("custom")
            click("custom_0")
            click("edit")
            screen!!.inputs.single().initial shouldBe (if (isJoin) "<green>● " else "<red>◆ ") + "%player_name% старая фраза"
            val template = "<gold>✦ <aqua>%player_name% <gray>снова с нами"
            click("formatting", template)
            plain(screen!!.body[1].text) shouldContain "<gradient:#92bed8:#ffacd5>"
            click("back")
            screen!!.inputs.single().initial shouldBe template
            click("preview", template)
            plain(screen!!.body.first().text) shouldBe "✦ Viewer снова с нами"
            click("edit")
            screen!!.inputs.single().initial shouldBe template
            click("preview", template)
            click("save")
            data.customMessages(isJoin) shouldBe setOf(template)
            data.selectedMessages(isJoin) shouldBe setOf(CustomJoinMessage.selectionKey(template))
            click("custom_0")
            click("toggle")
            click("custom_0")
            click("edit")
            click("preview", "<red>%player_name% отдыхает")
            click("save")
            data.selectedMessages(isJoin) shouldBe emptySet()
            data.customMessages(isJoin) shouldBe setOf("<red>%player_name% отдыхает")
        }
    }

    "edit rejects a deleted source inline and revoked permission prevents replacement" {
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        data.addCustomMessage("принёс чай", true)
        dialogs.show(player)
        click("custom")
        click("custom_0")
        click("edit")
        click("preview", "<aqua>%player_name% вернулся")
        permissions -= JoinMessageDialogs.CUSTOM_PERMISSION
        click("save")
        verify(exactly = 0) { JoinMessagesManager.editCustomMessageAsync(any(), any(), any(), any()) }
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        data.deleteCustomMessage("принёс чай", true)
        click("save")
        screen!!.id shouldBe "messages.editor.join"
        plain(screen!!.body.last().text) shouldContain "исходная фраза удалена"
        data.customMessages(true) shouldBe emptySet()
    }

    "revoking custom permission at preview prevents saving" {
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        dialogs.show(player)
        click("custom")
        click("create")
        click("preview", "%player_name% принёс чай")
        permissions -= JoinMessageDialogs.CUSTOM_PERMISSION
        click("save")
        verify(exactly = 0) { JoinMessagesManager.addCustomMessageAsync(any(), any(), any()) }
    }
})
