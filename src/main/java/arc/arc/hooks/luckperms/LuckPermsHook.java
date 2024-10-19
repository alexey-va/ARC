package arc.arc.hooks.luckperms;

import lombok.extern.slf4j.Slf4j;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.types.MetaNode;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Slf4j
public class LuckPermsHook {

    public boolean hasPermission(OfflinePlayer offlinePlayer, String perm) {
        UserManager userManager = LuckPermsProvider.get().getUserManager();
        if (offlinePlayer instanceof Player player) {
            return player.hasPermission(perm);
        }
        try {
            if (Thread.currentThread().getName().contains("main")) {
                System.out.println("Loading permission data from main thread!!!");
            }
            return userManager.loadUser(offlinePlayer.getUniqueId()).get()
                    .getCachedData()
                    .getPermissionData()
                    .checkPermission(perm)
                    .asBoolean();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error while checking permission", e);
            return false;
        }
    }

    public CompletableFuture<Void> setMeta(UUID uuid, String key, String value) {
        UserManager userManager = LuckPermsProvider.get().getUserManager();
        return userManager.modifyUser(uuid, user -> {
            user.getNodes().stream()
                    .filter(node -> node instanceof MetaNode)
                    .map(node -> (MetaNode) node)
                    .filter(node -> node.getMetaKey().equals(key))
                    .forEach(node -> user.data().remove(node));
            if (value == null) return;
            MetaNode node = MetaNode.builder()
                    .key(key)
                    .value(value)
                    .build();
            user.data().add(node);
        });
    }

    public CompletableFuture<String> getMeta(UUID uuid, String key) {
        UserManager userManager = LuckPermsProvider.get().getUserManager();
        return userManager.loadUser(uuid)
                .thenApply(user -> user.getCachedData().getMetaData().getMetaValue(key));
    }

}
