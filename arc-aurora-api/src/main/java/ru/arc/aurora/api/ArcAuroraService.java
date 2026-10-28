package ru.arc.aurora.api;

import java.util.UUID;

/** Optional Bukkit service provided and runtime-owned exclusively by ArcAurora. */
public interface ArcAuroraService {
    /**
     * Starts a temporary configured preset for every player in the specified world,
     * including later joins. Event effects explicitly bypass ordinary ambient
     * permission and personal toggle. Call and close on Paper's primary thread.
     * The idempotent lease removes only this event; newest open event wins in a world.
     * Provider shutdown retires all leases. Unknown presets/worlds fail before mutation.
     * Caller must close on completion, cancellation, reload and shutdown.
     *
     * @throws IllegalArgumentException for an unknown world or preset
     * @throws IllegalStateException when the provider has stopped or the thread is wrong
     */
    AutoCloseable startWorldEvent(UUID worldId, String preset);
}
