package ru.arc.onboarding

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.ScenePart
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class OnboardingQuestDialogTest : FreeSpec({
    val player = mockk<Player>(relaxed = true)
    val context = mockk<PaperDialogClickContext>()
    every { context.player } returns player
    val runId = UUID.randomUUID().toString()
    every { player.uniqueId } returns UUID.randomUUID()
    var current: OnboardingQuestView? = null
    var shown: PaperDialogScreen? = null
    var dismiss: (() -> Unit)? = null
    val actions = mutableListOf<String>()
    val dialog = OnboardingQuestDialog(
        view = { current },
        accept = { _, run -> actions += "accept:$run" },
        restart = { actions += "restart" },
        stop = { _, run -> actions += "stop:$run" },
        openGoal = { _, goal -> actions += "goal:$goal" },
        openTeleporter = { actions += "teleport" },
        showDialog = { _, screen, _, onDismiss -> shown = screen; dismiss = onDismiss },
    )
    fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    fun click(screen: PaperDialogScreen, id: String) = screen.buttons.single { it.id.value == id }.onClick.handle(context)
    val base = setOf(OnboardingMilestone.FIRST_RTP, OnboardingMilestone.HOME_CREATED,
        OnboardingMilestone.LAND_CLAIMED, OnboardingMilestone.FOOTHOLD_COMPLETE)

    beforeEach {
        dialog.close()
        current = OnboardingQuestView(runId, false, emptySet())
        shown = null
        dismiss = null
        actions.clear()
    }

    "offer uses the native table and exact run while hiding test controls from ordinary viewers" {
        val screen = dialog.screen(current)
        screen.id shouldBe "onboarding.quest"
        screen.buttons.map { it.id.value } shouldContainExactly listOf("accept", "teleport")
        screen.buttons.all { !it.closeDialogBeforeAction } shouldBe true
        val body = screen.body.joinToString("\n") { plain(it.text) }
        listOf("Мир выживания", "Точка дома", "Защита участка", "Первый дом").forEach { body shouldContain it }
        click(screen, "accept")
        actions shouldContainExactly listOf("accept:$runId")
    }

    "a stale run or withdrawn access refreshes the journal without dispatching its old action" {
        val screen = dialog.screen(current)
        current = current!!.copy(runId = UUID.randomUUID().toString())
        click(screen, "accept")
        actions shouldBe emptyList()
        shown!!.id shouldBe "onboarding.quest"
        current = null
        click(screen, "teleport")
        actions shouldBe emptyList()
        shown!!.buttons shouldBe emptyList()
    }

    "separate home and land do not look complete and send the player to fix the home" {
        current = OnboardingQuestView(runId, true, base - OnboardingMilestone.FOOTHOLD_COMPLETE)
        current!!.goal shouldBe OnboardingQuestGoal.HOME
        val screen = dialog.screen(current)
        screen.body.joinToString("\n") { plain(it.text) } shouldContain "Вне участка"
        click(screen, "goal")
        actions shouldContainExactly listOf("goal:HOME")
    }

    "construction in progress and completion hide the unnecessary kit action" {
        val building = OnboardingQuestView(runId, true, base + OnboardingMilestone.AUTOBUILD_STARTED)
        val complete = building.copy(milestones = building.milestones + OnboardingMilestone.AUTOBUILD_COMPLETE)
        for (view in listOf(building, complete)) {
            dialog.screen(view).buttons.map { it.id.value } shouldContainExactly listOf("teleport")
        }
        plain(dialog.screen(building).body.first().text) shouldContain "Дом строится"
        plain(dialog.screen(complete).body.first().text) shouldContain "Первый дом готов"
    }

    "test controls recheck administrator access before invoking state changes" {
        current = current!!.copy(canRestart = true)
        val screen = dialog.screen(current)
        current = current!!.copy(canRestart = false)
        click(screen, "restart")
        click(screen, "stop")
        actions shouldBe emptyList()
        current = current!!.copy(canRestart = true)
        click(screen, "stop")
        actions shouldContainExactly listOf("stop:$runId")
    }

    "pending work ignores dismissed or replaced visits and leaves a failed operation retryable" {
        val first = dialog.beginPending(player)
        shown!!.id shouldBe "onboarding.quest"
        shown!!.buttons shouldBe emptyList()
        dismiss!!.invoke()
        dialog.isCurrent(player, first) shouldBe false
        val dismissedScreen = shown
        dialog.finish(player, first)
        shown shouldBe dismissedScreen

        val second = dialog.beginPending(player)
        dialog.finish(player, first)
        dialog.isCurrent(player, second) shouldBe true
        dialog.finish(player, second, "Не удалось сохранить квест. Попробуйте ещё раз.")
        plain(shown!!.body.first().text) shouldContain "Не удалось сохранить"
        shown!!.buttons.map { it.id.value } shouldContainExactly listOf("accept", "teleport")
        dialog.isCurrent(player, second) shouldBe false
    }

    "NPC command is intercepted only for the configured NPC and eligible viewer" {
        val npc = mockk<NPC>()
        var eligible = false
        var id = 447
        every { npc.id } answers { id }
        val displays = mockk<PaperPacketDisplays>()
        every { displays.createAudienceGroup(any()) } returns mockk(relaxed = true)
        val controller = OnboardingQuestNpc(mockk<Plugin>(), 447, { true }, { eligible },
            { actions += "quest" }, displays = displays)
        val ordinary = NPCRightClickEvent(npc, player)
        controller.onNpcClick(ordinary)
        ordinary.isCancelled shouldBe false
        actions shouldBe emptyList()
        eligible = true
        val enrolled = NPCRightClickEvent(npc, player)
        controller.onNpcClick(enrolled)
        enrolled.isCancelled shouldBe true
        actions shouldContainExactly listOf("quest")
        id = 448
        val other = NPCRightClickEvent(npc, player)
        controller.onNpcClick(other)
        other.isCancelled shouldBe false
        actions.size shouldBe 1
    }

    "quest marker is one private gold cue above an unchanged NPC anchor" {
        val viewer = UUID.randomUUID()
        val at = Location(mockk<World>(), 10.324, 70.0, -3.4444)
        val frame = onboardingQuestMarkerFrame(viewer, at)
        (frame.audience as SceneAudience.Private).viewerId shouldBe viewer
        frame.parts.size shouldBe 1
        val sign = frame.parts.single() as ScenePart.Text
        plain(sign.text) shouldBe "!"
        sign.pose.location().y shouldBe 73.2
        sign.pose.transformation().scale.x shouldBe 2.5f
        sign.textStyle.seeThrough shouldBe false
        sign.textStyle.background!!.alpha shouldBe 0
        at.y shouldBe 70.0
    }

    "exports representative actual renderer output for the native preview" {
        val views = linkedMapOf(
            "offer" to OnboardingQuestView(runId, false, emptySet(), canRestart = true),
            "travel" to OnboardingQuestView(runId, true, emptySet(), canRestart = true),
            "claim" to OnboardingQuestView(runId, true, setOf(OnboardingMilestone.FIRST_RTP, OnboardingMilestone.HOME_CREATED)),
            "mismatch" to OnboardingQuestView(runId, true, base - OnboardingMilestone.FOOTHOLD_COMPLETE),
            "building" to OnboardingQuestView(runId, true, base + OnboardingMilestone.AUTOBUILD_STARTED),
            "complete" to OnboardingQuestView(runId, true, base + OnboardingMilestone.AUTOBUILD_STARTED + OnboardingMilestone.AUTOBUILD_COMPLETE),
        )
        val serializer = LegacyComponentSerializer.builder().character('&').hexColors().build()
        fun serialize(component: Component) = serializer.serialize(component)
        val export = views.mapValues { (_, view) ->
            val screen = dialog.screen(view)
            mapOf(
                "title" to serialize(screen.title),
                "columns" to screen.columns,
                "body" to screen.body.map { mapOf("text" to serialize(it.text), "width" to it.width) },
                "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to serialize(it.label),
                    "tooltip" to serialize(it.tooltip), "width" to it.width) },
                "exit" to mapOf("text" to serialize(screen.exitButton!!.label), "width" to screen.exitButton!!.width),
            )
        }
        val output = Path.of("build/reports/onboarding-quest/content.json")
        Files.createDirectories(output.parent)
        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        Files.writeString(output, gson.toJson(export))
        val npc = Location(mockk<World>(), 10.324, 70.0, -3.4444)
        val sign = onboardingQuestMarkerFrame(UUID.randomUUID(), npc).parts.single() as ScenePart.Text
        Files.writeString(output.resolveSibling("marker.json"), gson.toJson(mapOf(
            "text" to serialize(sign.text), "scale" to sign.pose.transformation().scale.x,
            "height" to sign.pose.location().y - npc.y, "shadow" to sign.textStyle.shadowed,
            "billboard" to sign.style.billboard.name, "range" to sign.style.viewRange * 64,
            "npc" to listOf(npc.x, npc.y, npc.z),
        )))
    }
})
