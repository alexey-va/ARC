package arc.arc.network;

import arc.arc.configs.MainConfig;
import arc.arc.network.repos.RedisRepoMessager;
import lombok.extern.log4j.Log4j2;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Log4j2
public class RedisManager extends JedisPubSub {

    JedisPooled sub;
    JedisPooled pub;
    ExecutorService executorService;

    Map<String, List<ChannelListener>> channelListeners = new ConcurrentHashMap<>();
    Set<String> channelList = new HashSet<>();
    Future<?> running;

    private static final String SERVER_DELIMITER = "<>#<>#<>";

    public RedisManager(String ip, int port, String userName, String password) {
        sub = new JedisPooled(ip, port, userName, password);
        pub = new JedisPooled(ip, port, userName, password);
        executorService = Executors.newFixedThreadPool(10);
        log.debug("RedisManager created");
    }

    public void onPong(String message) {
        System.out.printf("method: %s message: %s\n", "onPong", message);
    }

    @Override
    public void onMessage(String channel, String message) {
        //log.trace("Received message: {}\n{}", channel, message);
        if (!channelListeners.containsKey(channel)) {
            System.out.println("No listener for " + channel);
            return;
        }

        String[] strings = message.split(SERVER_DELIMITER, 2);
/*        if (strings.length == 1)
            channelListeners.get(channel).forEach((listener) -> listener.consume(channel, strings[0], null));*/
        channelListeners.get(channel).forEach((listener) -> listener.consume(channel, strings[1], strings[0]));
    }

    public void registerChannel(String channel, ChannelListener channelListener) {
        log.debug("Registering channel: " + channel);
        if (!channelListeners.containsKey(channel)) channelListeners.put(channel, new ArrayList<>());
        channelListeners.get(channel).add(channelListener);
        channelList.add(channel);
    }

    public void init() {
        if (running != null && !running.isCancelled()) {
            log.debug("Stopping old subscription");
            running.cancel(true);
        }
        log.debug("Starting new subscription");
        running = executorService.submit(() -> {
            log.info("Subscribing to: " + channelList);
            sub.subscribe(this, channelList.toArray(String[]::new));
        });
    }

    public void onSubscribe(String channel, int subscribedChannels) {
        System.out.println(channel + " subbed!");
    }

    public void publish(String channel, String message) {
        executorService.execute(() -> pub.publish(channel, MainConfig.server + SERVER_DELIMITER + message));
    }

    public void saveMap(String key, Map<String, String> map) {

        executorService.execute(() -> pub.hmset(key, map));
    }

    public CompletableFuture<?> saveMapEntries(String key, String... keyValuePairs) {
        record Pair(String key, String value) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            pairs.add(new Pair(keyValuePairs[i], keyValuePairs[i + 1]));
        }
        //log.trace("Saving map key: {} \n {}", key, pairs);

        var delete = pairs.stream().filter(pair -> pair.value == null).map(Pair::key).toArray(String[]::new);
        var update = pairs.stream()
                .filter(pair -> pair.value != null)
                .collect(Collectors.toMap(Pair::key, Pair::value));

        return CompletableFuture.supplyAsync(() -> {
            if (delete.length > 0) pub.hdel(key, delete);
            if (!update.isEmpty()) pub.hmset(key, update);
            return null;
        }, executorService);

    }

    public CompletableFuture<Map<String, String>> loadMap(String key) {
        //log.trace("Loading map: {}", key);
        return CompletableFuture.supplyAsync(() -> pub.hgetAll(key));
    }

    public CompletableFuture<List<String>> loadMapEntries(String key, String... mapKeys) {
        //log.trace("Loading map entry: {} \n {}", key, mapKeys);
        return CompletableFuture.supplyAsync(() -> pub.hmget(key, mapKeys));
    }


    public void close() {
        log.debug("Closing redis manager");
        if (running != null && !running.isCancelled()) running.cancel(true);
        sub.close();
        pub.close();
        executorService.shutdown();
    }

    public void unregisterChannel(String updateChannel, RedisRepoMessager messager) {
        if (channelListeners.containsKey(updateChannel)) {
            channelListeners.get(updateChannel).remove(messager);
        }
    }
}
