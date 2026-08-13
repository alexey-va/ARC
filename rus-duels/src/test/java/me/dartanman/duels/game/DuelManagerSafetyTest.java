package me.dartanman.duels.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.dartanman.duels.Duels;
import me.dartanman.duels.config.DuelsConfig;
import me.dartanman.duels.config.KitDefinition;
import me.dartanman.duels.model.Arena;
import me.dartanman.duels.model.DuelMode;
import me.dartanman.duels.recovery.RecoveryStore;
import me.dartanman.duels.stats.ArcStatsNotifier;
import me.dartanman.duels.stats.StatsRegistry;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class DuelManagerSafetyTest {
    private ServerMock server;
    private Duels plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(Duels.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void ownInventoryMatchRestoresBothPlayersAndRecordsOneResult(@TempDir Path temp) {
        Fixture fixture = fixture(temp, DuelMode.OWN_INVENTORY);
        PlayerMock winner = server.addPlayer("Winner");
        PlayerMock loser = server.addPlayer("Loser");
        winner.getInventory().setItem(5, new ItemStack(Material.DIAMOND, 4));
        loser.getInventory().setItem(8, new ItemStack(Material.EMERALD, 7));

        assertTrue(fixture.manager.start(winner, loser));
        winner.getInventory().clear();
        loser.getInventory().clear();
        fixture.manager.defeat(loser, DuelManager.EndReason.DEFEAT);

        assertEquals(Material.DIAMOND, winner.getInventory().getItem(5).getType());
        assertEquals(4, winner.getInventory().getItem(5).getAmount());
        assertEquals(Material.EMERALD, loser.getInventory().getItem(8).getType());
        assertEquals(7, loser.getInventory().getItem(8).getAmount());
        assertFalse(fixture.recovery.has(winner.getUniqueId()));
        assertFalse(fixture.recovery.has(loser.getUniqueId()));
        assertEquals(1, fixture.stats.get(winner.getUniqueId()).wins());
        assertEquals(1, fixture.stats.get(loser.getUniqueId()).losses());
        fixture.close();
    }

    @Test
    void kitMutationCanBeRecoveredByANewManagerAfterSimulatedCrash(@TempDir Path temp) {
        Fixture fixture = fixture(temp, DuelMode.KIT);
        PlayerMock first = server.addPlayer("CrashOne");
        PlayerMock second = server.addPlayer("CrashTwo");
        first.getInventory().setItem(2, new ItemStack(Material.NETHER_STAR));
        second.getInventory().setItem(3, new ItemStack(Material.ELYTRA));

        assertTrue(fixture.manager.start(first, second));
        assertEquals(Material.STONE_SWORD, first.getInventory().getItem(0).getType());
        assertTrue(fixture.recovery.has(first.getUniqueId()));
        assertTrue(fixture.recovery.has(second.getUniqueId()));

        DuelManager restarted = new DuelManager(plugin, fixture.config, fixture.recovery, fixture.stats, player -> { });
        assertTrue(restarted.restorePending(first));
        assertTrue(restarted.restorePending(second));

        assertEquals(Material.NETHER_STAR, first.getInventory().getItem(2).getType());
        assertEquals(Material.ELYTRA, second.getInventory().getItem(3).getType());
        assertFalse(fixture.recovery.has(first.getUniqueId()));
        assertFalse(fixture.recovery.has(second.getUniqueId()));
        fixture.close();
    }

    private Fixture fixture(Path temp, DuelMode mode) {
        Arena.Point first = new Arena.Point("world", 0.0, 70.0, 0.0, 0.0f, 0.0f);
        Arena.Point second = new Arena.Point("world", 10.0, 70.0, 0.0, 180.0f, 0.0f);
        Arena arena = new Arena("test", first, second, 32.0);
        KitDefinition kit = new KitDefinition(
                mode == DuelMode.KIT ? Map.of(0, new ItemStack(Material.STONE_SWORD)) : Map.of(),
                null,
                null,
                null,
                null,
                null
        );
        DuelsConfig config = new DuelsConfig(true, mode, 30, 0, Set.of("msg"), List.of(arena), kit);
        RecoveryStore recovery = new RecoveryStore(temp.resolve("recovery"));
        StatsRegistry stats = new StatsRegistry(
                temp.resolve("stats.yml"),
                plugin.getLogger(),
                new ArcStatsNotifier(plugin.getLogger())
        );
        stats.load();
        DuelManager manager = new DuelManager(plugin, config, recovery, stats, player -> { });
        return new Fixture(config, recovery, stats, manager);
    }

    private record Fixture(DuelsConfig config, RecoveryStore recovery, StatsRegistry stats, DuelManager manager) {
        void close() {
            stats.close(Duration.ofSeconds(2));
        }
    }
}
