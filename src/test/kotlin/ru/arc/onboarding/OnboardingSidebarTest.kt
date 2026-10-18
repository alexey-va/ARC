package ru.arc.onboarding

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.sidebar.SidebarSection
import ru.arc.sidebar.composeBaseSidebarSections
import ru.arc.sidebar.composeSidebarSections
import ru.arc.sidebar.resolveServerSidebarLine
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class OnboardingSidebarTest : FreeSpec({
    val serializer = LegacyComponentSerializer.builder().character('&').hexColors().build()
    fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    val run = UUID.randomUUID().toString()
    val firstRtp = setOf(OnboardingMilestone.FIRST_RTP)
    val home = firstRtp + OnboardingMilestone.HOME_CREATED
    val foothold = home + setOf(OnboardingMilestone.LAND_CLAIMED, OnboardingMilestone.FOOTHOLD_COMPLETE)
    fun view(milestones: Set<OnboardingMilestone> = emptySet()) = OnboardingGuideView(
        OnboardingQuestView(run, true, milestones), OnboardingPlaytime.LIMIT_TICKS, OnboardingGuideReward(200, 1),
    )

    "every current goal fits four short rows with one starred action and aligned supporting text" {
        val states = listOf(
            emptySet<OnboardingMilestone>() to "Выберите мир",
            firstRtp to "Сохраните точку дома",
            home to "Защитите участок",
            (home + OnboardingMilestone.LAND_CLAIMED) to "Перенесите точку дома",
            foothold to "Постройте дом",
            (foothold + OnboardingMilestone.BUILD_BOOK_OPENED) to "Начните постройку",
            (foothold + OnboardingMilestone.AUTOBUILD_STARTED) to "Дом строится",
        )
        states.forEach { (milestones, heading) ->
            val lines = onboardingGuideLines(view(milestones))
            lines.size shouldBe 4
            plain(lines[1]) shouldBe "★ $heading"
            serializer.serialize(lines[1]) shouldContain "&#77ee94"
            plain(lines[2]).startsWith("   ") shouldBe true
            plain(lines[2]).drop(3).first().isLowerCase() shouldBe true
            plain(lines[3]) shouldBe "   Награда 200 💰 1 "
            plain(lines[0]).startsWith("Первый дом · ") shouldBe true
            lines.all { plain(it).length <= 32 && '\n' !in plain(it) && '|' !in plain(it) } shouldBe true
        }
        plain(onboardingGuideLines(view(firstRtp))[2]) shouldBe "   на месте будущей базы"
    }

    "offered completed and expired views release the ordinary scoreboard" {
        val current = view()
        onboardingGuideLines(current.copy(quest = current.quest.copy(accepted = false))) shouldBe emptyList()
        onboardingGuideLines(view(OnboardingMilestone.entries.toSet())) shouldBe emptyList()
        onboardingGuideLines(current.copy(remainingTicks = 0)) shouldBe emptyList()
        onboardingGuideLines(current.copy(remainingTicks = -1)) shouldBe emptyList()
    }

    "reward amounts are supplied independently and both resource-pack glyphs stay white" {
        val reward = serializer.serialize(onboardingGuideLines(view())[3])
        reward shouldContain "&f💰"
        reward shouldContain "&f"
        plain(onboardingGuideLines(view().copy(reward = OnboardingGuideReward(0, 3)))[3]) shouldBe "   Награда 3 "
        plain(onboardingGuideLines(view().copy(reward = OnboardingGuideReward(50, 0)))[3]) shouldBe "   Награда 50 💰"
        plain(onboardingGuideLines(view().copy(reward = null))[3]) shouldBe "   Подсказки: 3ч 0м"
        plain(onboardingGuideLines(view().copy(reward = OnboardingGuideReward(0, 0), remainingTicks = 1))[3]) shouldBe "   Подсказки: 1м"
    }

    "active guide replaces all auxiliary sections while preserving basic visibility preferences" {
        val requested = mutableListOf<SidebarSection>()
        val guide = listOf("title", "goal", "action", "reward")
        fun rows(hidden: Set<SidebarSection> = emptySet()) = composeBaseSidebarSections(
            guide, { it !in hidden }, { requested += it; listOf(it.id) }, "", String::isBlank,
        )
        rows() shouldContainExactly listOf("balance", "", "location", "", "title", "goal", "action", "reward")
        requested shouldContainExactly listOf(SidebarSection.BALANCE, SidebarSection.LOCATION)
        rows(setOf(SidebarSection.BALANCE, SidebarSection.LOCATION, SidebarSection.QUESTS)) shouldContainExactly guide
    }

    "ending the guide restores the exact existing section selection and its fifteen-row budget" {
        val enabled = { section: SidebarSection -> section.defaultEnabled }
        val rows = { section: SidebarSection -> listOf(section.id) }
        val default = composeSidebarSections(enabled, rows, "", String::isBlank)
        composeBaseSidebarSections(null, enabled, rows, "", String::isBlank) shouldBe default
        composeBaseSidebarSections(emptyList(), enabled, rows, "", String::isBlank) shouldBe default
        val guide = (1..4).map { "guide $it" }
        val crowded = composeBaseSidebarSections(guide, { true }, { (1..12).map { row -> "${it.id} $row" } }, "", String::isBlank)
        crowded shouldContainExactly guide
        (crowded.size <= 15) shouldBe true
    }

    "exports one real guide composed with the configured balance and location rows" {
        val config = requireNotNull(OnboardingSidebarTest::class.java.classLoader.getResourceAsStream("modules/scoreboard.yml"))
            .use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        val values = mapOf(
            "%rediseco_bal_short_vault%" to "1.2K", "%rediseco_bal_short_tokens%" to "3",
            "%arc_worldname%" to "Новые биомы", "%lands_land_name_plain_here%" to "Моя земля",
        )
        fun resolve(template: String) = values.entries.fold(template) { text, (key, value) -> text.replace(key, value) }
        val rows = composeBaseSidebarSections(
            onboardingGuideLines(view(firstRtp)), { it.defaultEnabled },
            { section -> config.getStringList("sections.${section.id}")
                .mapNotNull { resolveServerSidebarLine(it, "survival") }.map { serializer.deserialize(resolve(it)) } },
            Component.empty(), { it == Component.empty() },
        )
        (rows.size <= 15) shouldBe true
        val export = mapOf("home" to mapOf(
            "title" to config.getString("title"), "lines" to rows.map(serializer::serialize),
            "server" to "survival", "maximumRows" to 15,
        ))
        val output = Path.of("build/reports/onboarding-sidebar/content.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(export))
    }
})
