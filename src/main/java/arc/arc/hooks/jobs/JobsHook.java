package arc.arc.hooks.jobs;

import arc.arc.ARC;
import org.bukkit.Bukkit;

public class JobsHook {

    private static JobsListener jobsListener;


    public JobsHook() {
        if(jobsListener == null){
            jobsListener = new JobsListener();
            Bukkit.getPluginManager().registerEvents(jobsListener, ARC.plugin);
        }
    }
}
