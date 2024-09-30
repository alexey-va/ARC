package arc.arc;

import arc.arc.network.adapters.PolymorphismAdapter;
import arc.arc.network.repos.ItemList;
import arc.arc.network.repos.ItemListSerializer;
import arc.arc.network.repos.ItemStackSerializer;
import arc.arc.util.Common;
import arc.arc.xserver.announcements.ArcCondition;
import arc.arc.xserver.announcements.PermissionCondition;
import arc.arc.xserver.announcements.PlayerCondition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.swing.tree.TreeNode;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Log4j2
class AnnouneConfigTest {

    public static class TestClass {
        List<String> list = new ArrayList<>();
    }

    @Test
    public void test() throws Exception {
        Gson gson = Common.gson;
        String json = gson.toJson(new PermissionCondition("test"));
        PermissionCondition permissionCondition = gson.fromJson(json, PermissionCondition.class);
        log.info(permissionCondition);
        log.info(json);

        PlayerCondition playerCondition = new PlayerCondition();
        playerCondition.setUuid(UUID.randomUUID());
        json = gson.toJson(playerCondition);
        log.info(json);
        PlayerCondition playerCondition1 = gson.fromJson(json, PlayerCondition.class);
        log.info(playerCondition1);
    }
}