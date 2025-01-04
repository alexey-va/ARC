package arc.arc.hooks.ztranslator;

import arc.arc.ARC;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Log4j2
public class TranslatorHook {

    Map<String, String> map = new HashMap<>();
    Gson gson = new Gson();

    public TranslatorHook() {
        Path path = ARC.plugin.getDataPath().resolve("lang.json");
        if (!Files.exists(path)) {
            log.warn("lang.json is not present in ARC folder! Disabling translating materials...");
            return;
        }
        try {
            String s = Files.readString(path);
            TypeToken<Map<String, String>> token = new TypeToken<>() {
            };
            map = gson.fromJson(s, token);
        } catch (Exception e) {
            log.error("Error loading translations", e);
        }
    }

    public String translate(ItemStack stack) {
        if (stack == null) return "Null";
        return map.getOrDefault(stack.translationKey(), toReadableName(stack.getType().name()));
    }

    public String translate(Material material) {
        return map.getOrDefault(material.translationKey(), toReadableName(material.name()));
    }

    private String toReadableName(String name) {
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (char c : name.toCharArray()) {
            if (start) {
                sb.append(Character.toUpperCase(c));
                start = false;
            }else if (c == '_') {
                sb.append(" ");
                start = true;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
