package arc.arc.hooks.citizens;

import arc.arc.ARC;
import lombok.extern.slf4j.Slf4j;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

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

    public void lookClose(int id) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc lookclose --id " + id);
        } catch (Exception e) {
            log.debug("Error looking close", e);
        }
    }

    public void setSkin(int id, String link) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc skin --url " + link + " --id " + id);
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
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc panimate " + animation.name() + " --id " + id);
        } catch (Exception e) {
            log.debug("Error animating npc", e);
        }
    }

    public void setMainHand(int id, ItemStack stack) {
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getById(id);
            if (npc == null) return;
            npc.getTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, stack);
        } catch (Exception e) {
            log.debug("Error setting main hand", e);
        }
    }

    public enum Animation {
        ARM_SWING, SIT, STOP_SITTING
    }
}
