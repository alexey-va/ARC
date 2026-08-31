package ru.arc.commands.arc.subcommands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.commandhide.CommandHideAdminController
import ru.arc.commandhide.CommandHideAdminResult
import ru.arc.commandhide.CommandHideBypassState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.util.TextUtils

class CommandHideSubCommandTest :
    FreeSpec({
        "admin command" - {
            "allow targets one exact online UUID and reports full access" {
                failOnUnsupportedMockBukkitOperation {
                    MockBukkitTestRuntime.open().use { paper ->
                        val sender = paper.addPlayer("Admin")
                        val target = paper.addPlayer("Target")
                        val controller = RecordingCommandHideController(state(managed = true, effective = true))
                        val command = command(controller, target)

                        command.execute(sender, arrayOf("allow", "target")) shouldBe true

                        controller.mutations shouldContainExactly listOf(target to true)
                        TextUtils.plain(requireNotNull(sender.nextComponentMessage())) shouldBe "allowed:Target"
                    }
                }
            }

            "restrict warns when another permission source still grants bypass" {
                failOnUnsupportedMockBukkitOperation {
                    MockBukkitTestRuntime.open().use { paper ->
                        val sender = paper.addPlayer("Admin")
                        val target = paper.addPlayer("Target")
                        val controller = RecordingCommandHideController(state(managed = false, effective = true))

                        command(controller, target).execute(sender, arrayOf("restrict", "Target"))

                        controller.mutations shouldContainExactly listOf(target to false)
                        TextUtils.plain(requireNotNull(sender.nextComponentMessage())) shouldBe "still-allowed:Target"
                    }
                }
            }

            "status distinguishes a managed grant from active restrictions" {
                failOnUnsupportedMockBukkitOperation {
                    MockBukkitTestRuntime.open().use { paper ->
                        val sender = paper.addPlayer("Admin")
                        val target = paper.addPlayer("Target")
                        val controller = RecordingCommandHideController(state(managed = true, effective = true))
                        val command = command(controller, target)

                        command.execute(sender, arrayOf("status", "Target"))
                        TextUtils.plain(requireNotNull(sender.nextComponentMessage())) shouldBe "status-managed:Target"

                        controller.next = state(managed = false, effective = false)
                        command.execute(sender, arrayOf("status", "Target"))
                        TextUtils.plain(requireNotNull(sender.nextComponentMessage())) shouldBe "status-restricted:Target"

                        controller.next = state(unmanaged = true, effective = true)
                        command.execute(sender, arrayOf("status", "Target"))
                        TextUtils.plain(requireNotNull(sender.nextComponentMessage())) shouldBe "status-external:Target"
                    }
                }
            }

            "reports protected LuckPerms conflicts without claiming success" {
                failOnUnsupportedMockBukkitOperation {
                    MockBukkitTestRuntime.open().use { paper ->
                        val sender = paper.addPlayer("Admin")
                        val target = paper.addPlayer("Target")
                        val controller = RecordingCommandHideController(CommandHideAdminResult.ConflictingDeny)

                        command(controller, target).execute(sender, arrayOf("allow", "Target"))

                        TextUtils.plain(requireNotNull(sender.nextComponentMessage())) shouldBe "deny:Target"
                    }
                }
            }

            "rejects malformed or non-local targets before mutation" {
                failOnUnsupportedMockBukkitOperation {
                    MockBukkitTestRuntime.open().use { paper ->
                        val sender = paper.addPlayer("Admin")
                        val target = paper.addPlayer("Target")
                        val controller = RecordingCommandHideController(state(managed = true, effective = true))
                        val command = command(controller, target)

                        command.execute(sender, arrayOf("allow", "../Target"))
                        TextUtils.plain(requireNotNull(sender.nextComponentMessage())) shouldBe "usage"
                        command.execute(sender, arrayOf("allow", "Missing"))
                        TextUtils.plain(requireNotNull(sender.nextComponentMessage())) shouldBe "offline:Missing"
                        controller.mutations shouldContainExactly emptyList()
                    }
                }
            }

            "tab completion exposes only fixed actions and online names" {
                failOnUnsupportedMockBukkitOperation {
                    MockBukkitTestRuntime.open().use { paper ->
                        val sender = paper.addPlayer("Admin")
                        val target = paper.addPlayer("Target")
                        val command = command(RecordingCommandHideController(state()), target)

                        command.tabComplete(sender, arrayOf("r")) shouldContainExactly listOf("restrict")
                        command.tabComplete(sender, arrayOf("allow", "ta")) shouldContainExactly listOf("Target")
                        command.tabComplete(sender, arrayOf("allow", "Target", "extra")) shouldContainExactly emptyList()
                    }
                }
            }
        }
    })

private fun command(
    controller: CommandHideAdminController,
    target: Player,
): CommandHideSubCommand =
    CommandHideSubCommand(
        controller = controller,
        messages = TestCommandHideMessages,
        findOnlinePlayer = { input -> target.takeIf { it.name.equals(input, ignoreCase = true) } },
        onlinePlayerNames = { listOf(target.name) },
    )

private fun state(
    managed: Boolean = false,
    unmanaged: Boolean = false,
    effective: Boolean = false,
): CommandHideAdminResult.State =
    CommandHideAdminResult.State(
        CommandHideBypassState(
            permission = "arc.command.hide.bypass",
            managedGrant = managed,
            unmanagedDirectGrant = unmanaged,
            effectiveBypass = effective,
        ),
    )

private class RecordingCommandHideController(
    var next: CommandHideAdminResult,
) : CommandHideAdminController {
    val mutations = mutableListOf<Pair<Player, Boolean>>()

    override fun status(player: Player): CommandHideAdminResult = next

    override fun setBypass(
        player: Player,
        enabled: Boolean,
        callback: (CommandHideAdminResult) -> Unit,
    ) {
        mutations += player to enabled
        callback(next)
    }
}

private object TestCommandHideMessages : CommandHideCommandMessages {
    override fun usage() = Component.text("usage")
    override fun playerOffline(player: String) = Component.text("offline:$player")
    override fun allowed(player: String) = Component.text("allowed:$player")
    override fun restricted(player: String) = Component.text("restricted:$player")
    override fun stillAllowed(player: String) = Component.text("still-allowed:$player")
    override fun savedButNotEffective(player: String) = Component.text("not-effective:$player")
    override fun statusManaged(player: String) = Component.text("status-managed:$player")
    override fun statusInherited(player: String) = Component.text("status-inherited:$player")
    override fun statusExternal(player: String) = Component.text("status-external:$player")
    override fun statusRestricted(player: String) = Component.text("status-restricted:$player")
    override fun statusFailed(player: String) = Component.text("status-failed:$player")
    override fun busy(player: String) = Component.text("busy:$player")
    override fun failed(player: String) = Component.text("failed:$player")
    override fun commandTreeRefreshFailed(player: String) = Component.text("tree-failed:$player")
    override fun conflictingDeny(player: String) = Component.text("deny:$player")
    override fun unmanagedGrant(player: String) = Component.text("unmanaged:$player")
    override fun savedWhileOffline(player: String) = Component.text("saved-offline:$player")
    override fun moduleDisabled() = Component.text("module-disabled")
    override fun bypassDisabled() = Component.text("bypass-disabled")
    override fun providerUnavailable() = Component.text("provider-unavailable")
}
