package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.util.TextUtil

class RewardCatalogGuiControllerTest : StringSpec({
    "contextualizes native right-click hints without changing ordinary lore" {
        val native = TextUtil.mm("<#8c8c8c>[<#92bed8>▶<#8c8c8c>] <#92bed8>ПКМ<#e6fff3> — открыть", true)
        val contextualized = contextualizeRewardCatalogNativeLore(native)

        PlainTextComponentSerializer.plainText().serialize(contextualized) shouldBe "После получения: ПКМ — открыть"
        PlainTextComponentSerializer.plainText().serialize(native) shouldBe "[▶] ПКМ — открыть"
        contextualized.color() shouldBe native.color()

        val ordinary = Component.text("Описание [важное]")
        contextualizeRewardCatalogNativeLore(ordinary) shouldBe ordinary

        val quotedHint = Component.text("Описание [важное]: подсказка [▶] ПКМ — открыть")
        contextualizeRewardCatalogNativeLore(quotedHint) shouldBe quotedHint
    }
})
