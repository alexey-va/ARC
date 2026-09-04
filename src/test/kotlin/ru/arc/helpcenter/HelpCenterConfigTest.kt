package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.contain
import io.kotest.assertions.withClue
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
            settings.text("root-title").contains("Главное меню") shouldBe true
            settings.text("now-title").contains("Про меня") shouldBe true
            val profileBodies = listOf(
                settings.text("now-identity"),
                settings.text("now-progress"),
                settings.text("now-location"),
            )
            profileBodies shouldHaveSize 3
            profileBodies.joinToString().contains("<balance>") shouldBe true
            val renderedProfile = renderedProfileLines(profileBodies)
            renderedProfile shouldBe listOf(
                "ArchitectureMax", "Сервер: survival · Онлайн: 27", "Ранг: Следопыт",
                "Баланс: 8 000 000", "Дома: 3/8 · Приваты: 2", "Мир: classic_survival",
                "Координаты: -1842, 71, 3260", "Чат: локальный",
            )
            renderedProfile.forEach { line ->
                line shouldNot contain(Regex(" {3,}"))
            }
            profileBodies.joinToString() shouldNot contain("#f0a9c6")
            listOf(
                "root-body",
                "my-label",
                "commands-label",
                "my-identity",
                "my-rank-label",
                "my-skills-label",
                "rules-label",
                "category-progress-label",
            ).map(settings::text).joinToString() shouldNot contain("#b68cff")
            profileBodies.forEach { source -> MiniMessage.miniMessage().deserialize(source).containsBold() shouldBe false }
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
                "now-title",
                "now-label",
                "players-label",
                "players-search-label",
                "player-label",
                "category-activities-label",
                "category-technology-label",
                "category-settings-label",
            ).forEach { key ->
                MiniMessage.miniMessage().deserialize(settings.text(key)).containsBold() shouldBe false
            }
            settings.text("travel-body").contains("<homes>") shouldBe true
            settings.text("travel-title").contains("Телепортация") shouldBe true
            settings.text("guide-body").contains("Мир строительства") shouldBe false
            settings.command("privat").label.contains("Приват") shouldBe true
            settings.command("privat").keywords.contains("посел") shouldBe true
            settings.command("dungeons").label.contains("Данжи") shouldBe true
            settings.command("skills").label.contains("Навыки") shouldBe true
            settings.command("vanilla").label.contains("Обычный мир") shouldBe true
            settings.command("biomes").label.contains("новых биомов") shouldBe true
            settings.command("vote").description.contains("голос", ignoreCase = true) shouldBe true
            settings.command("chat-global").description.contains("глобаль", ignoreCase = true) shouldBe true
            settings.command("chat-local").description.contains("локаль", ignoreCase = true) shouldBe true
            runCatching { settings.command("notes") }.isFailure shouldBe true
            runCatching { settings.command("donate") }.isFailure shouldBe true
            runCatching { settings.command("build") }.isFailure shouldBe true
            settings.intent("land-delete").keywords.contains("удал") shouldBe true

            listOf(
                "kit-label",
                "search-label",
                "home-label",
                "create-home-label",
                "home-teleport-label",
                "home-relocate-label",
                "warps-label",
                "spawn-label",
                "rtp-label",
                "back-command-label",
                "stuck-label",
                "vanilla-label",
                "mining-label",
                "biomes-label",
                "public-homes-label",
                "command-vote-label",
                "command-chat-global-label",
                "command-chat-local-label",
            ).forEach { key ->
                MiniMessage.miniMessage().deserialize(settings.text(key)).containsBold() shouldBe false
            }
            setOf(
                settings.text("now-label").substringBefore('>'),
                settings.text("players-label").substringBefore('>'),
                settings.text("privat-label").substringBefore('>'),
                settings.text("category-trade-label").substringBefore('>'),
            ).size shouldBe 2
            settings.text("back-command-label").contains("прежнее место", ignoreCase = true) shouldBe true
            listOf(
                "favorites-short-label",
                "requests-short-label",
                "context-short-label",
                "goals-short-label",
                "context-item-label",
                "action-run-label",
            ).forEach { key ->
                MiniMessage.miniMessage().deserialize(settings.text(key)).containsBold() shouldBe false
            }
            settings.text("requests-body") shouldNot contain("Iris")
            settings.text("context-body") shouldNot contain("Iris")
            settings.text("favorites-body").contains("<favorites>") shouldBe true
            settings.text("diagnostic-body").contains("<facts>") shouldBe true
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
            "online" to "27",
            "chat" to "локальный",
        ).map { (key, value) -> Placeholder.unparsed(key, value) },
    )
    val plain = PlainTextComponentSerializer.plainText()
    return source
        .flatMap { plain.serialize(MiniMessage.miniMessage().deserialize(it, placeholders)).lines() }
        .filter(String::isNotBlank)
}

private fun Component.containsBold(): Boolean =
    decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE || children().any(Component::containsBold)
