package ru.arc.landsui

import me.angeschossen.lands.api.events.player.role.PlayerToggleRoleFlagEvent
import me.angeschossen.lands.api.exceptions.ManagedByDifferentHolderException
import me.angeschossen.lands.api.flags.enums.RoleFlagCategory
import me.angeschossen.lands.api.flags.type.Flags
import me.angeschossen.lands.api.flags.type.RoleFlag
import me.angeschossen.lands.api.framework.exceptions.NoAccessException
import me.angeschossen.lands.api.framework.exceptions.RolePriorityException
import me.angeschossen.lands.api.land.Area
import me.angeschossen.lands.api.land.Land
import me.angeschossen.lands.api.land.enums.LandType
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.role.Role
import me.angeschossen.lands.api.role.enums.RoleType
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import ru.arc.util.Logging.warn
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * The public API can toggle a flag, but cannot enforce roles.yml's allow-toggle-flags
 * or the native owner/tenant/priority checks. Bind the audited 8.9.6 checks once.
 * Unknown versions keep the role page readable and cannot write role flags.
 */
internal class LandsRoleEditing internal constructor(
    private var native: NativeLandsRoleEditing?,
    private val reportFailure: (Throwable) -> Unit = ::roleEditingDisabled,
) {
    fun canEdit(player: Player, landPlayer: LandPlayer, land: Land, area: Area, role: Role, flag: RoleFlag): Boolean {
        val bound = native ?: return false
        if (flag.category != RoleFlagCategory.ACTION || role.holder !== area ||
            !player.hasPermission(flag.togglePermission)
        ) return false
        // The land editor cannot change nation-managed flags; those require the nation editor.
        if (role.type == RoleType.NATION && flag.isToggleableByNation) return false
        return checked(false) { bound.canEdit(landPlayer, land, area, role, flag) }
    }

    /** This is the native event gate, including events.yml conditions and Bukkit cancellation. */
    fun isCancelled(event: PlayerToggleRoleFlagEvent): Boolean {
        val bound = native ?: return true
        return checked(true) { bound.isCancelled(event) || event.isCancelled }
    }

    private fun checked(fallback: Boolean, operation: () -> Boolean): Boolean = try {
        operation()
    } catch (failure: InvocationTargetException) {
        when (val cause = failure.targetException) {
            is NoAccessException, is RolePriorityException, is ManagedByDifferentHolderException -> fallback
            else -> disable(cause, fallback)
        }
    } catch (failure: Exception) {
        disable(failure, fallback)
    } catch (failure: LinkageError) {
        disable(failure, fallback)
    }

    private fun disable(failure: Throwable, fallback: Boolean): Boolean {
        native = null
        reportFailure(failure)
        return fallback
    }

    companion object {
        val DISABLED = LandsRoleEditing(null)

        fun bind(plugin: Plugin): LandsRoleEditing = try {
            LandsRoleEditing(nativeLandsRoleEditing(plugin.javaClass.classLoader, plugin.description.version))
        } catch (failure: Exception) {
            roleEditingDisabled(failure)
            DISABLED
        } catch (failure: LinkageError) {
            roleEditingDisabled(failure)
            DISABLED
        }
    }
}

internal data class NativeLandsRoleEditing(
    val canEdit: (LandPlayer, Land, Area, Role, RoleFlag) -> Boolean,
    val isCancelled: (PlayerToggleRoleFlagEvent) -> Boolean,
)

/**
 * Exact names/signatures from Lands 8.9.6 (SHA-256
 * b0057ed700bda1ed190beb4b7d8e38ead8f2c4221999d8021f1445947ad273ca).
 * No private fields, menu construction or write-by-reflection. The role menu's
 * five-argument static helper also mutates state, so it must never be used as a guard.
 */
internal fun nativeLandsRoleEditing(loader: ClassLoader, version: String): NativeLandsRoleEditing {
    require(version == "8.9.6") { "Unsupported Lands role editor version: $version" }
    val nativeRole = Class.forName("me.angeschossen.lands.Dgduf.RLHXh.BdHcv.pdjAO", false, loader)
    val nativeHolder = Class.forName("me.angeschossen.lands.Dgduf.RLHXh.BdHcv.RLHXh", false, loader)
    val nativePlayer = Class.forName("me.angeschossen.lands.Dgduf.RLHXh.lGdKv.RLHXh.Dgduf", false, loader)
    val nativeConfig = Class.forName("me.angeschossen.lands.pdjAO.Dgduf.pdjAO", false, loader)
    val events = Class.forName("me.angeschossen.lands.framework.MNeU.wybtH", false, loader)
    check(Role::class.java.isAssignableFrom(nativeRole) && LandPlayer::class.java.isAssignableFrom(nativePlayer)) {
        "Lands role editor types do not match the installed API"
    }
    val booleanType = Boolean::class.javaPrimitiveType!!
    val roleConfig = nativeRole.getMethod("SknVT")
    val configured = nativeConfig.getMethod("RLHXh", RoleFlag::class.java)
    val settingAllowed = nativeHolder.getMethod("RLHXh", nativePlayer, RoleFlag::class.java, booleanType)
    val validateRole = nativeHolder.getMethod("RLHXh", nativePlayer, booleanType, nativeRole, booleanType, booleanType)
    val callEvent = events.getMethod("RLHXh", Event::class.java)
    check(roleConfig.returnType == nativeConfig && configured.returnType == booleanType &&
        settingAllowed.returnType == booleanType && validateRole.returnType == Void.TYPE &&
        callEvent.returnType == booleanType && Modifier.isStatic(callEvent.modifiers)) {
        "Lands 8.9.6 role editor contract changed"
    }
    return NativeLandsRoleEditing(
        canEdit = { player, land, area, role, flag ->
            check(nativeRole.isInstance(role) && nativeHolder.isInstance(area) && nativePlayer.isInstance(player)) {
                "Unexpected Lands role editor implementation"
            }
            val config = roleConfig.invoke(role)
            if (config != null && configured.invoke(config, flag) != true) false
            else if (settingAllowed.invoke(area, player, Flags.SETTING_EDIT_ROLE, false) != true) false
            else {
                // ACTION flags allow an owner to edit their own role; the native NATION branch does not.
                validateRole.invoke(area, player, false, role, role.type != RoleType.NATION, land.landType == LandType.ADMIN)
                true
            }
        },
        isCancelled = { event -> callEvent.invoke(null, event) as Boolean },
    )
}

private fun roleEditingDisabled(failure: Throwable) {
    warn("Lands dialog role editing disabled; role pages remain read-only: {}", failure.message, failure)
}
