package arc.arc.xserver.announcements;

import arc.arc.ARC;
import arc.arc.configs.AnnouneConfig;
import arc.arc.hooks.HookRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class AnnounceManager {


    TreeMap<Integer, AnnouncementData> announcements = new TreeMap<>();
    Deque<AnnouncementData> recentlyUsed = new ArrayDeque<>(2);
    BukkitTask task;
    int totalWeight;
    static int count=0;

    public static AnnouncementMessager messager;

    private static AnnounceManager instance;

    private AnnounceManager(){
        task = new BukkitRunnable() {
            @Override
            public void run() {
                announceNext();
            }
        }.runTaskTimer(ARC.plugin, AnnouneConfig.delay*20L, AnnouneConfig.delay*20L);
    }

    public static AnnounceManager instance(){
        if(instance == null){
            synchronized (AnnounceManager.class){
                if(instance == null){
                    instance = new AnnounceManager();
                }
            }
        }
        return instance;
    }

    public void sendMessage(UUID playerUuid, String mmString){
        announceGlobally(new AnnouncementData.AnnouncementDataBuilder()
                .message(mmString)
                .arcConditions(List.of(new PlayerCondition(playerUuid)))
                .build());
    }


    public void announceNext(){
        if(announcements.isEmpty()) return;
        AnnouncementData data = getRandom();
        announceGlobally(data);
    }

    public void clearData(){
        announcements.clear();
        if(task != null && !task.isCancelled()){
            task.cancel();
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    announceNext();
                }
            }.runTaskTimer(ARC.plugin, AnnouneConfig.delay*20L, AnnouneConfig.delay*20L);
        }
    }

    public static void announceGlobally(AnnouncementData data){
        Bukkit.getOnlinePlayers().forEach(p -> {
            for(ArcCondition condition : data.arcConditions){
                if(!condition.test(p)) return;
            }
            sendMessage(data, p);
        });
        if(messager != null) messager.send(data);
    }

    public static void announceLocally(AnnouncementData data){
        Bukkit.getOnlinePlayers().forEach(p -> {
            for(ArcCondition condition : data.arcConditions){
                if(!condition.test(p)) return;
            }
            sendMessage(data, p);
        });
    }

    private static void sendMessage(AnnouncementData data, Player player){
        switch (data.type){
            case CHAT -> player.sendMessage(data.component(player));
            case BOSSBAR -> {
                if(HookRegistry.cmiHook == null) {
                    System.out.println("I cant use bossbar without cmi... sorry");
                    return;
                }
                HookRegistry.cmiHook.sendBossbar("arcAnnounce", data.message,
                        player, data.bossBarColor, data.seconds);
            }
            case ACTIONBAR -> {
                if(HookRegistry.cmiHook == null) {
                    System.out.println("I cant use actionbar without cmi... sorry");
                    return;
                }
                HookRegistry.cmiHook.sendActionbar( data.message,
                        player, data.seconds);
            }
        }
    }

    public void addAnnouncement(AnnouncementData data){
        totalWeight+= data.weight;
        announcements.put(totalWeight, data);
    }

    private AnnouncementData getRandom(){
        int rng = new Random().nextInt(0, totalWeight+1);
        AnnouncementData data = announcements.ceilingEntry(rng).getValue();
        int count = 0;
        while (recentlyUsed.contains(data)){
            if(count == 10) break;
            count++;
            rng = new Random().nextInt(0, totalWeight+1);
            data = announcements.ceilingEntry(rng).getValue();
        }

        recentlyUsed.offerFirst(data);
        if(recentlyUsed.size()>queueSize())recentlyUsed.pollLast();

        return data;
    }

    private int queueSize(){
        return Math.min(announcements.size()-1, 3);
    }

}
