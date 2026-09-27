package ru.arc.chat

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class ChatGlyphPolicyStickerTest : FreeSpec({
    val chatPermission = "ia.user.image.chat"
    val imagePermission = "ia.user.image.use.warhammer"
    val rawSticker = "\uE6A1"
    val stickerDefinition = ChatGlyphDefinition(
        id = "arc_warhammer:wh_adeptus",
        unicode = rawSticker,
        permission = imagePermission,
        technical = false,
    )
    val metrics = mapOf(stickerDefinition.id to ChatStickerFontMetrics(height = 48, ascent = 8))
    val policy = ChatGlyphPolicy(listOf(stickerDefinition), metrics)
    val granted = setOf(chatPermission, imagePermission)

    "both raw and alias stickers require existing chat and per-image permissions" {
        policy.violation(rawSticker, hasPermission = { it == chatPermission }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, stickerDefinition.id)
        policy.violation(":wh_adeptus:", hasPermission = { it == chatPermission }) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, stickerDefinition.id)
        policy.violation(rawSticker, granted::contains) shouldBe null
        policy.violation(":wh_adeptus:", granted::contains) shouldBe null
    }

    "one listed sticker is allowed alone with optional whitespace and one routing prefix" {
        policy.violation("  $rawSticker \t", granted::contains) shouldBe null
        policy.violation("  !  :wh_adeptus:  ", granted::contains) shouldBe null
        policy.standaloneSticker("  !  :wh_adeptus:  ") shouldBe
            ChatStickerFontImage(stickerDefinition.id, rawSticker, 48, 8)
        policy.standaloneSticker("caption :wh_adeptus:") shouldBe null
    }

    "large stickers mixed with prose or another sticker are rejected" {
        listOf(
            "look $rawSticker",
            "$rawSticker hello",
            ":wh_adeptus:!",
            "$rawSticker$rawSticker",
            ":wh_adeptus: :wh_adeptus:",
            "! ! $rawSticker",
            "!!$rawSticker",
        ).forEach { input ->
            policy.violation(input, granted::contains) shouldBe
                ChatGlyphViolation(ChatGlyphViolationReason.STICKER_MUST_BE_STANDALONE, stickerDefinition.id)
        }
    }

    "permission failures precede the standalone-message constraint" {
        policy.violation(
            "caption $rawSticker",
            hasPermission = { it == chatPermission },
        ) shouldBe ChatGlyphViolation(ChatGlyphViolationReason.UNAUTHORIZED, stickerDefinition.id)
    }

    "technical and unlisted private-use glyphs remain blocked beside a sticker" {
        val policyWithTechnicalGlyph = ChatGlyphPolicy(
            listOf(
                stickerDefinition,
                ChatGlyphDefinition("arc:gui", "\uE6A2", null, technical = true),
            ),
            metrics,
        )

        policyWithTechnicalGlyph.violation("$rawSticker\uE6A2", granted::contains) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.TECHNICAL, "arc:gui")
        policy.violation("$rawSticker\uE6A3", granted::contains) shouldBe
            ChatGlyphViolation(ChatGlyphViolationReason.UNKNOWN_PRIVATE_USE)
    }

    "renderer mapping is exact-ID, read-only, and excludes missing or technical definitions" {
        val active = ChatGlyphPolicy(
            listOf(
                stickerDefinition,
                ChatGlyphDefinition("arc:other", "\uE6A2", null, technical = true),
            ),
            metrics + ("arc:other" to ChatStickerFontMetrics(48, 8)),
        )

        active.stickerFontImages() shouldBe mapOf(
            stickerDefinition.id to ChatStickerFontImage(stickerDefinition.id, rawSticker, 48, 8),
        )
        shouldThrowAny {
            (active.stickerFontImages() as MutableMap)["arc:injected"] = ChatStickerFontImage("arc:injected", "\uE6A4", 48, 8)
        }
    }
})
