package ru.arc.graves

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class DecorativeGravesTest : FunSpec({
    test("create captures a fixed target and every model radio renews its callbacks without a write") {
        decorativeJourney { f ->
            f.command("create")
            f.screen.id shouldBe "graves.decorative"
            f.screen.inputs.last().initial shouldBe ""
            f.export("editor")
            f.click("decor_models", "Точная надпись", "")
            f.screen.buttons.size shouldBe GraveStyle.entries.size
            f.export("models")
            for (style in GraveStyle.entries) {
                val id = "decor_style_${style.name.lowercase()}"
                f.click(id)
                f.plain(f.screen.buttons.single { it.id.value == id }.label).startsWith("✔ ") shouldBe true
                val count = f.screens
                f.click(id)
                f.screens shouldBe count + 1
                f.saved shouldBe emptyList()
            }
            f.click("decor_style_engraved_cairn")
            f.click("back")
            f.screen.inputs.first().initial shouldBe "Точная надпись"
            val stale = f.screen.buttons.first()
            f.click("decor_reroll", "Точная надпись", "Автор")
            stale.onClick.handle(f.context("Нельзя сохранить старой кнопкой", ""))
            f.saved shouldBe emptyList()
            // Looking at a different block after opening must not change the captured placement.
            every { f.support.x } returns 3
            f.click("decor_save", "Точная надпись", "")
            f.saved shouldBe emptyList()
            f.flush()
            val saved = f.persisted.single()
            saved.x shouldBe 0
            saved.style shouldBe GraveStyle.ENGRAVED_CAIRN
            saved.epitaph shouldBe "Точная надпись"
            saved.signature shouldBe ""
            saved.yaw shouldBe 180f // Viewer stands south of the local -Z front.
            saved.view("survival").items shouldBe emptyList()
        }
    }

    test("entry and final mutation reject missing rights changed position and stale lifecycle callbacks") {
        val invalidations: List<(DecorativeJourney) -> Unit> = listOf(
            { it.admin = false }, { it.eye = Location(it.world, 20.0, 65.6, .5) },
            { it.passable = false }, { it.solid = false }, { it.loaded = false },
            { it.online = false }, { it.dead = true }, { it.currentPlayer = mockk(relaxed = true) },
            { it.controller.world(PlayerChangedWorldEvent(it.player, it.world)) },
            { it.controller.quit(PlayerQuitEvent(it.player, "")) },
            { it.controller.command(PlayerCommandPreprocessEvent(it.player, "/spawn")) },
            { it.dismiss() }, { it.controller.closeAsync() },
        )
        invalidations.forEach { invalidate -> decorativeJourney { f ->
            f.command("create")
            val submit = f.screen.buttons.first()
            invalidate(f)
            submit.onClick.handle(f.context("Не создавать", ""))
            f.flush()
            f.saved shouldBe emptyList()
        } }
        decorativeJourney { f ->
            f.admin = false
            f.command("create", "random")
            f.screens shouldBe 0
            f.saved shouldBe emptyList()
        }
    }

    test("unloaded ray corridor never invokes Paper tracing and recoverable graves reserve their cell") {
        decorativeJourney { f ->
            f.loaded = false
            f.command("create")
            verify(exactly = 0) { f.player.rayTraceBlocks(any<Double>(), any<FluidCollisionMode>()) }
            f.loaded = true
            f.recoverable = listOf(decorativeRecord().view("survival").copy(decorative = false))
            f.command("create", "random")
            f.saved shouldBe emptyList()
            f.recoverable = emptyList()
            f.command("create")
            f.recoverable = listOf(decorativeRecord().view("survival").copy(decorative = false))
            f.click("decor_save", "Не перекрыть вещи", "")
            f.flush()
            f.saved shouldBe emptyList()
        }
    }

    test("random creation persists once then ray selection edits and confirms removal of owned scenery only") {
        decorativeJourney { f ->
            f.command("create", "random")
            val pending = f.controller.closeAsync()
            pending.isDone shouldBe false
            val count = f.screens
            f.flush()
            pending.isDone shouldBe true
            f.screens shouldBe count
            f.persisted.size shouldBe 1
            f.controller.views() shouldBe emptyList()
        }
        decorativeJourney { f ->
            f.command("create", "random"); f.flush()
            f.aimAtModel()
            f.command("edit")
            f.screen.id shouldBe "graves.decorative"
            f.click("decor_save", "Изменено", "Автор"); f.flush()
            f.persisted.single().revision shouldBe 1
            f.persisted.single().signature shouldBe "Автор"
            f.command("remove")
            f.screen.id shouldBe "graves.decorative.remove"
            f.export("remove")
            f.persisted.size shouldBe 1
            f.click("decor_remove"); f.flush()
            f.persisted shouldBe emptyList()
            f.command("remove")
            f.saved.size shouldBe 3
        }
        decorativeJourney(listOf(decorativeRecord().copy(createdBy = UUID(99, 99)))) { f ->
            f.aimAtModel(); f.command("remove")
            f.screens shouldBe 0
            f.saved shouldBe emptyList()
        }
    }

    test("custom inscriptions enforce supported plain text and optional signature without discarding the saved draft") {
        for (raw in listOf("", "   ", "x".repeat(81), "<red>markup", "§cцвет", "две\nстроки", "скрытый\u200Bзнак", "\u0488", "🪦"))
            DecorativeGraves.acceptedText(raw) shouldBe null
        DecorativeGraves.acceptedText("", signature = true) shouldBe ""
        DecorativeGraves.acceptedText("x".repeat(18), signature = true) shouldBe null
        DecorativeGraves.acceptedText("  Мои слова & мои правила  ") shouldBe "Мои слова & мои правила"
        decorativeJourney { f ->
            f.command("create")
            val previous = f.screen.inputs.first().initial
            f.click("decor_save", "<red>markup", "")
            f.screen.inputs.first().initial shouldBe previous
            f.screen.body.any { f.plain(it.text).contains("Введите непустую") } shouldBe true
            f.saved shouldBe emptyList()
            f.click("decor_save", "Новая надпись", "x".repeat(18))
            f.screen.inputs.first().initial shouldBe "Новая надпись"
            f.screen.inputs.last().initial shouldBe ""
            f.saved shouldBe emptyList()
        }
    }
})

