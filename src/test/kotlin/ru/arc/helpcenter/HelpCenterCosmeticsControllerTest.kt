@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Core's headless native presenter, real history and dispatch.

package ru.arc.helpcenter

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.cosmetics.CosmeticCategory
import ru.arc.cosmetics.CosmeticChange
import ru.arc.cosmetics.CosmeticEntry
import ru.arc.cosmetics.CosmeticOption
import ru.arc.cosmetics.CosmeticsCoreGateway
import ru.arc.cosmetics.CosmeticsSnapshot
import ru.arc.cosmetics.ParticlePresetChange
import ru.arc.cosmetics.ParticlePresetOption
import ru.arc.cosmetics.ParticlePresetsSnapshot
import ru.arc.cosmetics.PlayerParticlesGateway
import ru.arc.gui.ArcMenus
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogSessionRegistration
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class HelpCenterCosmeticsControllerTest : FreeSpec({
    "native categories keep locked items inspectable and refresh after the exact captured action" {
        cosmeticsJourney { f ->
            f.open()
            f.screen.id shouldBe "help.cosmetics"
            f.click("category_hat")
            f.export("hats")
            f.click("item_1")
            f.screen.buttons.map { it.id.value } shouldBe listOf("refresh")
            f.body().contains("ещё недоступна") shouldBe true
            f.click("back")
            f.click("item_0")
            f.export("hat-card")
            f.click("change")
            f.screen.id shouldBe "help.cosmetics.item"
            f.body().contains("Предмет надет") shouldBe true
            f.changeCalls shouldBe 1
            f.click("back")
            f.screen.id shouldBe "help.cosmetics.catalog.hat"
            f.click("back")
            f.screen.id shouldBe "help.cosmetics"
            f.export("wardrobe")
            f.closes shouldBe 0
        }
    }

    "three complete preset cycles return to the original cosmetics parent with both escape preferences" {
        for (escapeBack in listOf(true, false)) cosmeticsJourney(escapeBack) { f ->
            f.open()
            f.click("effects")
            repeat(3) {
                val inactive = f.screen.buttons.first { it.id.value.startsWith("preset_") && !f.plain(it.label).startsWith("✔") }
                f.click(inactive.id.value)
                f.body().contains("заменит текущие") shouldBe true
                f.click("apply")
                f.paper.performTicks(3)
                f.screen.id shouldBe "help.cosmetics.effects"
            }
            f.export("effects")
            f.click("back")
            f.screen.id shouldBe "help.cosmetics"
            f.closes shouldBe 0
            f.applyCalls shouldBe 3
        }
    }

    "late particle completion after native Back never reopens a dismissed action" {
        cosmeticsJourney { f ->
            f.open()
            f.click("effects")
            f.click("preset_0")
            val future = CompletableFuture<ParticlePresetChange>()
            f.pending = future
            f.click("apply")
            f.screen.buttons shouldBe emptyList()
            f.screen.id shouldBe "help.cosmetics.preset"
            f.click("back")
            f.screen.id shouldBe "help.cosmetics.effects"
            future.complete(ParticlePresetChange(true, f.particleSnapshot))
            f.paper.performTicks(3)
            f.screen.id shouldBe "help.cosmetics.effects"
            f.body().contains("Настройки эффектов обновлены") shouldBe false
        }
    }

    "native overlap copy and removal confirmation use actual snapshot semantics" {
        cosmeticsJourney { f ->
            f.particleSnapshot = f.particleSnapshot.copy(activeCount = 4, presetsReplaceActive = false,
                presets = f.particleSnapshot.presets.map { it.copy(active = true) })
            f.open()
            f.click("effects")
            f.click("preset_0")
            f.body().contains("добавятся к текущим") shouldBe true
            f.screen.buttons.map { it.id.value } shouldBe listOf("apply")
            f.click("back")
            f.click("reset")
            f.body().contains("4") shouldBe true
            f.export("reset-effects")
            f.click("confirm")
            f.paper.performTicks(3)
            f.particleSnapshot.activeCount shouldBe 0
            f.screen.id shouldBe "help.cosmetics.effects"
        }
    }

    "a native preset shadowed by a personal saved group stays inspectable without an impossible apply" {
        cosmeticsJourney { f ->
            f.particleSnapshot = f.particleSnapshot.copy(shadowedPresetIds = f.particleSnapshot.presets.map { it.id }.toSet())
            f.open()
            f.click("effects")
            f.click("preset_0")
            f.screen.buttons shouldBe emptyList()
            f.body().contains("совпадает имя") shouldBe true
            f.applyCalls shouldBe 0
        }
    }

    "unavailable wardrobe never claims an empty equipped state or exposes mutation controls" {
        cosmeticsJourney { f ->
            f.wardrobe = CosmeticsSnapshot(false)
            f.open()
            f.body().contains("Ничего не надето") shouldBe false
            f.screen.buttons.map { it.id.value } shouldBe listOf("effects", "refresh")
            f.export("unavailable-wardrobe")
        }
    }
})

