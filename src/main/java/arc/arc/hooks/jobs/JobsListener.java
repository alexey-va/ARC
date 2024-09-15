package arc.arc.hooks.jobs;

import arc.arc.network.repos.RedisRepo;
import com.gamingmesh.jobs.api.JobsExpGainEvent;
import com.gamingmesh.jobs.api.JobsPrePaymentEvent;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.log4j.Log4j2;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


@Log4j2
public class JobsListener implements Listener {

    enum PreviousBoostType {
        EXP,
        MONEY,
        POINTS
    }

    Cache<String, Map<PreviousBoostType, Double>> map = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.MINUTES).build();


    @EventHandler
    public void onJobsExp(JobsExpGainEvent event) {
        RedisRepo<BoostData> repo = JobsHook.getRepo();
        log.trace("Exp event: {}", event);
        if (repo == null) return;
        var future = repo.getOrNull(event.getPlayer().getUniqueId().toString());
        if (future.isDone()) {
            var data = future.join();
            if (data == null) return;
            double exp = event.getExp();
            double boost = data.getBoost(event.getJob(), JobsBoost.Type.EXP);
            try {
                String playerName = event.getPlayer().getName();
                if (playerName == null) return;
                var previousBoostedData = map.get(playerName, ConcurrentHashMap::new);
                log.trace("Previous boosted data: {} | {}", previousBoostedData, exp);
                if (Math.abs(previousBoostedData.getOrDefault(PreviousBoostType.EXP, 0.0) - exp) < 0.000001) {
                    return;
                }

                double boostExp = exp * boost;
                log.trace("Boosted exp: {}", boostExp);
                event.setExp(boostExp);
                previousBoostedData.put(PreviousBoostType.EXP, boostExp);
            } catch (Exception e) {
                log.error("Error in JobsExpGainEvent", e);
            }
        } else {
            log.debug("Boost data not loaded for player {}", event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onJobsPay(JobsPrePaymentEvent event) {
        RedisRepo<BoostData> repo = JobsHook.getRepo();
        if (repo == null) return;
        var future = repo.getOrNull(event.getPlayer().getUniqueId().toString());
        if (future.isDone()) {
            var data = future.join();
            if (data == null) return;
            double moneyBoost = data.getBoost(event.getJob(), JobsBoost.Type.MONEY);
            double pointsBoost = data.getBoost(event.getJob(), JobsBoost.Type.POINTS);
            double money = event.getAmount();
            double points = event.getPoints();
            try {
                String playerName = event.getPlayer().getName();
                if (playerName == null) return;
                var previousBoostedData = map.get(playerName, ConcurrentHashMap::new);
                log.trace("Previous boosted data: {} | {} | {}", previousBoostedData, money, points);
                if (Math.abs(previousBoostedData.getOrDefault(PreviousBoostType.MONEY, 0.0) - money) < 0.000001 ||
                        Math.abs(previousBoostedData.getOrDefault(PreviousBoostType.POINTS, 0.0) - points) < 0.000001) {
                    return;
                }

                log.trace("Got boost data for player {}", event.getPlayer().getName());
                log.trace("Initial money: {}", event.getAmount());
                log.trace("Initial points: {}", event.getPoints());
                log.trace("Boosted money: {}", event.getAmount() * moneyBoost);
                log.trace("Boosted points: {}", event.getPoints() * pointsBoost);
                event.setAmount(event.getAmount() * moneyBoost);
                event.setPoints(event.getPoints() * pointsBoost);
                log.trace("Final money: {}", event.getAmount());
                log.trace("Final points: {}", event.getPoints());

                previousBoostedData.put(PreviousBoostType.MONEY, event.getAmount());
                previousBoostedData.put(PreviousBoostType.POINTS, event.getPoints());
            } catch (Exception e) {
                log.error("Error in JobsPrePaymentEvent", e);
            }
        }
    }


}
