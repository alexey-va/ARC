package ru.arc.buildertools

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.autobuild.ConstructionSite
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

    fun startPlayerBuildBook(player: Player, site: ConstructionSite, book: ItemStack): Boolean =
        runtime?.startPlayerBuildBook(player, site, book) ?: false

    fun rejectUnsafeAuctionSale(player: Player) {
        runtime?.rejectUnsafeAuctionSale(player)
    }

    private fun bindCommands() {
        val command = ARC.instance.getCommand("builder")
        if (command == null) {
            warn("Builder-tools command 'builder' is missing from plugin.yml")
        } else {
            command.setExecutor(this)
            command.tabCompleter = this
        }
    }
}
