package arc.arc.xserver.announcements;

import arc.arc.ARC;
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

    private static AnnounceManager instance;

    private AnnounceManager(){
        task = new BukkitRunnable().runTaskTimer(ARC.plugin, )
    }

    public static AnnounceManager instance(){
        if(instance == null){
            synchronized (AnnounceManager.class){
                if(instance == null) instance = new AnnounceManager();
            }
        }
        return instance;
    }


    public void announceNext(){
        AnnouncementData data = getRandom();
        Bukkit.getOnlinePlayers().forEach(p -> {
                    for(ArcCondition condition : data.arcConditions){
                        if(!condition.test(p)) return;
                    }
                    p.sendMessage(data.component(p));
                });
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
        return 2;
    }

}
