package arc.arc.eliteloot;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.util.TextUtil;
import com.google.common.collect.Multimap;
import com.magmaguy.elitemobs.api.utils.EliteItemManager;
import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT;
import dev.lone.itemsadder.api.ItemsAdder;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

import static arc.arc.eliteloot.EliteLootManager.toLootType;


@Slf4j
public class EliteLootProcessor {

    final Config config;

    public EliteLootProcessor(Config config) {
        this.config = config;
    }

    public ItemStack processEliteLoot(ItemStack originalStack) {
        if (originalStack == null) return null;
        if (!EliteItemManager.isEliteMobsItem(originalStack)) return originalStack;

        ItemMeta meta = originalStack.getItemMeta();
        List<Component> trimmedLore = removeUselessEMLore(meta.lore());
        meta.lore(trimmedLore);
        originalStack.setItemMeta(meta);

        LootType lootType = toLootType(originalStack);
        //ARC.info("Loot type of {} is {}", originalStack.getType(), lootType);
        if (lootType == null) return originalStack;
        DecorPool decorPool = EliteLootManager.getMap().get(lootType);
        //ARC.info("Decor pool: {}", decorPool);
        if (decorPool == null) return originalStack;

        DecorItem decorItem = decorPool.randomItem();
        //ARC.info("selected {} decor for {}", decorItem, originalStack);
        if (decorItem == null) return originalStack;

        ItemStack updatedStack = originalStack;
        if (!Set.of(LootType.HELMET, LootType.BOOTS, LootType.CHESTPLATE, LootType.LEGGINGS).contains(lootType)) {
            updatedStack = changeItemMaterial(originalStack, decorItem.getMaterial(), true);
            meta = updatedStack.getItemMeta();
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
            ReadableNBT readableNBT = NBT.readNbt(updatedStack);
            //ARC.info(readableNBT.getKeys().toString());
        }

        return updatedStack;
    }

    private ItemStack changeItemMaterial(ItemStack origin, Material targetMaterial, boolean appendAttrLore) {
        if (origin.getType() == targetMaterial) return origin;
        ItemStack target = new ItemStack(targetMaterial);
        ItemMeta originMeta = origin.getItemMeta();
        ItemMeta targetMeta = target.getItemMeta();
        if (originMeta.hasDisplayName()) targetMeta.displayName(originMeta.displayName());
        if (originMeta.hasLore()) targetMeta.lore(originMeta.lore());
        if (originMeta.hasEnchants()) {
            originMeta.getEnchants().forEach((enchant, level) -> targetMeta.addEnchant(enchant, level, true));
        }
        ReadWriteNBT readWriteNBT = NBT.itemStackToNBT(origin);
        NBT.modify(target, nbt -> {
            nbt.mergeCompound(readWriteNBT);
        });

        if (originMeta.hasCustomModelData()) targetMeta.setCustomModelData(originMeta.getCustomModelData());
        originMeta.getItemFlags().forEach(targetMeta::addItemFlags);
        for (var slot : EquipmentSlot.values()) {
            origin.getType().getDefaultAttributeModifiers(slot).forEach(targetMeta::addAttributeModifier);
        }

        if (appendAttrLore) {
            targetMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            List<Component> currentLore = targetMeta.lore();
            if (currentLore == null) currentLore = new ArrayList<>();

            for (var eqSlot : EquipmentSlot.values()) {
                Multimap<Attribute, AttributeModifier> attr = targetMaterial.getDefaultAttributeModifiers(eqSlot);
                //ARC.info("Attributes for {} in {}: {}", targetMaterial, eqSlot, attr.entries());
                if (attr.isEmpty()) continue;

                //ARC.info("Adding attributes for {} in {}", targetMaterial, eqSlot);

                currentLore.add(Component.text(""));

                String slotTitle = config.string("attribute-slot-title." + eqSlot.name().toLowerCase(), eqSlot.name());
                currentLore.add(TextUtil.mm(slotTitle, true));

                for (var entry : attr.entries()) {
                    String attrName = config.string("attribute-name." + entry.getKey().name().toLowerCase(), entry.getKey().name());
                    String attrValue = config.string("attribute-value", "<dark_green><value>");
                    double afterPoint = Math.abs(entry.getValue().getAmount() - (int) entry.getValue().getAmount());
                    double value = entry.getValue().getAmount();
                    if (entry.getKey() == Attribute.GENERIC_ATTACK_SPEED) {
                        value += 4.0;
                    }
                    if (afterPoint > 0.05) {
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
        for (int i = 0; i < lore.size(); i++) {
            String line = PlainTextComponentSerializer.plainText().serialize(lore.get(i));
            boolean fits = false;
            for (String search : toSearch) {
                if (line.contains(search)) {
                    fits = true;
                    break;
                }
            }
            if (!fits) {
                newLore.add(lore.get(i));
                continue;
            }
            if (i + 1 < lore.size()) {
                String nextLine = PlainTextComponentSerializer.plainText().serialize(lore.get(i));
                if (containsNoLetters(nextLine)) {
                    i += 2;
                } else {
                    newLore.add(lore.get(i));
                }
            }
        }
        return newLore;
    }

    private boolean containsNonSpaceChars(String s) {
        if (s == null) return false;
        for (char c : s.toCharArray()) {
            if (!Character.isWhitespace(c)) return true;
        }
        return false;
    }

    private boolean containsNoLetters(String s) {
        if (s == null) return false;
        for (char c : s.toCharArray()) {
            if (!Character.isLetter(c)) return true;
        }
        return false;
    }
}
