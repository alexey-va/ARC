package arc.arc.util;

import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextUtilTest {

    @Test
    void testFormat(){
        double a = 0.73124;
        for (int i = 0; i < 7; i++) {
            System.out.println(TextUtil.formatAmount(a, i));
        }
    }

}