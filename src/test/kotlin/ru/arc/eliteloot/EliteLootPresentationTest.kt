package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class EliteLootPresentationTest : FreeSpec({
    "tooltip progression has stable boundaries independent of resale price" {
        listOf(0, 19, 20, 39, 40, 59, 60, 79, 80, 99, 100, 190).map(::eliteTooltipTier) shouldBe
            listOf("common", "common", "uncommon", "uncommon", "rare", "rare", "epic", "epic", "legendary", "legendary", "artifact", "artifact")
    }
    "lore keeps one name gap and one gap between sections without empty tail" {
        val lines = listOf("", " ", "Уровень: 43", "", " ", "Описание", "", "")
        val result = compactEliteLore(lines.map(Component::text))
        result.map(PlainTextComponentSerializer.plainText()::serialize) shouldBe listOf("", "Уровень: 43", "", "Описание")
        compactEliteLore(result) shouldBe result
    }
})
