package ru.arc.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;
import ru.arc.ARC;
import ru.arc.network.repos.RedisRepoMessager;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

public class RedisManager extends JedisPubSub {

    JedisPooled sub;
    JedisPooled pub;
    ExecutorService executorService;
    ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    Map<String, List<ChannelListener>> channelListeners = new ConcurrentHashMap<>();
    Set<String> channelList = new HashSet<>();
    Future<?> running;
    ScheduledFuture<?> scheduled;

    private static final String SERVER_DELIMITER = "<>#<>#<>";

    public RedisManager(String ip, int port, String userName, String password) {
        connect(ip, port, userName, password);
    }

    public void connect(String ip, int port, String userName, String password) {
        close();
        sub = new JedisPooled(ip, port, userName, password);
        pub = new JedisPooled(ip, port, userName, password);
        debug("RedisManager initialized");
        init();
    }

    public void onPong(String message) {
        System.out.printf("method: %s message: %s\n", "onPong", message);
    }

    @Override
    public void onMessage(String channel, String message) {
        try {
            //System.out.println("Received message: " + message + " on channel: " + channel);
            if (!channelListeners.containsKey(channel)) {
                System.out.println("No listener for " + channel);
                return;
            }

            String[] strings = message.split(SERVER_DELIMITER, 2);
            channelListeners.get(channel).forEach((listener) -> listener.consume(channel, strings[1], strings[0]));
        } catch (Exception e) {
            error("Error processing message", e);
        }
    }

    public void registerChannelUnique(String channel, ChannelListener channelListener) {
        channelListeners.putIfAbsent(channel, new ArrayList<>());
        channelListeners.get(channel).clear();
        channelListeners.get(channel).add(channelListener);
        channelList.add(channel);
    }

    public void init() {
        if (running != null && !running.isCancelled()) {
            info("Stopping old subscription");
            running.cancel(true);
        }
        if(scheduled != null && !scheduled.isCancelled() && !scheduled.isDone()) {
            info("Subscription already scheduled");
            return;
        }
        executorService = Executors.newCachedThreadPool();
        info("Scheduling subscription in 1 second");
        scheduled = scheduledExecutorService.schedule(() -> {
            running = executorService.submit(() -> {
                info("Subscribing to: {}", channelList);
                try {
                    sub.subscribe(this, channelList.toArray(String[]::new));
                } catch (Exception e) {
                    error("Error subscribing. Rescheduling in 100 ms", e);
                    scheduledExecutorService.schedule(this::init, 100, TimeUnit.MILLISECONDS);
                }
            });
        }, 1, TimeUnit.SECONDS);
    }

    public void onSubscribe(String channel, int subscribedChannels) {
        info("Subscribed to channel: {} with {} channels total", channel, subscribedChannels);
    }

    public void publish(String channel, String message) {
        executorService.execute(() -> pub.publish(channel, ARC.serverName + SERVER_DELIMITER + message));
    }

    public void saveMap(String key, Map<String, String> map) {
        executorService.execute(() -> pub.hmset(key, map));
    }

    public CompletableFuture<?> saveMapEntries(String key, String... keyValuePairs) {
        if (keyValuePairs.length == 0) {
            debug("No key value pairs to save for key {}", key);
        }
        debug("Saving map key: {} \n {}", key, Arrays.toString(keyValuePairs));
        try {
            record Pair(String key, String value) {
            }
            List<Pair> pairs = new ArrayList<>();
            for (int i = 0; i < keyValuePairs.length; i += 2) {
                pairs.add(new Pair(keyValuePairs[i], keyValuePairs[i + 1]));
            }

            var delete = pairs.stream()
                    .filter(pair -> pair.value == null)
                    .map(Pair::key)
                    .toArray(String[]::new);
            var update = pairs.stream()
                    .filter(pair -> pair.value != null)
                    .collect(Collectors.toMap(Pair::key, Pair::value));
            debug("Saving map key: {} \n {}", key, update);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (delete.length > 0) pub.hdel(key, delete);
                    if (!update.isEmpty()) pub.hmset(key, update);
                } catch (Exception e) {
                    error("Error saving map key: {} \n {}", key, update, e);
                }
                return null;
            }, executorService);
        } catch (Exception e) {
            error("Error saving map key: {} \n {}", key, keyValuePairs, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public CompletableFuture<Map<String, String>> loadMap(String key) {
        //// Logging removed - was using @Slf4j
        return CompletableFuture.supplyAsync(() -> pub.hgetAll(key));
    }

    public CompletableFuture<List<String>> loadMapEntries(String key, String... mapKeys) {
        //// Logging removed - was using @Slf4j
        return CompletableFuture.supplyAsync(() -> pub.hmget(key, mapKeys));
    }


    public void close() {
        try {
            info("Closing redis manager");
            if (running != null && !running.isCancelled()) running.cancel(true);
            if (sub != null) sub.close();
            if (sub != null) pub.close();
            if (executorService != null) executorService.shutdown();
        } catch (Exception e) {
            error("Error closing redis manager", e);
        }
    }

    public void unregisterChannel(String updateChannel, RedisRepoMessager messager) {
        if (channelListeners.containsKey(updateChannel)) {
            channelListeners.get(updateChannel).remove(messager);
        }
    }
}
