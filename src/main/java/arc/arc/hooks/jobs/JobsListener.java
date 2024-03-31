package arc.arc.hooks.jobs;

import arc.arc.network.repos.RedisRepo;
import com.gamingmesh.jobs.api.JobsExpGainEvent;
import com.gamingmesh.jobs.api.JobsPaymentEvent;
import com.gamingmesh.jobs.api.JobsPrePaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;


@Log4j2
public class JobsListener implements Listener {

    @EventHandler
    public void onJobsExp(JobsExpGainEvent event){
        RedisRepo<BoostData> repo = JobsHook.getRepo();
        if(repo == null) return;
        var future = repo.getOrNull(event.getPlayer().getUniqueId().toString());
        if(future.isDone()){
            var data = future.join();
            if(data == null) return;
            double boost = data.getBoost(event.getJob(), JobsBoost.Type.EXP);
            log.trace("Got boost data for player {}", event.getPlayer().getName());
            log.trace("Initial exp: {}", event.getExp());
            log.trace("Boosted exp: {}", event.getExp() * boost);
            event.setExp(event.getExp() * boost);
        }
    }

    @EventHandler
    public void onJobsPay(JobsPrePaymentEvent event){
        RedisRepo<BoostData> repo = JobsHook.getRepo();
        if(repo == null) return;
        var future = repo.getOrNull(event.getPlayer().getUniqueId().toString());
        if(future.isDone()){
            var data = future.join();
            double moneyBoost = data.getBoost(event.getJob(), JobsBoost.Type.MONEY);
            double pointsBoost = data.getBoost(event.getJob(), JobsBoost.Type.POINTS);
            log.trace("Got boost data for player {}", event.getPlayer().getName());
            log.trace("Initial money: {}", event.getAmount());
            log.trace("Initial points: {}", event.getPoints());
            log.trace("Boosted money: {}", event.getAmount() * moneyBoost);
            log.trace("Boosted points: {}", event.getPoints() * pointsBoost);
            event.setAmount(event.getAmount() * moneyBoost);
            event.setPoints(event.getPoints() * pointsBoost);
        }
    }


}
