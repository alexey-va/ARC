package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.contain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import java.nio.file.Files

class HelpCenterConfigTest : StringSpec({
    "bundled help center is enabled and keeps player-facing text in yaml" {
        val root = Files.createTempDirectory("arc-help-center-config")
        try {
            ConfigManager.clear()
            val settings = HelpCenterConfig.load(root).snapshot()

            settings.enabled shouldBe true
            settings.maxHomes shouldBe 12
            settings.maxSearchResults shouldBe 8
            settings.text("root-title").contains("Помощь") shouldBe true
            settings.text("my-title").contains("Про меня") shouldBe true
            val profileBodies = listOf(
                settings.text("my-identity"),
                settings.text("my-summary"),
                settings.text("my-location"),
            )
            profileBodies shouldHaveSize 3
            profileBodies.joinToString().contains("<balance>") shouldBe true
            renderedProfileLines(profileBodies).forEach { line ->
                line shouldNot contain(Regex(" {3,}"))
            }
            MiniMessage.miniMessage().deserialize(settings.text("my-summary")).containsBold() shouldBe false
            MiniMessage.miniMessage().deserialize(settings.text("my-location")).containsBold() shouldBe false
            listOf(
                "my-label",
                "guide-label",
                "commands-label",
                "travel-label",
                "privat-label",
                "main-menu-label",
                "my-homes-label",
                "my-lands-label",
                "my-rank-label",
                "my-jobs-label",
                "my-quests-label",
                "my-skills-label",
            ).forEach { key ->
                MiniMessage.miniMessage().deserialize(settings.text(key)).containsBold() shouldBe false
            }
            settings.text("travel-body").contains("<homes>") shouldBe true
            settings.text("guide-body").contains("Мир строительства") shouldBe false
            settings.command("privat").label.contains("Поселения") shouldBe true
            settings.command("privat").keywords.contains("посел") shouldBe true
            settings.command("skills").label.contains("Навыки") shouldBe true
            settings.command("vanilla").label.contains("Обычный мир") shouldBe true
            settings.command("biomes").label.contains("новых биомов") shouldBe true
            runCatching { settings.command("build") }.isFailure shouldBe true
            settings.intent("land-delete").keywords.contains("удал") shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "rejects unbounded dynamic lists" {
        val root = Files.createTempDirectory("arc-help-center-invalid")
        try {
            val modules = Files.createDirectories(root.resolve("modules"))
            Files.writeString(
                modules.resolve("help-center.yml"),
                "limits:\n  max-homes: 200\n  max-search-results: 200\n",
            )
            ConfigManager.clear()

            runCatching { HelpCenterConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})

private fun renderedProfileLines(source: List<String>): List<String> {
    val placeholders = TagResolver.resolver(
        mapOf(
            "player" to "ArchitectureMax",
            "server" to "survival",
            "rank" to "Следопыт",
            "balance" to "8 000 000",
            "homes" to "3",
            "max_homes" to "8",
            "lands" to "2",
            "world" to "classic_survival",
            "x" to "-1842",
            "y" to "71",
            "z" to "3260",
        ).map { (key, value) -> Placeholder.unparsed(key, value) },
    )
    val plain = PlainTextComponentSerializer.plainText()
    return source
        .flatMap { plain.serialize(MiniMessage.miniMessage().deserialize(it, placeholders)).lines() }
        .filter(String::isNotBlank)
}

private fun Component.containsBold(): Boolean =
    decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE || children().any(Component::containsBold)
