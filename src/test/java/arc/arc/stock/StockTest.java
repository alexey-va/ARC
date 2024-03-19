package arc.arc.stock;

import arc.arc.board.ItemIcon;
import com.google.gson.Gson;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockTest {

    @Test
    void testSerializing(){
        Stock stock = new Stock("AAPL", 100, 200, 1000L, "USD", List.of("NASDAQ"), ItemIcon.of(Material.PAPER, 0), 1000L, 10000, Stock.Type.STOCK);
        Gson gson = new Gson();
        System.out.println(gson.toJson(stock));
        Stock stock1 = gson.fromJson(gson.toJson(stock), Stock.class);
        System.out.println(stock1.icon);
        assertEquals(stock, stock1);
    }


}