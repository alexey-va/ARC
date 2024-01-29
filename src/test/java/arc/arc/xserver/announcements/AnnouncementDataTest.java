package arc.arc.xserver.announcements;

import arc.arc.network.RedisSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
class AnnouncementDataTest {

    @Test
    void testAnnSer(){
        System.out.println(RedisSerializer.toJson(AnnouncementData.builder()
                .arcConditions(List.of(new PermissionCondition("arc.test")))
                .message("Test message").build()));

        AnnouncementData data = RedisSerializer.fromJson(RedisSerializer.toJson(AnnouncementData.builder()
                .arcConditions(List.of(new PermissionCondition("arc.test")))
                .message("Test message").build()), AnnouncementData.class);
        System.out.println(data);
    }

    @Test
    void testDesAnn(){

    }

}