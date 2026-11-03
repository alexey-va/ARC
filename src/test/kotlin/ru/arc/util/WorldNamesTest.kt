package ru.arc.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class WorldNamesTest : FunSpec({
    test("remote menus and the world placeholder use the same explicit and wildcard aliases") {
        val aliases = linkedMapOf("em_*" to "&6Мир данжа", "em_town" to "&2Гильдия", "vanilla" to "&7Ванильный мир")
        WorldNames.configured("em_town", aliases) shouldBe "&2Гильдия"
        WorldNames.configured("em_dungeon", aliases) shouldBe "&6Мир данжа"
        PlainTextComponentSerializer.plainText().serialize(WorldNames.display("vanilla", aliases)) shouldBe "Ванильный мир"
        PlainTextComponentSerializer.plainText().serialize(WorldNames.display("unlisted", aliases)) shouldBe "Обычный мир"
    }
})
