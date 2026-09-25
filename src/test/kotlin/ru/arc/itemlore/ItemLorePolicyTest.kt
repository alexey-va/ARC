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
        val result = ItemLorePolicy.build(listOf(Component.empty(), rich), listOf("&6&lЛесной меч", "<red>буквально", "", "", "", ""))
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
        val unchanged = ItemLorePolicy.build(existing, listOf("&6&lпервая", "", "&bтретья")) as ItemLorePolicy.Result.Ready
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

    "dynamic rows beyond six are quoted and removing all fields clears lore" {
        val rows = List(ItemLorePolicy.MAX_ROWS) { "я" }
        val added = ItemLorePolicy.build(emptyList(), rows) as ItemLorePolicy.Result.Ready
        added.draft.visibleRows shouldBe rows
        added.draft.editDistance shouldBe 63 // 32 letters and 31 line breaks
        ItemLorePolicy.quoteMinor(added.draft.editDistance, 6, 500) shouldBe 189_000L
        val cleared = ItemLorePolicy.build(added.draft.lore, emptyList()) as ItemLorePolicy.Result.Ready
        cleared.draft.lore shouldBe null
        cleared.draft.editDistance shouldBe 63
        ItemLorePolicy.build(emptyList(), rows + "ещё") shouldBe
            ItemLorePolicy.Result.Rejected(ItemLorePolicy.Reason.TOO_MANY_FIELDS)
    }

    "color-only edits have a quote and equivalent codes are free" {
        val green = listOf(Component.empty(), Component.text("Меч", NamedTextColor.GREEN))
        ItemLorePolicy.editableText(green[1]) shouldBe "&aМеч"
        val red = ItemLorePolicy.build(green, listOf("&cМеч")) as ItemLorePolicy.Result.Ready
        red.draft.editDistance shouldBe 1
        red.draft.lore!![1].color() shouldBe NamedTextColor.RED
        ItemLorePolicy.quoteMinor(red.draft.editDistance, 6, 500) shouldBe 3_000L
        val same = ItemLorePolicy.build(green, listOf("&#55ff55&aМеч")) as ItemLorePolicy.Result.Ready
        same.draft.editDistance shouldBe 0
        same.draft.lore shouldBe green
        val remove = ItemLorePolicy.build(green, listOf("Меч")) as ItemLorePolicy.Result.Ready
        remove.draft.editDistance shouldBe 2
        remove.draft.lore!![1].color() shouldBe NamedTextColor.WHITE
    }

    "supports hex and styles without charging implicit white or parsing MiniMessage" {
        val original = listOf(Component.text("Меч"))
        val hex = ItemLorePolicy.build(original, listOf("&#12ab34Меч")) as ItemLorePolicy.Result.Ready
        hex.draft.editDistance shouldBe 8
        hex.draft.lore!![1].color()!!.value() shouldBe 0x12AB34
        val bold = ItemLorePolicy.build(original, listOf("&lМеч")) as ItemLorePolicy.Result.Ready
        bold.draft.editDistance shouldBe 2
        bold.draft.lore!![1].decoration(TextDecoration.BOLD) shouldBe TextDecoration.State.TRUE
        val white = ItemLorePolicy.build(original, listOf("&fМеч")) as ItemLorePolicy.Result.Ready
        white.draft.editDistance shouldBe 0
        val markup = ItemLorePolicy.build(emptyList(), listOf("<red>Меч")) as ItemLorePolicy.Result.Ready
        markup.draft.visibleRows shouldBe listOf("<red>Меч")
    }

    "round trips literal ampersands and accepts eighty colored Unicode characters" {
        val literal = listOf(Component.text("&aМеч"))
        ItemLorePolicy.editableText(literal[0]) shouldBe "&&aМеч"
        val same = ItemLorePolicy.build(literal, listOf("&&aМеч")) as ItemLorePolicy.Result.Ready
        same.draft.editDistance shouldBe 0
        same.draft.lore shouldBe literal
        val colored = ItemLorePolicy.build(literal, listOf("&aМеч")) as ItemLorePolicy.Result.Ready
        colored.draft.editDistance shouldBe 1
        colored.draft.visibleRows shouldBe listOf("Меч")
        val max = ItemLorePolicy.build(emptyList(), listOf("&#12ab34😀".repeat(80))) as ItemLorePolicy.Result.Ready
        max.draft.visibleRows shouldBe listOf("😀".repeat(80))
        ItemLorePolicy.build(emptyList(), listOf("&a" + "я".repeat(81))) shouldBe
            ItemLorePolicy.Result.Rejected(ItemLorePolicy.Reason.ROW_TOO_LONG)
    }

    "bounds colored quote work while accepting no-op and a small edit in maximum lore" {
        val rows = List(32) { "&#12ab34x&#654321x".repeat(40) }
        val original = (ItemLorePolicy.build(emptyList(), rows) as ItemLorePolicy.Result.Ready).draft.lore
        val unchanged = ItemLorePolicy.build(original, rows) as ItemLorePolicy.Result.Ready
        unchanged.draft.editDistance shouldBe 0
        unchanged.draft.lore shouldBe original
        val small = rows.toMutableList().also { it[it.lastIndex] = it.last().dropLast(1) + "я" }
        (ItemLorePolicy.build(original, small) as ItemLorePolicy.Result.Ready).draft.editDistance shouldBe 1
        ItemLorePolicy.build(original, List(32) { "&#abcdefy&#123456y".repeat(40) }) shouldBe
            ItemLorePolicy.Result.Rejected(ItemLorePolicy.Reason.COMPLEX_EDIT)
        val maximumPlain = List(32) { "я".repeat(80) }
        val plainLore = (ItemLorePolicy.build(emptyList(), maximumPlain) as ItemLorePolicy.Result.Ready).draft.lore
        (ItemLorePolicy.build(plainLore, List(32) { "ю".repeat(80) }) as ItemLorePolicy.Result.Ready).draft.editDistance shouldBe 2560
    }

    "rejects oversize existing content instead of truncating" {
        val tooMany = ItemLorePolicy.build((1..ItemLorePolicy.MAX_ROWS + 1).map { Component.text("$it") }, emptyList())
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
