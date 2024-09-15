package arc.arc.autobuild;

import arc.arc.ARC;
import arc.arc.hooks.HookRegistry;
import arc.arc.util.CooldownManager;
import arc.arc.util.TextUtil;
import arc.arc.util.Utils;
import com.sk89q.worldedit.math.BlockVector3;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@RequiredArgsConstructor
@Getter
public class ConstructionSite {

    final Building building;
    final Location centerBlock;
    final Player player;
    final int rotation;
    final World world;
    final int subRotation;
    final int yOffset;
    State state = State.CREATED;
    Set<Chunk> chunks = new HashSet<>();
    long timestamp = System.currentTimeMillis();

    Display display;
    Construction construction;

    int npcId = -1;

    Color[] colors = new Color[]{Color.WHITE, Color.AQUA, Color.PURPLE, Color.YELLOW, Color.MAROON, Color.GREEN, Color.TEAL,
            Color.OLIVE, Color.FUCHSIA, Color.LIME, Color.RED, Color.ORANGE};

    public void startDisplayingBorder(int seconds) {
        if (display != null) throw new IllegalStateException("Display is not null when trying to build!");

        display = new Display(this);
        display.showBorder(seconds);
        state = State.DISPLAYING_OUTLINE;
    }

    public void startDisplayingBorderAndDisplay(int seconds) {
        if (display == null || state != State.DISPLAYING_OUTLINE)
            throw new IllegalStateException("Display is null or state is not outline!");

        display.showBorderAndDisplay(seconds);
    }

    public Location getCenterBlock() {
        return centerBlock.clone().add(0, yOffset, 0);
    }

    List<Location> getCenterLocations(){
        Location center1 = this.centerBlock.toBlockLocation().clone().add(-0.05,-1.05, -0.05);
        Location center2 = center1.clone().add(1.1,1.1, 1.1);
        return Utils.getBorderLocations(center1, center2, 6);
    }

    List<Utils.LocationData> getBorderLocations() {
        ConstructionSite.Corners corners = this.getCorners();

        Location corner1 = new Location(this.getWorld(),
                corners.corner1().x() + this.getCenterBlock().x(),
                corners.corner1().y() + this.getCenterBlock().y(),
                corners.corner1().z() + this.getCenterBlock().z());

        Location corner2 = new Location(this.getWorld(),
                corners.corner2().x() + this.getCenterBlock().x() + 1,
                corners.corner2().y() + this.getCenterBlock().y() + 1,
                corners.corner2().z() + this.getCenterBlock().z() + 1);

        return Utils.getBorderLocationsWithCornerData(corner1, corner2, 2, 3);
    }

    public void spawnConfirmNpc(int seconds) {
        if (construction != null) throw new IllegalStateException("Construction is not null when creating NPC!");
        this.timestamp = System.currentTimeMillis();
        construction = new Construction(this);
        npcId = construction.createNpc(centerBlock, seconds);
        state = State.CONFIRMATION;
    }

    public void startBuild() {
        if (construction == null) throw new IllegalStateException("Construction is null when trying to build!");
        display.stop();
        forceloadChunks();
        construction.startBuilding();
        if (!player.hasPermission("arc.buildings.bypass-cooldown")) {
            CooldownManager.addCooldown(player.getUniqueId(), "building_cooldown", 20 * 60 * 60);
        }
        state = State.BUILDING;
    }

    public void stopBuild() {
        if (construction != null) {
            construction.cancel(0);
            state = State.CANCELLED;

            cleanup(0);
        } else {
            throw new IllegalStateException("Stoping build when not building!");
        }
    }

    public void stopOutlineDisplay() {
        if (display != null && state == State.DISPLAYING_OUTLINE) {
            display.stop();
            state = State.CREATED;
        } else {
            throw new IllegalStateException("Stoping display when not displaying!");
        }
    }

    public void stopConfirmStep() {
        if (display != null && construction != null && state == State.CONFIRMATION) {
            display.stop();
            construction.destroyNpc();
            state = State.CREATED;
        } else {
            throw new IllegalStateException("Stoping confirm when not confirming!");
        }
    }

    public void calculateChunks() {
        if (!chunks.isEmpty()) return;
        Corners corners = getCorners();
        for (int x = corners.corner1.x(); x < corners.corner2.x(); x++) {
            for (int z = corners.corner1.z(); z < corners.corner2.z(); z++) {
                Location location = new Location(world, x + centerBlock.x(), 1, z + centerBlock.z());
                Chunk chunk = location.getChunk();
                chunks.add(chunk);
            }
        }
    }

