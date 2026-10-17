package ru.arc.scheduled.guis

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.event.HandlerList
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.commands.arc.subcommands.SchedulesSubCommand
import ru.arc.config.ConfigManager
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.paper.menu.PaperMenuClickContext
import ru.arc.paper.menu.PaperMenuClickTarget
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuSession
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.scheduled.IntervalSchedule
import ru.arc.scheduled.ScheduledCommandEntry
import ru.arc.scheduled.ScheduledCommandsManager
import ru.arc.scheduled.ScheduledCommandsSettings
import ru.arc.scheduled.ValidationResult
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.logging.Logger

class ScheduledCommandsGuiAsyncTest : FunSpec({
    test("cold readiness does not occupy native queue and large list builds one card per step") {
        ScheduledGuiFixture(150).use { f ->
            ScheduledCommandsGuiFactory.openList(f.player)
            var unrelatedRan = false
            f.native.submit({ true }) { unrelatedRan = true; true }
            f.scheduler.tick()
            unrelatedRan shouldBe true
            f.cards shouldBe 0
            f.views.size shouldBe 0
            CompletableFuture.runAsync { f.ready.complete(null) }.join()
            f.scheduler.executeImmediate()
            repeat(30) {
                val before = f.cards
                f.scheduler.tick()
                (f.cards - before <= 7) shouldBe true
            }
            f.cards shouldBe 150
            f.views.single().regions.getValue(ArcMenuSchema.SCHEDULED_ENTRIES).size shouldBe 150
        }
    }

    test("closed navigation superseded request and changed menu generation cannot publish late") {
        ScheduledGuiFixture(30).use { f ->
            ScheduledCommandsGuiFactory.openList(f.player)
            f.paper.callEvent(InventoryCloseEvent(f.player.openInventory))
            f.ready.complete(null)
            f.scheduler.tick(10)
            f.views.size shouldBe 0

            ScheduledCommandsGuiFactory.openList(f.player)
            f.scheduler.executeImmediate()
            f.scheduler.tick()
            f.cards shouldBe 7
            ScheduledCommandsGuiFactory.openEditor(f.player, "entry_0")
            f.scheduler.tick(10)
            f.views.map { it.menu } shouldBe listOf(ArcMenuSchema.SCHEDULED_EDIT)

            ScheduledCommandsGuiFactory.openList(f.player)
            f.scheduler.executeImmediate()
            f.scheduler.tick()
            f.configuration = mockk()
            f.scheduler.tick(10)
            f.views.size shouldBe 1
        }
    }

    test("save acknowledges durability once and a departed editor does not reopen") {
        ScheduledGuiFixture(1).use { f ->
            f.ready.complete(null)
            val saved = CompletableFuture<ValidationResult>()
            every { ScheduledCommandsManager.saveEntryAsync(any()) } returns saved
            ScheduledCommandsGuiFactory.openEditor(f.player, "entry_0")
            f.scheduler.executeImmediate()
            val editor = f.views.single()
            editor.click("save", f)
            editor.click("save", f)
            verify(exactly = 1) { ScheduledCommandsManager.saveEntryAsync(any()) }
            f.player.nextMessage() shouldBe null
            f.views.size shouldBe 1
            saved.complete(ValidationResult.Ok)
            f.scheduler.tick(5)
            ChatColor.stripColor(f.player.nextMessage()) shouldBe "Изменения сохранены"
            f.views.last().menu shouldBe ArcMenuSchema.SCHEDULED_LIST

            val laterSave = CompletableFuture<ValidationResult>()
            every { ScheduledCommandsManager.saveEntryAsync(any()) } returns laterSave
            ScheduledCommandsGuiFactory.openEditor(f.player, "entry_0")
            f.scheduler.executeImmediate()
            val laterEditor = f.views.last()
            laterEditor.click("save", f)
            laterEditor.open = false
            laterSave.complete(ValidationResult.Ok)
            val count = f.views.size
            f.scheduler.tick(5)
            f.views.size shouldBe count
            f.player.nextMessage() shouldBe null
        }
    }

    test("manual run reports actual native result and persistence failure is not success or missing id") {
        ScheduledGuiFixture(1).use { f ->
            f.ready.complete(null)
            val dispatched = CompletableFuture<Boolean>()
            every { ScheduledCommandsManager.runNowAsync("entry_0") } returns dispatched
            SchedulesSubCommand.execute(f.player, arrayOf("run", "entry_0")) shouldBe true
            f.player.nextMessage() shouldBe null
            dispatched.complete(true)
            f.scheduler.executeImmediate()
            ChatColor.stripColor(f.player.nextMessage()) shouldBe "Запущена команда entry_0"

            every { ScheduledCommandsManager.saveEntryAsync(any()) } returns
                CompletableFuture.failedFuture(IllegalStateException("disk unavailable"))
            ScheduledCommandsGuiFactory.openEditor(f.player, "entry_0")
            f.scheduler.executeImmediate()
            f.views.last().click("save", f)
            f.scheduler.executeImmediate()
            ChatColor.stripColor(f.player.nextMessage()) shouldBe "Не удалось выполнить"
            f.views.size shouldBe 1
        }
    }
})

