package ru.arc.xserver.playerlist;

import ru.arc.network.ChannelListener;

public record PlayerListMessager(String channel) implements ChannelListener {

    @Override
    public void consume(String channel, String message, String server) {
        PlayerManager.readMessage(message);
    }

}
