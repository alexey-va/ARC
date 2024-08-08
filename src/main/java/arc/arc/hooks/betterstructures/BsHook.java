package arc.arc.hooks.betterstructures;

import arc.arc.ARC;
import org.bukkit.Bukkit;

public class BsHook {

    private static BSListener bsListener;

    public BsHook() {
        if(bsListener == null) {
            bsListener = new BSListener();
            Bukkit.getPluginManager().registerEvents(bsListener, ARC.plugin);
        }
    }

}
