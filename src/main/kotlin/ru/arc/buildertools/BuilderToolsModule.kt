package ru.arc.buildertools

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

object BuilderToolsModule : PluginModule, CommandExecutor, TabCompleter {
    override val name: String = "BuilderTools"
    override val priority: Int = 91

    private var runtime: BuilderToolsRuntime? = null

    override fun init() {
        bindCommands()
        val config = BuilderToolsConfig.load().validated()
        if (!config.enabled) {
            info("Builder tools disabled by configuration")
            return
        }
        runtime = BuilderToolsRuntime(ARC.instance, config)
        info("Builder tools enabled")
    }

    override fun shutdown() {
        runtime?.close()
        runtime = null
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val active = runtime
        if (active == null) {
            sender.sendMessage("Builder tools are disabled on this server.")
            return true
        }
        return active.onCommand(sender, command, label, args)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = runtime?.onTabComplete(sender, command, alias, args).orEmpty()

    private fun bindCommands() {
        for (name in listOf("builder", "deconstruction", "crown")) {
            val command = ARC.instance.getCommand(name)
            if (command == null) {
                warn("Builder-tools command '{}' is missing from plugin.yml", name)
            } else {
                command.setExecutor(this)
                command.tabCompleter = this
            }
        }
    }
}
