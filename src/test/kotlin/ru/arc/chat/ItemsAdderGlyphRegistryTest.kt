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
        result.getValue("_iainternal:cooldown").technical shouldBe true
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
            "_iainternal:cooldown" to "\uE104",
        )
    }
    fun getInternal(): FakeImageMetadata = FakeImageMetadata(if (id == "emoji:vip") "ia.user.image.use.vip" else null)
    fun getHeight(): Int = if (id == "arc:gui") 256 else 8
    fun getWidth(): Int = if (id == "arc:wide") 512 else 64
}

class FakeImageMetadata(private val permission: String?) {
    fun getPermission(): String? = permission
}
