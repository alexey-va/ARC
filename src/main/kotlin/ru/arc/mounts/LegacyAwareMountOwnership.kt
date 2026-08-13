package ru.arc.mounts

import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Read-only bridge for the small number of glow purchases formerly stored in
 * Denizen server flags. All new writes go to the normal ownership backend.
 */
class LegacyAwareMountOwnership(
    private val delegate: MountOwnership,
    private val legacyGlowOwners: () -> Map<String, Set<String>>,
) : MountOwnership {
    override fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile {
        val current = delegate.profile(subject, mount)
        val legacyOwned =
            mount.id in legacyGlowOwners()[subject.name.lowercase(Locale.ROOT)].orEmpty()
        if (!legacyOwned || current.glowOwned) return current
        return current.copy(glowOwned = true)
    }

    override fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void> =
        delegate.grantLevel(playerId, mount, level)

    override fun grantGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        delegate.grantGlow(playerId, mount)

    override fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void> =
        delegate.setGlowEnabled(playerId, mount, enabled)

    override fun resolveUniqueId(playerName: String): CompletableFuture<UUID?> = delegate.resolveUniqueId(playerName)
}
