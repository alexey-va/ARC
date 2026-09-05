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
import java.util.concurrent.CompletableFuture

class JoinMessageDialogTest : FreeSpec({
    val directory = Files.createTempDirectory("join-message-dialog")
    val config = Config(directory, "modules/join-message-dialog.yml")
    val dialogs = JoinMessageDialogs(config)
    val player = mockk<Player>(relaxed = true)
    var screen: PaperDialogScreen? = null
    var data = JoinMessagesData("Viewer")
    val permissions = mutableSetOf<String>()
    fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    fun click(id: String, input: String = "") {
        val context = mockk<PaperDialogClickContext>()
        every { context.player } returns player
        every { context.text(any()) } returns input
        val current = checkNotNull(screen)
        (current.buttons + listOfNotNull(current.exitButton)).single { it.id.value == id }.onClick.handle(context)
    }

    beforeEach {
        screen = null
        data = JoinMessagesData("Viewer")
        permissions.clear()
        permissions += "arc.join.message.gui"
        mockkObject(ArcMenus, JoinMessagesManager, JoinMessageCatalogManager, JoinMessageSubCommand, QuitMessageSubCommand)
        mockkStatic(JoinMessagesManager::class, JoinMessageCatalogManager::class)
        every { JoinMessageSubCommand.permission } returns "arc.join.message.gui"
        every { QuitMessageSubCommand.permission } returns "arc.join.message.gui"
        every { player.name } returns "Viewer"
        every { player.isOnline } returns true
        every { player.hasPermission(any<String>()) } answers { firstArg<String>() in permissions }
        every { ArcMenus.openDialog(player, any()) } answers { screen = secondArg() }
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
            JoinMessageCatalog(revision = "test", join = entries, leave = listOf(JoinMessageCatalogEntry(id = "leave-1", message = "%player_name% ушёл"))),
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

    "wide single-column pages toggle selections and keep page while switching join and leave" {
        dialogs.show(player)
        screen!!.columns shouldBe 1
        screen!!.buttons.filter { it.id.value.startsWith("phrase_") }.size shouldBe 6
        screen!!.buttons.all { it.width == 600 } shouldBe true
        screen!!.buttons.none { it.id.value == "custom" } shouldBe true
        plain(screen!!.buttons.first().label) shouldBe "Viewer принёс уют 1"
        click("phrase_0")
        plain(screen!!.buttons.first().label) shouldContain "[Вкл]"
        click("phrase_0")
        data.selectedMessages(true) shouldBe emptySet()
        click("next")
        plain(screen!!.body[1].text) shouldContain "2/3"
        click("phrase_0")
        plain(screen!!.body[1].text) shouldContain "2/3"
        click("next")
        screen!!.buttons.count { it.id.value.startsWith("phrase_") } shouldBe 1
        click("switch")
        plain(screen!!.title) shouldBe "Сообщения при выходе"
        click("phrase_0")
        data.selectedMessages(false) shouldBe setOf("%player_name% ушёл")
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
        screen!!.inputs.single().maxLength shouldBe 120
        click("preview", "<red>инъекция")
        screen!!.body.size shouldBe 2
        click("preview", "принёс чай")
        plain(screen!!.body.first().text) shouldBe "Viewer принёс чай"
        data.customMessages(true) shouldBe emptySet()
        click("save")
        data.customMessages(true) shouldBe setOf("принёс чай")
        click("custom_0")
        click("toggle")
        data.selectedMessages(true) shouldBe emptySet()
        data.customMessages(true) shouldBe setOf("принёс чай")
        click("custom_0")
        click("delete")
        data.customMessages(true) shouldBe emptySet()
        data.selectedMessages(true) shouldBe emptySet()
    }

    "revoking custom permission at preview prevents saving" {
        permissions += JoinMessageDialogs.CUSTOM_PERMISSION
        dialogs.show(player)
        click("custom")
        click("create")
        click("preview", "принёс чай")
        permissions -= JoinMessageDialogs.CUSTOM_PERMISSION
        click("save")
        verify(exactly = 0) { JoinMessagesManager.addCustomMessageAsync(any(), any(), any()) }
    }
})
