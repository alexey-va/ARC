package ru.arc.network.repos;

import lombok.RequiredArgsConstructor;
import ru.arc.ARC;
import ru.arc.network.ChannelListener;
import ru.arc.network.RedisOperations;

@RequiredArgsConstructor
public class RedisRepoMessager implements ChannelListener {
    private final RedisRepo<?> redisRepo;
    private final RedisOperations redisManager;
    @Override
    public void consume(String channel, String message, String originServer) {
        if(originServer.equals(ARC.serverName)) return;
        redisRepo.receiveUpdate(message);
    }

    public void send(String channel, String message){
        redisManager.publish(channel, message);
    }
}
