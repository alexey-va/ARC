package arc.arc.hooks.zauction;

import arc.arc.ARC;
import arc.arc.configs.AuctionConfig;
import arc.arc.hooks.HookRegistry;
import arc.arc.util.TextUtil;
import fr.maxlego08.zauctionhouse.api.AuctionItem;
import fr.maxlego08.zauctionhouse.api.AuctionManager;
import fr.maxlego08.zauctionhouse.api.ConvertManager;
import fr.maxlego08.zauctionhouse.api.blacklist.IBlacklist;
import fr.maxlego08.zauctionhouse.api.blacklist.IBlacklistManager;
import fr.maxlego08.zauctionhouse.api.category.Category;
import fr.maxlego08.zauctionhouse.api.category.CategoryManager;
import fr.maxlego08.zauctionhouse.api.filter.FilterManager;
import fr.maxlego08.zauctionhouse.api.inventory.InventoryManager;
import fr.maxlego08.zauctionhouse.api.transaction.TransactionManager;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Log4j2
public class AuctionHook {

    @Setter
    AuctionMessager auctionMessager;
    CategoryManager categoryManager;
    AuctionManager auctionManager;
    IBlacklistManager iBlacklistManager;

    AuctionListener auctionListener = null;

    BukkitTask broadcastItemsTask;

    public AuctionHook(){
        auctionManager = getProvider(AuctionManager.class);
        InventoryManager inventoryManager = getProvider(InventoryManager.class);
        categoryManager = getProvider(CategoryManager.class);
        TransactionManager transactionManager = getProvider(TransactionManager.class);
        iBlacklistManager = getProvider(IBlacklistManager.class);

        if(iBlacklistManager != null){
            iBlacklistManager.registerBlacklist(emBlackList());
            log.info("Registered soulbind blacklist");
        } else{
            log.warn("Black list manager was not found!");
        }

        if(auctionListener == null){
            auctionListener = new AuctionListener();
            Bukkit.getPluginManager().registerEvents(auctionListener, ARC.plugin);
        }

        startTasks();
    }

    public void cancelTasks(){
        if(broadcastItemsTask != null && !broadcastItemsTask.isCancelled()) broadcastItemsTask.cancel();
    }

    public void startTasks(){
        cancelTasks();
        broadcastItemsTask = new BukkitRunnable() {
            @Override
            public void run() {
                if(!AuctionConfig.broadcastItems) return;
                if(auctionMessager == null) return;
                auctionMessager.send(getAuctionItems());
            }
        }.runTaskTimerAsynchronously(ARC.plugin, AuctionConfig.refreshRate, AuctionConfig.refreshRate);
    }

    private List<AuctionItemDto> getAuctionItems(){
        record Pair(String category, AuctionItem item){}
        return AuctionConfig.categories.stream()
                .flatMap(s -> categoryManager.getByName(s).stream())
                .flatMap(c -> auctionManager.getItems(c).stream().map(i -> new Pair(c.getDisplayName(), i)))
                .map(pair -> fromAuctionItem(pair.category, pair.item))
                .toList();
    }

    private AuctionItemDto fromAuctionItem(String category, AuctionItem item){
        if(item.isExpired()) return null;

        ItemStack stack = item.getItemStack();
        ItemMeta meta = stack.getItemMeta();
        TextComponent displayComponent = (TextComponent)meta.displayName();
        String display;
        if(displayComponent != null) display = PlainTextComponentSerializer.plainText().serialize(displayComponent);
        else{
            if(HookRegistry.translatorHook != null){
                display = HookRegistry.translatorHook.translate(stack.getType());
            } else{
                display = stack.getType().name().replace("_", "").toLowerCase();
            }
        }

        List<String> lore = new ArrayList<>();
        List<Component> loreComponents = meta.lore();
        if(loreComponents != null) {
            lore = loreComponents.stream()
                    .map(line -> (TextComponent) line)
                    .map(TextComponent::content)
                    .toList();
        }

        return new AuctionItemDto.AuctionItemDtoBuilder()
                .display(display)
                .seller(item.getSellerName())
                .price(TextUtil.formatAmount(item.getPrice()))
                .expire(item.getExpireAt())
                .category(category)
                .amount(item.getAmount())
                .priority(item.getPriority())
                .lore(lore)
                .exist(true)
                .uuid(item.getUniqueId())
                .build();
    }

    private IBlacklist emBlackList(){
        return new IBlacklist() {
            @Override
            public String getName() {
                return "soulbind";
            }

            @Override
            public boolean isBlacklist(ItemStack itemStack) {
                return itemStack.getItemMeta().getPersistentDataContainer()
                        .get(new NamespacedKey("elitemobs", "soulbind"),
                                PersistentDataType.STRING) != null;
            }
        };
    }


    private <T> T getProvider(Class<T> classz) {
        RegisteredServiceProvider<T> provider = ARC.plugin.getServer().getServicesManager().getRegistration(classz);
        if (provider == null)
            return null;
        provider.getProvider();
        return provider.getProvider();
    }

}
