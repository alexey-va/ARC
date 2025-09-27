package ru.arc.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.TagPattern;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static ru.arc.util.TextUtil.mm;
import static ru.arc.util.TextUtil.strip;

public class ItemStackBuilder {


    public ItemStackBuilder enchant(Enchantment enchantment, int level, boolean ignoreLevelRestriction) {
        if (enchantment == null) return this;
        enchants.add(new EnchantData(enchantment, level, ignoreLevelRestriction));
        return this;
    }

    record EnchantData(Enchantment enchantment, int level, boolean ignoreLevelRestriction) {
    }

    record SerializedString(String string, Deserializer deserializer) {
        public Component deserialize(TagResolver tagResolver) {
            if (deserializer == Deserializer.LEGACY)
                return LegacyComponentSerializer.legacyAmpersand().deserialize(string);
            else return MiniMessage.miniMessage().deserialize(string, tagResolver);
            //return null;
        }

        public Component deserialize() {
            return deserialize(TagResolver.standard());
        }
    }

    TagResolver tagResolver = TagResolver.standard();
    Material material = Material.STONE;
    int count = 1;
    int modelData = 0;
    SerializedString display;
    List<EnchantData> enchants = new ArrayList<>();
    private Component componentDisplay;
    List<SerializedString> lore = new ArrayList<>();
    List<Component> componentLore;
    UUID skullUuid;
    List<ItemFlag> flags;
    Deserializer globalDeserializer = Deserializer.MINI_MESSAGE;

    public ItemStackBuilder(Material material) {
        this.material = material;
    }

    public ItemStackBuilder(ItemStack stack) {
        this(stack, null, Deserializer.MINI_MESSAGE);
    }

    public ItemStackBuilder(ItemStack stack, Deserializer deserializer) {
        this(stack, null, deserializer);
    }

    public ItemStackBuilder tagResolver(TagResolver tagResolver) {
        this.tagResolver = tagResolver;
        return this;
    }

    public ItemStackBuilder globalDeserializer(Deserializer deserializer) {
        this.globalDeserializer = deserializer;
        return this;
    }

    public ItemStackBuilder appendResolver(TagResolver append) {
        tagResolver = TagResolver.resolver(tagResolver, append);
        return this;
    }

    public ItemStackBuilder appendResolver(@TagPattern String name, String serializedString) {
        tagResolver = TagResolver.resolver(tagResolver, TagResolver.resolver(name, Tag.inserting(mm(serializedString, true))));
        return this;
    }

    public ItemStackBuilder(ItemStack stack, Material def, Deserializer deserializer) {
        if (stack == null) {
            if (def != null) this.material = def;
            return;
        }
        this.material = stack.getType();
        this.count = stack.getAmount();
        if (stack.getItemMeta().hasCustomModelData()) this.modelData = stack.getItemMeta().getCustomModelData();
        this.display = new SerializedString(MiniMessage.miniMessage().serialize(stack.displayName()), deserializer);
        if (stack.getItemMeta().lore() != null) this.lore = stack.getItemMeta().lore().stream()
                .map(line -> MiniMessage.miniMessage().serialize(line))
                .map(string -> new SerializedString(string, deserializer))
                .collect(Collectors.toList());
    }

    public ItemStackBuilder(Material material, TagResolver tagResolver) {
        this.material = material;
    }

    public ItemStackBuilder modelData(int modelData) {
        this.modelData = modelData;
        return this;
    }

    public ItemStackBuilder display(String display) {
        return display(display, Deserializer.MINI_MESSAGE);
    }

    public ItemStackBuilder display(Component display) {
        this.componentDisplay = display;
        return this;
    }

    public ItemStackBuilder display(String display, Deserializer deserializer) {
        this.display = new SerializedString(display, deserializer);
        return this;
    }

    public ItemStackBuilder lore(List<String> lore) {
        return lore(lore, Deserializer.MINI_MESSAGE);
    }

    public ItemStackBuilder componentLore(List<Component> lore) {
        this.componentLore = lore;
        return this;
    }

    public ItemStackBuilder lore(List<String> lore, Deserializer deserializer) {
        this.lore = lore.stream()
                .map(s -> new SerializedString(s, deserializer))
                .collect(Collectors.toList());
        return this;
    }

    public ItemStackBuilder appendLore(List<String> lore) {
        return appendLore(lore, Deserializer.MINI_MESSAGE);
    }

    public ItemStackBuilder appendComponentLore(List<Component> lore) {
        if (this.componentLore == null) this.componentLore = new ArrayList<>();
        if(lore == null || lore.isEmpty()) return this;
        this.componentLore.addAll(lore);
        return this;
    }

    public ItemStackBuilder appendLore(List<String> lore, Deserializer deserializer) {
        if (this.lore == null) this.lore = new ArrayList<>();
        this.lore.addAll(lore.stream()
                .map(s -> new SerializedString(s, deserializer))
                .toList());
        return this;
    }

    public ItemStackBuilder skull(UUID uniqueId) {
        this.skullUuid = uniqueId;
        return this;
    }

    public ItemStackBuilder flags(ItemFlag... flags) {
        this.flags = Arrays.stream(flags).toList();
        return this;
    }

    public ItemStackBuilder hideAll() {
        this.flags = List.of(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_PLACED_ON,
                ItemFlag.HIDE_DESTROYS, ItemFlag.HIDE_DYE, ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_PLACED_ON,
                ItemFlag.HIDE_DESTROYS, ItemFlag.HIDE_DYE, ItemFlag.HIDE_UNBREAKABLE);
        return this;
    }

    public ItemStack build() {
        ItemStack stack;
        if (skullUuid == null) stack = new ItemStack(material, count);
        else stack = HeadUtil.getSkull(skullUuid);
        ItemMeta meta = stack.getItemMeta();
        if (modelData != 0) meta.setCustomModelData(modelData);

        if (componentDisplay != null) meta.displayName(strip(componentDisplay));
        else meta.displayName(strip(display.deserialize(tagResolver)));

        if (componentLore != null) meta.lore(componentLore);
        else {
            meta.lore(lore.stream()
                    .map(line -> line.deserialize(tagResolver))
                    .map(TextUtil::strip)
                    .toList());
        }
        if (flags != null) meta.addItemFlags(flags.toArray(ItemFlag[]::new));
        else meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ATTRIBUTES);

        enchants.stream().filter(ed -> ed.enchantment != null).forEach(enchantData ->
                meta.addEnchant(enchantData.enchantment, enchantData.level, enchantData.ignoreLevelRestriction)
        );

        stack.setItemMeta(meta);
        return stack;
    }

    public GuiItemBuilder toGuiItemBuilder() {
        return new GuiItemBuilder(build());
    }


    public enum Deserializer {
        MINI_MESSAGE, LEGACY
    }


}
