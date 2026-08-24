package ru.arc.spy

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Containers.CMIMessageReplies
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

interface SpyStateAccess {
    fun isChatSpy(player: Player): Boolean

    fun isCommandSpy(player: Player): Boolean

    fun senderHidesChatSpy(player: Player): Boolean

    fun senderHidesCommandSpy(player: Player): Boolean

    fun canSeeUnlistedCommands(player: Player): Boolean

    fun commandBlacklist(): List<String>

    fun commandList(): List<String>

    fun replyTarget(playerName: String): String?

    fun targetUuid(playerName: String): UUID?
}

class CmiSpyAccess : SpyStateAccess {
    private val cmi: CMI get() = CMI.getInstance()

    override fun isChatSpy(player: Player): Boolean =
        cmi.playerManager.isSocialSpy(player.uniqueId)

    override fun isCommandSpy(player: Player): Boolean =
        cmi.playerManager.isCommandSpy(player.uniqueId)

    override fun senderHidesChatSpy(player: Player): Boolean =
        player.hasPermission("cmi.command.socialspy.hide")

    override fun senderHidesCommandSpy(player: Player): Boolean =
        player.hasPermission("cmi.command.commandspy.hide")

    override fun canSeeUnlistedCommands(player: Player): Boolean =
        player.hasPermission("cmi.security.admin")

    override fun commandBlacklist(): List<String> =
        cmi.configManager.commandSpyBlackListed.toList()

    override fun commandList(): List<String> =
        cmi.configManager.commandSpyCommandList.toList()

    override fun replyTarget(playerName: String): String? =
        CMIMessageReplies.getMessageReplyTo(playerName)

    override fun targetUuid(playerName: String): UUID? =
        Bukkit.getPlayerExact(playerName)?.uniqueId
            ?: cmi.bungeeCordManager.getBungeePlayer(playerName)?.uniqueId
}
