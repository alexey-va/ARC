package ru.arc.dungeons.api;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** ARC-owned settlement and native clan policy. Every call belongs to the server thread. */
public interface ArcPvpService {
    enum Result { ALLOWED, SETTLEMENT, TEAM, ALLY, TEAM_PROJECTILE, TEAM_TNT, UNAVAILABLE }

    /** Silent eligibility query; source preserves native projectile and TNT clan settings. */
    Result check(Player attacker, Player victim, Entity source);

    /** Reports an actual refused attempt with the shared per-player feedback cooldown. */
    void notifyDenied(Player attacker, Result reason);
}
