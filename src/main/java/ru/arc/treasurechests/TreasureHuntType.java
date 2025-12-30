package ru.arc.treasurechests;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import ru.arc.common.WeightedRandom;
import ru.arc.common.locationpools.LocationPool;
import ru.arc.common.locationpools.LocationPoolManager;

import static ru.arc.util.Logging.info;

@Data
@AllArgsConstructor
@Builder
public class TreasureHuntType {
    @Builder.Default
    WeightedRandom<ChestType> entries = new WeightedRandom<>();
    String locationPoolId;
    String bossBarMessage;
    boolean bossBarVisible;
    String bossBarColor;
    String bossBarOverlay;
    long secondsTTL;
    boolean announceStop;
    String stopMessage;
    boolean announceStart;
    boolean announceStartGlobally;
    String startMessage;
    boolean launchFireworks;

    public ChestType getRandomChestType() {
        return entries.random();
    }

    public LocationPool getLocationPool() {
        LocationPool pool = LocationPoolManager.getPool(locationPoolId);
        if (pool == null) {
            info("Could not find location pool with id: {}", locationPoolId);
            return null;
        }
        return pool;
    }
}

