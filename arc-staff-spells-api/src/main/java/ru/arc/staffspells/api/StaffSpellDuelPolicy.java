package ru.arc.staffspells.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Supplies the exact active duel context and authorizes a caster-target pair. Calls are made on the server thread. */
public interface StaffSpellDuelPolicy {
    /** Returns null unless the caster is currently in an active duel. */
    StaffSpellDuelContext context(Player caster);

    boolean canHit(Player caster, Player target, StaffSpellDuelContext context);

    /**
     * Whether staff input is allowed for this player. Return false for players owned by a managed duel
     * unless their current match state explicitly permits staff input. The default preserves the
     * behavior of older policy implementations.
     */
    default boolean permitsInput(Player player) {
        return true;
    }

    /** Whether the authorized staff mobility destination is allowed by this duel policy. */
    default boolean permitsMobility(Player player, Location destination) {
        return false;
    }

    /** Perform the policy-owned, round-authorized mobility teleport. */
    default boolean teleportMobility(Player player, Location destination) {
        return false;
    }
}
