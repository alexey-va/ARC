package ru.arc.hooks.citizens;

import ru.arc.ARC;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.HologramTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.warn;

@Slf4j
public class CitizensHook {

    private static CitizensListener listener;

    public void registerListeners() {
        if (listener != null) return;
        listener = new CitizensListener();
        Bukkit.getPluginManager().registerEvents(listener, ARC.plugin);
    }

    public int createNpc(String name, Location location) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
        //npc.setName(name);
        npc.spawn(location);
        return npc.getId();
    }

    public void deleteWithNames(Set<String> npcNames) {
        CitizensAPI.getNPCRegistry().forEach(npc -> {
            if (npcNames.contains(npc.getName())) {
                npc.destroy();
            }
        });
    }

    public record HologramLine(String text, int ticks) {
    }

    public record InsertedHologramLine(int line, long expireTime) {
    }

    Cache<Integer, ConcurrentLinkedDeque<InsertedHologramLine>> linesCache = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    public void addChatBubble(int id, List<HologramLine> lineList) {
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getById(id);
            if (npc == null) {
                warn("NPC {} is null", id);
                return;
            }
            HologramTrait trait = npc.getOrAddTrait(HologramTrait.class);
            var lineCache = linesCache.get(id, ConcurrentLinkedDeque::new);
            for (InsertedHologramLine line : lineCache.reversed()) {
                trait.removeLine(line.line);
            }
            lineCache.clear();

            AtomicInteger initialSize = new AtomicInteger(trait.getLines().size());
            lineList.reversed().forEach(line -> {
                trait.addTemporaryLine(line.text, line.ticks);
                lineCache.add(new InsertedHologramLine(initialSize.getAndIncrement(), System.currentTimeMillis() + line.ticks * 50L));
            });
        } catch (Exception e) {
            warn("Error adding hologram lines", e);
        }
    }

    public void lookClose(int id) {
        try {
            ARC.trySeverCommand("npc lookclose --id " + id);
        } catch (Exception e) {
            debug("Error looking close", e);
        }
    }

    public void setSkin(int id, String link) {
        ARC.trySeverCommand("npc skin --url " + link + " --id " + id);
    }

    public void deleteNpc(int id) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(id);
        if (npc == null) return;
        npc.destroy();
    }

    public void faceNpc(int id, Location location) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(id);
        if (npc == null) return;
        npc.faceLocation(location);
    }

    public void animateNpc(int id, Animation animation) {
        try {
            ARC.trySeverCommand("npc panimate " + animation.name() + " --id " + id);
        } catch (Exception e) {
            debug("Error animating npc", e);
        }
    }
    @SuppressWarnings("deprecation")
    public void setMainHand(int id, ItemStack stack) {
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getById(id);
            if (npc == null) return;
            npc.getTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, stack);
        } catch (Exception e) {
            debug("Error setting main hand", e);
        }
    }

    public enum Animation {
        ARM_SWING, SIT, STOP_SITTING
    }
}
