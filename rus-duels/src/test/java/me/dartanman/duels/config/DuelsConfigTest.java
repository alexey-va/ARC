package me.dartanman.duels.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import me.dartanman.duels.model.DuelMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class DuelsConfigTest {
    private static final Logger LOGGER = Logger.getLogger(DuelsConfigTest.class.getName());

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void loadsOwnInventoryServerWithoutAKit(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("rusduels.yml");
        Files.writeString(file, """
                enabled: true
                mode: OWN_INVENTORY
                request-expiration-seconds: 30
                countdown-seconds: 3
                allowed-commands: [msg, r]
                arenas:
                  snow:
                    first: {world: pvp, x: 1, y: 2, z: 3, yaw: 4, pitch: 5}
                    second: {world: pvp, x: 6, y: 7, z: 8, yaw: 9, pitch: 10}
                kit: {items: [], armor: {}, offhand: null}
                """);

        DuelsConfig config = DuelsConfig.load(file.toFile(), LOGGER);

        assertTrue(config.enabled());
        assertEquals(DuelMode.OWN_INVENTORY, config.mode());
        assertEquals(1, config.arenas().size());
        assertTrue(config.kit().isEmpty());
    }

    @Test
    void loadsKitServerAndRejectsAnEmptyKit(@TempDir Path temp) throws Exception {
        Path valid = temp.resolve("valid.yml");
        Files.writeString(valid, """
                enabled: true
                mode: KIT
                request-expiration-seconds: 30
                countdown-seconds: 3
                arenas:
                  kit:
                    first: {world: pvp, x: 1, y: 2, z: 3}
                    second: {world: pvp, x: 6, y: 7, z: 8}
                kit:
                  items:
                    - {slot: 0, material: DIAMOND_SWORD}
                  armor:
                    helmet: DIAMOND_HELMET
                  offhand: SHIELD
                """);
        DuelsConfig config = DuelsConfig.load(valid.toFile(), LOGGER);
        assertEquals(DuelMode.KIT, config.mode());
        assertFalse(config.kit().isEmpty());

        Path invalid = temp.resolve("invalid.yml");
        Files.writeString(invalid, Files.readString(valid).replace(
                """
                  items:
                    - {slot: 0, material: DIAMOND_SWORD}
                  armor:
                    helmet: DIAMOND_HELMET
                  offhand: SHIELD
                """,
                """
                  items: []
                  armor: {}
                  offhand: null
                """
        ));

        assertThrows(IllegalArgumentException.class, () -> DuelsConfig.load(invalid.toFile(), LOGGER));
    }
}
