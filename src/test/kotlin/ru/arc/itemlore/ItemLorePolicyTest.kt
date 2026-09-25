package ru.arc.itemlore

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ItemLorePolicyTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()

    "preserves rich unchanged lines and treats added input literally" {
        val rich = Component.text("Лесной меч", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
        val result = ItemLorePolicy.build(listOf(Component.empty(), rich), listOf("Лесной меч", "<red>буквально", "", "", "", ""))
            as ItemLorePolicy.Result.Ready

        result.draft.lore!!.first() shouldBe Component.empty()
        result.draft.lore[1] shouldBe rich
        plain.serialize(result.draft.lore[2]) shouldBe "<red>буквально"
        result.draft.lore[2].color() shouldBe NamedTextColor.WHITE
        result.draft.lore[2].decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        result.draft.editDistance shouldBe 15
    }

    "charges visible line breaks and Unicode code points" {
        ItemLorePolicy.levenshtein("меч", "мечи") shouldBe 1
        ItemLorePolicy.levenshtein("a\nb", "ab") shouldBe 1
        ItemLorePolicy.levenshtein("😀", "") shouldBe 1
        ItemLorePolicy.quoteMinor(editDistance = 1, stackAmount = 6, rateMinorPerCharacter = 500) shouldBe 3_000L
        ItemLorePolicy.quoteMinor(editDistance = 2, stackAmount = 1, rateMinorPerCharacter = 500) shouldBe 1_000L
        ItemLorePolicy.quoteMinor(editDistance = Int.MAX_VALUE, stackAmount = Int.MAX_VALUE, rateMinorPerCharacter = Long.MAX_VALUE) shouldBe null
    }

    "makes empty output an explicit clear and does not charge the presentation gap" {
        val result = ItemLorePolicy.build(listOf(Component.empty(), Component.text("старое")), listOf("", "", "", "", "", ""))
            as ItemLorePolicy.Result.Ready
        result.draft.lore shouldBe null
        result.draft.editDistance shouldBe 6
        val unchangedEmpty = ItemLorePolicy.build(emptyList(), listOf("", "", "", "", "", "")) as ItemLorePolicy.Result.Ready
        unchangedEmpty.draft.editDistance shouldBe 0
    }

    "leaves unchanged rich lore and interior blank rows byte-for-byte reusable" {
        val gap = Component.empty()
        val rich = Component.text("первая", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
        val blank = Component.empty().color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true)
        val last = Component.text("третья", NamedTextColor.AQUA)
        val existing = listOf(gap, rich, blank, last)
        val unchanged = ItemLorePolicy.build(existing, listOf("первая", "", "третья", "", "", "")) as ItemLorePolicy.Result.Ready
        unchanged.draft.editDistance shouldBe 0
        unchanged.draft.lore shouldBe existing
        plain.serialize(unchanged.draft.lore!![2]) shouldBe ""
        unchanged.draft.lore[1] shouldBe rich
    }

    "all-empty fields clear body blank rows too" {
        val existing = listOf(Component.empty(), Component.text("первая"), Component.empty(), Component.text("третья"))
        val result = ItemLorePolicy.build(existing, List(6) { "" }) as ItemLorePolicy.Result.Ready
        result.draft.lore shouldBe null
        result.draft.editDistance shouldBe 14
    }

    "rejects oversize existing content instead of truncating" {
        val tooMany = ItemLorePolicy.build((1..7).map { Component.text("$it") }, listOf("", "", "", "", "", ""))
        tooMany shouldBe ItemLorePolicy.Result.Rejected(ItemLorePolicy.Reason.TOO_MANY_EXISTING_ROWS)
        val tooLong = ItemLorePolicy.build(listOf(Component.text("x".repeat(81))), listOf("", "", "", "", "", ""))
        tooLong shouldBe ItemLorePolicy.Result.Rejected(ItemLorePolicy.Reason.EXISTING_ROW_TOO_LONG)
        val newline = ItemLorePolicy.build(emptyList(), listOf("a\nb"))
        newline shouldBe ItemLorePolicy.Result.Rejected(ItemLorePolicy.Reason.MULTILINE_INPUT)
        ItemLorePolicy.sameSnapshot(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)) shouldBe true
        ItemLorePolicy.sameSnapshot(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)) shouldBe false
        ItemLorePolicy.sameSnapshot(byteArrayOf(1, 2, 3), null) shouldBe false
    }
})
