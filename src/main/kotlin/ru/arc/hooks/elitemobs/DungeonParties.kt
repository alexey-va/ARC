package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.PartyConfig
import com.magmaguy.elitemobs.parties.PartyManager
import com.magmaguy.elitemobs.parties.PartyOperationResult
import org.bukkit.Bukkit
import org.bukkit.entity.Player

internal data class DungeonPartyView(
    val available: Boolean,
    val inParty: Boolean = false,
    val members: List<DungeonPartyMember> = emptyList(),
    val invitableNames: List<String> = emptyList(),
)

internal data class DungeonPartyMember(val name: String, val leader: Boolean, val online: Boolean)

internal class DungeonParties {
    fun view(player: Player): DungeonPartyView {
        if (!PartyConfig.isEnabled() || !player.hasPermission("elitemobs.party")) return DungeonPartyView(false)
        val party = PartyManager.getParty(player.uniqueId)
        if (party == null) return DungeonPartyView(
            available = true,
            invitableNames = PartyManager.getInvitablePlayers(player).map(Player::getName),
        )
        return DungeonPartyView(
            available = true,
            inParty = true,
            members = party.membersInDisplayOrder.map { id ->
                val online = Bukkit.getPlayer(id)
                DungeonPartyMember(online?.name ?: id.toString().take(8), id == party.leader, online != null)
            },
            invitableNames = PartyManager.getInvitablePlayers(player).map(Player::getName),
        )
    }

    fun create(player: Player): PartyOperationResult = PartyManager.create(player, false)
    fun invite(player: Player, target: String): PartyOperationResult = PartyManager.invite(player, target.trim(), true)
    fun accept(player: Player): PartyOperationResult = PartyManager.accept(player, true)
    fun leave(player: Player): PartyOperationResult = PartyManager.leave(player, true)
}
