package ru.arc.hooks

import com.alessiodp.parties.api.Parties
import java.util.UUID

class PartiesHook {

    private val api = Parties.getApi()

    fun description(playerUuid: UUID): String? {
        val player = api.getPartyPlayer(playerUuid) ?: return null
        if (!player.isInParty) return null
        val partyId = player.partyId ?: return null
        return api.getParty(partyId)?.description
    }

    fun name(playerUuid: UUID): String? {
        val player = api.getPartyPlayer(playerUuid) ?: return null
        if (!player.isInParty) return null
        val partyId = player.partyId ?: return null
        return api.getParty(partyId)?.name
    }

    fun tag(playerUuid: UUID): String? {
        val player = api.getPartyPlayer(playerUuid) ?: return null
        if (!player.isInParty) return null
        val partyId = player.partyId ?: return null
        return api.getParty(partyId)?.tag
    }

    fun color(playerUuid: UUID): String {
        val player = api.getPartyPlayer(playerUuid) ?: return ""
        if (!player.isInParty) return ""
        val partyId = player.partyId ?: return ""
        val party = api.getParty(partyId) ?: return ""
        return party.color?.code ?: ""
    }
}
