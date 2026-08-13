package me.dartanman.duels.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.Test;

class DuelListenerContractTest {
    @Test
    void forcedDeathFallbackRunsBeforeExternalInventoryListeners() throws Exception {
        EventHandler handler = DuelListener.class
                .getMethod("onDeath", PlayerDeathEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.LOWEST, handler.priority());
    }

    @Test
    void pluginLoadsBeforeInventoryAndGraveConsumers() throws Exception {
        try (var resource = DuelListenerContractTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            assertTrue(resource != null);
            YamlConfiguration plugin = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(resource, StandardCharsets.UTF_8)
            );
            List<String> loadBefore = plugin.getStringList("loadbefore");
            assertTrue(loadBefore.containsAll(List.of("ARC", "HuskSync", "GravesX")));
        }
    }
}
