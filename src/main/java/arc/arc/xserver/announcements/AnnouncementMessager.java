package arc.arc.xserver.announcements;

import arc.arc.network.ChannelListener;
import arc.arc.network.RedisSerializer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AnnouncementMessager implements ChannelListener {

    @Getter
    final String channel;

    @Override
    public void consume(String channel, String message) {
        AnnouncementData data = RedisSerializer.fromJson(message, AnnouncementData.class);

    }


    public void send(AnnouncementData data){
        
    }
}