private fun decorativeJourney(initial: List<DecorativeGraveRecord> = emptyList(), action: (DecorativeJourney) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val plugin = paper.createSimplePlugin("DecorativeGraves")
        Tasks.install(BukkitTaskScheduler(plugin))
        val f = DecorativeJourney(paper, plugin, initial)
        try { f.controller.start(); f.flush(); action(f) }
        finally { f.controller.closeAsync(); f.flush(); Tasks.reset() }
    }
}

private class DecorativeJourney(val paper: MockBukkitTestRuntime, plugin: org.bukkit.plugin.Plugin, initial: List<DecorativeGraveRecord>) {
    val player = mockk<Player>(relaxed = true)
    val world = mockk<World>(relaxed = true)
    val support = mockk<Block>(relaxed = true)
    private val air = mockk<Block>(relaxed = true)
    private val store = mockk<DecorativeGraveStore>()
    val saved = mutableListOf<List<DecorativeGraveRecord>>()
    var persisted = initial
    var recoverable = emptyList<GraveView>()
    var admin = true
    var online = true
    var dead = false
    var loaded = true
    var passable = true
    var solid = true
    var currentPlayer: Player? = player
    var eye = Location(world, .5, 65.6, 3.5).apply { direction = Vector(0.0, -1.6, -3.0) }
    var screens = 0
    lateinit var screen: PaperDialogScreen
    var dismiss: () -> Unit = {}
    private val work = ArrayDeque<Runnable>()
    val controller: DecorativeGraves

    init {
        every { world.name } returns "world"
        every { world.uid } returns UUID(3, 3)
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns air
        every { world.getBlockAt(any<Int>(), 63, any<Int>()) } returns support
        every { support.world } returns world
        every { support.x } returns 0
        every { support.y } returns 63
        every { support.z } returns 0
        every { support.type } answers { if (solid) Material.STONE else Material.WATER }
        every { air.isPassable } answers { passable }
        every { player.uniqueId } returns UUID(2, 2)
        every { player.world } returns world
        every { player.eyeLocation } answers { eye.clone() }
        every { player.isOnline } answers { online }
        every { player.isDead } answers { dead }
        every { player.hasPermission(GRAVES_ADMIN_PERMISSION) } answers { admin }
        every { player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER) } answers { RayTraceResult(Vector(.5, 64.0, .5), support, BlockFace.UP) }
        every { store.load() } answers { persisted }
        every { store.save(any()) } answers { persisted = firstArg<List<DecorativeGraveRecord>>().toList(); saved += persisted; Unit }
        val settings = GravesConfig(ConfigManager.empty()).snapshot()
        val epitaphs = GraveEpitaphs.load { javaClass.classLoader.getResourceAsStream(it) }
        controller = DecorativeGraves(plugin, settings, "survival", CompletableFuture.completedFuture(store), Executor { work.addLast(it) },
            epitaphs, { recoverable }, {}, { GraveAppearances.select(it.id, settings.appearance, false, it.view("survival").appearance).copy(yaw = it.yaw) },
            { _, value, _, onDismiss -> screen = value; screens++; dismiss = onDismiss }, {}, {}, { currentPlayer })
    }

    fun command(vararg args: String) = controller.command(player, args.toList())
    fun flush() { while (work.isNotEmpty()) work.removeFirst().run(); paper.performTicks(3) }
    fun aimAtModel() {
        eye = Location(world, .5, 64.35, 3.5).apply { direction = Vector(0.0, 0.0, -1.0) }
        every { player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER) } returns null
    }
    fun context(text: String? = null, signature: String? = null): PaperDialogClickContext = mockk {
        every { this@mockk.player } returns this@DecorativeJourney.player
        every { this@mockk.text(any()) } answers {
            // PaperDialogInputId is a JVM inline value; MockK sees its unboxed String argument.
            if (firstArg<String>() == "decor_signature") signature ?: screen.inputs.lastOrNull()?.initial.orEmpty()
            else text ?: screen.inputs.firstOrNull()?.initial.orEmpty()
        }
    }
    fun click(id: String, text: String? = null, signature: String? = null) =
        (screen.buttons + listOfNotNull(screen.exitButton)).single { it.id.value == id }.onClick.handle(context(text, signature))
    fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    fun export(name: String) {
        val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
        val gson = GsonComponentSerializer.gson()
        fun fields(component: (Component) -> Any) = mapOf("title" to component(screen.title), "columns" to screen.columns,
            "body" to screen.body.map { mapOf("text" to component(it.text), "width" to it.width) },
            "inputs" to screen.inputs.map { mapOf("id" to it.id.value, "label" to component(it.label), "initial" to it.initial, "width" to it.width) },
            "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to component(it.label), "tooltip" to component(it.tooltip), "width" to it.width) },
            "exit" to screen.exitButton?.let { mapOf("text" to component(it.label), "width" to it.width) })
        val output = Path.of("build/reports/decorative-graves-dialog")
        Files.createDirectories(output)
        Files.writeString(output.resolve("$name.json"), GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(mapOf(
            "content" to fields(legacy::serialize), "components" to fields { JsonParser.parseString(gson.serialize(it)) })))
    }
}
