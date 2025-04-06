package ru.arc.sync.base;

import ru.arc.ARC;
import ru.arc.network.ChannelListener;
import ru.arc.network.RedisManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;


@RequiredArgsConstructor
@Log4j2
public class SyncMessager<T extends SyncData> implements ChannelListener {

    final RedisManager redisManager;
    final String channel;
    final SyncRepo<T> repo;

    @Override
    public void consume(String channel, String message, String originServer) {
        if (originServer.equals(ARC.serverName)) return;
        T data = repo.gson.fromJson(message, repo.clazz);
        log.debug("Received data from channel: {} with data: {}", channel, data);
        repo.dataApplier.accept(data);
    }

    public void send(T data) {
        log.debug("Sending data to channel: {}", channel);
        redisManager.publish(channel, repo.gson.toJson(data));
    }
}
