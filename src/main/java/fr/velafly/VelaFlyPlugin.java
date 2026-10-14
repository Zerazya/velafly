package fr.velafly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class VelaFlyPlugin extends JavaPlugin {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private FlyManager fly;
    private NamespacedKey voucherKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        voucherKey = new NamespacedKey(this, "fly_voucher");
        fly = new FlyManager(this);
        fly.load();

        getServer().getPluginManager().registerEvents(new FlyListener(this), this);

        FlyCommands commands = new FlyCommands(this);
        for (String name : List.of("fly", "flytime", "velafly")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }

        Bukkit.getScheduler().runTaskTimer(this, fly::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, fly::saveIfDirty, 400L, 400L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            fly.sync(p);
        }
    }

    @Override
    public void onDisable() {
        if (fly != null) {
            fly.shutdown();
        }
    }

    public void reload() {
        reloadConfig();
        fly.reloadSettings();
    }

    public FlyManager fly() {
        return fly;
    }

    // ---------------------------------------------------------------- messages

    public Component component(String text, Object... kv) {
        TagResolver.Builder b = TagResolver.builder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.resolver(Placeholder.unparsed(String.valueOf(kv[i]), String.valueOf(kv[i + 1])));
        }
        return mm.deserialize(text, b.build());
    }

    public Component msg(String key, Object... kv) {
        return component(getConfig().getString("messages." + key, "<red>Message manquant : " + key), kv);
    }

    public void send(CommandSender to, String key, Object... kv) {
        to.sendMessage(msg("prefix").append(msg(key, kv)));
    }

    // ---------------------------------------------------------------- bon de vol (papier)

    public ItemStack voucher(long seconds, int amount) {
        String matName = getConfig().getString("voucher.material", "PAPER");
        Material material = Material.matchMaterial(matName == null ? "PAPER" : matName);
        if (material == null || !material.isItem() || material.isAir()) {
            material = Material.PAPER;
        }
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        String time = TimeUtil.format(seconds);
        meta.displayName(component(getConfig().getString("voucher.name", "<gold><bold>Bon de vol <gray>- <yellow><time>"),
                "time", time).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("voucher.lore")) {
            lore.add(component(line, "time", time).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        if (getConfig().getBoolean("voucher.glow", true)) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(voucherKey, PersistentDataType.LONG, seconds);
        item.setItemMeta(meta);
        return item;
    }

    /** Secondes de vol contenues dans ce papier (0 si ce n'est pas un bon de vol). */
    public long voucherSeconds(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Long v = item.getItemMeta().getPersistentDataContainer().get(voucherKey, PersistentDataType.LONG);
        return v == null ? 0 : Math.max(0, v);
    }
}
