package arc.arc.network;

import arc.arc.configs.MainConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class ServerLocation {

    String server, world;
    double x,y,z;
    float yaw,pitch;

    public static ServerLocation of(Location loc) {
        return new ServerLocation(MainConfig.server, loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    public Location toLocation(){
        World world1 = Bukkit.getWorld(this.world);
        if(world1 == null) return null;
        return new Location(world1, x, y, z);
    }

    public boolean onThisServer(){
        return server.equals(MainConfig.server);
    }

}
