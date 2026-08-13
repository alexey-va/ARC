package me.dartanman.duels;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import me.dartanman.duels.commands.DuelCommand;
import me.dartanman.duels.config.DuelsConfig;
import me.dartanman.duels.config.KitDefinition;
import me.dartanman.duels.game.ChallengeService;
import me.dartanman.duels.game.DuelManager;
import me.dartanman.duels.listeners.DuelListener;
import me.dartanman.duels.model.DuelMode;
import me.dartanman.duels.recovery.RecoveryStore;
import me.dartanman.duels.stats.ArcStatsNotifier;
import me.dartanman.duels.stats.StatsRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class Duels extends JavaPlugin {
    private DuelsConfig duelsConfig;
    private RecoveryStore recovery;
    private StatsRegistry stats;
    private DuelManager manager;

    @Override
    public void onEnable() {
        saveResource("rusduels.yml", false);
        recovery = new RecoveryStore(getDataFolder().toPath().resolve("recovery"));
        stats = new StatsRegistry(
                getDataFolder().toPath().resolve("rusduels-stats.yml"),
                getLogger(),
                new ArcStatsNotifier(getLogger())
        );
        stats.load();
        duelsConfig = loadConfigFailClosed();
        manager = new DuelManager(this, duelsConfig, recovery, stats);
        ChallengeService challenges = new ChallengeService(
                Duration.ofSeconds(duelsConfig.requestExpirationSeconds()),
                System::currentTimeMillis
        );

        Bukkit.getPluginManager().registerEvents(new DuelListener(this, manager, challenges), this);
        DuelCommand executor = new DuelCommand(this, manager, challenges);
        PluginCommand command = getCommand("duel");
        if (command == null) throw new IllegalStateException("duel command is missing from plugin.yml");
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) manager.restorePending(player);
        try {
            getLogger().info("Recovery journal pending players: " + recovery.pendingPlayers().size());
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Could not inspect recovery journal", exception);
        }
        getLogger().info("RusCrafting Duels enabled: mode=" + duelsConfig.mode()
                + ", arenas=" + duelsConfig.arenas().size()
                + ", enabled=" + duelsConfig.enabled());
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
        if (stats != null) stats.close(Duration.ofSeconds(5));
    }

    public DuelsConfig duelsConfig() {
        return duelsConfig;
    }

    public StatsRegistry stats() {
        return stats;
    }

    /** Stable JDK-only reflection boundary consumed by ARC's DuelsSync. */
    public Map<String, Object> getStatsData(UUID playerId) {
        return stats.export(playerId);
    }

    /** Stable JDK-only reflection boundary consumed by ARC's DuelsSync. */
    public void applyStatsData(UUID playerId, Map<?, ?> values) {
        stats.applyRemote(playerId, values);
    }

    private DuelsConfig loadConfigFailClosed() {
        File file = new File(getDataFolder(), "rusduels.yml");
        try {
            return DuelsConfig.load(file, getLogger());
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Invalid rusduels.yml; duel creation is disabled but recovery remains active", exception);
            return new DuelsConfig(
                    false,
                    DuelMode.DISABLED,
                    30,
                    3,
                    Set.of("msg", "r", "reply", "tell", "w"),
                    List.of(),
                    new KitDefinition(Map.of(), null, null, null, null, null)
            );
        }
    }
}
