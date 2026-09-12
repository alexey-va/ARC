package ru.arc.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readLines

class CoinGlyphColorTest : StringSpec({
    "every coin glyph in ARC resource text is explicitly white" {
        val colorTag = Regex("<(?:color:)?(#[0-9a-fA-F]{3,6}|[a-zA-Z_]+)(?:>|\\s[^>]*>)")
        val violations = mutableListOf<String>()
        val resources = Path.of("src/main/resources")

        Files.walk(resources).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension in setOf("yml", "yaml", "json") }
                .forEach { path ->
                    path.readLines().forEachIndexed { index, line ->
                        line.indices.filter { line.startsWith("💰", it) }.forEach { coinIndex ->
                            val nearestColor = colorTag.findAll(line.substring(0, coinIndex)).lastOrNull()?.groupValues?.get(1)
                            if (nearestColor?.lowercase() !in setOf("white", "#fff", "#ffffff")) {
                                violations += "${path}:${index + 1}: ${line.trim()}"
                            }
                        }
                    }
                }
        }

        violations.shouldBeEmpty()
    }
})
