
package id.server.bansos;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Bansos extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerData> data = new HashMap<>();

    private File dataFile;
    private YamlConfiguration dataConfig;

    private long onlineRequired;
    private long cooldown;
    private long saveInterval;

    private long saveCounter = 0L;

    private static final LegacyComponentSerializer LEGACY =
        LegacyComponentSerializer.legacyAmpersand();

    private static class PlayerData {
        long onlineSeconds;
        long lastClaim;

        PlayerData(long onlineSeconds, long lastClaim) {
            this.onlineSeconds = onlineSeconds;
            this.lastClaim = lastClaim;
        }
    }

    private static class BansosMenu implements InventoryHolder {

        private final Inventory inventory;

        BansosMenu(int size, Component title) {
            this.inventory = Bukkit.createInventory(this, size, title);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {

        saveDefaultConfig();

        onlineRequired = Math.max(
            1L,
            getConfig().getLong("settings.online-required-seconds", 10800L)
        );

        cooldown = Math.max(
            0L,
            getConfig().getLong("settings.cooldown-seconds", 259200L)
        );

        saveInterval = Math.max(
            1L,
            getConfig().getLong("settings.save-interval-seconds", 60L)
        );

        if (getCommand("bantuan") == null) {
            getLogger().severe("Command bantuan tidak ditemukan di plugin.yml!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        dataFile = new File(getDataFolder(), "data.yml");

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);

        getCommand("bantuan").setExecutor((sender, command, label, args) -> {

            if (args.length == 1 &&
                    args[0].equalsIgnoreCase("terima")) {

                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Command ini hanya untuk pemain.");
                    return true;
                }

                openMenu(player);
                return true;
            }

            if (args.length == 1 &&
                    args[0].equalsIgnoreCase("timer")) {

                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Command ini hanya untuk pemain.");
                    return true;
                }

                showTimer(player);
                return true;
            }

            if (args.length == 2 &&
                    args[0].equalsIgnoreCase("clear")) {

                if (!sender.hasPermission("bansos.admin")) {
                    sender.sendMessage("§cKamu tidak memiliki izin!");
                    return true;
                }

                Player target = Bukkit.getPlayerExact(args[1]);

                if (target == null) {
                    sender.sendMessage(
                        "§cPemain tidak ditemukan atau sedang offline!"
                    );
                    return true;
                }

                PlayerData pd = getData(target);

                pd.onlineSeconds = onlineRequired;

                saveData();

                sender.sendMessage(
                    "§aProgres bansos " + target.getName()
                    + " berhasil dipenuhi secara instan!"
                );

                target.sendMessage(
                    "§aProgres 3 jam bansos kamu telah dipenuhi oleh admin!"
                );

                return true;
            }

            sender.sendMessage("§e===== SISTEM BANTUAN =====");
            sender.sendMessage("§f/bantuan terima");
            sender.sendMessage("§f/bantuan timer");

            if (sender.hasPermission("bansos.admin")) {
                sender.sendMessage(
                    "§f/bantuan clear <nama pemain>"
                );
            }

            return true;
        });

        Bukkit.getScheduler().runTaskTimer(this, () -> {

            long now = System.currentTimeMillis();

            for (Player player : Bukkit.getOnlinePlayers()) {

                PlayerData pd = getData(player);

                boolean cooldownFinished =
                    now >= pd.lastClaim + cooldown * 1000L;

                if (cooldownFinished &&
                        pd.onlineSeconds < onlineRequired) {

                    pd.onlineSeconds++;
                }

                if (getConfig().getBoolean(
                        "notification.enabled", true)
                        && isReady(pd)) {

                    String message = getConfig().getString(
                        "notification.text",
                        "&eBANSOS SUDAH DAPAT DI CLAIM CUNG"
                    );

                    player.sendActionBar(
                        LEGACY.deserialize(message)
                    );
                }
            }

            saveCounter++;

            if (saveCounter >= saveInterval) {
                saveData();
                saveCounter = 0L;
            }

        }, 20L, 20L);

        getLogger().info("Bansos berhasil dimuat!");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private PlayerData getData(Player player) {

        return data.computeIfAbsent(
            player.getUniqueId(),
            uuid -> new PlayerData(0L, 0L)
        );
    }

    private boolean isReady(PlayerData pd) {

        long now = System.currentTimeMillis();

        return now >= pd.lastClaim + cooldown * 1000L
            && pd.onlineSeconds >= onlineRequired;
    }

    private void loadData() {

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection section =
            dataConfig.getConfigurationSection("players");

        if (section == null) return;

        for (String key : section.getKeys(false)) {

            try {

                UUID uuid = UUID.fromString(key);

                long online = dataConfig.getLong(
                    "players." + key + ".online", 0L
                );

                long claimed = dataConfig.getLong(
                    "players." + key + ".last-claim", 0L
                );

                data.put(
                    uuid,
                    new PlayerData(online, claimed)
                );

            } catch (IllegalArgumentException exception) {

                getLogger().warning("UUID tidak valid: " + key);
            }
        }
    }

    private void saveData() {

        if (dataConfig == null || dataFile == null) return;

        for (Map.Entry<UUID, PlayerData> entry : data.entrySet()) {

            String path = "players." + entry.getKey();

            dataConfig.set(
                path + ".online",
                entry.getValue().onlineSeconds
            );

            dataConfig.set(
                path + ".last-claim",
                entry.getValue().lastClaim
            );
        }

        try {

            dataConfig.save(dataFile);

        } catch (IOException exception) {

            getLogger().severe(
                "Gagal menyimpan data: " + exception.getMessage()
            );
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        saveData();
    }

    private void showTimer(Player player) {

        PlayerData pd = getData(player);

        long now = System.currentTimeMillis();

        long cooldownEnd = pd.lastClaim + cooldown * 1000L;

        if (now < cooldownEnd) {

            long remaining =
                (cooldownEnd - now + 999L) / 1000L;

            player.sendMessage(
                "§cCooldown bansos: §e" + formatTime(remaining)
            );

            return;
        }

        long remaining = Math.max(
            0L,
            onlineRequired - pd.onlineSeconds
        );

        if (remaining == 0L) {

            player.sendMessage(
                "§aBansos sudah bisa diklaim!"
            );

        } else {

            player.sendMessage(
                "§eWaktu online yang dibutuhkan: §f"
                + formatTime(remaining)
            );
        }
    }

    private String formatTime(long seconds) {

        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        if (days > 0L) {
            return days + " hari " + hours + " jam "
                + minutes + " menit";
        }

        return hours + " jam " + minutes
            + " menit " + secs + " detik";
    }

    private void openMenu(Player player) {

        PlayerData pd = getData(player);

        Component title = LEGACY.deserialize(
            getConfig().getString(
                "gui.title",
                "&8✦ BANSOS PEMAIN ✦"
            )
        );

        BansosMenu holder = new BansosMenu(54, title);

        Inventory inv = holder.getInventory();

        if (getConfig().getBoolean(
                "gui.filler.enabled", true)) {

            Material filler = Material.matchMaterial(
                getConfig().getString(
                    "gui.filler.material",
                    "GRAY_STAINED_GLASS_PANE"
                )
            );

            if (filler != null) {

                ItemStack item = new ItemStack(filler);

                ItemMeta meta = item.getItemMeta();

                meta.displayName(
                    LEGACY.deserialize(
                        getConfig().getString(
                            "gui.filler.name", " "
                        )
                    )
                );

                item.setItemMeta(meta);

                for (int i = 0; i < inv.getSize(); i++) {
                    inv.setItem(i, item);
                }
            }
        }

        ConfigurationSection rewards =
            getConfig().getConfigurationSection("rewards");

        if (rewards != null) {

            for (String key : rewards.getKeys(false)) {

                ConfigurationSection reward =
                    rewards.getConfigurationSection(key);

                if (reward == null) continue;

                ItemStack item = createRewardDisplay(reward);

                int slot = reward.getInt("slot", -1);

                if (item != null &&
                        slot >= 0 &&
                        slot < 54 &&
                        (slot < 45 || slot > 49)) {

                    inv.setItem(slot, item);
                }
            }
        }

        setGuiItem(inv, "gui.close-button");
        setGuiItem(inv, "gui.claim-button");

        if (isReady(pd)) {
            player.sendMessage("§aBansos sudah tersedia!");
        }

        player.openInventory(inv);
    }

    private void setGuiItem(Inventory inv, String path) {

        ConfigurationSection section =
            getConfig().getConfigurationSection(path);

        if (section == null) return;

        Material material = Material.matchMaterial(
            section.getString("material", "BARRIER")
        );

        if (material == null) return;

        ItemStack item = new ItemStack(material);

        ItemMeta meta = item.getItemMeta();

        meta.displayName(
            LEGACY.deserialize(
                section.getString("name", "")
            )
        );

        List<Component> lore = new ArrayList<>();

        for (String line : section.getStringList("lore")) {
            lore.add(LEGACY.deserialize(line));
        }

        if (!lore.isEmpty()) {
            meta.lore(lore);
        }

        item.setItemMeta(meta);

        int slot = section.getInt("slot", -1);

        if (slot >= 0 && slot < inv.getSize()) {
            inv.setItem(slot, item);
        }
    }

    private ItemStack createRewardDisplay(
            ConfigurationSection section) {

        ItemStack item = createReward(section);

        if (item == null) return null;

        int max = item.getMaxStackSize();

        if (item.getAmount() > max) {

            item.setAmount(max);

            ItemMeta meta = item.getItemMeta();

            List<Component> lore = meta.hasLore()
                ? new ArrayList<>(meta.lore())
                : new ArrayList<>();

            lore.add(
                LEGACY.deserialize(
                    "&eTotal hadiah: "
                    + section.getInt("amount", 1)
                )
            );

            meta.lore(lore);

            item.setItemMeta(meta);
        }

        return item;
    }

    private ItemStack createReward(
            ConfigurationSection section) {

        Material material = Material.matchMaterial(
            section.getString("material", "")
        );

        if (material == null) return null;

        int amount = section.getInt("amount", 1);

        if (amount <= 0) return null;

        ItemStack item = new ItemStack(
            material,
            Math.min(amount, material.getMaxStackSize())
        );

        ItemMeta meta = item.getItemMeta();

        if (section.contains("name")) {

            meta.displayName(
                LEGACY.deserialize(
                    section.getString("name", "")
                )
            );
        }

        ConfigurationSection enchants =
            section.getConfigurationSection("enchantments");

        if (enchants != null) {

            for (String key : enchants.getKeys(false)) {

                NamespacedKey enchantKey =
                    NamespacedKey.minecraft(
                        key.toLowerCase(Locale.ROOT)
                    );

                Enchantment enchant =
                    Enchantment.getByKey(enchantKey);

                if (enchant == null) continue;

                int level = enchants.getInt(key, 1);

                if (meta instanceof EnchantmentStorageMeta bookMeta) {

                    bookMeta.addStoredEnchant(
                        enchant, level, true
                    );

                } else {

                    meta.addEnchant(enchant, level, true);
                }
            }
        }

        item.setItemMeta(meta);

        return item;
    }

    private List<ItemStack> getRewards() {

        List<ItemStack> result = new ArrayList<>();

        ConfigurationSection section =
            getConfig().getConfigurationSection("rewards");

        if (section == null) return result;

        for (String key : section.getKeys(false)) {

            ConfigurationSection reward =
                section.getConfigurationSection(key);

            if (reward == null) continue;

            ItemStack base = createReward(reward);

            if (base == null) continue;

            int remaining = reward.getInt("amount", 1);
            int max = base.getMaxStackSize();

            while (remaining > 0) {

                int amount = Math.min(remaining, max);

                ItemStack stack = base.clone();
                stack.setAmount(amount);

                result.add(stack);

                remaining -= amount;
            }
        }

        return result;
    }

    private boolean hasEnoughSpace(
            Player player,
            List<ItemStack> rewards) {

        PlayerInventory inventory = player.getInventory();

        ItemStack[] storage = inventory.getStorageContents();

        for (int i = 0; i < storage.length; i++) {

            if (storage[i] != null) {
                storage[i] = storage[i].clone();
            }
        }

        Inventory temporary = Bukkit.createInventory(null, 36);

        temporary.setContents(storage);

        for (ItemStack reward : rewards) {

            if (!temporary.addItem(reward.clone()).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {

        if (!(event.getView().getTopInventory().getHolder()
                instanceof BansosMenu)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= event.getView()
                .getTopInventory().getSize()) {
            return;
        }

        if (slot == getConfig().getInt(
                "gui.close-button.slot", 45)) {

            player.closeInventory();
            return;
        }

        if (slot != getConfig().getInt(
                "gui.claim-button.slot", 49)) {
            return;
        }

        PlayerData pd = getData(player);

        if (!isReady(pd)) {

            player.sendMessage(
                LEGACY.deserialize(
                    getConfig().getString(
                        "messages.not-ready",
                        "&cBansos belum tersedia."
                    )
                )
            );

            player.closeInventory();
            return;
        }

        List<ItemStack> rewards = getRewards();

        if (rewards.isEmpty()) {

            player.sendMessage(
                "§cHadiah bansos belum dikonfigurasi."
            );

            return;
        }

        if (!hasEnoughSpace(player, rewards)) {

            player.sendMessage(
                LEGACY.deserialize(
                    getConfig().getString(
                        "messages.no-space",
                        "&cRuang inventaris tidak cukup."
                    )
                )
            );

            return;
        }

        for (ItemStack reward : rewards) {
            player.getInventory().addItem(reward.clone());
        }

        pd.onlineSeconds = 0L;
        pd.lastClaim = System.currentTimeMillis();

        saveData();

        player.closeInventory();

        player.sendMessage(
            LEGACY.deserialize(
                getConfig().getString(
                    "messages.claimed",
                    "&aBansos berhasil diklaim!"
                )
            )
        );
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {

        if (event.getView().getTopInventory().getHolder()
                instanceof BansosMenu) {

            event.setCancelled(true);
        }
    }
                }
                     
