package ru.arc.landsui

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LandsInvitesMenusTest : StringSpec({
    "inbox paginates TRUST OWNER and MERGE invitations and only opens their detail cards" {
        InvitationMenuFixture().use { f ->
            val exports = linkedMapOf<String, Any>()
            f.menus.open(f.player)
            f.screen.buttons.map { it.id.value } shouldBe listOf("invite_0", "invite_1", "refresh", "next")
            exports["inbox-first-page"] = f.export()
            f.click("next")
            f.screen.buttons.map { it.id.value } shouldBe listOf("invite_0", "refresh", "previous")
            exports["inbox-last-page"] = f.export()
            f.click("previous")
            f.screen.id shouldBe "lands.invites"
            listOf(f.trust to "accept", f.owner to "review_owner", f.merge to "review_merge").forEach { (invite, action) ->
                f.openInvitation(invite)
                f.screen.id shouldBe "lands.invites.detail"
                f.body().contains(invite.landName) shouldBe true
                f.screen.buttons.map { it.id.value } shouldBe listOf(action, "decline")
                exports["detail-${invite.intent.name.lowercase()}"] = f.export()
                if (invite.intent != LandsUiInviteIntent.TRUST) {
                    f.click(action)
                    exports["confirm-${invite.intent.name.lowercase()}"] = f.export()
                }
            }
            f.answers shouldBe emptyList()
            f.invitations = emptyList()
            f.menus.open(f.player)
            f.body().contains("нет действующих приглашений") shouldBe true
            exports["inbox-empty"] = f.export()
            val output = Path.of("build/reports/lands-invites-ui/screens.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(exports))
        }
    }

    "OWNER requires confirmation and MERGE requires the matching typed land name" {
        InvitationMenuFixture().use { f ->
            f.openInvitation(f.owner)
            f.click("review_owner")
            f.screen.id shouldBe "lands.invites.confirm"
            f.body().contains(f.owner.landName) shouldBe true
            f.screen.inputs shouldBe emptyList()
            f.answers shouldBe emptyList()
            val ownerConfirm = f.screen.buttons.single()
            f.click("confirm")
            ownerConfirm.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
            f.answers shouldBe listOf(f.owner to true)
            f.response.complete(LandsUiChangeResult.STALE)
            f.flush()

            f.response = CompletableFuture()
            f.openInvitation(f.merge)
            f.click("review_merge")
            f.screen.inputs.single().id.value shouldBe "land_name"
            f.body().contains("необратимое") shouldBe true
            f.body().contains(f.merge.landName) shouldBe true
            f.click("confirm", "Не то поселение")
            f.screen.id shouldBe "lands.invites.confirm"
            f.screen.inputs.single().initial shouldBe "Не то поселение"
            f.body().contains("Название не совпало") shouldBe true
            f.answers shouldBe listOf(f.owner to true)
            f.click("confirm", f.merge.landName)
            f.answers shouldBe listOf(f.owner to true, f.merge to true)
        }
    }

    "a stale invitation card never responds and a stale gateway result never announces success" {
        InvitationMenuFixture().use { f ->
            f.menus.open(f.player)
            f.invitations = emptyList()
            f.click("invite_0")
            f.screen.id shouldBe "lands.invites"
            f.body().contains("больше недоступно") shouldBe true
            f.answers shouldBe emptyList()

            f.invitations = listOf(f.trust)
            f.openInvitation(f.trust)
            f.click("accept")
            f.response.complete(LandsUiChangeResult.STALE)
            f.flush()
            f.screen.id shouldBe "lands.invites"
            f.body().contains("больше недоступно") shouldBe true
            f.closes shouldBe 0
            f.messages shouldBe emptyList()
            verify(exactly = 0) { f.player.performCommand(any()) }
        }
    }

    "DISPATCHED closes with neutral feedback for both acceptance and decline" {
        listOf(true, false).forEach { accept ->
            InvitationMenuFixture().use { f ->
                f.openInvitation(f.trust)
                f.click(if (accept) "accept" else "decline")
                f.answers shouldBe listOf(f.trust to accept)
                f.response.complete(LandsUiChangeResult.DISPATCHED)
                f.flush()
                f.closes shouldBe 1
                f.messages.single() shouldBe "Ответ на приглашение отправлен. Результат — в сообщении сервера."
                verify(exactly = 0) { f.gateway.select(any(), any()) }
                verify(exactly = 0) { f.gateway.change(any(), any(), any()) }
            }
        }
    }

    "late completion cannot close or replace a changed or dismissed view" {
        listOf(false, true).forEach { dismiss ->
            InvitationMenuFixture().use { f ->
                f.openInvitation(f.trust)
                f.click("accept")
                if (dismiss) f.dismiss() else f.menus.open(f.player)
                val screen = f.screen
                val displays = f.displays
                f.response.complete(LandsUiChangeResult.DISPATCHED)
                f.flush()
                f.displays shouldBe displays
                (f.screen === screen) shouldBe true
                f.closes shouldBe 0
                f.messages shouldBe emptyList()
            }
        }
    }
})

private class InvitationMenuFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val directory = Files.createTempDirectory("lands-invites-menu")
    val player = mockk<Player>(relaxed = true)
    val gateway = mockk<LandsUiGateway>(relaxed = true)
    private val sender = UUID.randomUUID()
    val trust = LandsUiInvitation("trust-land", "Лесная гавань", sender, 101, LandsUiInviteIntent.TRUST)
    val owner = LandsUiInvitation("owner-land", "Северная долина", sender, 102, LandsUiInviteIntent.OWNER)
    val merge = LandsUiInvitation("merge-land", "Берёзовый берег", sender, 103, LandsUiInviteIntent.MERGE)
    var invitations = listOf(trust, owner, merge)
    var response = CompletableFuture<LandsUiChangeResult>()
    val answers = mutableListOf<Pair<LandsUiInvitation, Boolean>>()
    val messages = mutableListOf<String>()
    lateinit var screen: PaperDialogScreen
    var displays = 0
    var closes = 0
    private var onDismiss: () -> Unit = {}
    val menus: LandsInvitesMenus

    init {
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.isOnline } returns true
        every { player.hasPermission(any<String>()) } returns true
        every { player.sendMessage(any<Component>()) } answers { messages += plain(firstArg()) }
        every { gateway.invitations(player) } answers { invitations }
        every { gateway.playerName(sender) } returns "BuilderAlex"
        every { gateway.respondToInvitation(player, any(), any()) } answers {
            answers += secondArg<LandsUiInvitation>() to thirdArg<Boolean>()
            response
        }
        Tasks.install(BukkitTaskScheduler(paper.createSimplePlugin("LandsInvitesTest")))
        ConfigManager.clear()
        val settings = LandsUiConfig.load(directory).snapshot().copy(maxListedPlayers = 2)
        mockkObject(ArcMenus)
        every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers {
            onDismiss()
            screen = secondArg()
            onDismiss = arg(4)
            displays++
            Unit
        }
        every { ArcMenus.closeDialog(player) } answers { closes++; onDismiss() }
        menus = LandsInvitesMenus(settings, gateway) {}
    }

    fun openInvitation(invitation: LandsUiInvitation) {
        menus.open(player)
        val index = invitations.indexOf(invitation)
        check(index >= 0)
        repeat(index / 2) { click("next") }
        click("invite_${index % 2}")
    }

    fun click(id: String, input: String = "") {
        val context = mockk<PaperDialogClickContext>(relaxed = true)
        every { context.player } returns player
        every { context.text(any()) } returns input
        screen.buttons.single { it.id.value == id }.onClick.handle(context)
    }

    fun flush() = paper.performTicks(2)
    fun dismiss() = onDismiss()
    fun body() = screen.body.joinToString("\n") { plain(it.text) }
    fun export(): Map<String, Any> {
        val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
        return exportLandsDialogScreen(screen) + ("inputs" to screen.inputs.map {
            mapOf("id" to it.id.value, "text" to legacy.serialize(it.label),
                "font-source" to MiniMessage.miniMessage().serialize(it.label), "initial" to it.initial, "width" to it.width)
        })
    }

    override fun close() {
        menus.close()
        unmockkObject(ArcMenus)
        Tasks.reset()
        ConfigManager.clear()
        directory.toFile().deleteRecursively()
        paper.close()
    }
}

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
