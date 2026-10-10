package ru.arc.staffspells.api;

import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Public service for ARC-owned, duel-only staff spells. */
public interface ArcStaffSpellService {
    /** Creates the exact spell ID as a native, ItemsAdder-independent duel staff. Main thread only. */
    ItemStack createDuelStaff(String spellId);

    /** Stable digest of the ARC version and validated gameplay-relevant staff settings. Main thread only. */
    String fingerprint();

    /** Whether the staff spell controller is currently ready to create and execute duel spells. Main thread only. */
    boolean isAvailable();

    /** Registers an enabled plugin's duel policy. The idempotent handle removes only that registration. Main thread only. */
    AutoCloseable registerDuelPolicy(Plugin owner, StaffSpellDuelPolicy policy);

    /** Clears this player's pending casts, displays, HUD and cooldown. It does not revoke duel context. Main thread only. */
    void resetPlayer(UUID playerId);
}
