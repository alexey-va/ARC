package arc.arc.xserver.commands;

import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CommandSender {

    private final RedisManager redisManager;
    @Getter
    private final String publishChannel;

    public void dispatch(CommandData data){
        redisManager.publish(publishChannel, RedisSerializer.toJson(data));
    }

}
