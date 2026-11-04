@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Real native history and headless presentation.

package ru.arc.helpcenter

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.DialogResponseView
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.graves.GravePreferences
import ru.arc.graves.GraveStyle
import ru.arc.graves.GraveTextMode
import ru.arc.gui.ArcMenus
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogSessionRegistration
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture

class HelpCenterGraveSettingsTest : FreeSpec({
    "every model persists through the existing metadata backend and native Back returns to settings" {
        graveSettingsJourney { f ->
            f.open()
            f.export("settings")
            f.click("settings_grave")
            f.body().contains("будущие могилы") shouldBe true
            f.export("overview")
            f.click("grave_models")
            f.screen.buttons.map { it.id.value } shouldBe GraveStyle.entries.map { "grave_style_${it.name.lowercase(Locale.ROOT)}" }
            f.body().contains("Если изображения недоступны") shouldBe true
            f.screen.buttons.all { f.plain(it.tooltip).isNotBlank() } shouldBe true
            f.export("models")

            for (style in GraveStyle.entries.drop(1) + GraveStyle.RANDOM) {
                val id = "grave_style_${style.name.lowercase(Locale.ROOT)}"
                f.click(id)
                f.preferences().style shouldBe style
                f.screen.id shouldBe "help.settings.grave.models"
                val selected = f.screen.buttons.single { it.id.value == id }
                f.plain(selected.label).startsWith("✔ ") shouldBe true
                selected.label.color() shouldBe TextColor.color(0x9bd48d)
                f.screen.buttons.filter { it.id.value != id }.forEach {
                    it.label.color() shouldBe TextColor.color(0xffffff)
                }
                val writes = f.writes.size
                f.click(id)
                f.writes.size shouldBe writes // Selected radio is a no-op.
            }
            f.writes.size shouldBe GraveStyle.entries.size
            f.writes.all { it.first == GravePreferences.META_KEY } shouldBe true
            f.click("back")
            f.screen.id shouldBe "help.settings.grave"
            f.click("back")
            f.screen.id shouldBe "help.category.settings"
            f.closes shouldBe 0
        }
    }

    "all inscription modes preserve custom content and the preview saves the exact accepted text" {
        graveSettingsJourney { f ->
            f.openEditor()
            f.export("editor")
            val custom = "Мои вещи & мои правила."
            f.click("grave_preview", "  $custom  ")
            f.screen.id shouldBe "help.settings.grave.preview"
            f.body().contains(custom) shouldBe true
            f.writes shouldBe emptyList()
            f.export("preview")
            f.click("grave_save_text")
            f.preferences() shouldBe GravePreferences(textMode = GraveTextMode.CUSTOM, customText = custom)
            f.screen.buttons.any { it.id.value == "grave_save_text" } shouldBe false
            f.body().contains("Сохранено") shouldBe true
            f.click("back")
            f.screen.id shouldBe "help.settings.grave.editor"
            f.screen.inputs.single().initial shouldBe "  $custom  " // Core restores the submitted form, not defaults.
            f.click("back")
            f.screen.id shouldBe "help.settings.grave.text"
            f.click("grave_mode_none")
            f.preferences() shouldBe GravePreferences(textMode = GraveTextMode.NONE, customText = custom)
            val writesAfterNone = f.writes.size
            f.click("grave_mode_none")
            f.writes.size shouldBe writesAfterNone
            f.click("grave_mode_auto")
            f.preferences() shouldBe GravePreferences(customText = custom)
            val writesAfterAuto = f.writes.size
            f.click("grave_mode_auto")
            f.writes.size shouldBe writesAfterAuto
            f.export("modes")
            f.click("grave_mode_custom")
            f.screen.inputs.single().initial shouldBe custom
            f.click("back")
            f.click("back")
            f.click("back")
            f.screen.id shouldBe "help.category.settings"
        }
    }

    "custom validation rejects empty overlong hidden multiline markup and unmeasured text without saving" {
        val unsupported = "\u0488"
        DialogTextLayout.naturalWidth(Component.text(unsupported)) shouldBe null
        val invalid = listOf("", "   ", "а".repeat(81), "две\nстроки", "<red>цвет", "§cцвет", "скрытый\u200Bзнак", "\uE000", "🪦", unsupported)
        for (raw in invalid) HelpCenterGraveSettings.acceptedText(raw) shouldBe null
        val limit = "я".repeat(GravePreferences.MAX_TEXT_LENGTH)
        HelpCenterGraveSettings.acceptedText(limit) shouldBe limit
        graveSettingsJourney { f ->
            f.openEditor()
            for (raw in invalid) {
                f.click("grave_preview", raw)
                f.screen.id shouldBe "help.settings.grave.editor"
                f.body().contains("Введите непустую строку") shouldBe true
                f.writes shouldBe emptyList()
            }
            f.export("invalid")
            f.click("grave_preview", limit)
            f.visibleText(f.screen.body.first().text).contains(limit) shouldBe true
            f.export("preview-long")
            f.click("grave_save_text")
            f.preferences().customText shouldBe limit
        }
    }

    "failed save keeps the literal draft and retry updates the setting only after success" {
        graveSettingsJourney { f ->
            val draft = "Не тот поворот."
            f.openEditor()
            f.click("grave_preview", draft)
            val failure = CompletableFuture<Boolean>()
            f.pending = failure
            f.click("grave_save_text")
            f.screen.buttons shouldBe emptyList()
            f.export("saving")
            failure.complete(false)
            f.paper.performTicks(3)
            f.preferences() shouldBe GravePreferences()
            f.body().contains("Сохранение не подтверждено") shouldBe true
            f.body().contains(draft) shouldBe true
            f.export("save-failed")
            f.click("grave_edit_text")
            f.screen.inputs.single().initial shouldBe draft
            f.click("grave_preview", draft)
            f.click("grave_save_text")
            f.preferences().customText shouldBe draft
        }
    }

    "late saves after native Back close logout or controller shutdown never reclaim the screen" {
        for (exit in listOf("back", "close", "logout", "shutdown")) graveSettingsJourney { f ->
            f.open()
            f.click("settings_grave")
            f.click("grave_models")
            val pending = CompletableFuture<Boolean>()
            f.pending = pending
            f.click("grave_style_obelisk")
            f.screen.buttons shouldBe emptyList()
            when (exit) {
                "back" -> { f.click("back"); f.screen.id shouldBe "help.settings.grave" }
                "close" -> f.dialogs.close(f.player)
                "logout" -> every { f.player.isOnline } returns false
                "shutdown" -> f.controller.close()
            }
            val screens = f.screenCount
            pending.complete(true)
            f.paper.performTicks(3)
            f.screenCount shouldBe screens
            f.preferences().style shouldBe GraveStyle.OBELISK
        }
    }

    "a pending save cannot be overtaken after leaving and reopening the model catalog" {
        graveSettingsJourney { f ->
            f.open()
            f.click("settings_grave")
            f.click("grave_models")
            val pending = CompletableFuture<Boolean>()
            f.pending = pending
            f.click("grave_style_obelisk")
            f.click("back")
            f.click("grave_models")
            f.click("grave_style_cairn")
            f.writes.size shouldBe 1
            f.body().contains("Сохраняем настройку") shouldBe true
            pending.complete(true)
            f.paper.performTicks(3)
            f.click("grave_style_cairn")
            f.preferences().style shouldBe GraveStyle.CAIRN
            f.writes.size shouldBe 2
        }
    }
})

