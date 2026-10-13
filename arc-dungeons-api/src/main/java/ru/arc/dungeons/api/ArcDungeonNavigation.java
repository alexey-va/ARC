package ru.arc.dungeons.api;
import java.util.UUID;
import org.bukkit.entity.Player;
/** Navigation to ARC-owned screens and travel. All calls run on the server thread. */
public interface ArcDungeonNavigation {
    boolean openMain(Player player);
    boolean openGuide(Player player, Runnable returnTo);
    void openPortal(Player player, Runnable action);
    boolean teleport(Player player, String server, String world, double x, double y, double z, float yaw, float pitch);
    boolean escapeGoesBack(UUID player);
}
