package arc.arc.hooks.jobs;

import com.gamingmesh.jobs.api.JobsExpGainEvent;
import com.gamingmesh.jobs.api.JobsPaymentEvent;
import com.gamingmesh.jobs.api.JobsPrePaymentEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class JobsListener implements Listener {

    @EventHandler
    public void onJobsExp(JobsExpGainEvent event){
        System.out.println("Gain exp: "+event.getExp()+" "+event.getJob()+" "+event.getPlayer().getName());
    }

    @EventHandler
    public void onJobsPay(JobsPrePaymentEvent event){
        System.out.println("PrePaying: "+event.getJob()+" "+event.getPlayer().getName()+" "+event.getAmount()+" "+event.getPoints());
    }

    @EventHandler
    public void onJobsPay2(JobsPaymentEvent event){
        System.out.println("Paying: "+event.getPayment()+" "+event.getPlayer().getName());
    }

}
