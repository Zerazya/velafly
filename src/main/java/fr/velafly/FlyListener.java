package fr.velafly;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class FlyListener implements Listener {

    private final VelaFlyPlugin plugin;
    private final FlyManager fly;

    public FlyListener(VelaFlyPlugin plugin) {
        this.plugin = plugin;
        this.fly = plugin.fly();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        fly.sync(e.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> fly.sync(e.getPlayer()));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> fly.sync(e.getPlayer()));
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> fly.sync(e.getPlayer()));
    }

    // ---------------------------------------------------------------- chute et PvP

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p
                && fly.consumeFallGrace(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!plugin.getConfig().getBoolean("fly.disable-on-pvp", true) || !(e.getEntity() instanceof Player victim)) {
            return;
        }
        Entity damager = e.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!(damager instanceof Player attacker) || attacker.equals(victim)) {
            return;
        }
        for (Player p : new Player[]{attacker, victim}) {
            if (fly.active(p.getUniqueId()) && !p.hasPermission("velafly.bypass.pvp")) {
                fly.disable(p, "pvp-disabled");
            }
        }
    }

    // ---------------------------------------------------------------- bon de vol (clic droit sur le papier)

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseVoucher(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        long secs = plugin.voucherSeconds(e.getItem());
        if (secs <= 0) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("velafly.redeem")) {
            plugin.send(p, "no-permission");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (plugin.voucherSeconds(hand) != secs) {
            return;
        }
        int count = p.isSneaking() ? hand.getAmount() : 1;
        long max = plugin.getConfig().getLong("limits.max-seconds", 0);
        long current = fly.seconds(p.getUniqueId());
        if (max > 0) {
            long room = max - current;
            if (room < secs) {
                plugin.send(p, "redeem-limit", "max", TimeUtil.format(max));
                return;
            }
            count = (int) Math.min(count, room / secs);
        }
        // le papier est retiré AVANT d'ajouter le temps (aucune duplication possible)
        if (hand.getAmount() <= count) {
            p.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - count);
        }
        long total = secs * count;
        fly.add(p.getUniqueId(), total, p.getName());
        fly.saveNow();
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        plugin.send(p, "redeemed", "amount", count, "time", TimeUtil.format(total),
                "total", TimeUtil.format(fly.seconds(p.getUniqueId())));
        if (plugin.getConfig().getBoolean("voucher.auto-enable", false) && !fly.active(p.getUniqueId())) {
            fly.enable(p);
        }
    }

    /** Le papier n'est pas utilisable en fabrication (cartes, livres...). */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack item : e.getInventory().getMatrix()) {
            if (plugin.voucherSeconds(item) > 0) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }
}
