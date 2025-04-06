package ru.arc.network.repos;

import ru.arc.ARC;
import ru.arc.network.ChannelListener;
import ru.arc.network.RedisManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@RequiredArgsConstructor
@Log4j2
public class RedisRepoMessager implements ChannelListener {
    private final RedisRepo<?> redisRepo;
    private final RedisManager redisManager;
    @Override
    public void consume(String channel, String message, String originServer) {
        if(originServer.equals(ARC.serverName)) return;
        redisRepo.receiveUpdate(message);
    }

    public void send(String channel, String message){
        redisManager.publish(channel, message);
    }
}
