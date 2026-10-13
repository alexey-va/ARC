package ru.arc.dungeons.api;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
/** Shares ARC's existing round-robin autosave budget; callbacks only capture state on the main thread. */
public interface ArcPlayerSaveService {
    AutoCloseable register(Plugin owner, Consumer<Player> snapshot);
}
