package ru.arc.hooks.slimefun;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.sync.SyncManager;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import lombok.extern.log4j.Log4j2;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

import static ru.arc.util.TextUtil.mm;

@Log4j2
public class SFHook implements Listener {

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "backpacks.yml");

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseBackpack(PlayerRightClickEvent event) {
        if (checkForOthersPlayersBackpackUse(event)) return;
        if (checkForBackpack(event)) return;
        SyncManager.processEvent(event);
    }

    public boolean isSlimefunBlock(Block block) {
        return Slimefun.getBlockDataService().getBlockData(block).isPresent();
    }

    public boolean isSlimefunItem(ItemStack stack) {
        return SlimefunItem.getByItem(stack) != null;
    }

    public ItemStack getSlimefunItemStack(String id) {
        SlimefunItem byId = SlimefunItem.getById(id);
        if (byId == null) return null;
        return byId.getItem();
    }


    public boolean checkForOthersPlayersBackpackUse(PlayerRightClickEvent event) {
        if (event.getPlayer().getInventory().getItemInMainHand().getType() == Material.AIR) return false;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        SlimefunItem sfItem = SlimefunItem.getByItem(item);
        if (sfItem == null) return false;
        if (sfItem.getId().contains("BACKPACK")) {
            List<Component> lore = item.getItemMeta().lore();
            if (lore == null || lore.isEmpty()) return false;
            for (var comp : lore) {
                if (comp == null) continue;
                String string = PlainTextComponentSerializer.plainText().serialize(comp);
                if (string.contains("ID")) {
                    String[] split = string.split(" ");
                    if (split.length < 2) return false;
                    String id = split[1];
                    String uuidString = id.split("#")[0];
                    if (uuidString.length() != 36) return false;
                    UUID uuid = UUID.fromString(uuidString);
                    if (uuid.equals(event.getPlayer().getUniqueId())) return false;
                    event.cancel();

                    event.getPlayer().sendMessage(mm(
                            config.string("backpacks.use-other-player", "<dark_red>Вы не можете использовать чужие рюкзаки!")
                    ));
                    return true;
                }
            }
        }
        return false;
    }

    public boolean checkForBackpack(PlayerRightClickEvent event) {
        if (event.getPlayer().getInventory().getItemInMainHand().getType() == Material.AIR) return false;
        if (!config.bool("backpack-disabled", false)) return false;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        SlimefunItem sfItem = SlimefunItem.getByItem(item);
        if (sfItem == null) return false;
        if (sfItem.getId().contains("BACKPACK")) {
            event.getPlayer().sendMessage(config.componentDef("backpacks.forbidden", "<dark_red>Вы не можете использовать рюкзак здесь!"));
            event.cancel();
            return true;
        }
        return false;
    }

}