private fun cosmeticsJourney(escapeBack: Boolean = true, block: (CosmeticsJourney) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val root = Files.createTempDirectory("cosmetics-dialog-")
        val plugin = paper.createSimplePlugin("CosmeticsDialog")
        Tasks.install(BukkitTaskScheduler(plugin))
        ConfigManager.clear()
        val player = spyk<Player>(paper.addPlayer("Viewer"))
        val f = CosmeticsJourney(paper, player)
        every { player.isOnline } returns true
        every { player invokeNoArgs "closeDialog" } answers { f.closes++; Unit }
        val navigation = HelpCenterNavigation(plugin)
        val settings = HelpCenterConfig.load(root).snapshot().copy(decorationFont = "arc:dialog_ui_preview")
        val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
        PaperDialogRuntime(plugin) { _, screen, registered -> f.screen = screen; f.registration = registered }.use { dialogs ->
            val close = PaperDialogButton(PaperDialogActionId.of("close_menu"), Component.text("Закрыть"),
                width = 200, closeDialogBeforeAction = true, onClick = {})
            val controller = HelpCenterCosmeticsController(settings, navigation, { viewer, screen ->
                // Match ArcMenus.openDialog: an explicit returning footer owns native Escape too.
                val footerBack = MenuEscapeBehavior.footerGoesBack(screen, escapeBack)
                dialogs.open(viewer, ArcMenus.prepareDialog(screen, footerBack, close),
                    navigation.returnTarget(viewer), { navigation.visit(viewer) }, !footerBack)
            }, f.cosmetics, f.particles)
            f.open = { controller.open(player) {} }
            f.click = { id ->
                val button = (f.screen.buttons + listOfNotNull(f.screen.exitButton)).single { it.id.value == id }
                button.closeDialogBeforeAction shouldBe false
                val key = f.registration.key(button.id)
                dialogs.onCustomClick(mockk {
                    every { commonConnection } returns connection
                    every { identifier } returns Key.key(key)
                    every { dialogResponseView } returns null
                })
            }
            try { block(f) } finally { controller.close(); navigation.close() }
        }
        Tasks.reset()
        ConfigManager.clear()
        root.toFile().deleteRecursively()
    }
}

private class CosmeticsJourney(val paper: MockBukkitTestRuntime, val player: Player) {
    lateinit var screen: PaperDialogScreen
    lateinit var registration: PaperDialogSessionRegistration
    lateinit var open: () -> Unit
    lateinit var click: (String) -> Unit
    var closes = 0
    var changeCalls = 0
    var applyCalls = 0
    var pending: CompletableFuture<ParticlePresetChange>? = null
    val hat = CosmeticEntry("chef_hat", "Колпак повара", CosmeticCategory.HAT)
    val locked = CosmeticEntry("witch_hat", "Шляпа ведьмы", CosmeticCategory.HAT)
    val backpack = CosmeticEntry("leather_backpack", "Кожаный рюкзак", CosmeticCategory.BODY)
    val balloon = CosmeticEntry("amogus_balloon", "Воздушный шар «Амогус»", CosmeticCategory.BALLOON)
    var wardrobe = CosmeticsSnapshot(true, listOf(CosmeticOption(hat, false, true), CosmeticOption(locked, false, false),
        CosmeticOption(backpack, true, true), CosmeticOption(balloon, true, true)),
        mapOf(CosmeticCategory.HAT to 1, CosmeticCategory.BODY to 3, CosmeticCategory.BALLOON to 2), true)
    var particleSnapshot = ParticlePresetsSnapshot(true, listOf(
        ParticlePresetOption("northern_lights", "Северное сияние", false),
        ParticlePresetOption("sparkles", "Золотые искры", false),
    ), 0, true)
    val cosmetics = mockk<CosmeticsCoreGateway> {
        every { canOpen(player) } returns true
        every { snapshot(player) } answers { wardrobe }
        every { change(player, any(), any()) } answers {
            changeCalls++
            val entry = secondArg<CosmeticEntry>()
            val equip = thirdArg<Boolean>()
            wardrobe = wardrobe.copy(entries = wardrobe.entries.map { if (it.entry == entry) it.copy(equipped = equip) else it })
            CosmeticChange.APPLIED
        }
    }
    val particles = mockk<PlayerParticlesGateway> {
        every { snapshot(player) } answers { particleSnapshot }
        every { applyPreset(player, any(), any()) } answers {
            applyCalls++
            pending ?: run {
                val id = secondArg<String>()
                particleSnapshot = particleSnapshot.copy(activeCount = 2,
                    presets = particleSnapshot.presets.map { it.copy(active = it.id == id) })
                CompletableFuture.completedFuture(ParticlePresetChange(true, particleSnapshot))
            }
        }
        every { reset(player, any()) } answers {
            particleSnapshot = particleSnapshot.copy(activeCount = 0, presets = particleSnapshot.presets.map { it.copy(active = false) })
            CompletableFuture.completedFuture(ParticlePresetChange(true, particleSnapshot))
        }
        every { close() } returns Unit
    }

    fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
    fun body(): String = screen.body.joinToString("\n") { plain(it.text) }

    /** Compiled screen source for a renderer; deliberately separate from live-client acceptance. */
    fun export(name: String) {
        val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
        val gson = GsonComponentSerializer.gson()
        fun fields(component: (Component) -> Any) = mapOf(
            "title" to component(screen.title), "columns" to screen.columns,
            "body" to screen.body.map { mapOf("text" to component(it.text), "width" to it.width) },
            "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to component(it.label),
                "tooltip" to component(it.tooltip), "width" to it.width) },
            "exit" to screen.exitButton?.let { mapOf("text" to component(it.label), "width" to it.width) },
        )
        val output = Path.of("build/reports/cosmetics-dialog")
        Files.createDirectories(output)
        Files.writeString(output.resolve("$name.json"), GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(mapOf(
            "content" to fields(legacy::serialize),
            "components" to fields { JsonParser.parseString(gson.serialize(it)) },
        )))
    }
}
