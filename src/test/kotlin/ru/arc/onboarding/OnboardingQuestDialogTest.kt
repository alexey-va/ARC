package ru.arc.onboarding

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCDespawnEvent
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.LivingEntity
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.hooks.HookRegistry
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.hooks.packetevents.PacketEventsHook
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.ScenePart
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

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
    var scheduler = TestTaskScheduler()
    var tracked = false
    var trackingSave = CompletableFuture.completedFuture<Void>(null)
    fun newDialog() = OnboardingQuestDialog(
        view = { current },
        accept = { _, run -> actions += "accept:$run" },
        openGoal = { _, goal -> actions += "goal:$goal" },
        showDialog = { _, screen, _, onDismiss -> shown = screen; dismiss = onDismiss },
        isTracked = { _, _ -> tracked },
        track = { _, id -> trackingSave.thenApply { tracked = id != null; null } },
        tasks = LifecycleTaskScope(scheduler),
    )
    var dialog = newDialog()
    fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
    fun click(screen: PaperDialogScreen, id: String) = screen.buttons.single { it.id.value == id }.onClick.handle(context)
    val base = setOf(OnboardingMilestone.FIRST_RTP, OnboardingMilestone.HOME_CREATED,
        OnboardingMilestone.LAND_CLAIMED, OnboardingMilestone.FOOTHOLD_COMPLETE)

    beforeEach {
        dialog.close()
        scheduler = TestTaskScheduler()
        tracked = false
        trackingSave = CompletableFuture.completedFuture(null)
        dialog = newDialog()
        current = OnboardingQuestView(runId, false, emptySet())
        shown = null
        dismiss = null
        actions.clear()
    }

    "offer shows the full four-step route and one accept action for the exact run" {
        val screen = dialog.screen(current)
        screen.id shouldBe "onboarding.quest"
        screen.buttons.map { it.id.value } shouldContainExactly listOf("accept")
        screen.buttons.all { !it.closeDialogBeforeAction } shouldBe true
        screen.body.size shouldBe 2
        plain(screen.body.first().text) shouldContain "первый дом"
        plain(screen.body.first().text).codePoints().anyMatch { it in 0xFA300..0xFAF1B } shouldBe true
        plain(screen.body.last().text) shouldContain "Защита участка"
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
        click(screen, "accept")
        actions shouldBe emptyList()
        shown!!.buttons shouldBe emptyList()
    }

    "separate home and land do not look complete and send the player to fix the home" {
        current = OnboardingQuestView(runId, true, base - OnboardingMilestone.FOOTHOLD_COMPLETE)
        current!!.goal shouldBe OnboardingQuestGoal.HOME
        val screen = dialog.screen(current)
        screen.body.joinToString("\n") { plain(it.text) } shouldContain "внутри своего привата"
        click(screen, "goal")
        actions shouldContainExactly listOf("goal:HOME")
    }

    "construction in progress and completion hide the unnecessary kit action" {
        val building = OnboardingQuestView(runId, true, base + OnboardingMilestone.AUTOBUILD_STARTED)
        val complete = building.copy(milestones = building.milestones + OnboardingMilestone.AUTOBUILD_COMPLETE)
        dialog.screen(building).buttons.map { it.id.value } shouldContainExactly listOf("track")
        dialog.screen(complete).buttons shouldBe emptyList()
        plain(dialog.screen(building).body.first().text) shouldContain "Дом строится"
        plain(dialog.screen(complete).body.first().text) shouldContain "Дом готов"
        current = OnboardingQuestView(runId, true, base)
        val beforeConstruction = dialog.screen(current)
        current = building
        click(beforeConstruction, "goal")
        actions shouldBe emptyList()
    }

    "only the current goal is actionable and an older goal cannot dispatch after progress changes" {
        val stages = listOf(
            emptySet<OnboardingMilestone>() to OnboardingQuestGoal.TRAVEL,
            setOf(OnboardingMilestone.FIRST_RTP) to OnboardingQuestGoal.HOME,
            setOf(OnboardingMilestone.FIRST_RTP, OnboardingMilestone.HOME_CREATED) to OnboardingQuestGoal.CLAIM,
            base to OnboardingQuestGoal.BUILD,
        )
        for ((milestones, goal) in stages) {
            current = OnboardingQuestView(runId, true, milestones)
            val screen = dialog.screen(current)
            screen.body.size shouldBe 2
            screen.buttons.map { it.id.value } shouldContainExactly listOf("goal", "track")
            click(screen, "goal")
            actions.last() shouldBe "goal:$goal"
        }
        actions.clear()
        val oldBuild = dialog.screen(current)
        current = OnboardingQuestView(runId, true, emptySet())
        click(oldBuild, "goal")
        actions shouldBe emptyList()
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
        shown!!.buttons.map { it.id.value } shouldContainExactly listOf("accept")
        dialog.isCurrent(player, second) shouldBe false
    }

    "tracking save failure retains the selected quest and dismissed saves cannot reopen the dialog" {
        current = OnboardingQuestView(runId, true, emptySet())
        tracked = true
        dialog.open(player)
        plain(shown!!.buttons.single { it.id.value == "track" }.label) shouldContain "✔"
        trackingSave = CompletableFuture()
        click(shown!!, "track")
        trackingSave.completeExceptionally(IllegalStateException("fixture tracking save failure"))
        scheduler.executeImmediate()
        tracked shouldBe true
        plain(shown!!.body.first().text) shouldContain "Не удалось сохранить отслеживание"
        trackingSave = CompletableFuture()
        click(shown!!, "track")
        dismiss!!.invoke()
        val dismissed = shown
        trackingSave.complete(null)
        scheduler.executeImmediate()
        tracked shouldBe false
        shown shouldBe dismissed
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

    "quest glow follows the offer audience and restores the exact old NPC and hook on lifecycle exits" {
        val originalPackets = HookRegistry.packetEventsHook
        mockkStatic(CitizensAPI::class)
        val plugin = mockk<Plugin>()
        val server = mockk<Server>()
        val world = mockk<World>()
        val otherWorld = mockk<World>()
        val first = mockk<LivingEntity>()
        val second = mockk<LivingEntity>()
        val oldPackets = mockk<PacketEventsHook>(relaxed = true)
        val newPackets = mockk<PacketEventsHook>(relaxed = true)
        val npc = mockk<NPC>()
        val registry = mockk<NPCRegistry>()
        val displays = mockk<PaperPacketDisplays>(relaxed = true)
        val marker = mockk<PacketTextDisplay>(relaxed = true)
        var entity = first
        var offered = true
        var allowed = false
        var viewerWorld = world
        every { plugin.server } returns server
        every { server.getPlayer(player.uniqueId) } returns player
        every { player.isOnline } returns true
        every { player.world } answers { viewerWorld }
        every { first.location } returns Location(world, 10.324, 70.0, -3.4444)
        every { second.location } returns Location(world, 10.324, 70.0, -3.4444)
        every { CitizensAPI.getNPCRegistry() } returns registry
        every { registry.getById(447) } returns npc
        every { npc.id } returns 447
        every { npc.isSpawned } returns true
        every { npc.entity } answers { entity }
        every { displays.spawnText(any(), any()) } returns marker
        every { marker.isValid } returns true
        val controller = OnboardingQuestNpc(plugin, 447, { offered }, { allowed }, {}, displays = displays)
        try {
            HookRegistry.packetEventsHook = oldPackets
            controller.refresh(player)
            verify(exactly = 0) { oldPackets.setEntityGlowingFor(any(), any(), any()) }
            allowed = true
            controller.refresh(player)
            entity = second
            controller.refresh(player)
            HookRegistry.packetEventsHook = newPackets
            controller.refresh(player)
            offered = false
            controller.refresh(player)
            verifyOrder {
                oldPackets.setEntityGlowingFor(first, player, true)
                oldPackets.restoreEntityGlowingFor(first, player)
                oldPackets.setEntityGlowingFor(second, player, true)
                oldPackets.restoreEntityGlowingFor(second, player)
                newPackets.setEntityGlowingFor(second, player, true)
                newPackets.restoreEntityGlowingFor(second, player)
            }

            offered = true
            controller.refresh(player)
            viewerWorld = otherWorld
            controller.onWorldChange(PlayerChangedWorldEvent(player, world))
            viewerWorld = world
            controller.refresh(player)
            val despawn = mockk<NPCDespawnEvent>()
            every { despawn.npc } returns npc
            controller.onNpcDespawn(despawn)
            controller.refresh(player)
            val quit = mockk<PlayerQuitEvent>()
            every { quit.player } returns player
            controller.onQuit(quit)
            controller.refresh(player)
            controller.close()
            verify(exactly = 5) { newPackets.restoreEntityGlowingFor(second, player) }
            verify(exactly = 0) { first.isGlowing = any() }
            verify(exactly = 0) { second.isGlowing = any() }
        } finally {
            controller.close()
            HookRegistry.packetEventsHook = originalPackets
            unmockkStatic(CitizensAPI::class)
        }
    }

    "quest marker is one larger private gold cue close above the unchanged NPC anchor" {
        val viewer = UUID.randomUUID()
        val at = Location(mockk<World>(), 10.324, 70.0, -3.4444)
        val frame = onboardingQuestMarkerFrame(viewer, at)
        (frame.audience as SceneAudience.Private).viewerId shouldBe viewer
        frame.parts.size shouldBe 1
        val sign = frame.parts.single() as ScenePart.Text
        plain(sign.text) shouldBe "!"
        sign.pose.location().y shouldBe 72.65
        sign.pose.transformation().scale.x shouldBe 4f
        sign.textStyle.seeThrough shouldBe false
        sign.textStyle.background!!.alpha shouldBe 0
        at.y shouldBe 70.0
    }

    "exports representative actual renderer output for the native preview" {
        val views = linkedMapOf(
            "offer" to OnboardingQuestView(runId, false, emptySet()),
            "travel" to OnboardingQuestView(runId, true, emptySet()),
            "home" to OnboardingQuestView(runId, true, setOf(OnboardingMilestone.FIRST_RTP)),
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
        Files.writeString(output.resolveSibling("components.json"), gson.toJson(views.mapValues { (_, view) ->
            val screen = dialog.screen(view)
            mapOf("id" to screen.id, "body" to screen.body.map {
                mapOf("text" to GsonComponentSerializer.gson().serializeToTree(it.text), "width" to it.width)
            }, "actions" to screen.buttons.map { it.id.value })
        }))
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
