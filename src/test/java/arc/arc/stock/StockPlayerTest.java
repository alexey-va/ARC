package arc.arc.stock;

import com.google.gson.Gson;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class StockPlayerTest {

    @Test
    void testGsonStockPlayerSerialization(){
        StockPlayer stockPlayer = new StockPlayer("TestPlayer", null);
        stockPlayer.setBalance(100);
        stockPlayer.setReceivedDividend(10);
        stockPlayer.setTotalGains(20);
        List<Position> positions = new ArrayList<>();
        positions.add(Position.builder()
                .symbol("TestStock")
                .amount(10)
                .commission(0.1)
                .iconMaterial(Material.PAPER)
                .leverage(1)
                .lowerBoundMargin(0.1)
                .startPrice(10)
                .timestamp(System.currentTimeMillis())
                .type(Position.Type.BOUGHT)
                .upperBoundMargin(0.1)
                .positionUuid(UUID.randomUUID())
                .build());
        positions.add(Position.builder()
                .symbol("TestStock")
                .amount(10)
                .commission(0.1)
                .iconMaterial(Material.PAPER)
                .leverage(1)
                .lowerBoundMargin(0.1)
                .startPrice(10)
                .timestamp(System.currentTimeMillis())
                .type(Position.Type.BOUGHT)
                .upperBoundMargin(0.1)
                .positionUuid(UUID.randomUUID())
                .build());
        stockPlayer.positionMap.put("TestStock", positions);

        Gson gson = new Gson();
        System.out.println(gson.toJson(stockPlayer));
        StockPlayer deserialized = gson.fromJson(gson.toJson(stockPlayer), StockPlayer.class);
        System.out.println(deserialized.getPositionMap().getClass());
        assertEquals(stockPlayer, deserialized);
    }

}