package ru.arc.chat

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class ItemsAdderGlyphRegistryTest : FreeSpec({
    "runtime adapter uses raw registry values and already effective permission nodes" {
        val result = ItemsAdderGlyphRegistry(FakeFontImage::class.java).snapshot().associateBy { it.id }
        result.getValue("emoji:vip") shouldBe ChatGlyphDefinition("emoji:vip", "\uE100", "ia.user.image.use.vip", false)
        result.getValue("emoji:supplementary").unicode shouldBe String(Character.toChars(0x100123))
        result.getValue("rank:compact").technical shouldBe false
        result.getValue("arc:gui").technical shouldBe true
        result.getValue("arc:wide").technical shouldBe true
        result.getValue("arc:unlisted_large").technical shouldBe true
        result.getValue("arc:sticker").technical shouldBe true
        result.getValue("_iainternal:cooldown").technical shouldBe true
    }

    "configured exact IDs bypass only the matching height threshold" {
        val configured = mapOf(
            "arc:gui" to ChatStickerFontMetrics(48, 8),
            "arc:wide" to ChatStickerFontMetrics(48, 8),
            "arc:unlisted_large" to ChatStickerFontMetrics(48, 8),
            "arc:sticker" to ChatStickerFontMetrics(48, 8),
            "arc:height_mismatch" to ChatStickerFontMetrics(48, 8),
            "arc:undersized_mismatch" to ChatStickerFontMetrics(48, 8),
            "_iainternal:cooldown" to ChatStickerFontMetrics(48, 8),
            "arc:offset_manual" to ChatStickerFontMetrics(48, 8),
        )
        val result = ItemsAdderGlyphRegistry(FakeFontImage::class.java, configured).snapshot().associateBy { it.id }

        result.getValue("arc:gui").technical shouldBe true
        result.getValue("arc:wide").technical shouldBe true
        result.getValue("arc:unlisted_large").technical shouldBe true
        result.getValue("arc:sticker").technical shouldBe false
        result.getValue("arc:height_mismatch").technical shouldBe true
        result.getValue("arc:undersized_mismatch").technical shouldBe true
        result.getValue("_iainternal:cooldown").technical shouldBe true
        result.getValue("arc:offset_manual").technical shouldBe true
    }

    "unsupported third party API fails explicitly instead of returning a permissive empty map" {
        shouldThrowAny { ItemsAdderGlyphRegistry(String::class.java).snapshot() }
    }
})

class FakeFontImage(private val id: String) {
    companion object {
        @JvmStatic fun getNamespacedIdsAndValueInRegistry(): Map<String, String> = linkedMapOf(
            "emoji:vip" to "\uE100", "emoji:supplementary" to String(Character.toChars(0x100123)),
            "rank:compact" to "\uE101", "arc:gui" to "\uE102", "arc:wide" to "\uE103",
            "arc:unlisted_large" to "\uE105", "arc:sticker" to "\uE106",
            "arc:height_mismatch" to "\uE107", "arc:undersized_mismatch" to "\uE108",
            "arc:offset_manual" to "\uE109",
            "_iainternal:cooldown" to "\uE104",
        )
    }
    fun getInternal(): FakeImageMetadata = FakeImageMetadata(if (id == "emoji:vip") "ia.user.image.use.vip" else null)
    fun getHeight(): Int = when (id) {
        "arc:gui" -> 256
        "arc:wide", "arc:sticker", "arc:offset_manual" -> 48
        "arc:unlisted_large" -> 17
        "arc:height_mismatch" -> 64
        else -> 8
    }
    fun getWidth(): Int = if (id == "arc:wide") 512 else 64
}

class FakeImageMetadata(private val permission: String?) {
    fun getPermission(): String? = permission
}
