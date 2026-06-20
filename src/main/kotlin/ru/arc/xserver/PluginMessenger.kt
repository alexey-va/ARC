package ru.arc.xserver

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import ru.arc.ARC
import ru.arc.util.Logging.error
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class PluginMessenger : PluginMessageListener {

    init {
        Bukkit.getServer().messenger.registerOutgoingPluginChannel(ARC.instance, "BungeeCord")
    }

    override fun onPluginMessageReceived(s: String, player: Player, bytes: ByteArray) {}

    fun sendBungeeCord(player: Player, bytes: ByteArray) {
        player.sendPluginMessage(ARC.instance, "BungeeCord", bytes)
    }

    fun sendPlayerToServer(player: Player, server: String) {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        try {
            out.writeUTF("Connect")
            out.writeUTF(server)
        } catch (e: Exception) {
            error("Error in sendPlayerToServer", e)
            return
        }
        sendBungeeCord(player, bytes.toByteArray())
    }
}
