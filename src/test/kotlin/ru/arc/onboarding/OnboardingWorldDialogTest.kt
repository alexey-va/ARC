package ru.arc.onboarding

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogClickContext
import java.nio.file.Files
import java.nio.file.Path

class OnboardingWorldDialogTest : FreeSpec({
    val player = mockk<Player>()
    val context = mockk<PaperDialogClickContext>()
    every { context.player } returns player
    val destinations = mutableListOf<String>()
    val dialog = OnboardingWorldDialog { actor, world ->
        actor shouldBe player
        destinations += world
    }
    val serializer = LegacyComponentSerializer.builder().character('&').hexColors().build()
    fun serialize(component: Component) = serializer.serialize(component)
    fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    "the two settlement choices close to gameplay and route to the existing RTP world names" {
        val screen = dialog.screen()
        screen.id shouldBe "onboarding.world"
        screen.columns shouldBe 2
        screen.buttons.map { it.id.value } shouldContainExactly listOf("vanilla", "survival")
        screen.buttons.map { plain(it.label) } shouldContainExactly listOf("Обычный мир", "Новые биомы")
        destinations.clear()
        screen.buttons.forEach {
            it.closeDialogBeforeAction shouldBe true
            it.onClick.handle(context)
        }
        destinations shouldContainExactly listOf("vanilla", "survival")
    }

    "the compact comparison uses the real table frame with two unwrapped rows and vivid labels" {
        val screen = dialog.screen()
        screen.body.size shouldBe 2
        val table = screen.body.last()
        table.width shouldBe 310
        val content = plain(table.text)
        content.lines().size shouldBe 5
        content shouldContain "\uE570"
        content shouldContain "Обычный мир"
        content shouldContain "обычное выживание"
        content shouldContain "Новые биомы"
        content shouldContain "необычные ландшафты"
        serialize(table.text) shouldContain "&#77ee94"
        serialize(table.text) shouldContain "&#6de4f2"
    }

    "exports the actual native screen for one representative preview" {
        val screen = dialog.screen()
        val export = mapOf("world" to mapOf(
            "title" to serialize(screen.title),
            "columns" to screen.columns,
            "body" to screen.body.map { mapOf("text" to serialize(it.text), "width" to it.width) },
            "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to serialize(it.label),
                "tooltip" to serialize(it.tooltip), "width" to it.width) },
            "exit" to mapOf("text" to serialize(screen.exitButton!!.label), "width" to screen.exitButton!!.width),
        ))
        val output = Path.of("build/reports/onboarding-world/content.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(export))
    }
})
