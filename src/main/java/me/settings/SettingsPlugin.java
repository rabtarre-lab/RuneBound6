package me.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SettingsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    // Неоновый градиент: жёлтый -> оранжевый
    private static final TextColor NEON_YELLOW = TextColor.fromHexString("#FFF200");
    private static final TextColor NEON_AMBER = TextColor.fromHexString("#FF9D00");
    private static final TextColor NEON_ORANGE = TextColor.fromHexString("#FF5A00");

    private static final double MIN_H = 0.4, MAX_H = 1.3;
    private static final int[] HEIGHT_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Set<UUID> hiddenNick = new HashSet<>();
    private final Set<UUID> noRide = new HashSet<>();
    private final Set<UUID> pingShown = new HashSet<>();
    private final Set<UUID> pvpOff = new HashSet<>();
    private final Map<UUID, Double> heights = new HashMap<>();
    private final Set<UUID> awaitingHeight = new HashSet<>();

    private File dataFile;
    private Team hiddenTeam;

    enum Type { MAIN, HEIGHT }

    static class Holder implements InventoryHolder {
        final Type type;
        Inventory inv;
        Holder(Type t) { this.type = t; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ================= запуск =================
    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("settings").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        hiddenTeam = sb.getTeam("st_hidden");
        if (hiddenTeam == null) hiddenTeam = sb.registerNewTeam("st_hidden");
        hiddenTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

        // обновление пинга в табе
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers())
                if (pingShown.contains(p.getUniqueId())) updatePing(p);
        }, 40L, 40L);
    }

    @Override
    public void onDisable() { saveData(); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (s instanceof Player p) {
            sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
            openMain(p);
        } else s.sendMessage("Только для игроков.");
        return true;
    }

    // ================= оформление =================
    /** Плавный градиент по буквам, без жирности и курсива. */
    private Component gradient(String text, TextColor... stops) {
        Component out = Component.empty()
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
        int n = text.length();
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0f : (float) i / (n - 1);
            float scaled = t * (stops.length - 1);
            int seg = Math.min((int) scaled, stops.length - 2);
            float k = scaled - seg;
            TextColor a = stops[seg], b = stops[seg + 1];
            int r = Math.round(a.red() + (b.red() - a.red()) * k);
            int g = Math.round(a.green() + (b.green() - a.green()) * k);
            int bl = Math.round(a.blue() + (b.blue() - a.blue()) * k);
            out = out.append(Component.text(String.valueOf(text.charAt(i)), TextColor.color(r, g, bl)));
        }
        return out;
    }

    private Component neon(String text) {
        return gradient(text, NEON_YELLOW, NEON_AMBER, NEON_ORANGE);
    }

    private Component title() { return neon("Settings"); }

    private void msg(Player p, String text) {
        p.sendMessage(neon("Settings").append(LEGACY.deserialize(" §8» §f" + text)));
    }

    private void sound(Player p, Sound s, float pitch) {
        p.playSound(p.getLocation(), s, 0.6f, pitch);
    }

    private ItemStack pane(Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(" "));
        meta.setHideTooltip(true);
        it.setItemMeta(meta);
        return it;
    }

    /** Рамка: чёрное стекло, у углов — жёлтое и оранжевое. */
    private void fill(Inventory inv) {
        int size = inv.getSize();
        int rows = size / 9;
        ItemStack black = pane(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack orange = pane(Material.ORANGE_STAINED_GLASS_PANE);
        ItemStack yellow = pane(Material.YELLOW_STAINED_GLASS_PANE);
        for (int s = 0; s < size; s++) {
            int row = s / 9, col = s % 9;
            boolean edgeRow = row == 0 || row == rows - 1;
            boolean edgeCol = col == 0 || col == 8;
            if (edgeRow && edgeCol) inv.setItem(s, orange);
            else if ((edgeRow && (col == 1 || col == 7)) || (edgeCol && (row == 1 || row == rows - 2)))
                inv.setItem(s, yellow);
            else inv.setItem(s, black);
        }
    }

    private ItemStack item(Material m, String name, boolean glow, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(neon(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    private boolean canHeight(Player p) {
        for (String perm : getConfig().getStringList("height-permissions"))
            if (p.hasPermission(perm)) return true;
        return false;
    }

    // ================= меню =================
    private void openMain(Player p) {
        UUID id = p.getUniqueId();
        Holder h = new Holder(Type.MAIN);
        Inventory inv = Bukkit.createInventory(h, 27, title());
        h.inv = inv;
        fill(inv);

        boolean nick = hiddenNick.contains(id);
        boolean ride = noRide.contains(id);
        boolean ping = pingShown.contains(id);
        boolean pvp = pvpOff.contains(id);
        double cur = heights.getOrDefault(id, 1.0);

        inv.setItem(11, item(Material.ENDER_CHEST, "Скрыть ник", nick,
                "§7Прячет твой ник над головой.", "",
                "§7Сейчас: " + (nick ? "§c● Ник скрыт" : "§a● Ник виден"),
                "", "§8» §eНажми, чтобы переключить"));

        inv.setItem(12, item(Material.AMETHYST_CLUSTER, "Сменить рост", cur != 1.0,
                "§7Выбери рост от §f" + MIN_H + " §7до §f" + MAX_H + "§7.", "",
                "§7Сейчас: §f" + cur,
                canHeight(p) ? "" : "§c✖ Только для Sponsor и Sponsor+",
                canHeight(p) ? "§8» §eНажми, чтобы открыть" : ""));

        inv.setItem(13, item(Material.SLIME_BLOCK, "Сидеть на мне", ride,
                "§7Можно ли садиться на тебя (GSit).", "",
                "§7Сейчас: " + (ride ? "§c● Запрещено" : "§a● Разрешено"),
                "", "§8» §eНажми, чтобы переключить"));

        inv.setItem(14, item(Material.SCULK_SENSOR, "Пинг в табе", ping,
                "§7Показывает твой пинг рядом с ником в табе.", "",
                "§7Сейчас: " + (ping ? "§a● Показан" : "§c● Скрыт"),
                "", "§8» §eНажми, чтобы переключить"));

        inv.setItem(15, item(Material.MACE, "PvP режим", pvp,
                "§7Отключает урон от игроков и по тебе.", "",
                "§7Сейчас: " + (pvp ? "§c● PvP отключён" : "§a● PvP включён"),
                "", "§8» §eНажми, чтобы переключить"));

        inv.setItem(22, item(Material.BARRIER, "Закрыть", false, "§7Закрыть меню."));
        p.openInventory(inv);
    }

    private void openHeight(Player p) {
        UUID id = p.getUniqueId();
        double cur = heights.getOrDefault(id, 1.0);
        Holder h = new Holder(Type.HEIGHT);
        Inventory inv = Bukkit.createInventory(h, 36, title());
        h.inv = inv;
        fill(inv);

        for (int i = 0; i < HEIGHT_SLOTS.length; i++) {
            double v = heightAt(i);
            boolean sel = Math.abs(cur - v) < 0.001;
            ItemStack it = item(Material.AMETHYST_SHARD, "Рост " + v, sel,
                    "§7Выбрать рост §f" + v, "",
                    sel ? "§a✔ Сейчас выбран" : "§8» §eНажми, чтобы выбрать");
            it.setAmount((int) Math.round(v * 10));
            inv.setItem(HEIGHT_SLOTS[i], it);
        }
        inv.setItem(30, item(Material.LIME_DYE, "Сбросить (1.0)", false, "§7Вернуть обычный рост."));
        inv.setItem(31, item(Material.ARROW, "Назад", false, "§7Вернуться в настройки."));
        inv.setItem(32, item(Material.WRITABLE_BOOK, "Свой рост", false,
                "§7Введи любое число от §f" + MIN_H + " §7до §f" + MAX_H, "§7в чат, например §f0.75"));
        p.openInventory(inv);
    }

    private double heightAt(int i) {
        return Math.round((MIN_H + i * 0.1) * 10) / 10.0;
    }

    // ================= клики =================
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        UUID id = p.getUniqueId();
        int slot = e.getSlot();

        if (h.type == Type.MAIN) {
            switch (slot) {
                case 11 -> { toggle(hiddenNick, id); applyNick(p); }
                case 12 -> {
                    if (!canHeight(p)) {
                        sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
                        msg(p, "§cСмена роста доступна только для Sponsor и Sponsor+.");
                        return;
                    }
                    sound(p, Sound.UI_BUTTON_CLICK, 1.2f);
                    openHeight(p);
                    return;
                }
                case 13 -> toggle(noRide, id);
                case 14 -> {
                    toggle(pingShown, id);
                    if (pingShown.contains(id)) updatePing(p);
                    else p.playerListName(null);
                }
                case 15 -> toggle(pvpOff, id);
                case 22 -> { sound(p, Sound.UI_BUTTON_CLICK, 0.8f); p.closeInventory(); return; }
                default -> { return; }
            }
            sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f);
            saveData();
            openMain(p);
        } else {
            if (!canHeight(p)) { p.closeInventory(); return; }
            if (slot == 31) { sound(p, Sound.UI_BUTTON_CLICK, 0.8f); openMain(p); return; }
            if (slot == 30) { setHeight(p, 1.0); return; }
            if (slot == 32) {
                awaitingHeight.add(id);
                p.closeInventory();
                msg(p, "§eВведи рост от §f" + MIN_H + " §eдо §f" + MAX_H + " §eв чат §7(или «cancel»).");
                return;
            }
            for (int i = 0; i < HEIGHT_SLOTS.length; i++) {
                if (HEIGHT_SLOTS[i] == slot) { setHeight(p, heightAt(i)); return; }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private void toggle(Set<UUID> set, UUID id) {
        if (!set.remove(id)) set.add(id);
    }

    // ================= ник / пинг =================
    private void applyNick(Player p) {
        if (hiddenNick.contains(p.getUniqueId())) hiddenTeam.addEntry(p.getName());
        else hiddenTeam.removeEntry(p.getName());
    }

    private void updatePing(Player p) {
        p.playerListName(LEGACY.deserialize(p.getName() + " §7" + p.getPing() + "ms"));
    }

    // ================= рост =================
    private AttributeInstance scale(Player p) {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
        return a == null ? null : p.getAttribute(a);
    }

    private void setHeight(Player p, double v) {
        v = Math.max(MIN_H, Math.min(MAX_H, v));
        AttributeInstance at = scale(p);
        if (at == null) { msg(p, "§cЭта версия не поддерживает смену роста."); return; }
        at.setBaseValue(v);
        heights.put(p.getUniqueId(), v);
        saveData();
        sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
        msg(p, "§aРост изменён: §f" + v);
        openHeight(p);
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!awaitingHeight.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            awaitingHeight.remove(p.getUniqueId());
            if (text.equalsIgnoreCase("cancel")) { msg(p, "§7Отменено."); return; }
            if (!canHeight(p)) return;
            try {
                double v = Double.parseDouble(text.replace(',', '.'));
                if (v < MIN_H || v > MAX_H) { msg(p, "§cДопустимо от " + MIN_H + " до " + MAX_H + "."); return; }
                setHeight(p, v);
            } catch (NumberFormatException ex) {
                msg(p, "§cЭто не число.");
            }
        });
    }

    // ================= вход / выход =================
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        applyNick(p);
        if (pingShown.contains(p.getUniqueId())) updatePing(p);
        Double h = heights.get(p.getUniqueId());
        AttributeInstance at = scale(p);
        if (h != null && at != null) {
            if (canHeight(p)) at.setBaseValue(h);
            else { at.setBaseValue(1.0); heights.remove(p.getUniqueId()); }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        awaitingHeight.remove(e.getPlayer().getUniqueId());
    }

    // ================= посадка на игрока =================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Player t && noRide.contains(t.getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("Этот игрок запретил садиться на него", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMount(EntityMountEvent e) {
        if (e.getMount() instanceof Player t && noRide.contains(t.getUniqueId())) e.setCancelled(true);
    }

    // ================= PvP =================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player damager = null;
        if (e.getDamager() instanceof Player p) damager = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) damager = p;
        if (damager == null || damager.equals(victim)) return;

        if (pvpOff.contains(damager.getUniqueId())) {
            e.setCancelled(true);
            damager.sendActionBar(Component.text("У тебя отключён PvP режим", NamedTextColor.RED));
        } else if (pvpOff.contains(victim.getUniqueId())) {
            e.setCancelled(true);
            damager.sendActionBar(Component.text("У этого игрока отключён PvP", NamedTextColor.RED));
        }
    }

    // ================= сохранение =================
    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        load(y, "hidden-nick", hiddenNick);
        load(y, "no-ride", noRide);
        load(y, "ping", pingShown);
        load(y, "pvp-off", pvpOff);
        if (y.isConfigurationSection("heights"))
            for (String k : y.getConfigurationSection("heights").getKeys(false))
                heights.put(UUID.fromString(k), y.getDouble("heights." + k));
    }

    private void load(YamlConfiguration y, String key, Set<UUID> set) {
        for (String s : y.getStringList(key)) set.add(UUID.fromString(s));
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("hidden-nick", hiddenNick.stream().map(UUID::toString).toList());
        y.set("no-ride", noRide.stream().map(UUID::toString).toList());
        y.set("ping", pingShown.stream().map(UUID::toString).toList());
        y.set("pvp-off", pvpOff.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, Double> en : heights.entrySet())
            y.set("heights." + en.getKey(), en.getValue());
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Не удалось сохранить data.yml: " + ex.getMessage());
        }
    }
}
