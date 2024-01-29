package arc.arc.network;

import arc.arc.board.BoardEntry;
import arc.arc.board.ItemIcon;
import arc.arc.xserver.playerlist.PlayerData;
import arc.arc.xserver.playerlist.PlayerList;
import arc.arc.xserver.commands.CommandData;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

class RedisSerializerTest {

    @Test
    void toJson() {
        ServerLocation location = ServerLocation.builder()
                .server("spawn")
                .world("world")
                .x(0)
                .y(0)
                .z(0)
                .yaw(0)
                .pitch(0)
                .build();
        System.out.println(RedisSerializer.toJson(location));
    }

    @Test
    void fromJson() {
        String json = "{\"server\":\"spawn\",\"world\":\"world\",\"x\":0.0,\"y\":0.0,\"z\":0.0,\"yaw\":0.0,\"pitch\":0.0}";
        ServerLocation location = RedisSerializer.fromJson(json, ServerLocation.class);
        System.out.println(location);

    }

    @Test
    void playerList() {
        PlayerList playerList = new PlayerList();
        playerList.addPlayer(new PlayerData("test", "spawn" , null));
        System.out.println(RedisSerializer.toJson(playerList));
    }

    @Test
    void commandData(){
        CommandData data = CommandData.builder()
                .command("home")
                .everywhere(true)
                .playerUuid(UUID.randomUUID())
                .servers(List.of("a","b"))
                .sender(CommandData.Sender.PLAYER)
                .build();
        System.out.println(RedisSerializer.toJson(data));
        System.out.println(RedisSerializer.fromJson(RedisSerializer.toJson(data), CommandData.class));
    }

}