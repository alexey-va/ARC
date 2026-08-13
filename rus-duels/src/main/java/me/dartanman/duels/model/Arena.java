package me.dartanman.duels.model;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record Arena(String id, Point first, Point second, double radius) {
    public Arena {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (id.isBlank()) throw new IllegalArgumentException("Arena id must not be blank");
        if (radius <= 0.0) throw new IllegalArgumentException("Arena radius must be positive");
        if (!first.world().equals(second.world())) {
            throw new IllegalArgumentException("Arena positions must be in the same world");
        }
    }

    public Location firstLocation() {
        return first.toLocation();
    }

    public Location secondLocation() {
        return second.toLocation();
    }

    public boolean contains(Location location) {
        if (location == null || !location.getWorld().getName().equals(first.world())) return false;
        double centerX = (first.x() + second.x()) / 2.0;
        double centerY = (first.y() + second.y()) / 2.0;
        double centerZ = (first.z() + second.z()) / 2.0;
        double dx = location.getX() - centerX;
        double dy = location.getY() - centerY;
        double dz = location.getZ() - centerZ;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    public record Point(String world, double x, double y, double z, float yaw, float pitch) {
        public Point {
            Objects.requireNonNull(world, "world");
            if (world.isBlank()) throw new IllegalArgumentException("World must not be blank");
        }

        public Location toLocation() {
            World resolved = Bukkit.getWorld(world);
            if (resolved == null) throw new IllegalStateException("World is not loaded: " + world);
            return new Location(resolved, x, y, z, yaw, pitch);
        }
    }
}
