package arc.arc.hooks;

import com.Zrips.CMI.CMI;
import lombok.extern.slf4j.Slf4j;
import net.Zrips.CMILib.ActionBar.CMIActionBar;
import net.Zrips.CMILib.Advancements.CMIAdvancement;
import net.Zrips.CMILib.BossBar.BossBarInfo;
import net.Zrips.CMILib.CMILib;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

@Slf4j
public class CMIHook {

    public void sendBossbar(String bossBarName,
                            String message,
                            Player player,
                            BarColor barColor,
                            int seconds,
                            int keepFor) {

        BossBarInfo info = new BossBarInfo(player, bossBarName);
        info.setKeepForTicks(0);
        info.setColor(barColor);
        info.setAuto(1);
        info.setMakeVisible(true);
        info.setTranslateColors(true);
        info.setTitleOfBar(message);
        info.setKeepForTicks(keepFor);
        int iters = seconds * 20;
        info.setAdjustPerc(1.0 / iters);

        //log.info("Sending bossbar {} to player: {}", bossBarName, player.getName());

        CMILib.getInstance().getBossBarManager().Show(info);
    }

    public void sendActionbar(String message,
                              List<Player> players,
                              int seconds) {
        CMIActionBar.send(players, message, seconds * 1000);
    }

    public void sendToast(String desc, String title, int model, Material material, Player... players) {
        CMIAdvancement advancement = new CMIAdvancement();
        advancement.setAnnounce(true);
        if (model != 0) advancement.setCustomModelData(model);
        if (material != null) advancement.setItem(ItemStack.of(material));
        if (title != null) advancement.setTitle(title);
        if (desc != null) advancement.setDescription(desc);
        advancement.setHidden(false);
        advancement.show(players);
    }


    public boolean warpExists(String arg) {
        return CMI.getInstance().getWarpManager().getWarp(arg) != null;
    }
}
