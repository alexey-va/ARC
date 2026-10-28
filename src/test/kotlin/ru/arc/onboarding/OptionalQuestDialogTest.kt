package ru.arc.onboarding

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.onboarding.api.CasinoIntroService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class OptionalQuestDialogTest : FreeSpec({
    val player = mockk<Player>(relaxed = true)
    every { player.uniqueId } returns UUID.randomUUID()
    every { player.isOnline } returns true
    every { player.isDead } returns false
    val context = mockk<PaperDialogClickContext>()
    every { context.player } returns player
    var current: OptionalQuestView? = null
    var shown: PaperDialogScreen? = null
    var dismiss: (() -> Unit)? = null
    val actions = mutableListOf<String>()
    val dialog = OptionalQuestDialog(
        view = { _, _ -> current },
        accept = { _, kind -> actions += "accept:${kind.id}" },
        action = { _, _, action -> actions += action.name },
        showDialog = { _, screen, _, onDismiss -> shown = screen; dismiss = onDismiss },
    )
    fun text(key: String) = MiniMessage.miniMessage().deserialize(OptionalQuestDialog.DEFAULT_TEXT.getValue(key))
    fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    fun view(kind: OptionalQuestKind, steps: Set<OptionalQuestStep> = emptySet()) =
        OptionalQuestView(kind, true, false, steps)
    fun click(screen: PaperDialogScreen, id: String = screen.buttons.first().id.value) =
        screen.buttons.single { it.id.value == id }.onClick.handle(context)
    val floors = setOf(OptionalQuestStep.CASINO_GROUND_VISITED, OptionalQuestStep.CASINO_MIDDLE_VISITED,
        OptionalQuestStep.CASINO_UPPER_VISITED)

    beforeEach {
        dialog.close()
        current = null
        shown = null
        dismiss = null
        actions.clear()
    }

    "acceptance and travel callbacks revalidate their kind and current step" {
        current = view(OptionalQuestKind.ADVENTURE).copy(accepted = false)
        val offer = dialog.screen(OptionalQuestKind.ADVENTURE, current)
        offer.buttons.map { it.id.value } shouldContainExactly listOf("accept", "guild")
        click(offer)
        actions shouldContainExactly listOf("accept:adventure")
        actions.clear()
        current = view(OptionalQuestKind.ADVENTURE)
        click(offer)
        actions shouldBe emptyList()
        val guild = dialog.screen(OptionalQuestKind.ADVENTURE, current)
        click(guild)
        actions shouldContainExactly listOf("GUILD")
        actions.clear()
        current = view(OptionalQuestKind.ADVENTURE, setOf(OptionalQuestStep.GUILD_VISITED))
        click(guild)
        actions shouldContainExactly listOf("GUILD")
        actions.clear()
        val primis = dialog.screen(OptionalQuestKind.ADVENTURE, current)
        click(primis)
        actions shouldContainExactly listOf("PRIMIS")
        current = view(OptionalQuestKind.FARM)
        actions.clear()
        click(primis)
        actions shouldBe emptyList()
        shown!!.buttons shouldBe emptyList()
    }

    "casino admission only arms the next real round and does not pretend it is complete" {
        for ((game, action) in listOf(
            CasinoIntroService.Game.SLOTS to "TRIAL_SLOTS",
            CasinoIntroService.Game.BLACKJACK to "TRIAL_BLACKJACK",
            CasinoIntroService.Game.PLINKO to "TRIAL_PLINKO",
        )) {
            val earlierGames = CasinoIntroService.Game.entries.takeWhile { it != game }.map { it.questStep() }
            current = view(OptionalQuestKind.CASINO, floors + earlierGames)
            val ready = dialog.screen(OptionalQuestKind.CASINO, current)
            ready.buttons.first().id.value shouldBe action.lowercase()
            click(ready)
            actions.last() shouldBe action
            for (state in CasinoIntroService.State.entries.filter { it != CasinoIntroService.State.AVAILABLE }) {
                current = current!!.copy(trialStates = mapOf(game to state))
                dialog.screen(OptionalQuestKind.CASINO, current).buttons.map { it.id.value } shouldContainExactly listOf("casino")
                val oldActionCount = actions.size
                click(ready)
                actions.size shouldBe oldActionCount
                current!!.completed shouldBe false
            }
            current = current!!.copy(trialStates = mapOf(game to CasinoIntroService.State.ARMED))
            plain(dialog.screen(OptionalQuestKind.CASINO, current).body.first().text) shouldContain "Сыграйте"
            current = current!!.copy(trialStates = mapOf(game to CasinoIntroService.State.COMPLETE))
            plain(dialog.screen(OptionalQuestKind.CASINO, current).body.first().text) shouldContain "Сохраняем результат"
        }
    }

    "safe NPC destinations remain available before acceptance and after completion" {
        for ((kind, destinations) in listOf(
            OptionalQuestKind.ADVENTURE to listOf("guild", "primis"),
            OptionalQuestKind.FARM to listOf("farm"),
            OptionalQuestKind.CASINO to listOf("casino"),
        )) {
            current = view(kind).copy(accepted = false)
            val offer = dialog.screen(kind, current)
            offer.buttons.map { it.id.value } shouldContainExactly listOf("accept", destinations.first())
            click(offer, destinations.first())
            current = view(kind, kind.steps).copy(completed = true)
            val completed = dialog.screen(kind, current)
            completed.buttons.map { it.id.value } shouldContainExactly destinations
            click(completed, destinations.first())
            actions.takeLast(2) shouldContainExactly listOf(destinations.first().uppercase(), destinations.first().uppercase())
        }
    }

    "a dismissed or different quest visit never reopens on a late durable result" {
        current = view(OptionalQuestKind.ADVENTURE)
        val first = dialog.beginPending(player, OptionalQuestKind.ADVENTURE)
        val dismissFirst = dismiss!!
        val second = dialog.beginPending(player, OptionalQuestKind.FARM)
        dismissFirst()
        dialog.isCurrent(player, OptionalQuestKind.FARM, second) shouldBe true
        dialog.finish(player, OptionalQuestKind.ADVENTURE, first)
        shown!!.id shouldBe "onboarding.optional.farm"
        dismiss!!.invoke()
        val dismissed = shown
        dialog.finish(player, OptionalQuestKind.FARM, second)
        shown shouldBe dismissed
        dialog.isCurrent(player, OptionalQuestKind.FARM, second) shouldBe false
    }

    "every offered progress and trial state resolves readable short hints and exportable panels" {
        val samples = linkedMapOf<String, OptionalQuestView>()
        for (kind in OptionalQuestKind.entries) {
            samples["${kind.id}-offer"] = view(kind).copy(accepted = false)
            var steps = emptySet<OptionalQuestStep>()
            for (step in kind.steps) {
                val snapshot = view(kind, steps)
                samples[step.name.lowercase()] = snapshot
                step.trialGame()?.let { game ->
                    for (state in CasinoIntroService.State.entries) {
                        samples["${step.name.lowercase()}-${state.name.lowercase()}"] = snapshot.copy(trialStates = mapOf(game to state))
                    }
                }
                steps = steps + step
            }
            samples["${kind.id}-complete"] = view(kind, steps).copy(completed = true)
        }
        val serializer = LegacyComponentSerializer.builder().character('&').hexColors().build()
        val export = samples.mapValues { (_, snapshot) ->
            val screen = dialog.screen(snapshot.kind, snapshot)
            val lines = optionalQuestGuideLines(snapshot, ::text)
            (lines.size <= 4) shouldBe true
            if (!snapshot.accepted || snapshot.completed) lines shouldBe emptyList()
            lines.forEach { line ->
                (plain(line).sumOf(DialogTextLayout::glyphWidth) <= 250) shouldBe true
            }
            mapOf(
                "title" to serializer.serialize(screen.title), "columns" to screen.columns,
                "body" to screen.body.map { mapOf("text" to serializer.serialize(it.text), "width" to it.width) },
                "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to serializer.serialize(it.label), "width" to it.width) },
                "exit" to mapOf("text" to serializer.serialize(screen.exitButton!!.label), "width" to screen.exitButton!!.width),
                "sidebar" to lines.map(serializer::serialize),
            )
        }
        OptionalQuestDialog.DEFAULT_TEXT.values.forEach { source ->
            source.contains("<gray>") shouldBe false
            source.contains("<dark_gray>") shouldBe false
        }
        val output = Path.of("build/reports/optional-quests/content.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(export))
    }

    "the HUD reuses its bar and cleans it up when the quest ends or the player dies" {
        val shownBars = mutableListOf<BossBar>()
        every { player.showBossBar(capture(shownBars)) } returns Unit
        OptionalQuestHud(::text).use { hud ->
            hud.refresh(player, view(OptionalQuestKind.FARM).copy(accepted = false))
            shownBars.size shouldBe 0
            hud.refresh(player, view(OptionalQuestKind.FARM))
            hud.refresh(player, view(OptionalQuestKind.FARM, setOf(OptionalQuestStep.FARM_VISITED)))
            shownBars.size shouldBe 1
            plain(shownBars.single().name()) shouldBe "Завершите первую смену"
            every { player.isDead } returns true
            hud.refresh(player, view(OptionalQuestKind.FARM))
            verify(exactly = 1) { player.hideBossBar(shownBars.single()) }
            every { player.isDead } returns false
            hud.refresh(player, view(OptionalQuestKind.FARM))
            hud.refresh(player, view(OptionalQuestKind.FARM, OptionalQuestKind.FARM.steps).copy(completed = true))
            verify(exactly = 1) { player.hideBossBar(shownBars.last()) }
        }
    }
})