    public boolean canBuild() {
        calculateChunks();
        if (HookRegistry.landsHook != null) {
            for (Chunk chunk : chunks) {
                if (!HookRegistry.landsHook.canBuild(player, chunk)) return false;
            }
        }
        if (HookRegistry.wgHook != null) {
            Corners corners = getCorners();
            for (int x = corners.corner1.x(); x < corners.corner2.x(); x++) {
                for (int y = corners.corner1.y(); y < corners.corner2.y(); y++) {
                    for (int z = corners.corner1.z(); z < corners.corner2.z(); z++) {
                        if (!HookRegistry.wgHook.canBuild(player, new Location(world, x, y, z))) return false;
                    }
                }
            }
        }
        return true;
    }

    public void forceloadChunks() {
        calculateChunks();
        for (Chunk chunk : chunks) {
            chunk.setForceLoaded(true);
        }
    }

    public void stopForceload() {
        chunks.stream().filter(Chunk::isForceLoaded).forEach(c -> c.setForceLoaded(false));
    }

    public void finishBuildState() {
        construction.finishInstantly();
        stopBuild();
    }

    public double getProgress() {
        if (state != State.BUILDING) return 0;
        int built = construction.pointer.get();
        return ((double) built) / building.volume();
    }


    public record Corners(BlockVector3 corner1, BlockVector3 corner2) {
    }

    public boolean same(Player player, Location location, Building building) {
        return location.toCenterLocation().equals(centerBlock.toCenterLocation()) && building == this.building &&
                rotation == BuildingManager.rotationFromYaw(player.getYaw());
    }


    public Corners getCorners() {
        BlockVector3 c1 = building.getCorner1((rotation+subRotation)%360);
        BlockVector3 c2 = building.getCorner2((rotation+subRotation)%360);

        BlockVector3 r1 = BlockVector3.at(Math.min(c1.x(), c2.x()),
                Math.min(c1.y(), c2.y()),
                Math.min(c1.z(), c2.z()));
        BlockVector3 r2 = BlockVector3.at(Math.max(c1.x(), c2.x()),
                Math.max(c1.y(), c2.y()),
                Math.max(c1.z(), c2.z()));
        r1 = r1.add(0, yOffset, 0);
        r2 = r2.add(0, yOffset, 0);
        return new Corners(r1, r2);
    }


    public void finalizeBuilding() {
        sendFinalMessage();
        launchFireWorks();
        Bukkit.getScheduler().runTaskLater(ARC.plugin, construction::destroyNpc, 60);
        state = State.DONE;

        cleanup(60);
    }

    private void sendFinalMessage() {
        player.sendMessage(TextUtil.strip(
                Component.text("\uD83D\uDEE0 ", NamedTextColor.GRAY)
                        .append(Component.text("Строительство завершено!", NamedTextColor.DARK_GREEN))
        ));
    }

    private void launchFireWorks() {
        try {
            AtomicInteger counter = new AtomicInteger(0);
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (counter.incrementAndGet() >= 5) this.cancel();
                    Firework firework = (Firework) world.spawnEntity(centerBlock, EntityType.FIREWORK_ROCKET);
                    FireworkEffect effect = FireworkEffect.builder()
                            .flicker(ThreadLocalRandom.current().nextBoolean())
                            .trail(ThreadLocalRandom.current().nextBoolean())
                            .with(Utils.random(FireworkEffect.Type.values()))
                            .withColor(Utils.random(colors, 3))
                            .withFade(Utils.random(colors, 3))
                            .build();

                    FireworkMeta meta = firework.getFireworkMeta();
                    meta.setPower(ThreadLocalRandom.current().nextInt(1, 3));
                    meta.addEffect(effect);
                    firework.setFireworkMeta(meta);
                    Bukkit.getScheduler().runTaskLater(ARC.plugin, firework::detonate, 20);
                }
            }.runTaskTimer(ARC.plugin, 0L, 10L);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void cleanup(int destroyNpcDelaySeconds) {
        BuildingManager.removeConstruction(player.getUniqueId());
        stopForceload();
        if (display != null){
            display.stop();
        }
        if (construction != null) construction.cancel(destroyNpcDelaySeconds);
    }

    public enum State {
        CREATED, DISPLAYING_OUTLINE, CONFIRMATION, BUILDING, CANCELLED, DONE
    }

}
