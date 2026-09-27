package ru.arc.chat

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Modules.ChatFormat.ChatFormatManager
import net.Zrips.CMILib.Container.CMIKyori
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/** CMI's staff/room sinks bypass Paper's final renderer and cannot reserve sticker rows. */
internal object CmiStickerChannels {
    fun isPublicChat(player: Player, message: Component): Boolean {
        val cmi = CMI.getInstance()
        val staff = player.uniqueId in cmi.chatFormatManager.staffChats
        val room = cmi.playerManager.getUser(player)?.chatRoom
        if (!staff && room == null) return true
        // A single routing prefix cannot escape two active private consumers.
        if (staff && room != null) return false
        val source = CMIKyori.serialize(message)
        return if (staff) source.startsWith("!")
        else room != null && !room.isLocked && source.startsWith(ChatFormatManager.ChatRoomShout)
    }
}
