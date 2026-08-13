package me.dartanman.duels.game;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import me.dartanman.duels.Duels;
import me.dartanman.duels.config.DuelsConfig;
import me.dartanman.duels.model.Arena;
import me.dartanman.duels.model.DuelMode;
import me.dartanman.duels.recovery.PlayerSnapshot;
import me.dartanman.duels.recovery.RecoveryStore;
import me.dartanman.duels.stats.StatsRegistry;
import me.dartanman.duels.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

public final class DuelManager {
    public enum EndReason { DEFEAT, FORFEIT, SHUTDOWN, START_FAILURE }

    @FunctionalInterface
    interface PlayerDataSaver {
        void save(Player player);
    }

    private final Duels plugin;
    private final DuelsConfig config;
    private final RecoveryStore recovery;
    private final StatsRegistry stats;
    private final PlayerDataSaver playerDataSaver;
    private final Map<UUID, DuelSession> sessions = new HashMap<>();
    private final Set<String> occupiedArenas = new HashSet<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Map<UUID, Location> pendingRespawnLocations = new HashMap<>();

    public DuelManager(Duels plugin, DuelsConfig config, RecoveryStore recovery, StatsRegistry stats) {
        this(plugin, config, recovery, stats, Player::saveData);
    }

    DuelManager(
            Duels plugin,
            DuelsConfig config,
            RecoveryStore recovery,
            StatsRegistry stats,
            PlayerDataSaver playerDataSaver
    ) {
        this.plugin = plugin;
        this.config = config;
        this.recovery = recovery;
        this.stats = stats;
        this.playerDataSaver = playerDataSaver;
    }

    public boolean start(Player first, Player second) {
        if (!config.enabled()) {
            Messages.send(first, "<#c42323>Дуэли на этом сервере отключены.");
            return false;
        }
        if (first.equals(second) || isInDuel(first.getUniqueId()) || isInDuel(second.getUniqueId())) {
            Messages.send(first, "<#c42323>Один из игроков уже участвует в дуэли.");
            return false;
        }
        if (recovery.has(first.getUniqueId()) || recovery.has(second.getUniqueId())) {
            Messages.send(first, "<#c42323>Сначала нужно завершить восстановление инвентаря.");
            return false;
        }
        Optional<Arena> available = config.arenas().stream().filter(arena -> !occupiedArenas.contains(arena.id())).findFirst();
        if (available.isEmpty()) {
            Messages.send(first, "<#ff9f0f>Сейчас нет свободной арены.");
            return false;
        }

        Arena arena = available.get();
        PlayerSnapshot firstSnapshot;
        PlayerSnapshot secondSnapshot;
        first.closeInventory();
        second.closeInventory();
        try {
            // Resolve both worlds before persisting or mutating either player.
            arena.firstLocation();
            arena.secondLocation();
            firstSnapshot = PlayerSnapshot.capture(first, config.mode(), System.currentTimeMillis());
            secondSnapshot = PlayerSnapshot.capture(second, config.mode(), System.currentTimeMillis());
            recovery.prepare(firstSnapshot);
            try {
                recovery.prepare(secondSnapshot);
            } catch (Exception exception) {
                recovery.acknowledge(first.getUniqueId());
                throw exception;
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not prepare safe duel snapshots", exception);
            Messages.send(first, "<#c42323>Не удалось безопасно сохранить инвентари. Матч не начат.");
            Messages.send(second, "<#c42323>Не удалось безопасно сохранить инвентари. Матч не начат.");
            return false;
        }

        DuelSession session = new DuelSession(first.getUniqueId(), second.getUniqueId(), arena, config.mode());
        sessions.put(first.getUniqueId(), session);
        sessions.put(second.getUniqueId(), session);
        occupiedArenas.add(arena.id());
        try {
            prepareParticipant(first, arena.firstLocation());
            prepareParticipant(second, arena.secondLocation());
            beginCountdown(session);
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not start prepared duel", exception);
            finish(session, null, null, EndReason.START_FAILURE);
            return false;
        }
    }

    public void defeat(Player loser, EndReason reason) {
        DuelSession session = sessions.get(loser.getUniqueId());
        if (session == null || session.state() == DuelSession.State.ENDING) return;
        UUID winnerId = session.opponent(loser.getUniqueId());
        finish(session, winnerId, loser.getUniqueId(), reason);
    }

    public void forfeit(Player player) {
        defeat(player, EndReason.FORFEIT);
    }

    public void onQuit(Player player) {
        DuelSession session = sessions.get(player.getUniqueId());
        if (session != null && session.state() != DuelSession.State.ENDING) {
            defeat(player, EndReason.FORFEIT);
        } else if (recovery.has(player.getUniqueId())) {
            restore(player);
        }
    }

    public boolean restorePending(Player player) {
        if (!recovery.has(player.getUniqueId())) return true;
        return restore(player);
    }

    public void shutdown() {
        Set<DuelSession> active = new HashSet<>(sessions.values());
        for (DuelSession session : active) finish(session, null, null, EndReason.SHUTDOWN);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (recovery.has(player.getUniqueId())) restore(player);
        }
    }

