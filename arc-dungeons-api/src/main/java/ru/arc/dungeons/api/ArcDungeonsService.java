package ru.arc.dungeons.api;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
/** Optional feature provider owned by ArcDungeons. Resolve at the point of use. */
public interface ArcDungeonsService {
    enum GrantStatus { APPLIED, ALREADY_GRANTED, DISABLED, NOT_READY, UNKNOWN_FORM, RUN_LOCKED, FAILED }
    record SkillIncrease(String name, int previousLevel, int newLevel) {}
    record ClassGrant(GrantStatus status, String formId, String formName, List<SkillIncrease> skillIncreases) {}
    String caseDefinition(String id);
    ItemStack createCase(Player player, String id);
    ItemStack createLuckyTicket(Player player);
    List<String> classFormIds();
    ClassGrant grantClass(Player player, String formId);
    List<String> staffSpellIds();
    boolean giveStaffSpells(Player player, List<String> ids);
    boolean staffSpellsReady();
    void openDecorLoot(Player player);
    String decorType(ItemStack item);
    boolean addDecor(String type, org.bukkit.Material material, double weight, int modelId,
                     org.bukkit.Color color, String itemsAdderNamespace, String itemsAdderId);
    boolean nativeReady();
    void resetShop();
    void openShop(Player player, boolean gear);
    boolean canLaunchSeasonDungeon(Player player, String world);
    void launchSeasonDungeon(Player player, String world);
    void dungeonAction(Player player, String action, List<String> arguments);
    boolean usesMenuShortcut(Player player);
    String scoreboardValue(UUID player, String key);
    boolean isEliteTreasureChest(Location location);
    boolean isEliteItem(ItemStack item);
    boolean isEliteMob(org.bukkit.entity.Entity entity);
    void reload();
}
