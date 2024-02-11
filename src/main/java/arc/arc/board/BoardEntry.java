package arc.arc.board;

import arc.arc.ARC;
import arc.arc.configs.BoardConfig;
import arc.arc.configs.Config;
import arc.arc.network.ArcSerializable;
import arc.arc.util.TextUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static arc.arc.util.TextUtil.strip;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@AllArgsConstructor
@NoArgsConstructor
@ToString
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
    public Set<String> positiveRatings = new HashSet<>();
    public Set<String> negativeRatings = new HashSet<>();
    public Set<String> reports = new HashSet<>();

    @JsonIgnore
    public boolean dirty = false;

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

        dirty = true;
    }

    public ItemStack item() {
        Component shortName = title == null ? strip(Component.text("Нет", NamedTextColor.RED)) :
                strip(LegacyComponentSerializer.legacyAmpersand().deserialize(title));

        TagResolver resolver = TagResolver.builder()
                .resolver(TagResolver.resolver("short_name", Tag.inserting(shortName)))
                .resolver(TagResolver.resolver("player", Tag.inserting(Component.text(playerName == null ? "" : playerName))))
                .resolver(TagResolver.resolver("type", Tag.inserting(type == null ? Component.empty() : type.name)))
                .resolver(TagResolver.resolver("expire", Tag.inserting(TextUtil.parseTime(tillExpire(), TimeUnit.MILLISECONDS))))
                .resolver(TagResolver.resolver("reports", Tag.inserting(
                        reports.isEmpty() ? Component.text("Нет", NamedTextColor.GREEN) :
                                Component.text(reports.size())
                ))).build();

        ItemStack stack = icon.stack();
        ItemMeta meta = stack.getItemMeta();

        Component display = strip(MiniMessage.miniMessage().deserialize(BoardConfig.getString("item.display"), resolver));
        meta.displayName(display);

        meta.lore(lore(resolver));
        meta.addItemFlags(ItemFlag.HIDE_ITEM_SPECIFICS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_DESTROYS, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_PLACED_ON,
                ItemFlag.HIDE_UNBREAKABLE);
        stack.setItemMeta(meta);
        return stack;
    }

    public int hasRated(Player player) {
        for (String s : positiveRatings) {
            if (s.equals(player.getName())) return 1;
        }
        for (String s : negativeRatings) {
            if (s.equals(player.getName())) return -1;
        }
        return 0;
    }

    public boolean hasReported(Player player) {
        return reports.contains(player.getName());
    }

    public boolean canEdit(Player player) {
        if (player.getUniqueId() == playerUuid) return true;
        return player.hasPermission("arc.board.admin");
    }

    public boolean canRate(Player player) {
        return player.getUniqueId() != playerUuid || player.hasPermission("arc.rate-own");
    }

    private List<Component> lore(TagResolver resolver) {
        List<Component> lore = new ArrayList<>();

        for (String s : BoardConfig.getStringList("item.lore")) {
            if (s.contains("<description>")) {
                lore.addAll(description(resolver, text));
                continue;
            }
            lore.add(strip(MiniMessage.miniMessage().deserialize(s, resolver)));
        }

        return lore;
    }

    public static List<Component> description(TagResolver resolver, String text) {
        if (text == null) return new ArrayList<>();
        String[] strings = text.split(" ");
        StringBuilder builder = new StringBuilder();
        int count = 0;

        List<Component> components = new ArrayList<>();

        for (String string : strings) {
            builder.append(string).append(" ");
            count += string.length() + 1;
            if (count >= 40) {
                Component component = strip(LegacyComponentSerializer.legacyAmpersand().deserialize(builder.toString()));
                if (!components.isEmpty()) {
                    List<Component> children = components.get(components.size()-1).children();
                    Style style = children.get(children.size() - 1).style();
                    Component pre = Component.text("", style);
                    component = pre.append(component);
                }
                components.add(component);
                count = 0;
                builder = new StringBuilder();
            }
        }
        if (!builder.isEmpty()) {
            Component component = strip(LegacyComponentSerializer.legacyAmpersand().deserialize(builder.toString()));
            if (!components.isEmpty()) {
                Style style = components.get(components.size() - 1).style();
                Component pre = Component.text("", style);
                component = pre.append(component);
            }
            components.add(component);
        }

        Component prefix = strip(MiniMessage.miniMessage().deserialize(BoardConfig.getString("item.description-prefix")));

        components = components.stream()
                .map(prefix::append)
                .map(TextUtil::strip)
                .map(c -> c.color(NamedTextColor.GRAY))
                .toList();

        List<Component> result = new ArrayList<>();
        for (String s : BoardConfig.getStringList("item.description")) {
            if (s.contains("<description_text>")) {
                result.addAll(components);
                continue;
            }
            result.add(strip(MiniMessage.miniMessage().deserialize(s, resolver)));
        }

        return result;
    }

    public long tillExpire() {
        return BoardConfig.secondsLifetime * 1000L + timestamp - System.currentTimeMillis();
    }

    public void rate(String name, int i) {
        if (i == 1) {
            negativeRatings.remove(name);
            positiveRatings.add(name);
        } else if (i == -1) {
            positiveRatings.remove(name);
            negativeRatings.add(name);
        }
        dirty = true;
    }

    public void report(String name) {
        reports.add(name);
        dirty = true;
    }

    public void setText(String text) {
        if (text.equals(this.text)) return;
        this.text = text;
        dirty = true;
    }

    public void setTitle(String title) {
        if (title.equals(this.title)) return;
        this.title = title;
        dirty = true;
    }

    public void setIcon(ItemIcon icon) {
        if (icon.equals(this.icon)) return;
        this.icon = icon;
        dirty = true;
    }

    public void setType(Type type) {
        if (type.equals(this.type)) return;
        this.type = type;
        dirty = true;
    }

    public void setLastShown(long lastShown) {
        if (this.lastShown == lastShown) return;
        this.lastShown = lastShown;
        dirty = true;
    }

    public enum Type {
        BUY("&aПокупаю", Material.GOLD_INGOT, "red"),
        SELL("&eПродаю", Material.CHEST, "blue"),
        LOOKING_FOR("&6Ищу человека", Material.PLAYER_HEAD, "yellow"),
        INFO("&3Сообщаю", Material.FLOWER_BANNER_PATTERN, "white");

        public final Component name;
        public final Material icon;
        public final String color;

        Type(String s, Material material, String color) {
            name = strip(LegacyComponentSerializer.legacyAmpersand().deserialize(s));
            icon = material;
            this.color = color;
        }
    }
}



