package me.dartanman.duels.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import me.dartanman.duels.Duels;
import me.dartanman.duels.game.ChallengeService;
import me.dartanman.duels.game.DuelManager;
import me.dartanman.duels.model.DuelStats;
import me.dartanman.duels.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DuelCommand implements CommandExecutor, TabCompleter {
    private final Duels plugin;
    private final DuelManager manager;
    private final ChallengeService challenges;

    public DuelCommand(Duels plugin, DuelManager manager, ChallengeService challenges) {
        this.plugin = plugin;
        this.manager = manager;
        this.challenges = challenges;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "<#c42323>Команда доступна только игрокам.");
            return true;
        }
        if (!player.hasPermission("duels.use")) {
            Messages.send(player, "<#c42323>Недостаточно прав.");
            return true;
        }
        if (args.length == 0) {
            help(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "accept", "принять" -> accept(player, args);
            case "deny", "decline", "отклонить" -> deny(player, args);
            case "leave", "quit", "сдаться" -> {
                if (!manager.isInDuel(player.getUniqueId())) Messages.send(player, "<#ff9f0f>Вы не участвуете в дуэли.");
                else manager.forfeit(player);
            }
            case "stats", "стата" -> stats(player, args);
            case "help", "помощь" -> help(player);
            case "status" -> status(player);
            default -> challenge(player, args[0]);
        }
        return true;
    }

    private void challenge(Player challenger, String targetName) {
        if (!plugin.duelsConfig().enabled()) {
            Messages.send(challenger, "<#c42323>Дуэли на этом сервере отключены.");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            Messages.send(challenger, "<#c42323>Игрок не найден.");
            return;
        }
        if (target.equals(challenger)) {
            Messages.send(challenger, "<#c42323>Нельзя вызвать себя на дуэль.");
            return;
        }
        if (manager.isInDuel(challenger.getUniqueId()) || manager.isInDuel(target.getUniqueId())) {
            Messages.send(challenger, "<#c42323>Один из игроков уже участвует в дуэли.");
            return;
        }
        challenges.request(challenger.getUniqueId(), target.getUniqueId());
        Messages.send(challenger, "<#e6fff3>Запрос отправлен игроку <#92bed8>" + target.getName() + "<#e6fff3>.");
        Messages.send(target, "<#92bed8>" + challenger.getName() + " <#e6fff3>вызывает вас на дуэль.");
        target.sendMessage(Component.text("/duel accept " + challenger.getName()));
    }

    private void accept(Player target, String[] args) {
        UUID expected = resolveOptionalPlayer(args);
        challenges.accept(target.getUniqueId(), expected).ifPresentOrElse(challenge -> {
            Player challenger = Bukkit.getPlayer(challenge.challenger());
            if (challenger == null || !challenger.isOnline()) {
                Messages.send(target, "<#c42323>Игрок уже вышел с сервера.");
                return;
            }
            manager.start(challenger, target);
        }, () -> Messages.send(target, "<#ff9f0f>Подходящий запрос не найден или он истёк."));
    }

    private void deny(Player target, String[] args) {
        UUID expected = resolveOptionalPlayer(args);
        challenges.deny(target.getUniqueId(), expected).ifPresentOrElse(challenge -> {
            Player challenger = Bukkit.getPlayer(challenge.challenger());
            Messages.send(target, "<#e6fff3>Запрос отклонён.");
            if (challenger != null) Messages.send(challenger, "<#ff9f0f>Игрок отклонил ваш запрос.");
        }, () -> Messages.send(target, "<#ff9f0f>Подходящий запрос не найден или он истёк."));
    }

    private UUID resolveOptionalPlayer(String[] args) {
        if (args.length < 2) return null;
        Player player = Bukkit.getPlayerExact(args[1]);
        return player == null ? UUID.nameUUIDFromBytes(("missing:" + args[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8)) : player.getUniqueId();
    }

    private void stats(Player viewer, String[] args) {
        Player subject = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : viewer;
        if (subject == null) {
            Messages.send(viewer, "<#c42323>Статистика офлайн-игроков доступна только по UUID через администратора.");
            return;
        }
        DuelStats stats = plugin.stats().get(subject.getUniqueId());
        Messages.send(viewer, "<#e6fff3>Статистика <#92bed8>" + subject.getName() + "<#8c8c8c>:"
                + " <#2bba43>" + stats.wins() + " побед"
                + " <#8c8c8c>• <#c42323>" + stats.losses() + " поражений"
                + " <#8c8c8c>• <#92bed8>" + stats.rating() + " рейтинг");
        Messages.send(viewer, "<#e6fff3>Серия: <#92bed8>" + stats.currentStreak()
                + " <#8c8c8c>• <#e6fff3>лучшая: <#92bed8>" + stats.bestStreak());
    }

    private void status(Player player) {
        if (!player.hasPermission("duels.admin")) {
            Messages.send(player, "<#c42323>Недостаточно прав.");
            return;
        }
        Messages.send(player, "<#e6fff3>Режим: <#92bed8>" + plugin.duelsConfig().mode()
                + " <#8c8c8c>• <#e6fff3>арен: <#92bed8>" + plugin.duelsConfig().arenas().size()
                + " <#8c8c8c>• <#e6fff3>запросов: <#92bed8>" + challenges.size());
    }

    private void help(Player player) {
        Messages.send(player, "<#92bed8>/duel <игрок> <#8c8c8c>— <#e6fff3>вызвать на дуэль");
        Messages.send(player, "<#92bed8>/duel accept [игрок] <#8c8c8c>— <#e6fff3>принять запрос");
        Messages.send(player, "<#92bed8>/duel deny [игрок] <#8c8c8c>— <#e6fff3>отклонить запрос");
        Messages.send(player, "<#92bed8>/duel leave <#8c8c8c>— <#e6fff3>сдаться");
        Messages.send(player, "<#92bed8>/duel stats [игрок] <#8c8c8c>— <#e6fff3>посмотреть статистику");
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("accept", "deny", "leave", "stats", "help"));
            Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(values::add);
            return prefix(values, args[0]);
        }
        if (args.length == 2 && List.of("accept", "deny", "stats").contains(args[0].toLowerCase(Locale.ROOT))) {
            return prefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private List<String> prefix(List<String> values, String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
