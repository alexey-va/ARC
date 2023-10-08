package arc.arc;

import arc.arc.board.BoardEntry;
import arc.arc.board.ItemIcon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RedisManager extends JedisPubSub {


    private static BukkitTask reqTask = null;
    Map<UUID, PendingRequest> pendingRequests = new HashMap<>();
    JedisPooled sub;
    JedisPooled pub;
    ExecutorService executorService;

    public RedisManager(String ip, int port, String userName, String password) {
        sub = new JedisPooled(ip, port, userName, password);
        pub = new JedisPooled(ip, port, userName, password);
        executorService = Executors.newFixedThreadPool(2);

        executorService.execute(() -> sub.subscribe(this, "arc.player_list", "arc.lands_requests", "arc.lands_response", "arc.board_update", "arc.board_delete"));
        System.out.println("Setting up redis...");
        if (reqTask == null)
            reqTask = new BukkitRunnable() {
                @Override
                public void run() {
                    List<PendingRequest> toRemove = new ArrayList<>();
                    pendingRequests.forEach((k, v) -> {
                        if (v.isStale()) toRemove.add(v);
                    });
                    toRemove.forEach(r -> {
                        pendingRequests.remove(r.uuid);
                        denyMessage(r.uuid);
                    });

                    publishPlayerList();

                    //System.out.println("Running");
                }
            }.runTaskTimer(ARC.plugin, 0L, 10L);
    }

    private static void denyMessage(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) player.sendMessage(Component.text("Спавн поселения не найден!", NamedTextColor.RED));
    }

    public void onSubscribe(String channel, int subscribedChannels) {
        System.out.printf(
                "method: %s channel: %s subscribedChannels: %d\n",
                "onSubscribe", channel, subscribedChannels);
    }

    public void onUnsubscribe(String channel, int subscribedChannels) {
        System.out.printf(
                "method: %s channel: %s subscribedChannels: %d\n",
                "onUnsubscribe", channel, subscribedChannels);
    }

    public void onPUnsubscribe(String pattern, int subscribedChannels) {
        System.out.printf(
                "method: %s patten: %s subscribedChannels: %d\n",
                "onPUnsubscribe", pattern, subscribedChannels);
    }

    public void onPSubscribe(String pattern, int subscribedChannels) {
        System.out.printf(
                "method: %s patten: %s subscribedChannels: %d\n",
                "onPSubscribe", pattern, subscribedChannels);
    }

    public void onPong(String message) {
        System.out.printf("method: %s message: %s\n", "onPong", message);
    }

    public void onMessage(String channel, String message) {
        //System.out.println("Message: " + message + " | " + channel);
        if (channel.equalsIgnoreCase("arc.player_list"))
            processPlayerList(message.split(":::")[0], message.split(":::")[1]);
        else if (channel.equalsIgnoreCase("arc.lands_requests"))
            processLandsRequest(message.split(":::")[0], message.split(":::")[1]);
        else if (channel.equalsIgnoreCase("arc.lands_response"))
            receiveLandsResponse(message.split(":::")[0], message.split(":::")[1]);
        else if(channel.equalsIgnoreCase("arc.board_update"))
            processBoardUpdate(message.split(":::")[0], message.split(":::")[1]);
        else if(channel.equalsIgnoreCase("arc.board_delete"))
            processBoardDelete(message.split(":::")[0], message.split(":::")[1]);

    }

    private void processPlayerList(String trimmedMessage, String server) {
        Map<String, UUID> playerData = new HashMap<>();
        for (String s : trimmedMessage.split(";;;")) {
            String[] data = s.split("</>");
            String name = data[0];
            UUID uuid = UUID.fromString(data[1]);
            playerData.put(name, uuid);
        }
        PlayerManager.addPlayers(server, playerData);
    }

    private void publishPlayerList() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        StringBuilder message1 = new StringBuilder();
        for (Player player : Bukkit.getOnlinePlayers())
            message1.append(player.getName()).append("</>").append(player.getUniqueId()).append(";;;");

        String message = message1.toString();
        if (message.endsWith(";;;")) message = message.substring(0, message.length() - 3);
        String finalMessage = message;
        executorService.execute(() -> pub.publish("arc.player_list", (finalMessage + ":::" + Config.server)));
    }

    private void receiveLandsResponse(String message, String server) {
        String[] strings = message.split(";;;");
        UUID uuid = UUID.fromString(strings[0]);
        if (!pendingRequests.containsKey(uuid)) return;
        pendingRequests.remove(uuid);

        String world = strings[1];
        if (world.equalsIgnoreCase("NULL")) {
            denyMessage(uuid);
            return;
        }
        double x = Double.parseDouble(strings[2]);
        double y = Double.parseDouble(strings[3]);
        double z = Double.parseDouble(strings[4]);
        float yaw = Float.parseFloat(strings[5]);
        float pitch = Float.parseFloat(strings[6]);

        if (ARC.plugin.huskHomesHook != null) ARC.plugin.huskHomesHook.teleport(Bukkit.getPlayer(uuid),
                server, x, y, z, yaw, pitch, world);
    }

    private void processLandsRequest(String message, String server) {
        if (server.equalsIgnoreCase(Config.server) || ARC.plugin.landsHook == null) return;
        UUID uuid = UUID.fromString(message);
        ARC.plugin.landsHook.sendSpawnLocation(uuid, pub);
    }

    public void publishLandsRequest(UUID uuid) {
        String request = uuid + ":::" + Config.server;
        pendingRequests.put(uuid, new PendingRequest(uuid));
        executorService.execute(() -> pub.publish("arc.lands_requests", request));
    }

    public void deleteBoardEntry(UUID uuid){
        executorService.execute(() -> {
            pub.hdel("arc_board_entries", uuid.toString());
            pub.publish("arc.board_delete", uuid+":::"+Config.server);
        });
    }

    private void processBoardUpdate(String message, String server){
        if(server.equalsIgnoreCase(Config.server)) return;
        UUID uuid = UUID.fromString(message);
        ARC.plugin.board.loadBoardEntry(uuid);
    }

    private void processBoardDelete(String message, String server){
        if(server.equalsIgnoreCase(Config.server)) return;
        UUID uuid = UUID.fromString(message);
        ARC.plugin.board.deleteBoard(uuid, false);
    }

    public void publishBoardEntry(BoardEntry boardEntry){
        executorService.execute(() -> {
            pub.hset("arc_board_entries", boardEntry.getUuid().toString(), BoardEntry.serialiseBoardEntry(boardEntry));
            pub.publish("arc.board_update", boardEntry.getUuid()+":::"+Config.server);
        });
    }

    public CompletableFuture<BoardEntry> getBoardEntry(UUID uuid) {
        CompletableFuture<BoardEntry> future = new CompletableFuture<>();
        executorService.execute(() -> {
            String s = sub.hget("arc_board_entries", uuid.toString());
            future.complete(BoardEntry.parseBoardEntry(s, uuid));
        });
        return future;
    }

    public CompletableFuture<List<BoardEntry>> getBoardEntries() {
        CompletableFuture<List<BoardEntry>> future = new CompletableFuture<>();
        executorService.execute(() -> {
            Map<String, String> map = sub.hgetAll("arc_board_entries");
            System.out.print(map);
            List<BoardEntry> list = new ArrayList<>();
            for (var entry : map.entrySet())
                list.add(BoardEntry.parseBoardEntry(entry.getValue(), UUID.fromString(entry.getKey())));
            list.sort((b1, b2) -> (int) (b1.timestamp - b2.timestamp));
            future.complete(list);
        });
        return future;
    }



    static class PendingRequest {
        long timestamp;
        UUID uuid;

        public PendingRequest(UUID uuid) {
            this.uuid = uuid;
            timestamp = System.currentTimeMillis();
        }

        boolean isStale() {
            return (System.currentTimeMillis() - timestamp > 3000);
        }
    }

}