private class ScheduledGuiFixture(count: Int) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val player = paper.addPlayer("Scheduler")
    val scheduler = TestTaskScheduler()
    private val scope = LifecycleTaskScope(scheduler)
    val native = BudgetedTaskQueue(scope, maximumSteps = 7, nanoTime = { 0L })
    val ready = CompletableFuture<Void>()
    val views = mutableListOf<ScheduleView>()
    var configuration: PaperMenuConfiguration = mockk()
    var cards = 0
    private val root = Files.createTempDirectory("scheduled-gui-")
    private val plugin = mockk<ARC>(relaxed = true)

    init {
      try {
        player.isOp = true
        ConfigManager.of(root, "guis/scheduled-commands.yml")
        val entries = List(count) { index -> ScheduledCommandEntry("entry_$index", true, "say $index", null,
            IntervalSchedule(Duration.ofMinutes(5))) }
        val byId = entries.associateBy { it.id }
        val settings = mockk<ScheduledCommandsSettings> {
            every { entries() } returns entries
            every { guiTitle } returns "<gold>Расписание команд"
        }
        mockkObject(ArcMenus, ScheduledCommandsManager)
        mockkStatic(ScheduledCommandsManager::class)
        every { plugin.server } returns paper.server
        every { plugin.pluginLoader } returns paper.createSimplePlugin("ScheduledFixtureHost").pluginLoader
        every { plugin.isEnabled } returns true
        every { plugin.name } returns "ScheduledGuiFixture"
        every { plugin.logger } returns Logger.getLogger("ScheduledGuiFixture")
        every { plugin.dataFolder } returns root.toFile()
        every { plugin.nativeWork } returns native
        every { plugin.readiness() } returns CompletableFuture.completedFuture(null)
        every { plugin.whenCompleteOnMain<Any>(any(), any()) } answers {
            val future = firstArg<CompletionStage<Any>>()
            val callback = secondArg<(Any?, Throwable?) -> Unit>()
            future.whenComplete { result, failure -> scope.callSync { callback(result, failure) } }
            Unit
        }
        ARC.plugin = plugin
        every { ScheduledCommandsManager.readiness() } answers { ready.copy() }
        every { ScheduledCommandsManager.settings() } answers {
            check(ready.isDone && !ready.isCompletedExceptionally)
            settings
        }
        every { ScheduledCommandsManager.entry(any()) } answers { byId[firstArg()] }
        every { ArcMenus.current() } answers { configuration }
        every { ArcMenus.item(any<String>(), any()) } answers {
            Bukkit.isPrimaryThread() shouldBe true
            if (firstArg<String>().startsWith("scheduled-entry-")) cards++
            ItemStack(Material.STONE)
        }
        every { ArcMenus.item(any<MenuId>(), any(), any()) } answers { ItemStack(Material.STONE) }
        every { ArcMenus.open(any(), any(), any(), any(), any()) } answers {
            Bukkit.isPrimaryThread() shouldBe true
            views.lastOrNull()?.open = false
            val view = ScheduleView(MenuId.of(arg<String>(1)), arg(3), arg(4))
            views += view
            view.session
        }
      } catch (failure: Throwable) {
          close()
          throw failure
      }
    }

    override fun close() {
        paper.callEvent(InventoryCloseEvent(player.openInventory))
        native.close(); scope.close()
        HandlerList.unregisterAll(plugin)
        ARC.plugin = null
        unmockkStatic(ScheduledCommandsManager::class)
        unmockkObject(ArcMenus, ScheduledCommandsManager)
        ConfigManager.clear()
        paper.close()
        root.toFile().deleteRecursively()
    }
}

private class ScheduleView(
    val menu: MenuId,
    val elements: Map<String, PaperMenuEntry>,
    val regions: Map<MenuRegionId, List<PaperMenuEntry>>,
) {
    var open = true
    val session = mockk<PaperMenuSession> { every { isOpen } answers { open } }

    fun click(id: String, fixture: ScheduledGuiFixture) {
        elements.getValue(id).onClick.handle(PaperMenuClickContext(session, fixture.player,
            PaperMenuClickTarget.Element(MenuElementId.of(id)), mockk(relaxed = true)))
    }
}
