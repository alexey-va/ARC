package me.dartanman.duels.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import me.dartanman.duels.model.DuelMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class RecoveryStoreTest {
    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void snapshotSurvivesMutationAndIsAcknowledgedOnlyAfterRestore(@TempDir Path temp) throws Exception {
        PlayerMock player = server.addPlayer("SafetyTest");
        player.teleport(new Location(server.getWorld("world"), 12.5, 70.0, -3.5, 90.0f, 5.0f));
        player.getInventory().setItem(7, new ItemStack(Material.DIAMOND, 17));
        player.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        player.setLevel(9);
        player.setExp(0.5f);
        player.setFoodLevel(13);
        player.setGameMode(GameMode.SURVIVAL);

        PlayerSnapshot captured = PlayerSnapshot.capture(player, DuelMode.KIT, 1234L);
        RecoveryStore store = new RecoveryStore(temp.resolve("recovery"));
        store.prepare(captured);

        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.STICK));
        player.setLevel(0);
        player.setFoodLevel(20);
        player.teleport(new Location(server.getWorld("world"), 100.0, 90.0, 100.0));

        PlayerSnapshot loaded = store.load(player.getUniqueId()).orElseThrow();
        loaded.apply(player);

        assertEquals(Material.DIAMOND, player.getInventory().getItem(7).getType());
        assertEquals(17, player.getInventory().getItem(7).getAmount());
        assertEquals(Material.DIAMOND_HELMET, player.getInventory().getHelmet().getType());
        assertEquals(9, player.getLevel());
        assertEquals(13, player.getFoodLevel());
        assertEquals(12.5, player.getLocation().getX());
        assertTrue(store.has(player.getUniqueId()), "journal must remain until durable player save succeeds");

        store.acknowledge(player.getUniqueId());
        assertFalse(store.has(player.getUniqueId()));
    }

    @Test
    void refusesToOverwriteAnUnresolvedSnapshot(@TempDir Path temp) throws Exception {
        PlayerMock player = server.addPlayer("NoOverwrite");
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player, DuelMode.OWN_INVENTORY, 1L);
        RecoveryStore store = new RecoveryStore(temp.resolve("recovery"));
        store.prepare(snapshot);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> store.prepare(snapshot));
        assertEquals(1, store.pendingPlayers().size());
    }
}
