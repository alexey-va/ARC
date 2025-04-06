package ru.arc.hooks;

import com.alessiodp.parties.api.Parties;
import com.alessiodp.parties.api.interfaces.PartiesAPI;
import com.alessiodp.parties.api.interfaces.Party;
import com.alessiodp.parties.api.interfaces.PartyPlayer;

import java.util.UUID;

public class PartiesHook {

    PartiesAPI api;

    public PartiesHook(){
        api = Parties.getApi();
    }


    public String description(UUID playerUuid){
        PartyPlayer player = api.getPartyPlayer(playerUuid);
        if(player == null || !player.isInParty()) return null;
        Party party = api.getParty(player.getPartyId());
        return party.getDescription();
    }

    public String name(UUID playerUuid){
        PartyPlayer player = api.getPartyPlayer(playerUuid);
        if(player == null || !player.isInParty()) return null;
        Party party = api.getParty(player.getPartyId());
        return party.getName();
    }

    public String tag(UUID playerUuid){
        PartyPlayer player = api.getPartyPlayer(playerUuid);
        if(player == null || !player.isInParty()) return null;
        UUID uuid = player.getPartyId();
        if(uuid == null) return null;
        Party party = api.getParty(player.getPartyId());
        if(party == null) return null;
        return party.getTag();
    }

    public String color(UUID playerUuid){
        PartyPlayer player = api.getPartyPlayer(playerUuid);
        if(player == null || !player.isInParty()) return "";
        if (player.getPartyId() == null) return "";
        Party party = api.getParty(player.getPartyId());
        if(party == null || party.getColor() == null) return "";
        String code = party.getColor().getCode();
        return code == null ? "" : code;
    }


}
