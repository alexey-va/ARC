package ru.arc.util;

import ru.arc.ARC;
import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static ru.arc.util.Logging.error;
import static ru.arc.util.TextUtil.strip;

@Slf4j
public class GuiUtils {

    private static Map<BgKey, GuiItem> backgrounds = new HashMap<>();

    record BgKey(Material material, int model) {
    }

    public static GuiItem background(Material material, int model) {
        GuiItem guiItem = backgrounds.get(new BgKey(material, model));
        if (guiItem != null) return guiItem;

        ItemStack bgItem = new ItemStack(material);
        ItemMeta meta = bgItem.getItemMeta();
        if (model != 0) meta.setCustomModelData(model);
        meta.displayName(Component.text(" "));
        bgItem.setItemMeta(meta);
        guiItem = new GuiItem(bgItem, inventoryClickEvent -> inventoryClickEvent.setCancelled(true));
        backgrounds.put(new BgKey(material, model), guiItem);

        return guiItem;
    }

    public static BukkitTask temporaryChange(ItemStack stack, Component display, List<Component> lore, long ticks, Runnable callback, TagResolver resolver) {
        ItemMeta meta = stack.getItemMeta();
        final Component oldDisplay = meta.displayName();
        final List<Component> oldLore = meta.lore();
        if (display != null) meta.displayName(strip(display));
        if (lore != null && !lore.isEmpty()) meta.lore(lore.stream().map(TextUtil::strip).toList());
        stack.setItemMeta(meta);
        if (ticks < 0) return null;
        return new BukkitRunnable() {
            @Override
            public void run() {
                ItemMeta meta1 = stack.getItemMeta();
                meta1.displayName(strip(oldDisplay));
                if (oldLore != null) meta1.lore(oldLore.stream().map(TextUtil::strip).toList());
                else meta1.lore(null);
                stack.setItemMeta(meta1);
                callback.run();
            }
        }.runTaskLater(ARC.plugin, ticks);
    }

    public static BukkitTask temporaryChange(ItemStack stack, Component display, List<Component> lore, long ticks, Runnable callback) {
        return temporaryChange(stack, display, lore, ticks, callback, null);
    }

    public static boolean cooldownCheck(GuiItem guiItem, UUID playerUuid, ChestGui chestGui) {
        long cooldown = CooldownManager.cooldown(playerUuid, "gui_click");
        if (cooldown > 0) {
            temporaryChange(guiItem.getItem(),
                    strip(Component.text("Не кликайте так быстро!", NamedTextColor.RED)),
                    null, cooldown, () -> {
                        if (chestGui != null) chestGui.update();
                    }
            );
            return false;
        }
        CooldownManager.addCooldown(playerUuid, "gui_click", 10);
        return true;
    }

    public static void constructAndShowAsync(Supplier<ChestGui> supplier, HumanEntity player, int delay) {
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return supplier.get();
                    } catch (Exception e) {
                        error("Error opening menu", e);
                        return null;
                    }
                })
                .thenAccept(gui -> new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (gui == null) {
                            error("Gui is null {}", supplier);
                            return;
                        }
                        gui.show(player);
                    }
                }.runTaskLater(ARC.plugin, delay));
    }

    public static void constructAndShowAsync(Supplier<ChestGui> supplier, HumanEntity player) {
        constructAndShowAsync(supplier, player, 3);
    }

    public static GuiItem background(Material material) {
        return background(material, 0);
    }

    public static GuiItem background() {
        return background(Material.GRAY_STAINED_GLASS_PANE, 11000);
    }


}
