package arc.arc.network.entries;

import arc.arc.Config;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public class CrossServerLocation {

    private String server, world;
    private double x,y,z;
    private float yaw,pitch;

    public CrossServerLocation(String server, String world, double x, double y, double z, float yaw, float pitch) {

        this.server = server;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public CrossServerLocation(String serialisedLocation){
        String[] strings = serialisedLocation.split("<###>");
        if(strings.length != 7) {
            System.out.print("ERROR LOCATION: "+serialisedLocation);
            return;
        }

        this.server=strings[0];
        this.world=strings[1];
        this.x=Integer.parseInt(strings[2]);
        this.y=Integer.parseInt(strings[3]);
        this.z=Integer.parseInt(strings[4]);
        this.yaw=Float.parseFloat(strings[5]);
        this.pitch=Float.parseFloat(strings[6]);
    }

    public boolean onThisServer(){
        return Config.server.equals(server);
    }

    public Location toLocation(){
        if(!onThisServer()) return null;
        World world1 = Bukkit.getWorld(world);
        if(world1 == null){
            System.out.print("ERROR: World "+world+" is not loaded!");
            return null;
        }
        return new Location(world1, x,y,z,yaw,pitch);
    }

    public String serialise(){
        return server + "<###>" +
                world + "<###>" +
                x + "<###>" +
                y + "<###>" +
                z + "<###>" +
                yaw + "<###>" +
                pitch;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public String getWorld() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world;
    }
}
