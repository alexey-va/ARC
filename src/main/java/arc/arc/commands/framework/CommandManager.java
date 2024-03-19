package arc.arc.commands.framework;

import arc.arc.ARC;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CommandManager {

    static Map<String, ArcCommand> commandMap = new ConcurrentHashMap<>();

    public static void addCommand(ArcCommand arcCommand) {
        commandMap.put(arcCommand.name, arcCommand);
    }

    static void registerAllCommands() {
        commandMap.forEach(CommandManager::registerCommand);
        syncCommands();
    }

    private static void syncCommands(){
        try{
            final Server server = Bukkit.getServer();
            final Method syncCommandsMethod = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
            syncCommandsMethod.setAccessible(true);
            syncCommandsMethod.invoke(server);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void registerCommand(String name, ArcCommand arcCommand) {
        try{
            final Server server = Bukkit.getServer();
            Field commandMapField = server.getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            final CommandMap commandMap = (CommandMap) commandMapField.get(server);
            Constructor<PluginCommand> commandConstructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            commandConstructor.setAccessible(true);
            PluginCommand pluginCommand = commandConstructor.newInstance(name, ARC.plugin);
            commandMap.register(name, pluginCommand);
            pluginCommand.setExecutor(arcCommand);
            pluginCommand.setTabCompleter(arcCommand);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
