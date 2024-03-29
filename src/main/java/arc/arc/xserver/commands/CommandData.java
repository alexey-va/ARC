package arc.arc.xserver.commands;


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
public class CommandData {

    Sender sender;
    String command;
    @Builder.Default
    boolean everywhere = true;
    boolean notOrigin;
    List<String> servers = new ArrayList<>();
    UUID playerUuid;
    String playerName;

    public enum Sender{
        PLAYER, CONSOLE
    }

}


