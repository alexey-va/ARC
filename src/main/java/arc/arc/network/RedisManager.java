package arc.arc.network;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RedisManager extends JedisPubSub {

    JedisPooled sub;
    JedisPooled pub;
    ExecutorService executorService;

    Map<String, List<ChannelListener>> channelListeners = new ConcurrentHashMap<>();
    Set<String> channelList = new HashSet<>();

    public RedisManager(String ip, int port, String userName, String password) {
        sub = new JedisPooled(ip, port, userName, password);
        pub = new JedisPooled(ip, port, userName, password);
        executorService = Executors.newCachedThreadPool();
        System.out.println("Setting up redis...");
    }

    public void onPong(String message) {
        System.out.printf("method: %s message: %s\n", "onPong", message);
    }

    @Override
    public void onMessage(String channel, String message) {
        //System.out.println(channel+" "+message);
        if(!channelListeners.containsKey(channel)){
            System.out.println("No listener for "+channel);
            return;
        }
        channelListeners.get(channel).forEach((listener) -> listener.consume(channel, message));
    }

    public void registerChannel(String channel, ChannelListener channelListener) {
        if (!channelListeners.containsKey(channel)) channelListeners.put(channel, new ArrayList<>());
        channelListeners.get(channel).add(channelListener);
        channelList.add(channel);

    }

    public void init(){
        executorService.execute(() -> sub.subscribe(this, channelList.toArray(String[]::new)));
    }

    public void onSubscribe(String channel, int subscribedChannels) {
        System.out.println(channel+" subbed!");
    }

    public void publish(String channel, String message) {
        //System.out.println("Publishing: "+channel+" | "+message);
        executorService.execute(() -> pub.publish(channel, message));
    }


}
