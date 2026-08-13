package me.dartanman.duels.listeners;

import java.util.Locale;
import java.util.UUID;
import me.dartanman.duels.Duels;
import me.dartanman.duels.game.ChallengeService;
import me.dartanman.duels.game.DuelManager;
import me.dartanman.duels.game.DuelSession;
import me.dartanman.duels.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class DuelListener implements Listener {
    private final Duels plugin;
    private final DuelManager manager;
    private final ChallengeService challenges;

    public DuelListener(Duels plugin, DuelManager manager, ChallengeService challenges) {
        this.plugin = plugin;
        this.manager = manager;
        this.challenges = challenges;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        manager.restorePending(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        challenges.removePlayer(event.getPlayer().getUniqueId());
        manager.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        challenges.removePlayer(event.getPlayer().getUniqueId());
        manager.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        Player victim = event.getEntity() instanceof Player player ? player : null;
        Player attacker = event instanceof EntityDamageByEntityEvent byEntity ? attackingPlayer(byEntity.getDamager()) : null;

        if (attacker != null && manager.isInDuel(attacker.getUniqueId())) {
            if (victim == null || !manager.isOpponent(attacker.getUniqueId(), victim.getUniqueId())
                    || !manager.isActive(attacker.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }

        if (victim == null || !manager.isInDuel(victim.getUniqueId())) return;
        if (!manager.isActive(victim.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent && (attacker == null
                || !manager.isOpponent(victim.getUniqueId(), attacker.getUniqueId()))) {
            event.setCancelled(true);
            return;
        }
        if (event.getFinalDamage() >= victim.getHealth()) {
            event.setCancelled(true);
            manager.defeat(victim, DuelManager.EndReason.DEFEAT);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!manager.isInDuel(player.getUniqueId())) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        manager.defeat(player, DuelManager.EndReason.DEFEAT);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Location returnLocation = manager.consumeRespawnLocation(event.getPlayer().getUniqueId());
        if (returnLocation != null) event.setRespawnLocation(returnLocation);
        Bukkit.getScheduler().runTask(plugin, () -> manager.restorePending(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (manager.isInDuel(playerId) && !manager.isInternalTeleport(playerId)) {
            event.setCancelled(true);
            Messages.send(event.getPlayer(), "<#c42323>Во время дуэли нельзя телепортироваться.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        DuelSession session = manager.session(playerId);
        if (session == null || event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (session.state() == DuelSession.State.COUNTDOWN
                && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
            return;
        }
        if (!manager.isInsideArena(playerId, to)) event.setTo(from);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!manager.isInDuel(event.getPlayer().getUniqueId())) return;
        String raw = event.getMessage().substring(1).trim();
        if (raw.isEmpty()) return;
        String[] parts = raw.split("\\s+", 3);
        String label = parts[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) label = label.substring(namespace + 1);
        boolean duelLeave = (label.equals("duel") || label.equals("duels") || label.equals("1v1"))
                && parts.length >= 2 && parts[1].equalsIgnoreCase("leave");
        if (!duelLeave && !manager.isAllowedCommand(label)) {
            event.setCancelled(true);
            Messages.send(event.getPlayer(), "<#c42323>Эта команда недоступна во время дуэли.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.isInDuel(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && manager.isInDuel(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && manager.isInDuel(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && manager.isInDuel(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && manager.isInDuel(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!manager.isInDuel(event.getPlayer().getUniqueId()) || event.getClickedBlock() == null) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.ALLOW);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (manager.isInDuel(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (manager.isInDuel(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (manager.isInDuel(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (manager.isInDuel(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (manager.isInDuel(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (manager.isInDuel(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && manager.isInDuel(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && manager.isInDuel(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (manager.isInDuel(event.getPlayer().getUniqueId())
                && !manager.isInternalTeleport(event.getPlayer().getUniqueId())
                && event.getNewGameMode() != org.bukkit.GameMode.SURVIVAL) event.setCancelled(true);
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }
}
