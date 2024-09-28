package arc.arc.hooks.luckperms;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.UserManager;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.concurrent.ExecutionException;

public class LuckPermsHook {

    public boolean hasPermission(OfflinePlayer offlinePlayer, String perm){
        UserManager userManager = LuckPermsProvider.get().getUserManager();
        if(offlinePlayer instanceof Player player){
            return player.hasPermission(perm);
        }
        try {
            if(Thread.currentThread().getName().contains("main")){
                System.out.println("Loading permission data from main thread!!!");
            }
            return userManager.loadUser(offlinePlayer.getUniqueId()).get()
                    .getCachedData()
                    .getPermissionData()
                    .checkPermission(perm)
                    .asBoolean();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

}
