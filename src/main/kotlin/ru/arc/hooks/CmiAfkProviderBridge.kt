package ru.arc.hooks

import ru.arc.paper.api.ArcAfkProvider
import java.util.UUID

/** Uses the current CMI hook so an absent or retired integration has no AFK viewers. */
internal object CmiAfkProviderBridge : ArcAfkProvider {
    override fun isAfk(playerId: UUID): Boolean = HookRegistry.cmiHook?.isAfk(playerId) == true
}
