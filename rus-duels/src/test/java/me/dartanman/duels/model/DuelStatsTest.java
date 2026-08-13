package me.dartanman.duels.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DuelStatsTest {
    @Test
    void recordsModeSpecificWinsLossesAndStreaks() {
        DuelStats winner = DuelStats.empty().win(DuelMode.KIT, 1016, 100L).win(DuelMode.OWN_INVENTORY, 1032, 200L);
        DuelStats loser = DuelStats.empty().loss(DuelMode.KIT, 984, 100L);

        assertEquals(2, winner.wins());
        assertEquals(2, winner.currentStreak());
        assertEquals(2, winner.bestStreak());
        assertEquals(1, winner.kitWins());
        assertEquals(1, winner.ownWins());
        assertEquals(1, loser.losses());
        assertEquals(1, loser.kitLosses());
        assertEquals(0, loser.currentStreak());
    }

    @Test
    void mapRoundTripKeepsTheNetworkContract() {
        DuelStats expected = new DuelStats(4, 3, 1055, 2, 3, 1, 2, 3, 1, 999L);
        assertEquals(expected, DuelStats.fromMap(expected.toMap()));
    }
}
