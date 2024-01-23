package arc.arc.xserver.commands;


import arc.arc.network.ArcSerializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommandData extends ArcSerializable {

    Sender sender;
    String command;
    @Builder.Default
    boolean everywhere = true;
    List<String> servers = new ArrayList<>();
    UUID playerUuid;
    String playerName;

    public enum Sender{
        PLAYER, CONSOLE
    }

}


