package arc.arc.hooks.packetevents;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

public record BlockDisplayReq(Location location, BlockData data) {
}
