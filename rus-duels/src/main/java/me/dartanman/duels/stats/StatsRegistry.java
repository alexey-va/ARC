package me.dartanman.duels.stats;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.dartanman.duels.model.DuelMode;
import me.dartanman.duels.model.DuelStats;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class StatsRegistry {
    private static final int ELO_K = 32;

    private final Path file;
    private final Logger logger;
    private final ArcStatsNotifier arcNotifier;
    private final Map<UUID, DuelStats> stats = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RusDuels-stats-writer");
        thread.setDaemon(true);
        return thread;
    });

    public StatsRegistry(Path file, Logger logger, ArcStatsNotifier arcNotifier) {
        this.file = file.toAbsolutePath().normalize();
        this.logger = logger;
        this.arcNotifier = arcNotifier;
    }

    public synchronized void load() {
        stats.clear();
        if (!Files.isRegularFile(file)) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String rawId : players.getKeys(false)) {
            try {
                UUID id = UUID.fromString(rawId);
                ConfigurationSection section = players.getConfigurationSection(rawId);
                if (section != null) stats.put(id, DuelStats.fromMap(section.getValues(false)));
            } catch (IllegalArgumentException exception) {
                logger.warning("Ignoring invalid duel stats entry: " + rawId);
            }
        }
    }

    public synchronized DuelStats get(UUID playerId) {
        return stats.getOrDefault(playerId, DuelStats.empty());
    }

    public synchronized Map<String, Object> export(UUID playerId) {
        return get(playerId).toMap();
    }

    public synchronized void applyRemote(UUID playerId, Map<?, ?> values) {
        DuelStats incoming = DuelStats.fromMap(values);
        DuelStats current = get(playerId);
        if (incoming.updatedAt() < current.updatedAt()) return;
        stats.put(playerId, incoming);
        queueSave(snapshot());
    }

    public void recordResult(UUID winnerId, UUID loserId, DuelMode mode) {
        Map<UUID, DuelStats> persisted;
        synchronized (this) {
            DuelStats winner = get(winnerId);
            DuelStats loser = get(loserId);
            double expectedWinner = 1.0 / (1.0 + Math.pow(10.0, (loser.rating() - winner.rating()) / 400.0));
            int delta = Math.max(1, (int) Math.round(ELO_K * (1.0 - expectedWinner)));
            long now = System.currentTimeMillis();
            stats.put(winnerId, winner.win(mode, winner.rating() + delta, now));
            stats.put(loserId, loser.loss(mode, Math.max(0, loser.rating() - delta), now));
            persisted = snapshot();
        }
        queueSave(persisted);
        arcNotifier.statsChanged(winnerId);
        arcNotifier.statsChanged(loserId);
    }

    public void close(Duration timeout) {
        queueSave(snapshot());
        writer.shutdown();
        try {
            if (!writer.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                logger.severe("Timed out while flushing duel statistics");
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    private synchronized Map<UUID, DuelStats> snapshot() {
        return Map.copyOf(stats);
    }

    private void queueSave(Map<UUID, DuelStats> snapshot) {
        if (writer.isShutdown()) return;
        writer.execute(() -> {
            try {
                write(snapshot);
            } catch (Exception exception) {
                logger.log(Level.SEVERE, "Could not persist duel statistics", exception);
            }
        });
    }

    private void write(Map<UUID, DuelStats> snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        snapshot.forEach((id, value) -> yaml.createSection("players." + id, value.toMap()));
        byte[] bytes = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
