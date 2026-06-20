package ru.arc.hooks.zauction;

import java.util.ArrayList;
import java.util.List;

import fr.maxlego08.zauctionhouse.api.AuctionManager;
import fr.maxlego08.zauctionhouse.api.AuctionPlugin;
import fr.maxlego08.zauctionhouse.api.category.CategoryManager;
import fr.maxlego08.zauctionhouse.api.item.Item;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ru.arc.ARC;
import ru.arc.configs.AuctionConfig;
import ru.arc.hooks.HookRegistry;
import ru.arc.util.TextUtil;

import static ru.arc.util.Logging.info;

public class AuctionHook {

    @Setter
    public AuctionMessager auctionMessager;
    CategoryManager categoryManager;
    AuctionManager auctionManager;

    AuctionListener auctionListener = null;

    BukkitTask broadcastItemsTask;

    public AuctionHook() {
        resolveApi();

        if (auctionManager == null || categoryManager == null) {
            info("zAuctionHouse API providers not available yet");
            return;
        }

        if (auctionListener == null) {
            auctionListener = new AuctionListener();
            Bukkit.getPluginManager().registerEvents(auctionListener, ARC.getInstance());
        }

        startTasks();
        info("zAuctionHouse hook initialized");
    }

    public void cancelTasks() {
        if (broadcastItemsTask != null && !broadcastItemsTask.isCancelled()) {
            broadcastItemsTask.cancel();
        }
    }

    public void startTasks() {
        cancelTasks();
        if (auctionManager == null) {
            return;
        }
        broadcastItemsTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!AuctionConfig.broadcastItems) {
                    return;
                }
                if (auctionMessager == null) {
                    return;
                }
                auctionMessager.send(getAuctionItems());
            }
        }.runTaskTimerAsynchronously(ARC.getInstance(), AuctionConfig.refreshRate, AuctionConfig.refreshRate);
    }

    private List<AuctionItemDto> getAuctionItems() {
        return auctionManager.getItems(StorageType.LISTED).stream()
                .filter(item -> !item.isExpired())
                .filter(this::matchesConfiguredCategory)
                .map(item -> fromAuctionItem(resolveCategory(item), item))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private boolean matchesConfiguredCategory(Item item) {
        return AuctionConfig.categories.stream().anyMatch(item::hasCategory);
    }

    private String resolveCategory(Item item) {
        return AuctionConfig.categories.stream()
                .filter(item::hasCategory)
                .findFirst()
                .orElse("misc");
    }

    private AuctionItemDto fromAuctionItem(String category, Item item) {
        if (item.isExpired()) {
            return null;
        }

        String display = item.getItemDisplay();
        if (display == null || display.isBlank()) {
            ItemStack stack = item.buildItemStack(null);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                Component name = meta.displayName();
                if (name instanceof TextComponent textComponent) {
                    display = PlainTextComponentSerializer.plainText().serialize(textComponent);
                }
            }
            if (display == null || display.isBlank()) {
                if (HookRegistry.translatorHook != null) {
                    display = HookRegistry.translatorHook.translate(stack);
                } else {
                    display = stack.getType().name().replace("_", "").toLowerCase();
                }
            }
        }

        List<String> lore = new ArrayList<>();
        ItemStack stack = item.buildItemStack(null);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<Component> loreComponents = meta.lore();
            if (loreComponents != null) {
                lore = loreComponents.stream()
                        .filter(TextComponent.class::isInstance)
                        .map(line -> ((TextComponent) line).content())
                        .toList();
            }
        }

        return new AuctionItemDto(
                display,
                item.getSellerName(),
                TextUtil.formatAmount(item.getPrice().doubleValue()),
                item.getExpiredAt().getTime(),
                category,
                item.getAmount(),
                0,
                String.valueOf(item.getId()),
                true,
                lore
        );
    }

    private void resolveApi() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("zAuctionHouse");
        if (plugin == null) {
            plugin = Bukkit.getPluginManager().getPlugin("zAuctionHouseV3");
        }
        if (plugin instanceof AuctionPlugin auctionPlugin) {
            auctionManager = auctionPlugin.getAuctionManager();
            categoryManager = auctionPlugin.getCategoryManager();
            return;
        }
        auctionManager = getProvider(AuctionManager.class);
        categoryManager = getProvider(CategoryManager.class);
    }

    private <T> T getProvider(Class<T> classz) {
        RegisteredServiceProvider<T> provider =
                ARC.getInstance().getServer().getServicesManager().getRegistration(classz);
        if (provider == null) {
            return null;
        }
        return provider.getProvider();
    }

}
