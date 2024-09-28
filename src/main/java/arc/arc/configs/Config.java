package arc.arc.configs;

import arc.arc.util.TextUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static arc.arc.util.TextUtil.mm;

@Slf4j
@SuppressWarnings("unchecked")
public class Config {

    Map<String, Object> map;
    private final Path folder;
    private final String filePath;


    @SneakyThrows
    Config(Path folder, String filePath) {
        this.folder = folder;
        this.filePath = filePath;

        copyDefaultConfig(filePath, folder, false);
        load();
    }

    public int integer(String path, int def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        return ((Number) o).intValue();
    }

    public boolean bool(String path, boolean def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        return (boolean) o;
    }

    public double real(String path, double def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        return ((Number) o).doubleValue();
    }

    public String string(String path, String def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        try {
            return (String) o;
        } catch (Exception e) {
            return o.toString();
        }
    }

    public Component component(String path, String... replacement) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, path);
            return mm(path, true);
        }
        String s;
        if (o instanceof String) s = (String) o;
        else s = o.toString();

        for (int i = 0; i < replacement.length; i += 2) {
            if (i + 1 >= replacement.length) break;
            s = s.replace(replacement[i], replacement[i + 1]);
        }

        return mm(s, true);
    }

    public Component componentDef(String path, String def, String... replacement) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return component(path, replacement);
        }
        String s;
        if (o instanceof String) s = (String) o;
        else s = o.toString();

        for (int i = 0; i < replacement.length; i += 2) {
            if (i + 1 >= replacement.length) break;
            s = s.replace(replacement[i], replacement[i + 1]);
        }

        return mm(s, true);
    }

    public Component componentDef(String path, String def, TagResolver tagResolver) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return TextUtil.strip(mm(def, tagResolver));
        }
        String s;
        if (o instanceof String) s = (String) o;
        else s = o.toString();

        return TextUtil.strip(mm(s, tagResolver));
    }

    public Component component(String path, TagResolver tagResolver) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, path);
            return TextUtil.strip(mm(path));
        }
        String s;
        if (o instanceof String) s = (String) o;
        else s = o.toString();

        return TextUtil.strip(mm(s, tagResolver));
    }

    public List<Component> componentList(String path, String... replacement) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, List.of(path));
            return List.of(TextUtil.strip(mm(path)));
        }

        List<Component> list = new ArrayList<>();
        if (o instanceof String s) {
            for (int i = 0; i < replacement.length; i += 2) {
                if (i + 1 >= replacement.length) break;
                s = s.replace(replacement[i], replacement[i + 1]);
            }
            list.add(mm(s, true));
        } else if (o instanceof List l) {
            for (Object obj : l) {
                if (obj instanceof String s1) {
                    for (int i = 0; i < replacement.length; i += 2) {
                        if (i + 1 >= replacement.length) break;
                        s1 = s1.replace(replacement[i], replacement[i + 1]);
                    }
                    list.add(mm(s1, true));
                }
            }
        }
        return list;
    }

    public List<Component> componentListDef(String path, List<String> def, String... replacement) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return componentList(path, replacement);
        }

        List<Component> list = new ArrayList<>();
        if (o instanceof String s) {
            for (int i = 0; i < replacement.length; i += 2) {
                if (i + 1 >= replacement.length) break;
                s = s.replace(replacement[i], replacement[i + 1]);
            }
            list.add(mm(s, true));
        } else if (o instanceof List l) {
            for (Object obj : l) {
                if (obj instanceof String s1) {
                    for (int i = 0; i < replacement.length; i += 2) {
                        if (i + 1 >= replacement.length) break;
                        s1 = s1.replace(replacement[i], replacement[i + 1]);
                    }
                    list.add(mm(s1, true));
                }
            }
        }
        return list;
    }

    public List<String> stringList(String path) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, new ArrayList<String>());
            return new ArrayList<>();
        }
        if (o instanceof String s) {
            return List.of(s);
        }
        return (List<String>) o;
    }

    public List<String> stringList(String path, List<String> def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        if (o instanceof String s) {
            return List.of(s);
        }
        return (List<String>) o;
    }

    public <T> Map<String, T> map(String path) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, new LinkedHashMap<String, T>());
            return new LinkedHashMap<>();
        }
        return (Map<String, T>) o;
    }

    public <T> Map<String, T> map(String path, Map<String, T> def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        return (Map<String, T>) o;
    }

    public <T> List<T> list(String path) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, new ArrayList<>());
            return new ArrayList<>();
        }
        return (List<T>) o;
    }

    public <T> List<T> list(String path, List<T> def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        return (List<T>) o;
    }

    public List<String> keys(String path) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, Map.of());
            return new ArrayList<>();
        }
        return new ArrayList<>(((Map<String, Object>) o).keySet());
    }

    private Object getValueForKeyPath(String keyPath) {
        String[] keyParts = keyPath.split("\\.");

        Map<String, Object> currentLevel = map;
        for (int i = 0; i < keyParts.length; i++) {
            String keyPart = keyParts[i];
            if (currentLevel.containsKey(keyPart)) {
                if (i == keyParts.length - 1) return currentLevel.get(keyPart);
                if (currentLevel.get(keyPart) instanceof Map) {
                    currentLevel = (Map<String, Object>) currentLevel.get(keyPart);
                }
            } else {
                // Key not found or not a mapping; handle accordingly
                System.out.println("Key not found: " + keyPath);
                return null;
            }
        }
        // Access the value at the final level
        return currentLevel.get(keyParts[keyParts.length - 1]);
    }

    public void injectDeepKey(String keyPath, Object value) {
        try {
            System.out.println("Injecting key: " + keyPath + " with value: " + value);
            load();
            String[] keyParts = keyPath.split("\\.");

            Map<String, Object> currentLevel = map;
            for (int i = 0; i < keyParts.length - 1; i++) {
                currentLevel.putIfAbsent(keyParts[i], new LinkedHashMap<>());
                currentLevel = (Map<String, Object>) currentLevel.get(keyParts[i]);
            }

            // Inject the value at the final level
            currentLevel.put(keyParts[keyParts.length - 1], value);
            save();
        } catch (Exception e) {
            System.out.println("Could not inject key: " + keyPath + " with value: " + value);
            e.printStackTrace();
        }
    }

    @SneakyThrows
    public void load() {
        Yaml yaml = new Yaml();
        File configFile = folder.resolve(filePath).toFile();
        map = yaml.load(new FileInputStream(configFile));
        if (map == null) map = new HashMap<>();
        System.out.println("Loaded config: " + filePath);
    }

    public void save() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        String file = folder.resolve(filePath).toFile().getPath();
        try (var writer = new FileWriter(file)) {
            yaml.dump(map, writer);
        } catch (Exception e) {
            log.error("Could not save config: {}", file);
        }
    }


    public static void copyDefaultConfig(String resource, Path folder, boolean replace) {
        try (var stream = Config.class.getClassLoader().getResourceAsStream(resource)) {
            Path path = folder.resolve(resource);
            if (Files.exists(path)) {
                if (!replace) {
                    //System.out.println(resource + " already exists! Skipping...");
                    return;
                }
            }
            Files.createDirectories(path.getParent());
            if (stream == null) {
                Files.createFile(path);
            } else {
                Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error("Could not copy default config: {}", resource);
        }
    }


    public String string(String s) {
        return string(s, s);
    }

    public void addToList(String path, Object value) {
        List<Object> list = list(path);
        list.add(value);
        injectDeepKey(path, list);
    }

    public Set<Material> materialSet(String s) {
        List<String> list = stringList(s);
        Set<Material> materials = new HashSet<>();
        for (String mat : list) {
            try {
                materials.add(Material.valueOf(mat.toUpperCase()));
            } catch (Exception e) {
                log.warn("Could not parse material: {}", mat);
            }
        }
        return materials;
    }

    public Set<Material> materialSet(String s, Set<Material> def) {
        List<String> list = stringList(s, def.stream().map(Enum::name).toList());
        Set<Material> materials = new HashSet<>();
        for (String mat : list) {
            try {
                materials.add(Material.valueOf(mat.toUpperCase()));
            } catch (Exception e) {
                log.warn("Could not parse material: {}", mat);
            }
        }
        return materials;
    }

    public Particle particle(String s, Particle def) {
        try {
            return Particle.valueOf(string(s, def.name()).toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }

    public @NotNull Material material(String s, Material material) {
        try {
            return Material.valueOf(string(s, material.name()).toUpperCase());
        } catch (Exception e) {
            return material;
        }
    }
}
