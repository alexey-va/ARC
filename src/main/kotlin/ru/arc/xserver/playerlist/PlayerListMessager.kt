package ru.arc.xserver.playerlist

import ru.arc.network.ChannelListener

class PlayerListMessager(val channel: String) : ChannelListener {
    override fun consume(channel: String, message: String, originServer: String) {
        PlayerManager.readMessage(message)
    }
}
