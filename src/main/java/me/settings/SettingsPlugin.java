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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SettingsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    // Неоновый жёлто-золотой, без жирности
    private static final TextColor NEON_GOLD = TextColor.fromHexString("#FFE600");
    private static final double MIN_H = 0.4, MAX_H = 1.3;
    private static final int[] HEIGHT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
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
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (pingShown.contains(p.getUniqueId())) updatePing(p);
            }
        }, 40L, 40L);
    }

    @Override
    public void onDisable() { saveData(); }

    private void updatePing(Player p) {
        p.playerListName(LEGACY.deserialize(p.getName() + " §7" + p.getPing() + "ms"));
    }

    // ---------- команда ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (s instanceof Player p) openMain(p);
        else s.sendMessage("Только для игроков.");
        return true;
    }

    // ---------- меню ----------
    private Component title() {
        return Component.text("Settings", NEON_GOLD)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material m, String name, NamedTextColor color, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(LEGACY.deserialize("§7" + s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private boolean canHeight(Player p) {
        for (String perm : getConfig().getStringList("height-permissions"))
            if (p.hasPermission(perm)) return true;
        return false;
    }

    private void openMain(Player p) {
        UUID id = p.getUniqueId();
        Holder h = new Holder(Type.MAIN);
        Inventory inv = Bukkit.createInventory(h, 27, title());
        h.inv = inv;

        inv.setItem(10, item(Material.NAME_TAG, "Скрыть ник", NamedTextColor.YELLOW,
                "Ник над головой: " + (hiddenNick.contains(id) ? "§cскрыт" : "§aвиден")));
        inv.setItem(12, item(Material.LADDER, "Сменить рост", NamedTextColor.YELLOW,
                "Текущий: §f" + heights.getOrDefault(id, 1.0),
                canHeight(p) ? "§aНажми, чтобы выбрать" : "§cТолько для Sponsor и Sponsor+"));
        inv.setItem(14, item(Material.SADDLE, "Сидеть на мне (GSit)", NamedTextColor.YELLOW,
                "Садиться на тебя: " + (noRide.contains(id) ? "§cзапрещено" : "§aразрешено")));
        inv.setItem(16, item(Material.CLOCK, "Пинг в табе", NamedTextColor.YELLOW,
                "Пинг: " + (pingShown.contains(id) ? "§aпоказан" : "§cскрыт")));
        inv.setItem(22, item(Material.DIAMOND_SWORD, "PvP режим", NamedTextColor.YELLOW,
                "PvP: " + (pvpOff.contains(id) ? "§cотключён" : "§aвключён")));
        p.openInventory(inv);
    }

    private void openHeight(Player p) {
        Holder h = new Holder(Type.HEIGHT);
        Inventory inv = Bukkit.createInventory(h, 36, title());
        h.inv = inv;
        for (int i = 0; i < HEIGHT_SLOTS.length; i++) {
            double v = Math.round((MIN_H + i * 0.1) * 10) / 10.0;
            inv.setItem(HEIGHT_SLOTS[i], item(Material.GOLD_NUGGET, "Рост " + v, NamedTextColor.YELLOW, "Нажми, чтобы выбрать"));
        }
        inv.setItem(23, item(Material.LIME_DYE, "Сбросить (1.0)", NamedTextColor.GREEN));
        inv.setItem(24, item(Material.WRITABLE_BOOK, "Свой рост", NamedTextColor.AQUA,
                "Введи любое число от " + MIN_H + " до " + MAX_H + " в чат"));
        inv.setItem(31, item(Material.ARROW, "Назад", NamedTextColor.WHITE));
        p.openInventory(inv);
    }

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
                case 10 -> { toggle(hiddenNick, id); applyNick(p); }
                case 12 -> {
                    if (!canHeight(p)) { p.sendMessage("§cСмена роста доступна только для Sponsor и Sponsor+."); return; }
                    openHeight(p);
                    return;
                }
                case 14 -> toggle(noRide, id);
                case 16 -> {
                    toggle(pingShown, id);
                    if (pingShown.contains(id)) updatePing(p);
                    else p.playerListName(null);
                }
                case 22 -> toggle(pvpOff, id);
                default -> { return; }
            }
            saveData();
            openMain(p);
        } else {
            if (!canHeight(p)) { p.closeInventory(); return; }
            if (slot == 31) { openMain(p); return; }
            if (slot == 23) { setHeight(p, 1.0); return; }
            if (slot == 24) {
                awaitingHeight.add(id);
                p.closeInventory();
                p.sendMessage("§eВведи рост от " + MIN_H + " до " + MAX_H + " в чат (или «cancel»).");
                return;
            }
            for (int i = 0; i < HEIGHT_SLOTS.length; i++) {
                if (HEIGHT_SLOTS[i] == slot) {
                    setHeight(p, Math.round((MIN_H + i * 0.1) * 10) / 10.0);
                    return;
                }
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

    // ---------- ник ----------
    private void applyNick(Player p) {
        if (hiddenNick.contains(p.getUniqueId())) hiddenTeam.addEntry(p.getName());
        else hiddenTeam.removeEntry(p.getName());
    }

    // ---------- рост ----------
    private AttributeInstance scale(Player p) {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
        return a == null ? null : p.getAttribute(a);
    }

    private void setHeight(Player p, double v) {
        v = Math.max(MIN_H, Math.min(MAX_H, v));
        AttributeInstance at = scale(p);
        if (at == null) { p.sendMessage("§cЭта версия не поддерживает смену роста."); return; }
        at.setBaseValue(v);
        heights.put(p.getUniqueId(), v);
        saveData();
        p.sendMessage("§aРост изменён: §f" + v);
        openHeight(p);
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!awaitingHeight.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        String msg = e.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            awaitingHeight.remove(p.getUniqueId());
            if (msg.equalsIgnoreCase("cancel")) { p.sendMessage("§7Отменено."); return; }
            if (!canHeight(p)) return;
            try {
                double v = Double.parseDouble(msg.replace(',', '.'));
                if (v < MIN_H || v > MAX_H) { p.sendMessage("§cДопустимо от " + MIN_H + " до " + MAX_H + "."); return; }
                setHeight(p, v);
            } catch (NumberFormatException ex) {
                p.sendMessage("§cЭто не число.");
            }
        });
    }

    // ---------- вход игрока ----------
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

    // ---------- посадка на игрока ----------
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

    // ---------- PvP ----------
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

    // ---------- сохранение ----------
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