    public boolean isInDuel(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public boolean isActive(UUID playerId) {
        DuelSession session = sessions.get(playerId);
        return session != null && session.state() == DuelSession.State.ACTIVE;
    }

    public boolean isOpponent(UUID playerId, UUID otherId) {
        DuelSession session = sessions.get(playerId);
        return session != null && session.opponent(playerId).equals(otherId);
    }

    public boolean isInternalTeleport(UUID playerId) {
        return internalTeleports.contains(playerId);
    }

    public boolean isInsideArena(UUID playerId, Location location) {
        DuelSession session = sessions.get(playerId);
        return session == null || session.arena().contains(location);
    }

    public boolean isAllowedCommand(String commandLabel) {
        return config.allowedCommands().contains(commandLabel.toLowerCase(java.util.Locale.ROOT));
    }

    public Location consumeRespawnLocation(UUID playerId) {
        return pendingRespawnLocations.remove(playerId);
    }

    public DuelSession session(UUID playerId) {
        return sessions.get(playerId);
    }

    private void prepareParticipant(Player player, Location spawn) {
        player.leaveVehicle();
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(true);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setAbsorptionAmount(0.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setExhaustion(0.0f);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
        if (config.mode() == DuelMode.KIT) config.kit().apply(player.getInventory());
        teleport(player, spawn);
        player.updateInventory();
    }

    private void beginCountdown(DuelSession session) {
        int seconds = config.countdownSeconds();
        if (seconds == 0) {
            activate(session);
            return;
        }
        final int[] remaining = {seconds};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player first = Bukkit.getPlayer(session.first());
            Player second = Bukkit.getPlayer(session.second());
            if (first == null || second == null) {
                Player absent = first == null ? Bukkit.getOfflinePlayer(session.first()).getPlayer() : second;
                if (absent != null) defeat(absent, EndReason.FORFEIT);
                else finish(session, first == null ? session.second() : session.first(), first == null ? session.first() : session.second(), EndReason.FORFEIT);
                return;
            }
            if (remaining[0] <= 0) {
                activate(session);
                return;
            }
            Messages.send(first, "<#e6fff3>Матч начнётся через <#92bed8>" + remaining[0] + "<#e6fff3>.");
            Messages.send(second, "<#e6fff3>Матч начнётся через <#92bed8>" + remaining[0] + "<#e6fff3>.");
            remaining[0]--;
        }, 0L, 20L);
        session.countdownTask(task);
    }

    private void activate(DuelSession session) {
        if (session.state() == DuelSession.State.ENDING) return;
        session.cancelCountdown();
        session.state(DuelSession.State.ACTIVE);
        Player first = Bukkit.getPlayer(session.first());
        Player second = Bukkit.getPlayer(session.second());
        if (first != null) {
            first.setInvulnerable(false);
            Messages.send(first, "<#2bba43>Матч начался.");
        }
        if (second != null) {
            second.setInvulnerable(false);
            Messages.send(second, "<#2bba43>Матч начался.");
        }
    }

    private void finish(DuelSession session, UUID winnerId, UUID loserId, EndReason reason) {
        if (session.state() == DuelSession.State.ENDING) return;
        session.state(DuelSession.State.ENDING);
        session.cancelCountdown();

        PlayerSnapshot firstSnapshot = loadSnapshot(session.first());
        PlayerSnapshot secondSnapshot = loadSnapshot(session.second());
        if (firstSnapshot != null) pendingRespawnLocations.put(session.first(), safeLocation(firstSnapshot));
        if (secondSnapshot != null) pendingRespawnLocations.put(session.second(), safeLocation(secondSnapshot));

        Player first = Bukkit.getPlayer(session.first());
        Player second = Bukkit.getPlayer(session.second());
        if (first != null) restore(first);
        if (second != null) restore(second);

        sessions.remove(session.first());
        sessions.remove(session.second());
        occupiedArenas.remove(session.arena().id());

        if (winnerId != null && loserId != null && (reason == EndReason.DEFEAT || reason == EndReason.FORFEIT)) {
            stats.recordResult(winnerId, loserId, session.mode());
            Player winner = Bukkit.getPlayer(winnerId);
            Player loser = Bukkit.getPlayer(loserId);
            if (winner != null) Messages.send(winner, "<#2bba43>Вы победили в дуэли.");
            if (loser != null) Messages.send(loser, "<#c42323>Вы проиграли дуэль.");
        } else if (reason == EndReason.SHUTDOWN) {
            if (first != null) Messages.send(first, "<#ff9f0f>Матч остановлен: сервер выключается.");
            if (second != null) Messages.send(second, "<#ff9f0f>Матч остановлен: сервер выключается.");
        } else if (reason == EndReason.START_FAILURE) {
            if (first != null) Messages.send(first, "<#c42323>Матч отменён, инвентарь восстановлен.");
            if (second != null) Messages.send(second, "<#c42323>Матч отменён, инвентарь восстановлен.");
        }
    }

    private PlayerSnapshot loadSnapshot(UUID playerId) {
        try {
            return recovery.load(playerId).orElse(null);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not read recovery snapshot for " + playerId, exception);
            return null;
        }
    }

    private Location safeLocation(PlayerSnapshot snapshot) {
        try {
            return snapshot.location().resolve();
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean restore(Player player) {
        try {
            Optional<PlayerSnapshot> snapshot = recovery.load(player.getUniqueId());
            if (snapshot.isEmpty()) return true;
            internalTeleports.add(player.getUniqueId());
            try {
                snapshot.get().apply(player);
            } finally {
                internalTeleports.remove(player.getUniqueId());
            }
            playerDataSaver.save(player);
            recovery.acknowledge(player.getUniqueId());
            plugin.getLogger().info("Restored protected duel snapshot for " + player.getName());
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not restore protected duel snapshot for " + player.getUniqueId(), exception);
            Messages.send(player, "<#c42323>Не удалось восстановить инвентарь. Вход временно заблокирован; снимок сохранён.");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.kick(Component.text("Не удалось безопасно восстановить инвентарь. Обратитесь к администрации."));
            });
            return false;
        }
    }

    private void teleport(Player player, Location location) {
        internalTeleports.add(player.getUniqueId());
        try {
            if (!player.teleport(location)) throw new IllegalStateException("Teleport was rejected for " + player.getName());
        } finally {
            internalTeleports.remove(player.getUniqueId());
        }
    }
}
