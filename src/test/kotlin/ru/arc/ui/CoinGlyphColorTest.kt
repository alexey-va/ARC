package ru.arc.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines

class CoinGlyphColorTest : StringSpec({
    "every coin glyph in ARC resource text is explicitly white" {
        val colorToken = Regex("<(?:color:)?(#[0-9a-fA-F]{3,6}|[a-zA-Z_]+)(?:>|\\s[^>]*>)|&([0-9a-fA-F])")
        val violations = mutableListOf<String>()
        val resources = Path.of("src/main/resources")

        Files.walk(resources).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension in setOf("yml", "yaml", "json") }
                .forEach { path ->
                    path.readLines().forEachIndexed { index, line ->
                        line.indices.filter { line.startsWith("💰", it) }.forEach { coinIndex ->
                            val match = colorToken.findAll(line.substring(0, coinIndex)).lastOrNull()
                            val nearestColor = match?.groupValues?.let { it[1].ifEmpty { it[2] } }
                            if (nearestColor?.lowercase() !in setOf("white", "#fff", "#ffffff", "f")) {
                                violations += "${path}:${index + 1}: ${line.trim()}"
                            }
                        }
                    }
                }
        }

        violations.shouldBeEmpty()
    }
})
