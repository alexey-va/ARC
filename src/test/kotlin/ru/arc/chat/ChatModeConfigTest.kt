package ru.arc.chat

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class ChatModeConfigTest : FreeSpec({
    "bundled defaults keep the explicit large-sticker ID table empty" {
        val dataPath = Files.createTempDirectory("arc-chat-stickers-default-")
        ConfigManager.clear()
        try {
            ChatModeConfig.load(dataPath).stickerFontImageMetrics shouldBe emptyMap()
        } finally {
            ConfigManager.clear()
        }
    }

    "loads exact IDs and immutable positive geometry" {
        val dataPath = Files.createTempDirectory("arc-chat-stickers-valid-")
        Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            dataPath.resolve("modules/chat-mode.yml"),
            """
            stickers:
              font-images:
                arc:warhammer_one:
                  height: 48
                  ascent: 8
                arc:warhammer_two:
                  height: 32
                  ascent: 6
            """.trimIndent(),
        )
        ConfigManager.clear()
        try {
            val metrics = ChatModeConfig.load(dataPath).stickerFontImageMetrics
            metrics shouldBe mapOf(
                "arc:warhammer_one" to ChatStickerFontMetrics(height = 48, ascent = 8),
                "arc:warhammer_two" to ChatStickerFontMetrics(height = 32, ascent = 6),
            )
            shouldThrowAny { (metrics as MutableMap)["arc:injected"] = ChatStickerFontMetrics(20, 10) }
        } finally {
            ConfigManager.clear()
        }
    }

    "strictly rejects missing, extra, or mistyped geometry fields" {
        listOf(
            """
            stickers:
              font-images:
                arc:broken:
                  height: 48
            """.trimIndent(),
            """
            stickers:
              font-images:
                arc:broken:
                  height: 48
                  ascent: 8
                  width: 96
            """.trimIndent(),
            """
            stickers:
              font-images:
                arc:broken:
                  height: "48"
                  ascent: 8
            """.trimIndent(),
        ).forEach { yaml ->
            val dataPath = Files.createTempDirectory("arc-chat-stickers-shape-")
            Files.createDirectories(dataPath.resolve("modules"))
            Files.writeString(dataPath.resolve("modules/chat-mode.yml"), yaml)
            ConfigManager.clear()
            try {
                shouldThrowAny { ChatModeConfig.load(dataPath).stickerFontImageMetrics }
            } finally {
                ConfigManager.clear()
            }
        }
    }

    "rejects dimensions outside the large-sticker safety bounds and reserved IDs" {
        val invalidConfigs = listOf(
            InvalidSticker("arc:short", 16, 8),
            InvalidSticker("arc:negative_ascent", 48, -1),
            InvalidSticker("_iainternal:reserved", 48, 8),
            InvalidSticker("arc:offset_manual", 48, 8),
        )
        invalidConfigs.forEach { (id, height, ascent) ->
            val dataPath = Files.createTempDirectory("arc-chat-stickers-range-")
            Files.createDirectories(dataPath.resolve("modules"))
            Files.writeString(
                dataPath.resolve("modules/chat-mode.yml"),
                """
                stickers:
                  font-images:
                    $id:
                      height: $height
                      ascent: $ascent
                """.trimIndent(),
            )
            ConfigManager.clear()
            try {
                shouldThrowAny { ChatModeConfig.load(dataPath).stickerFontImageMetrics }
            } finally {
                ConfigManager.clear()
            }
        }
    }
})

private data class InvalidSticker(val id: String, val height: Int, val ascent: Int)
