package arc.arc.hooks.betterstructures;

import arc.arc.ARC;
import lombok.Getter;
import org.bukkit.Bukkit;

@Getter
public class BsHook {

    private BSListener bsListener;

    public BsHook() {
        bsListener = new BSListener();
        registerListeners();
    }

    public void registerListeners() {
        Bukkit.getPluginManager().registerEvents(bsListener, ARC.plugin);
    }
}
