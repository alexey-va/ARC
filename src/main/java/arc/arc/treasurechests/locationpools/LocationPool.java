package arc.arc.treasurechests.locationpools;

import arc.arc.network.ServerLocation;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Location;

import java.util.*;
import java.util.stream.Collectors;

@NoArgsConstructor
@Data
public class LocationPool {

    String id;
    Set<ServerLocation> locations = new HashSet<>();
    boolean dirty=false;

    public LocationPool(String id) {
        this.id = id;
    }

    public void addLocation(Location location){
        ServerLocation serverLocation = ServerLocation.of(location);
        locations.add(serverLocation);
        dirty = true;
    }

    public Set<Location> getNRandom(int n){
        if(n >= locations.size()) {
            if(n > locations.size())System.out.println("Location pool is smaller than "+n);
            return locations.stream().map(ServerLocation::toLocation).collect(Collectors.toSet());
        }

        List<ServerLocation> copyList = new ArrayList<>(locations);
        Collections.shuffle(copyList);
        return copyList.stream().map(ServerLocation::toLocation).limit(n).collect(Collectors.toSet());
    }

    public List<Location> nearbyLocations(Location location){
        return locations.stream()
                .map(ServerLocation::toLocation)
                .filter(l->l.distanceSquared(location) < 1000)
                .collect(Collectors.toList());
    }


    public boolean removeLocation(Location location) {
        boolean res = locations.removeIf(sl -> sl.toLocation().toCenterLocation().distanceSquared(location.toCenterLocation()) < 0.01);
        if(res) setDirty(true);
        return res;
    }
}
