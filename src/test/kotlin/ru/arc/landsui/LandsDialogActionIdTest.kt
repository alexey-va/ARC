package ru.arc.landsui

import io.kotest.matchers.collections.shouldNotBeEmpty
import ru.arc.paper.menu.PaperDialogActionId
import io.kotest.core.spec.style.StringSpec
import java.nio.file.Files
import java.nio.file.Path

class LandsDialogActionIdTest : StringSpec({
    "static Lands dialog action ids use Paper's allowed alphabet" {
        val sources = listOf(
            Path.of("src/main/kotlin/ru/arc/landsui/LandsUiController.kt"),
            Path.of("src/main/kotlin/ru/arc/landsui/RegionTool.kt"),
        ).flatMap { path ->
            Regex("(?:button|contextButton|commandButton|back|action|PaperDialogActionId\\.of)\\(\\\"([^\\\"]+)\\\"")
                .findAll(Files.readString(path))
                .map { it.groupValues[1] }
                .filterNot { '\$' in it }
                .toList()
        }
        sources.shouldNotBeEmpty()
        sources.forEach { PaperDialogActionId.of(it) }
    }
})
