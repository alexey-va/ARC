package arc.arc.xserver.commands;

import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j2;

@RequiredArgsConstructor
@Log4j2
public class CommandSender {

    private final RedisManager redisManager;
    @Getter
    private final String publishChannel;

    public void dispatch(CommandData data){
        log.debug("Sending data to channel: " + publishChannel);
        redisManager.publish(publishChannel, RedisSerializer.toJson(data));
    }

}
