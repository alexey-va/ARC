package arc.arc.board;

import arc.arc.ARC;
import arc.arc.util.TextUtil;
import de.tr7zw.changeme.nbtapi.NBTItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BoardEntry {

    public Type type;
    public String playerName;
    public ItemIcon icon;
    public String text;
    public String tldr;
    public long timestamp;
    public UUID uuid;
    public UUID playerUuid;
    public long lastShown;

    public BoardEntry(Type type, String playerName, UUID playerUuid, ItemIcon icon, String text, String tldr, long timestamp, UUID uuid) {
        this.type = type;
        this.playerName = playerName;
        this.icon = icon;
        this.text = text;
        this.tldr = tldr;
        this.timestamp = timestamp;
        this.uuid = uuid;
        this.playerUuid = playerUuid;

        this.lastShown = System.currentTimeMillis();
    }

    public ItemStack getItem() {
        ItemStack stack = icon.icon.clone();
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize(tldr)));
        meta.lore(getLore());
        meta.getPersistentDataContainer()
                .set(new NamespacedKey(ARC.plugin, "uuid"), PersistentDataType.STRING, uuid.toString());
        meta.addItemFlags(ItemFlag.HIDE_ITEM_SPECIFICS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_DESTROYS, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_PLACED_ON, ItemFlag.HIDE_UNBREAKABLE);
        stack.setItemMeta(meta);
        return stack;
    }


    //Отправитель: ..
    //Тип: ...
    // Сообщение
    private List<Component> getLore() {
        List<Component> lore = new ArrayList<>();
        lore.add(TextUtil.strip(
                Component.text("Отправитель: ", NamedTextColor.GRAY).append(
                        Component.text(playerName, NamedTextColor.YELLOW)
                )
        ));

        lore.add(TextUtil.strip(
                Component.text("Истечет через: ", NamedTextColor.GRAY)
                        .append(TextUtil.parseTime(tillExpire()))
        ));

        lore.add(TextUtil.strip(
                Component.text("Тип: ", NamedTextColor.GRAY)
                        .append(type.name)
        ));

        if(text != null) {
            lore.add(Component.text(""));
            lore.add(TextUtil.strip(Component.text("Комментарий:", NamedTextColor.GRAY)));
            lore.addAll(getTextLore(text));
        }

        return lore;
    }

    public static List<Component> getTextLore(String s1){

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
        if(builder.length() > 0) textComponents.add(TextUtil.strip(Component.text("> ", NamedTextColor.DARK_GRAY).append(
                Component.text(builder.toString(), NamedTextColor.GRAY))));
        return new ArrayList<>(textComponents);
    }

    public boolean isExpired(){
        return (System.currentTimeMillis() - timestamp > 604800000L);
    }

    public long tillExpire(){
        return ((timestamp + 604800000L) - System.currentTimeMillis());
    }

    public enum Type {
        BUY("&aПокупаю"),
        SELL("&eПродаю"),
        LOOKING_FOR("&6Ищу человека"),
        INFO("&3Сообщаю");

        public final Component name;

        private Type(String s){
            name = TextUtil.strip(LegacyComponentSerializer.legacyAmpersand().deserialize(s));
        }
    }

    public static BoardEntry parseBoardEntry(String s, UUID uuid) {
        String[] strings = s.split("<:::>");

        BoardEntry.Type type = BoardEntry.Type.valueOf(strings[0].toUpperCase()); // 0
        String playerName = strings[1]; // 1
        UUID playerUuid = UUID.fromString(strings[2]); // 2

        String iconData = strings[3]; // 3
        String[] iconStrings = iconData.split(">:::<");
        ItemIcon icon = null;
        if (iconStrings[0].equalsIgnoreCase("ITEM")) {
            Material material = Material.valueOf(iconStrings[1].toUpperCase());
            int model = Integer.parseInt(iconStrings[2]);
            icon = new ItemIcon(material, model);
        } else if (iconStrings[0].equalsIgnoreCase("HEAD")) {
            icon = new ItemIcon(playerUuid);
        }

        String text = strings[4]; // 4
        if(text.equalsIgnoreCase("null")) text = null;
        String tldr = strings[5]; // 5
        long timestamp = Long.parseLong(strings[6]); // 6

        return new BoardEntry(type, playerName, playerUuid, icon, text, tldr, timestamp, uuid);
    }

    public static String serialiseBoardEntry(BoardEntry boardEntry) {
        StringBuilder builder = new StringBuilder();
        builder.append(boardEntry.type.name()).append("<:::>"); // 0
        builder.append(boardEntry.playerName).append("<:::>"); // 1
        builder.append(boardEntry.getPlayerUuid()).append("<:::>"); // 2

        String iconData = "";
        if (boardEntry.getIcon().icon.getType() == Material.PLAYER_HEAD) {
            iconData += "HEAD";
        } else {
            int modelData = boardEntry.getIcon().icon.getItemMeta().getCustomModelData();
            iconData += "ITEM>:::<" + boardEntry.getIcon().icon.getType().name() + ">:::<" + modelData;
        }

        builder.append(iconData).append("<:::>"); // 3
        builder.append(boardEntry.getText()).append("<:::>"); // 4
        builder.append(boardEntry.getTldr()).append("<:::>"); // 5
        builder.append(boardEntry.getTimestamp()); // 6

        return builder.toString();
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public ItemIcon getIcon() {
        return icon;
    }

    public void setIcon(ItemIcon icon) {
        this.icon = icon;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getTldr() {
        return tldr;
    }

    public void setTldr(String tldr) {
        this.tldr = tldr;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }
}



