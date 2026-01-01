package ru.arc.common.locationpools;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Location;
import ru.arc.common.ServerLocation;
import ru.arc.common.WeightedRandom;

@NoArgsConstructor
@Data
public class LocationPool {

    private String id;
    private WeightedRandom<ServerLocation> locations = new WeightedRandom<>();
    private transient boolean dirty = false;

    public LocationPool(String id) {
        this.id = id;
    }

    /**
     * Public getter for Kotlin interop (Lombok getters are not visible to Kotlin).
     */
    public String getId() {
        return id;
    }

    /**
     * Public getter for Kotlin interop (Lombok getters are not visible to Kotlin).
     */
    public WeightedRandom<ServerLocation> getLocations() {
        return locations;
    }

    public void addLocation(Location location, double weight) {
        ServerLocation serverLocation = ServerLocation.of(location);
        locations.add(serverLocation, weight);
        dirty = true;
    }

    public void addLocation(ServerLocation location, double weight) {
        locations.add(location, weight);
        dirty = true;
    }

    public Set<ServerLocation> getNRandom(int n) {
        return new HashSet<>(locations.getNRandom(n));
    }

    public Set<ServerLocation> nearbyLocations(Location location, double distance) {
        return locations.values().stream()
                .filter(sl -> sl.distance(location).orElse(Double.MAX_VALUE) <= distance)
                .collect(Collectors.toSet());

    }

    public boolean removeLocation(ServerLocation location) {
        return locations.remove(location);
    }

    public boolean removeLocation(Location location) {
        ServerLocation serverLocation = ServerLocation.of(location);
        return locations.remove(serverLocation);
    }
}