private fun graveSettingsJourney(block: (GraveSettingsJourney) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val root = Files.createTempDirectory("grave-settings-")
        val plugin = paper.createSimplePlugin("GraveSettings")
        Tasks.install(BukkitTaskScheduler(plugin))
        ConfigManager.clear()
        val player = spyk<Player>(paper.addPlayer("Viewer"))
        val f = GraveSettingsJourney(paper, player)
        every { player.isOnline } returns true
        every { player invokeNoArgs "closeDialog" } answers { f.closes++; Unit }
        val navigation = HelpCenterNavigation(plugin)
        val settings = HelpCenterConfig.load(root).snapshot().copy(decorationFont = "arc:dialog_ui")
        ArcMenus.configureDialogDecoration(settings.decorationFont)
        val gateway = mockk<HelpCenterGateway> {
            every { this@mockk.settings(player) } returns HelpCenterSettingSnapshot(HelpCenterChatMode.LOCAL, false, true)
        }
        val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
        PaperDialogRuntime(plugin) { _, screen, registered ->
            f.screen = screen; f.registration = registered; f.screenCount++
        }.use { dialogs ->
            f.dialogs = dialogs
            val close = PaperDialogButton(PaperDialogActionId.of("close_menu"), Component.text("Закрыть"),
                width = 200, closeDialogBeforeAction = true, onClick = {})
            f.controller = HelpCenterSettingsController(settings, gateway, f.legacy, navigation, { viewer, screen ->
                val footerBack = MenuEscapeBehavior.footerGoesBack(screen, true)
                dialogs.open(viewer, ArcMenus.prepareDialog(screen, footerBack, close),
                    navigation.returnTarget(viewer), { navigation.visit(viewer) }, !footerBack)
            }, { _, _ -> error("Unexpected external command") }, { _, _ -> error("Unexpected inventory") }, {})
            f.open = { dialogs.beginFlow(player); f.controller.open(player); paper.performTicks(2) }
            f.dispatchClick = { id, input ->
                val button = (f.screen.buttons + listOfNotNull(f.screen.exitButton)).single { it.id.value == id }
                button.closeDialogBeforeAction shouldBe false
                val key = f.registration.key(button.id)
                val response = input?.let { value -> mockk<DialogResponseView> { every { getText(any()) } returns value } }
                dialogs.onCustomClick(mockk {
                    every { commonConnection } returns connection
                    every { identifier } returns Key.key(key)
                    every { dialogResponseView } returns response
                })
                paper.performTicks(2)
            }
            try { block(f) } finally { f.controller.close(); navigation.close() }
        }
        ArcMenus.configureDialogDecoration("")
        Tasks.reset()
        ConfigManager.clear()
        root.toFile().deleteRecursively()
    }
}

