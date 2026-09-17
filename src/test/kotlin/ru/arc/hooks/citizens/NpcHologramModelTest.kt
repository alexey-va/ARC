package ru.arc.hooks.citizens

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class NpcHologramModelTest : StringSpec({
    "source components are rebuilt only when authored content changes" {
        val state = NpcHologramState()
        val first = NpcHologramSource(name = "&6Guide", lines = listOf("&7One", "&fTwo"), lineHeight = 0.25, viewRange = 14)

        state.apply(first) shouldBe true
        state.apply(first) shouldBe false
        state.bodyText() shouldBe "&7One\n&fTwo"

        state.apply(first.copy(lines = listOf("&7Changed", "&fTwo"))) shouldBe true
        state.bodyText() shouldBe "&7Changed\n&fTwo"
    }

    "temporary bubble replaces only the temporary layer and expires by ticks" {
        val state = NpcHologramState()
        state.apply(NpcHologramSource(name = "Guide", lines = listOf("Body"), lineHeight = 0.25, viewRange = 14))

        state.showBubble(listOf("Hello", "World"), ttlTicks = 2)
        state.visibleLines() shouldBe listOf("Hello", "World")
        state.tick() shouldBe false
        state.visibleLines() shouldBe listOf("Hello", "World")
        state.tick() shouldBe true
        state.visibleLines() shouldBe listOf("Body")
    }

    "an owned temporary bubble can be cleared without touching another owner" {
        val state = NpcHologramState()
        state.apply(NpcHologramSource(name = "Guide", lines = listOf("Body"), lineHeight = 0.25, viewRange = 14))

        state.showBubble(listOf("Owned"), ttlTicks = 20, owner = "dialogue-a")
        state.clearBubble("dialogue-b") shouldBe false
        state.visibleLines() shouldBe listOf("Owned")
        state.clearBubble("dialogue-a") shouldBe true
        state.visibleLines() shouldBe listOf("Body")
    }

    "a legacy final name line is removed when ARC renders the name separately" {
        val presentation = resolveNpcHologramPresentation(
            lines = listOf("&eСоветы о кузне", "&7Главный кузнец", "&6&lЭдгар"),
            npcName = "Эдгар",
        )

        presentation.name shouldBe "&6&lЭдгар"
        presentation.lines shouldBe listOf("&eСоветы о кузне", "&7Главный кузнец")
    }

    "a role-prefixed NPC name may deduplicate its final plain-name token" {
        normalizeNpcHologramLines(
            lines = listOf("&eКланы", "&6&lНатан"),
            npcName = "Клановый писарь Натан",
        ) shouldBe listOf("&eКланы")
    }

    "multiline body is raised far enough to stay clear of the name" {
        npcHologramBodyOffset(bodyGap = 0.26, lineHeight = 0.25, lineCount = 1) shouldBe 0.26
        npcHologramBodyOffset(bodyGap = 0.26, lineHeight = 0.25, lineCount = 3) shouldBe 0.51
    }

    "Citizens tracking range keeps its block-distance meaning" {
        npcHologramViewRange(citizensRange = 16, fallbackMultiplier = 1.0f) shouldBe 0.25f
        npcHologramViewRange(citizensRange = -1, fallbackMultiplier = 1.0f) shouldBe 1.0f
    }

    "legacy no-chat speech remains silent through the ARC event bridge" {
        npcSpeechBridgeCancelsChat(false) shouldBe true
        npcSpeechBridgeCancelsChat(true) shouldBe false
        npcSpeechBridgeCancelsChat(null) shouldBe false
    }

    "speech hologram has no decorative bullet and keeps line breaks" {
        val component = npcSpeechComponent(
            listOf(Component.text("Первая"), Component.text("Вторая")),
            TextColor.color(0xE6EDF3),
        )

        PlainTextComponentSerializer.plainText().serialize(component) shouldBe "Первая\nВторая"
    }
})
