package arc.arc.hooks.jobs;

import arc.arc.audit.AuditManager;
import arc.arc.audit.Type;
import arc.arc.hooks.HookRegistry;
import arc.arc.network.repos.RedisRepo;
import com.gamingmesh.jobs.api.JobsExpGainEvent;
import com.gamingmesh.jobs.api.JobsPrePaymentEvent;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static arc.arc.hooks.jobs.JobsListener.PreviousBoostType.*;


@Log4j2
public class JobsListener implements Listener {

    enum PreviousBoostType {
        EXP,
        MONEY,
        POINTS
    }

    Cache<String, Map<PreviousBoostType, Double>> map = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.SECONDS).build();

    @EventHandler
    @SneakyThrows
    public void onJobsExp(JobsExpGainEvent event) {
        //log.info("Job exp event: Player: {}, Exp: {}, Job: {}", event.getPlayer(), event.getExp(), event.getJob().getName());
        String playerName = event.getPlayer().getName();
        if (playerName == null) return;

        double exp = event.getExp();

        double baseExpBoost = 0.0;
        if (event.getPlayer() instanceof Player player) {
            baseExpBoost = HookRegistry.jobsHook.getBoost(player, event.getJob().getName(), JobsBoost.Type.EXP);
        }

        double effectiveExpBoost = baseExpBoost;
        boolean hasCustomBoost = false;

        RedisRepo<BoostData> repo = JobsHook.getRepo();
        if (repo != null) {
            var future = repo.getOrNull(event.getPlayer().getUniqueId().toString());
            if (future.isDone()) {
                var data = future.join();
                if (data != null) {
                    double boost = data.getBoost(event.getJob(), JobsBoost.Type.EXP);
                    if (Math.abs(boost - 1) > 0.000001) {
                        hasCustomBoost = true;
                    }
                    effectiveExpBoost = baseExpBoost + boost - 1;
                    //log.info("Effective exp boost: {}", effectiveExpBoost);
                }
            }
        }

        if (!hasCustomBoost) return;

        double targetExp = exp * (effectiveExpBoost + 1);
        //log.info("Target exp: {}", targetExp);

        double preExp = targetExp / (1.0 + baseExpBoost);
        //log.info("Pre exp: {}", preExp);

        var previousBoostedData = map.get(playerName, ConcurrentHashMap::new);
        if (Math.abs(previousBoostedData.getOrDefault(EXP, 0.0) - exp) < 0.000001) {
            //log.info("Skipping event for player {} data: {} {}", playerName, previousBoostedData.get(EXP), exp);
            return;
        }

        previousBoostedData.put(EXP, preExp);

        event.setExp(preExp);
    }

    @EventHandler
    @SneakyThrows
    public void onJobsPay(JobsPrePaymentEvent event) {
        //log.info("Job payment event: Player: {}, Money: {}, Points: {}, Job: {}, Action: {}",
        //        event.getPlayer(), event.getAmount(), event.getPoints(), event.getJob().getName(), event.getActionInfo());

        String playerName = event.getPlayer().getName();
        if (playerName == null) return;


        double money = event.getAmount();
        double points = event.getPoints();

        double baseMoneyBoost = 0.0;
        double basePointsBoost = 0.0;

        if (event.getPlayer() instanceof Player player) {
            baseMoneyBoost = HookRegistry.jobsHook.getBoost(player, event.getJob().getName(), JobsBoost.Type.MONEY);
            basePointsBoost = HookRegistry.jobsHook.getBoost(player, event.getJob().getName(), JobsBoost.Type.POINTS);
        }

        //log.info("Base money boost: {}, base points boost: {}", baseMoneyBoost, basePointsBoost);


        double effectiveMoneyBoost = baseMoneyBoost;
        double effectivePointsBoost = basePointsBoost;

        boolean hasCustomMoneyBoost = false;
        boolean hasCustomPointsBoost = false;

        RedisRepo<BoostData> repo = JobsHook.getRepo();
        if (repo != null) {
            var future = repo.getOrNull(event.getPlayer().getUniqueId().toString());
            if (future.isDone()) {
                var data = future.join();
                if (data != null) {
                    double moneyBoost = data.getBoost(event.getJob(), JobsBoost.Type.MONEY);
                    double pointsBoost = data.getBoost(event.getJob(), JobsBoost.Type.POINTS);

                    if (Math.abs(moneyBoost - 1) > 0.000001) {
                        hasCustomMoneyBoost = true;
                    }
                    if (Math.abs(pointsBoost - 1) > 0.000001) {
                        hasCustomPointsBoost = true;
                    }

                    //log.info("Money boost: {}, points boost: {}", moneyBoost, pointsBoost);

                    effectiveMoneyBoost = baseMoneyBoost + moneyBoost - 1;
                    effectivePointsBoost = basePointsBoost + pointsBoost - 1;

                    //log.info("Effective money boost: {}, effective points boost: {}", effectiveMoneyBoost, effectivePointsBoost);
                }
            }
        }

        if (!hasCustomMoneyBoost && !hasCustomPointsBoost) {
            AuditManager.operation(playerName, money * (baseMoneyBoost + 1), Type.JOB, event.getJob().getName());
            return;
        }

        double targetMoney = money * (effectiveMoneyBoost + 1);
        double targetPoints = points * (effectivePointsBoost + 1);
        //log.info("Target money: {}, target points: {}", targetMoney, targetPoints);

        double preMoney = targetMoney / (1.0 + baseMoneyBoost);
        double prePoints = targetPoints / (1.0 + basePointsBoost);
        //log.info("Pre money: {}, pre points: {}", preMoney, prePoints);


        var previousBoostedData = map.get(playerName, ConcurrentHashMap::new);
        if (hasCustomMoneyBoost && Math.abs(previousBoostedData.getOrDefault(MONEY, 0.0) - money) < 0.000001) {
            //log.info("Skipping event for player {} data: {} {}", playerName, previousBoostedData.get(MONEY), money);
        } else {
            previousBoostedData.put(MONEY, preMoney);
            event.setAmount(preMoney);
            AuditManager.operation(playerName, targetMoney, Type.JOB, event.getJob().getName());
        }

        if (hasCustomPointsBoost && Math.abs(previousBoostedData.getOrDefault(POINTS, 0.0) - points) < 0.000001) {
            //log.info("Skipping event for player {} data: {} {}", playerName, previousBoostedData.get(POINTS), points);
        } else {
            previousBoostedData.put(POINTS, prePoints);
            event.setPoints(prePoints);
        }
    }


}