private class GraveSettingsJourney(val paper: MockBukkitTestRuntime, val player: Player) {
    lateinit var screen: PaperDialogScreen
    lateinit var registration: PaperDialogSessionRegistration
    lateinit var controller: HelpCenterSettingsController
    lateinit var dialogs: PaperDialogRuntime
    lateinit var open: () -> Unit
    lateinit var dispatchClick: (String, String?) -> Unit
    var screenCount = 0
    var closes = 0
    var pending: CompletableFuture<Boolean>? = null
    val writes = mutableListOf<Pair<String, String>>()
    private val metadata = mutableMapOf<String, String>()
    private val backend = mockk<HelpCenterLegacySettings.Backend> {
        every { hasPermission(any(), any()) } returns false
        every { meta(any(), any()) } answers { metadata[secondArg<String>()] }
        every { cmiOption(any(), any()) } returns null
        every { flightState(any()) } returns null
        every { tpaEnabled(any()) } returns null
        every { setMeta(any(), any(), any()) } answers {
            val key = secondArg<String>()
            val value = thirdArg<String>()
            writes += key to value
            val future = pending ?: CompletableFuture.completedFuture(true)
            pending = null
            future.thenApply { accepted -> if (accepted) metadata[key] = value; accepted }
        }
    }
    val legacy = HelpCenterLegacySettings(backend)

    fun click(id: String, input: String? = null) = dispatchClick(id, input)
    fun openEditor() { open(); click("settings_grave"); click("grave_text"); click("grave_mode_custom") }
    fun preferences() = legacy.gravePreferences(player)
    fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
    fun body(): String = screen.body.joinToString("\n") { plain(it.text) }
    fun visibleText(component: Component): String {
        val points = plain(component).codePoints()
            .filter { Character.getType(it) != Character.PRIVATE_USE.toInt() && it != '\n'.code }.toArray()
        return String(points, 0, points.size)
    }

    /** Export the composed screen, preserving literal user input and real panel glyphs. */
    fun export(name: String) {
        val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
        val gson = GsonComponentSerializer.gson()
        fun fields(component: (Component) -> Any) = mapOf(
            "title" to component(screen.title), "columns" to screen.columns,
            "body" to screen.body.map { mapOf("text" to component(it.text), "width" to it.width) },
            "inputs" to screen.inputs.map { mapOf("id" to it.id.value, "label" to component(it.label),
                "initial" to it.initial, "width" to it.width) },
            "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to component(it.label),
                "tooltip" to component(it.tooltip), "width" to it.width) },
            "exit" to screen.exitButton?.let { mapOf("text" to component(it.label), "width" to it.width) },
        )
        val output = Path.of("build/reports/grave-settings-dialog")
        Files.createDirectories(output)
        Files.writeString(output.resolve("$name.json"), GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(mapOf(
            "content" to fields(legacy::serialize),
            "components" to fields { JsonParser.parseString(gson.serialize(it)) },
        )))
    }
}
