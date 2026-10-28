package ru.arc.staffspells.api;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Optional quest guidance in the existing staff HUD's private camera-relative card.
 * ARC owns these API classes; ArcDungeons borrows its existing display owner and placement.
 * All methods run on the Paper thread. Holding a staff keeps the staff HUD's priority.
 */
public interface QuestGuidanceHudService {
    String PERMISSION = "arc.onboarding.hud.hologram";

    enum Source { FIRST_HOME, OPTIONAL }

    /**
     * Returns true only when the current HUD generation accepted a visible quest card.
     * False means the caller must retain its ordinary HUD (permission, provider or staff priority).
     * FIRST_HOME takes priority over OPTIONAL; a source may clear only its own current card.
     */
    boolean show(Player player, Source source, Component text);

    /** Removes this source's hint, including its pending camera updates. Idempotent. */
    void clear(UUID playerId, Source source);
}
