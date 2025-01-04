package arc.arc.common.locationpools;

import arc.arc.common.ServerLocation;
import arc.arc.common.WeightedRandom;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Location;

import java.util.*;
import java.util.stream.Collectors;

@NoArgsConstructor
@Data
public class LocationPool {

    String id;
    WeightedRandom<ServerLocation> locations = new WeightedRandom<>();
    transient boolean dirty = false;

    public LocationPool(String id) {
        this.id = id;
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
