package ru.arc.common;

import ru.arc.ARC;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Optional;

@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class ServerLocation {

    String server, world;
    double x,y,z;
    float yaw,pitch;

    public static ServerLocation of(Location loc) {
        return new ServerLocation(ARC.serverName, loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    public Location toLocation(){
        World world1 = Bukkit.getWorld(this.world);
        if(world1 == null) return null;
        return new Location(world1, x, y, z);
    }

    public Optional<Double> distance(Location location){
        if(!ARC.serverName.equals(server)) return Optional.empty();
        if(!world.equals(location.getWorld().getName())) return Optional.empty();
        double distance = Math.pow(location.x() - x, 2) + Math.pow(location.y() - y, 2) + Math.pow(location.z() - z, 2);
        return Optional.of(Math.sqrt(distance));
    }

    public boolean isSameServer(){
        return server.equals(ARC.serverName);
    }

}
