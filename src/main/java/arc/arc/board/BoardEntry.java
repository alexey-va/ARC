package arc.arc.board;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.network.ArcSerializable;
import arc.arc.util.TextUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Setter @Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BoardEntry extends ArcSerializable {

    public Type type;
    public String playerName;

    public String text;
    public String title;
    public ItemIcon icon;
    public UUID entryUuid;
    public UUID playerUuid;
    public long timestamp;
    public long lastShown;

    public BoardEntry(Type type, String playerName, UUID playerUuid, ItemIcon icon, String text, String title, long timestamp, long lastShown, UUID entryUuid) {
        this.type = type;
        this.playerName = playerName;
        this.icon = icon;
        this.text = text;
        this.title = title;
        this.timestamp = timestamp;
        this.entryUuid = entryUuid;
        this.playerUuid = playerUuid;
        this.lastShown = lastShown;
    }

    public ItemStack item() {
        ItemStack stack = icon.stack();
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize(title)));
        meta.lore(lore());
        meta.getPersistentDataContainer()
                .set(new NamespacedKey(ARC.plugin, "uuid"), PersistentDataType.STRING, entryUuid.toString());
        meta.addItemFlags(ItemFlag.HIDE_ITEM_SPECIFICS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_DESTROYS, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_PLACED_ON, ItemFlag.HIDE_UNBREAKABLE);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean canEdit(Player player){
        if(player.getUniqueId() == playerUuid) return true;
        return player.hasPermission("arc.board.admin");
    }

    public boolean canRate(Player player){
        return player.getUniqueId() != playerUuid;
    }

    private List<Component> lore() {
        List<Component> lore = new ArrayList<>();
        lore.add(TextUtil.strip(
                Component.text("Отправитель: ", NamedTextColor.GRAY).append(
                        Component.text(playerName, NamedTextColor.YELLOW)
                )
        ));

        lore.add(TextUtil.strip(
                Component.text("Истечет через: ", NamedTextColor.GRAY)
                        .append(TextUtil.parseTime(tillExpire(), TimeUnit.MINUTES))
        ));

        lore.add(TextUtil.strip(
                Component.text("Тип: ", NamedTextColor.GRAY)
                        .append(type.name)
        ));

        if(text != null) {
            lore.add(Component.text(""));
            lore.add(TextUtil.strip(Component.text("Комментарий:", NamedTextColor.GRAY)));
            lore.addAll(textLore(text));
        }

        return lore;
    }

    public static List<Component> textLore(String s1){
        String[] strings = s1.split(" ");
        List<Component> textComponents = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        int counter = 0;
        for(String s : strings){
            counter+=s.length()+1;
            builder.append(s).append(" ");
            if(counter > 40){
                counter = 0;
                String res = builder.toString();
                builder = new StringBuilder();
                textComponents.add(TextUtil.strip(Component.text("> ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(res, NamedTextColor.GRAY))));
            }
        }
        if(!builder.isEmpty()) textComponents.add(TextUtil.strip(Component.text("> ", NamedTextColor.DARK_GRAY).append(
                Component.text(builder.toString(), NamedTextColor.GRAY))));
        return new ArrayList<>(textComponents);
    }

    public boolean isExpired(){
        return (System.currentTimeMillis() - timestamp)*1000 > Config.boardEntryLifetimeMinutes;
    }

    public long tillExpire(){
        return 1000;
    }

    public enum Type {
        BUY("&aПокупаю"),
        SELL("&eПродаю"),
        LOOKING_FOR("&6Ищу человека"),
        INFO("&3Сообщаю");

        public final Component name;

        Type(String s){
            name = TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize(s));
        }
    }
}



