package fr.velafly;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Temps de vol des joueurs : stockage, consommation à la seconde, activation et désactivation du vol. */
public final class FlyManager {

    private static final class Data {
        long seconds;
        boolean active;
        String name = "?";
    }

    private final VelaFlyPlugin plugin;
    private final File file;
    private final Map<UUID, Data> data = new HashMap<>();
    private final Map<UUID, Long> fallGrace = new HashMap<>();
    private boolean dirty;

    private boolean onlyWhenFlying = true;
    private boolean manageOnJoin = true;
    private long graceMillis = 10_000;
    private final Set<Long> warnings = new HashSet<>();
    private final Set<String> disabledWorlds = new HashSet<>();

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VelaFly-IO");
        t.setDaemon(true);
        return t;
    });

    public FlyManager(VelaFlyPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    // ---------------------------------------------------------------- réglages et stockage

    public void reloadSettings() {
        var c = plugin.getConfig();
        onlyWhenFlying = c.getBoolean("fly.consume-only-when-flying", true);
        manageOnJoin = c.getBoolean("fly.reset-flight-on-join", true);
        graceMillis = Math.max(0, c.getLong("fly.fall-protection-seconds", 10)) * 1000L;
        warnings.clear();
        for (long w : c.getLongList("warnings")) {
            warnings.add(w);
        }
        disabledWorlds.clear();
        for (String w : c.getStringList("fly.disabled-worlds")) {
            disabledWorlds.add(w.toLowerCase());
        }
    }

    public void load() {
        reloadSettings();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps == null) {
            return;
        }
        for (String key : ps.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Data d = new Data();
                d.seconds = Math.max(0, ps.getLong(key + ".seconds"));
                d.active = ps.getBoolean(key + ".active");
                d.name = ps.getString(key + ".name", "?");
                data.put(id, d);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Entrée ignorée dans data.yml : " + key);
            }
        }
    }

    /** Enregistre sans bloquer le serveur (écriture atomique). */
    public void saveNow() {
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Data> e : data.entrySet()) {
            Data d = e.getValue();
            if (d.seconds <= 0 && !d.active) {
                continue;
            }
            String p = "players." + e.getKey() + ".";
            y.set(p + "seconds", d.seconds);
            y.set(p + "active", d.active);
            y.set(p + "name", d.name);
        }
        String text = y.saveToString();
        io.execute(() -> {
            Path target = file.toPath();
            Path tmp = target.resolveSibling("data.yml.tmp");
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                plugin.getLogger().severe("Impossible d'écrire data.yml : " + ex.getMessage());
            }
        });
    }

    public void saveIfDirty() {
        if (dirty) {
            saveNow();
        }
    }

    public void shutdown() {
        saveNow();
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- temps

    private Data data(UUID id) {
        return data.computeIfAbsent(id, k -> new Data());
    }

    public long seconds(UUID id) {
        Data d = data.get(id);
        return d == null ? 0 : d.seconds;
    }

    public void setSeconds(UUID id, long seconds, String name) {
        Data d = data(id);
        d.seconds = Math.max(0, seconds);
        if (name != null) {
            d.name = name;
        }
        dirty = true;
    }

    public void add(UUID id, long seconds, String name) {
        setSeconds(id, seconds(id) + seconds, name);
    }

    public boolean active(UUID id) {
        Data d = data.get(id);
        return d != null && d.active;
    }

    public boolean unlimited(Player p) {
        return p.hasPermission("velafly.unlimited");
    }

    public String timeText(Player p) {
        return unlimited(p) ? plugin.getConfig().getString("unlimited-text", "illimité") : TimeUtil.format(seconds(p.getUniqueId()));
    }

    private boolean worldDisabled(World w) {
        return disabledWorlds.contains(w.getName().toLowerCase());
    }

    private static boolean survivalLike(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    // ---------------------------------------------------------------- activation du vol

    public boolean enable(Player p) {
        if (!survivalLike(p)) {
            plugin.send(p, "creative");
            return false;
        }
        if (worldDisabled(p.getWorld())) {
            plugin.send(p, "world-disabled");
            return false;
        }
        Data d = data(p.getUniqueId());
        d.name = p.getName();
        if (!unlimited(p) && d.seconds <= 0) {
            plugin.send(p, "no-time");
            return false;
        }
        d.active = true;
        dirty = true;
        p.setAllowFlight(true);
        if (!p.isOnGround()) {
            p.setFlying(true);
        }
        plugin.send(p, "fly-enabled", "time", timeText(p));
        return true;
    }

    /** Coupe le vol. messageKey = message à envoyer (null = silencieux). */
    public void disable(Player p, String messageKey) {
        Data d = data(p.getUniqueId());
        boolean was = d.active;
        d.active = false;
        dirty = true;
        if (survivalLike(p)) {
            boolean inAir = p.isFlying() || !p.isOnGround();
            p.setAllowFlight(false);
            if (inAir && graceMillis > 0) {
                fallGrace.put(p.getUniqueId(), System.currentTimeMillis() + graceMillis);
            }
        }
        if (messageKey != null && was) {
            plugin.send(p, messageKey);
        }
    }

    /** Dégâts de chute annulés juste après la fin du vol. */
    public boolean consumeFallGrace(UUID id) {
        Long until = fallGrace.get(id);
        if (until == null) {
            return false;
        }
        fallGrace.remove(id);
        return System.currentTimeMillis() < until;
    }

    /** Connexion, changement de monde ou de mode de jeu : remet l'état du vol en cohérence. */
    public void sync(Player p) {
        Data d = data(p.getUniqueId());
        d.name = p.getName();
        if (!survivalLike(p)) {
            return;
        }
        boolean ok = d.active && (unlimited(p) || d.seconds > 0) && !worldDisabled(p.getWorld());
        if (ok) {
            p.setAllowFlight(true);
        } else {
            if (d.active) {
                d.active = false;
                dirty = true;
            }
            if (manageOnJoin) {
                p.setAllowFlight(false);
            }
        }
    }

    // ---------------------------------------------------------------- boucle (chaque seconde)

    public void tick() {
        var cfg = plugin.getConfig();
        long now = System.currentTimeMillis();
        fallGrace.values().removeIf(t -> t < now);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Data d = data.get(p.getUniqueId());
            if (d == null || !d.active || !survivalLike(p)) {
                continue;
            }
            if (worldDisabled(p.getWorld())) {
                disable(p, "world-disabled");
                continue;
            }
            if (!p.getAllowFlight()) {
                p.setAllowFlight(true);
            }
            boolean unlimited = unlimited(p);
            if (!unlimited && (!onlyWhenFlying || p.isFlying())) {
                d.seconds--;
                dirty = true;
                if (d.seconds <= 0) {
                    d.seconds = 0;
                    disable(p, "time-up");
                    continue;
                }
                if (warnings.contains(d.seconds)) {
                    plugin.send(p, "time-warning", "time", TimeUtil.format(d.seconds));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                }
            }
            if (cfg.getBoolean("actionbar.enabled", true) && (p.isFlying() || !onlyWhenFlying)) {
                String key = unlimited ? "actionbar.unlimited" : "actionbar.format";
                Component bar = plugin.component(cfg.getString(key, "<aqua>✈ <white><time>"), "time", timeText(p));
                p.sendActionBar(bar);
            }
        }
    }

    public List<String> knownNames() {
        return data.values().stream().map(d -> d.name).filter(n -> !"?".equals(n)).distinct().toList();
    }
}
