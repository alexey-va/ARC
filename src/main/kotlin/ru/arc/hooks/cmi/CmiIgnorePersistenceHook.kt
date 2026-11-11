package ru.arc.hooks.cmi

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Containers.CMIUser
import com.Zrips.CMI.Containers.Snd
import com.Zrips.CMI.Locale.CMILC
import com.Zrips.CMI.commands.CAnnotation
import com.Zrips.CMI.commands.CMICommand
import com.Zrips.CMI.commands.Cmd
import com.Zrips.CMI.commands.CommandsHandler
import com.Zrips.CMI.commands.list.ignore as NativeIgnore
import com.Zrips.CMI.events.CMIConfigReloadEvent
import net.Zrips.CMILib.Colors.CMIChatColor
import net.Zrips.CMILib.Container.PageInfo
import net.Zrips.CMILib.FileHandler.ConfigReader
import net.Zrips.CMILib.Locale.LC
import net.Zrips.CMILib.RawMessages.RawMessage
import net.Zrips.CMILib.RawMessages.RawMessageCommand
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/** CMI 9.8.11.1 forgets to queue saves in its ignore-list delete callback. */
class CmiIgnorePersistenceHook(private val cmi: CMI) : Listener, AutoCloseable {
    private var binding: Pair<CMICommand, ignore>? = null
    private var closed = false

    fun install() {
        check(!closed) { "CMI ignore persistence hook is closed" }
        val command = checkNotNull(cmi.commandManager.commands["ignore"]) { "CMI ignore command is missing" }
        if (binding?.let { it.first === command && command.cmdClass === it.second } == true) return
        val original = command.cmdClass
        check(original.javaClass == NativeIgnore::class.java) { "CMI ignore command already has a custom handler" }
        unbind()
        val replacement = ignore(original)
        command.setCmdClass(replacement)
        binding = command to replacement
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onCmiReload(event: CMIConfigReloadEvent) {
        if (!closed) install()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        binding?.second?.clearCallbacks(event.player)
    }

    override fun close() {
        if (closed) return
        closed = true
        unbind()
    }

    private fun unbind() {
        binding?.let { (command, replacement) ->
            replacement.close()
            if (command.cmdClass === replacement) command.setCmdClass(replacement.original)
        }
        binding = null
    }
}

// CMI derives localization/usage keys from this simple name and reads perform's annotation.
@Suppress("ClassName")
internal class ignore(val original: Cmd) : Cmd, AutoCloseable {
    private val callbacks = mutableMapOf<CommandSender, MutableList<RawMessageCommand>>()
    private var closed = false

    override fun getExtra(config: ConfigReader) = original.getExtra(config)

    @CAnnotation(
        priority = 48,
        info = "Ignores player",
        args = "(playerName/uuid/all) (-p:[playerName])",
        multiTab = ["[playername],all"],
        explanation = ["cmi.command.ignore.bypass - to bypass ignore list"],
        regVar = [0, 1, 2],
        consoleVar = [1, 2],
        customAlias = ["!ignorelist"],
        others = true,
    )
    override fun perform(plugin: CMI, sender: CommandSender, args: Array<String>): Boolean? {
        if (closed) return null
        var name: String? = null
        var page: Int? = null
        var targetName: String? = null
        // Match native parsing, including numeric player names and argument order.
        for (arg in args) {
            if (name == null && plugin.playerManager.getUser(arg) != null) {
                name = arg
                continue
            }
            val number = arg.toIntOrNull()
            when {
                number != null -> page = number
                arg.startsWith("-p:") -> targetName = arg.substring(3)
                else -> name = arg
            }
        }
        if (name != null && page == null) return original.perform(plugin, sender, args)
        val user = plugin.getUser(sender, targetName, original) ?: return null
        clearCallbacks(sender)
        if (user.ignores.isEmpty()) {
            CMILC.info(original, sender, "empty")
            return null
        }

        val pages = PageInfo(15, user.ignores.size, page ?: 1)
        val targetArg = targetName?.let { "-p:${user.uniqueId}" }
        for (ignored in user.ignores) {
            if (!pages.isEntryOk) continue
            val context = Snd().setSender(sender)
            if (ignored == plugin.playerManager.emptyUserUUID) {
                context.setTarget(Bukkit.getConsoleSender())
            } else {
                context.setTarget(plugin.playerManager.getUser(ignored))
            }
            val text = CMILC.getIM(original, "listNames", context)
            val delete = callback(sender, keep = true) {
                val confirm = callback(sender) {
                    removeAndQueueIgnore(user, ignored)
                    clearCallbacks(sender)
                    plugin.commandManager.performCMICommand(
                        sender,
                        NativeIgnore::class.java,
                        *listOfNotNull(pages.currentPage.toString(), targetArg).toTypedArray(),
                    )
                }
                RawMessage()
                    .addText(LC.info_ClickToConfirmDelete.getLocale("[name]", text))
                    .addHover(LC.info_Click.getLocale())
                    .addCommand(confirm)
                    .show(sender)
            }
            printRow(sender, pages, text, context.targetName, delete)
        }
        pages.autoPagination(sender, listOfNotNull(CommandsHandler.getLabel(), "ignore", targetArg).joinToString(" "))
        return true
    }

    /** Same composition as ChatMessageObjectEdit; own handles allow scoped hotload cleanup. */
    private fun printRow(
        sender: CommandSender,
        pages: PageInfo,
        text: String,
        suggestion: String?,
        delete: RawMessageCommand,
    ) {
        val position = pages.positionForOutput
        var number = position.toString()
        if ((pages.start < 99 && pages.end > 98 && position < 100) || position < 10) {
            number = LC.modify_listAlign.getLocale() + CMIChatColor.getLastColors(LC.modify_listNumbering.getLocale()) + number
        }
        val line = RawMessage()
            .addText(LC.modify_deleteSymbol.getLocale() + " ")
            .addHover(LC.modify_deleteSymbolHover.getLocale("[text]", position))
            .addCommand(delete)
            .addText(LC.modify_listNumbering.getLocale("[number]", number))
            .addText(LC.modify_editLineColor.getLocale() + text)
        if (suggestion != null) line.addSuggestion(suggestion)
        line.show(sender)
    }

    private fun callback(sender: CommandSender, keep: Boolean = false, action: () -> Unit): RawMessageCommand =
        object : RawMessageCommand() {
            override fun run(clicker: CommandSender) {
                if (!closed && clicker == sender) action()
            }
        }.also {
            it.isKeep = keep
            callbacks.getOrPut(sender) { mutableListOf() }.add(it)
        }

    internal fun clearCallbacks(sender: CommandSender) {
        callbacks.remove(sender)?.forEach(RawMessageCommand::delete)
    }

    override fun close() {
        closed = true
        callbacks.keys.toList().forEach(::clearCallbacks)
    }
}

internal fun removeAndQueueIgnore(user: CMIUser, ignored: UUID): Boolean {
    if (ignored !in user.ignores) return false
    user.removeIgnore(ignored)
    user.addForDelayedSave()
    return true
}
