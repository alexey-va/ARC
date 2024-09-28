package arc.arc.hooks;

import com.Zrips.CMI.CMI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class CMIHook {

    public void sendBossbar(String bossBarName,
                            String message,
                            Player player,
                            String color,
                            int seconds) {

        String command = "cmi bossbarmsg " + player.getName() + " " +
                "-sec:" + seconds + " " +
                "-t:0 " + message + " " +
                "-n:" + bossBarName + " " +
                "-c:" + color;
        //System.out.println("Dispatching: "+command);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    public void sendActionbar(String message,
                              Player player,
                              int seconds) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "cmi actionbarmsg " + player.getName() + " " +
                        "-s:" + seconds + " " +
                        message
        );
    }


    public boolean warpExists(String arg) {
        return CMI.getInstance().getWarpManager().getWarp(arg) != null;
    }
}
