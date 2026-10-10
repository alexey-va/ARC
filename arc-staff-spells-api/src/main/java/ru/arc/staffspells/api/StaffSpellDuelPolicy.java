package ru.arc.staffspells.api;

import org.bukkit.entity.Player;

/** Supplies the exact active duel context and authorizes a caster-target pair. Calls are made on the server thread. */
public interface StaffSpellDuelPolicy {
    /** Returns null unless the caster is currently in an active duel. */
    StaffSpellDuelContext context(Player caster);

    boolean canHit(Player caster, Player target, StaffSpellDuelContext context);
}
