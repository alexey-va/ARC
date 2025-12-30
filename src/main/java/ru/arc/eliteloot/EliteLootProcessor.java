package ru.arc.eliteloot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Multimap;
import com.magmaguy.elitemobs.api.utils.EliteItemManager;
import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.TextUtil;

import static ru.arc.eliteloot.EliteLootManager.toLootType;
import static ru.arc.util.Logging.error;


public class EliteLootProcessor {
    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "elite-loot.yml");
    final Set<Material> leathers = Set.of(Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS);

    public ItemStack processEliteLoot(ItemStack originalStack) {
        if (originalStack == null) return null;
        if (!EliteItemManager.isEliteMobsItem(originalStack)) return originalStack;
        if(originalStack.getItemMeta().hasCustomModelData()) return originalStack;

        double replaceChance = config.real("replace-chance", 0.4);
        if (Math.random() > replaceChance) return originalStack;

        ItemMeta meta = originalStack.getItemMeta();
        if(config.bool("clear-lore", false)){
            List<Component> trimmedLore = removeUselessEMLore(meta.lore());
            meta.lore(trimmedLore);
        }

        originalStack.setItemMeta(meta);

        LootType lootType = toLootType(originalStack);
        if (lootType == null) return originalStack;
        DecorPool decorPool = EliteLootManager.getMap().get(lootType);
        if (decorPool == null) return originalStack;

        DecorItem decorItem = decorPool.randomItem();
        if (decorItem == null) return originalStack;

        ItemStack updatedStack = originalStack;
        if (!Set.of(LootType.HELMET, LootType.BOOTS, LootType.CHESTPLATE, LootType.LEGGINGS).contains(lootType)) {
            boolean appendAttributes = config.bool("append-attributes", false);
            updatedStack = changeItemMaterial(originalStack, decorItem.getMaterial(), appendAttributes);
            meta = updatedStack.getItemMeta();
        } else if (meta instanceof LeatherArmorMeta leatherMeta) {
            leatherMeta.setColor(decorItem.getColor());
        }

        if (decorItem.getModelId() == 0) meta.setCustomModelData(null);
        else meta.setCustomModelData(decorItem.getModelId());

        if (meta instanceof Damageable damageable) {
            damageable.setDamage(0);
        }

        updatedStack.setItemMeta(meta);

        if (decorItem.getIaNamespace() != null && decorItem.getIaId() != null) {
            //ARC.info("Adding ia nbt to {}", updatedStack);
            NBT.modify(updatedStack, nbt -> {
                ReadWriteNBT itemsadder = nbt.getOrCreateCompound("itemsadder");
                itemsadder.setString("namespace", decorItem.getIaNamespace());
                itemsadder.setString("id", decorItem.getIaId());
            });
        }

        return updatedStack;
    }

    @SuppressWarnings("UnstableApiUsage")
    private ItemStack changeItemMaterial(ItemStack origin, Material targetMaterial, boolean appendAttrLore) {
        if (origin.getType() == targetMaterial) return origin;
        ItemStack target = origin.withType(targetMaterial);

        ItemMeta originMeta = origin.getItemMeta();
        ItemMeta targetMeta = target.getItemMeta();
        //if (originMeta.hasDisplayName()) targetMeta.displayName(originMeta.displayName());
        //if (originMeta.hasLore()) targetMeta.lore(originMeta.lore());
        //if (originMeta.hasEnchants()) {
        //    originMeta.getEnchants().forEach((enchant, level) -> targetMeta.addEnchant(enchant, level, true));
        //}

        //if (originMeta.hasCustomModelData()) targetMeta.setCustomModelData(originMeta.getCustomModelData());
        //originMeta.getItemFlags().forEach(targetMeta::addItemFlags);

        var attrs = origin.getType().getDefaultAttributeModifiers();
        try {
            targetMeta.setAttributeModifiers(attrs);
        } catch (Exception e) {
            error("Failed to add attributes for {}", origin.getType(), e);
            error("Default attributes for origin {}: {}", origin.getType(), attrs);
            error("Default attributes for target {}: {}", targetMaterial, targetMaterial.getDefaultAttributeModifiers());
        }

        if (appendAttrLore) {
            targetMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            List<Component> currentLore = targetMeta.lore();
            if (currentLore == null) currentLore = new ArrayList<>();

            Multimap<Attribute, AttributeModifier> attr = targetMaterial.getDefaultAttributeModifiers();
            //ARC.info("Attributes for {} in {}: {}", targetMaterial, eqSlot, attr.entries());
            if (!attr.isEmpty()) {
                boolean append1 = false;
                currentLore.add(Component.text(""));
                for (var entry : attr.entries()) {
                    String slot = entry.getValue().getSlotGroup().toString().replace(".", "_").toLowerCase();
                    String slotTitle = config.string("attribute-slot-title." + slot);
                    if(!append1) {
                        currentLore.add(TextUtil.mm(slotTitle, true));
                        append1 = true;
                    }
                    String attrName = config.string("attribute-name." + entry.getKey().translationKey(), entry.getKey().translationKey());
                    String attrValue = config.string("attribute-value", "<dark_green><value>");
                    double value = entry.getValue().getAmount();
                    if (entry.getKey() == Attribute.ATTACK_SPEED) {
                        value += 4.0;
                    }
                    double afterPoint = Math.abs(entry.getValue().getAmount() - (int) entry.getValue().getAmount());
                    if (Math.abs(afterPoint) > 0.05) {
                        attrValue = attrValue.replace("<value>", String.format("%.1f", value));
                    } else {
                        attrValue = attrValue.replace("<value>", String.valueOf((int) value));
                    }

                    Component attrLine = TextUtil.mm((attrName + attrValue), true);

                    currentLore.add(attrLine);
                }
            }
            //currentLore.add(Component.text(""));

            targetMeta.lore(currentLore);
        }

        target.setItemMeta(targetMeta);
        return target;
    }

    private List<Component> removeUselessEMLore(List<Component> lore) {
        if (lore == null) return new ArrayList<>();
        List<String> toSearch = config.stringList("useless-lore");
        List<Component> newLore = new ArrayList<>();

        boolean prevIsEmpty = false;
        for (Component component : lore) {
            String line = PlainTextComponentSerializer.plainText().serialize(component);
            boolean remove = false;
            boolean noLetters = noLetters(line);
            for (String search : toSearch) {
                if (line.contains(search)) {
                    remove = true;
                    break;
                }
            }
            if (onlySpaces(line) && prevIsEmpty) remove = true;
            if (!remove) newLore.add(component);
            prevIsEmpty = noLetters;
        }
        Iterator<Component> iter = newLore.iterator();
        Component prev = null;
        while (iter.hasNext()) {
            Component component = iter.next();
            String line = PlainTextComponentSerializer.plainText().serialize(component);
            String prevLine = prev == null ? "" : PlainTextComponentSerializer.plainText().serialize(prev);
            if (onlySpaces(line) && onlySpaces(prevLine)) {
                iter.remove();
            }
            prev = component;
        }
        return newLore;
    }

    private boolean onlySpaces(String s) {
        if (s == null) return false;
        for (char c : s.toCharArray()) {
            if (!Character.isWhitespace(c)) return false;
        }
        return true;
    }

    private boolean noLetters(String s) {
        if (s == null) return false;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) return false;
        }
        return true;
    }
}
