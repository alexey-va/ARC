package arc.arc.hooks.betterstructures;

import arc.arc.ARC;
import com.magmaguy.betterstructures.api.ChestFillEvent;
import lombok.Getter;
import org.bukkit.Bukkit;

import java.util.Arrays;

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
