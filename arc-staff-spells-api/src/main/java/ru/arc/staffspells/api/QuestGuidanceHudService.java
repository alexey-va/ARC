package ru.arc.staffspells.api;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Quest and claim guidance in the existing staff HUD's private camera-relative card.
 * ARC owns these API classes; ArcDungeons borrows its existing display owner and placement.
 * All methods run on the Paper thread. Holding a staff keeps the staff HUD's priority.
 */
public interface QuestGuidanceHudService {
    String PERMISSION = "arc.onboarding.hud.hologram";

    enum Source { FIRST_HOME, OPTIONAL, CLAIM }

    /**
     * Returns true when the current generation owns the hint, including a temporarily hidden hint.
     * Suppression must not trigger an ordinary bossbar fallback. False means unavailable or denied.
     * Staff has priority over CLAIM, then FIRST_HOME, then OPTIONAL. CLAIM needs no quest permission.
     * Clearing a higher source restores the latest lower source without losing its desired content.
     */
    boolean show(Player player, Source source, Component text);

    /** Claim card with the existing per-viewer height adjustment, in camera-up blocks. */
    boolean showClaim(Player player, Component text, double verticalOffset);

    /** True only while aiming at the visible, Shift-frozen claim card; never performs an action. */
    boolean isClaimTarget(Player player);

    /**
     * A current foreground surface hides quest hints, not claim or staff controls. Reassert true at
     * least once per second; each owner/viewer/surface lease expires after three monotonic seconds.
     * False releases only that key. Disable, quit, world change and provider close release leases.
     * Use one stable nonblank surface key per independently owned lifecycle (at most 100 characters).
     */
    void setSuppressed(Plugin owner, UUID playerId, String surfaceKey, boolean suppressed);

    /** Removes this source's hint, including its pending camera updates. Idempotent. */
    void clear(UUID playerId, Source source);
}
