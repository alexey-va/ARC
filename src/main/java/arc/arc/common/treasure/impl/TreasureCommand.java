package arc.arc.common.treasure.impl;

import arc.arc.ARC;
import arc.arc.common.treasure.GiveFlags;
import arc.arc.common.treasure.Treasure;
import arc.arc.hooks.HookRegistry;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class TreasureCommand extends Treasure {

    List<String> commands;

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {
        for (String s : commands) {
            String command = HookRegistry.papiHook == null ? s :
                    HookRegistry.papiHook.parse(s, player);
            ARC.trySeverCommand(command);
        }
    }

    @Override
    public Map<String, Object> serializeInternal() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "command");
        map.put("commands", commands);
        return map;
    }

    @Override
    protected void setFields(Map<String, Object> map) {
        this.commands = (List<String>) map.getOrDefault("commands", List.of());
        if (this.commands.isEmpty()) {
            log.error("Empty commands list {}", this);
        }
    }

}
