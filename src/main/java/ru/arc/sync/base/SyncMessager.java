package ru.arc.sync.base;

import lombok.RequiredArgsConstructor;
import ru.arc.ARC;
import ru.arc.network.ChannelListener;
import ru.arc.network.RedisManager;

import static ru.arc.util.Logging.debug;


@RequiredArgsConstructor
public class SyncMessager<T extends SyncData> implements ChannelListener {

    final RedisManager redisManager;
    final String channel;
    final SyncRepo<T> repo;

    @Override
    public void consume(String channel, String message, String originServer) {
        if (originServer.equals(ARC.serverName)) return;
        T data = repo.gson.fromJson(message, repo.clazz);
        debug("Received data from channel: {} with data: {}", channel, data);
        repo.dataApplier.accept(data);
    }

    public void send(T data) {
        debug("Sending data to channel: {}", channel);
        redisManager.publish(channel, repo.gson.toJson(data));
    }
}
