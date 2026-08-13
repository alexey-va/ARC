package me.dartanman.duels.recovery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class RecoveryStore {
    private final Path directory;

    public RecoveryStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public synchronized void prepare(PlayerSnapshot snapshot) throws IOException {
        Files.createDirectories(directory);
        Path target = path(snapshot.playerId());
        if (Files.exists(target)) {
            throw new IllegalStateException("Unresolved recovery snapshot already exists for " + snapshot.playerId());
        }
        Path temporary = directory.resolve(snapshot.playerId() + ".yml.tmp");
        byte[] bytes = snapshot.toYaml().getBytes(StandardCharsets.UTF_8);
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
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
            forceDirectory();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized Optional<PlayerSnapshot> load(UUID playerId) throws IOException {
        Path path = path(playerId);
        if (!Files.isRegularFile(path)) return Optional.empty();
        String text = Files.readString(path, StandardCharsets.UTF_8);
        PlayerSnapshot snapshot = PlayerSnapshot.fromYaml(text);
        if (!snapshot.playerId().equals(playerId)) {
            throw new IllegalStateException("Recovery filename and payload UUID differ for " + playerId);
        }
        return Optional.of(snapshot);
    }

    public synchronized boolean has(UUID playerId) {
        return Files.isRegularFile(path(playerId));
    }

    public synchronized void acknowledge(UUID playerId) throws IOException {
        Files.deleteIfExists(path(playerId));
        forceDirectory();
    }

    public synchronized List<UUID> pendingPlayers() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        List<UUID> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.yml")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                try {
                    result.add(UUID.fromString(name.substring(0, name.length() - 4)));
                } catch (IllegalArgumentException ignored) {
                    // Ignore foreign files; recovery owns only canonical UUID.yml names.
                }
            }
        }
        return List.copyOf(result);
    }

    private Path path(UUID playerId) {
        return directory.resolve(playerId + ".yml");
    }

    private void forceDirectory() {
        if (!Files.isDirectory(directory)) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // The file itself is already fsynced; not every filesystem permits directory fsync.
        }
    }
}
