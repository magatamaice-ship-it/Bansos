
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
import org.bukkit.event.player.PlayerJoinEvent;
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

    private static final long ONLINE_REQUIRED = 10800L;
    private static final long COOLDOWN = 259200L;

    private final Map<UUID, PlayerData> data = new HashMap<>();

    private File dataFile;
    private YamlConfiguration dataConfig;
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

        BansosMenu(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        dataFile = new File(getDataFolder(), "data.yml");

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);

        getCommand("bansos").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Command ini hanya untuk pemain.");
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("claim")) {
                openMenu(player);
            } else {
                player.sendMessage("§eGunakan /bansos claim");
            }

            return true;
        });

        getCommand("timer").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Command ini hanya untuk pemain.");
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("bansos")) {
                showTimer(player);
            } else {
                player.sendMessage("§eGunakan /timer bansos");
            }

            return true;
        });

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();

            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData pd = getData(player);

                if (now >= pd.lastClaim + COOLDOWN * 1000L
                        && pd.onlineSeconds < ONLINE_REQUIRED) {
                    pd.onlineSeconds++;
                }

                if (isReady(pd)) {
                    player.sendActionBar(
                        LEGACY.deserialize(
                            getConfig().getString(
                                "notification.text",
                                "&eBANSOS SUDAH DAPAT DI CLAIM CUNG"
                            )
                        )
                    );
                }
            }

            saveCounter++;

            if (saveCounter >= 60L) {
                saveData();
                saveCounter = 0L;
            }
        }, 20L, 20L);

        getLogger().info("Bansos berhasil dimuat.");
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
        return System.currentTimeMillis() >= pd.lastClaim + COOLDOWN * 1000L
            && pd.onlineSeconds >= ONLINE_REQUIRED;
    }

    private void loadData() {
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection section =
            dataConfig.getConfigurationSection("players");

        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);

                long online = section.getLong(key + ".online", 0L);
                long claimed = section.getLong(key + ".last-claim", 0L);

                data.put(uuid, new PlayerData(online, claimed));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("UUID tidak valid: " + key);
            }
        }
    }

    private void saveData() {
        for (Map.Entry<UUID, PlayerData> entry : data.entrySet()) {
            String path = "players." + entry.getKey();

            dataConfig.set(path + ".online", entry.getValue().onlineSeconds);
            dataConfig.set(path + ".last-claim", entry.getValue().lastClaim);
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException exception) {
            getLogger().severe("Gagal menyimpan data bansos: "
                + exception.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        getData(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        saveData();
    }

    private void showTimer(Player player) {
        PlayerData pd = getData(player);
        long now = System.currentTimeMillis();

        if (now < pd.lastClaim + COOLDOWN * 1000L) {
            long remaining =
                (pd.lastClaim + COOLDOWN * 1000L - now + 999L) / 1000L;

            player.sendMessage(
                "§cCooldown bansos: §e" + formatTime(remaining)
            );
            return;
        }

        long remaining = Math.max(0L, ONLINE_REQUIRED - pd.onlineSeconds);

        if (remaining == 0L) {
            player.sendMessage("§aBansos sudah bisa diklaim!");
        } else {
            player.sendMessage(
                "§eWaktu online yang dibutuhkan: §f"
                + formatTime(remaining)
            );
        }
    }

    private String formatTime(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        return hours + " jam " + minutes + " menit " + secs + " detik";
    }

    private void openMenu(Player player) {
        PlayerData pd = getData(player);

        Inventory inv = Bukkit.createInventory(
            new BansosMenu(null),
            54,
            LEGACY.deserialize(
                getConfig().getString("gui.title", "&8✦ BANSOS PEMAIN ✦")
            )
        );

        BansosMenu holder = new BansosMenu(inv);
        inv = holder.getInventory();

        if (getConfig().getBoolean("gui.filler.enabled", true)) {
            Material filler = Material.matchMaterial(
                getConfig().getString(
                    "gui.filler.material", "GRAY_STAINED_GLASS_PANE"
                )
            );

            if (filler != null) {
                ItemStack item = new ItemStack(filler);
                ItemMeta meta = item.getItemMeta();

                meta.displayName(LEGACY.deserialize(
                    getConfig().getString("gui.filler.name", " ")
                ));

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

                ItemStack item = createReward(reward);

                int slot = reward.getInt("slot", -1);

                if (item != null && slot >= 0 && slot < 54
                        && (slot < 45 || slot > 49)) {
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

        meta.displayName(LEGACY.deserialize(
            section.getString("name", "")
        ));

        List<Component> lore = new ArrayList<>();

        for (String line : section.getStringList("lore")) {
            lore.add(LEGACY.deserialize(line));
        }

        meta.lore(lore);
        item.setItemMeta(meta);

        int slot = section.getInt("slot", -1);

        if (slot >= 0 && slot < inv.getSize()) {
            inv.setItem(slot, item);
        }
    }

    private ItemStack createReward(ConfigurationSection section) {
        Material material = Material.matchMaterial(
            section.getString("material", "")
        );

        if (material == null) return null;

        int amount = section.getInt("amount", 1);

        if (amount <= 0) return null;

        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();

        if (section.contains("name")) {
            meta.displayName(LEGACY.deserialize(
                section.getString("name", "")
            ));
        }

        ConfigurationSection enchants =
            section.getConfigurationSection("enchantments");

        if (enchants != null) {
            for (String key : enchants.getKeys(false)) {
                NamespacedKey enchantKey =
                    NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT));

                Enchantment enchant =
                    Enchantment.getByKey(enchantKey);

                if (enchant == null) continue;

                int level = enchants.getInt(key, 1);

                if (meta instanceof EnchantmentStorageMeta bookMeta) {
                    bookMeta.addStoredEnchant(enchant, level, true);
                } else {
                    meta.addEnchant(enchant, level, true);
                }
            }
        }

        item.setItemMeta(meta);
        return item;
    }

    private List<ItemStack> getRewards() {
        List<ItemStack> rewards = new ArrayList<>();

        ConfigurationSection section =
            getConfig().getConfigurationSection("rewards");

        if (section == null) return rewards;

        for (String key : section.getKeys(false)) {
            ConfigurationSection reward =
                section.getConfigurationSection(key);

            if (reward == null) continue;

            ItemStack item = createReward(reward);

            if (item != null) rewards.add(item);
        }

        return rewards;
    }

    private boolean hasEnoughSpace(Player player, List<ItemStack> rewards) {
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
            if (temporary.addItem(reward.clone()).size() > 0) {
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

        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();

        if (slot == getConfig().getInt("gui.close-button.slot", 45)) {
            player.closeInventory();
            return;
        }

        if (slot != getConfig().getInt("gui.claim-button.slot", 49)) {
            return;
        }

        PlayerData pd = getData(player);

        if (!isReady(pd)) {
            player.sendMessage(
                getConfig().getString(
                    "messages.not-ready",
                    "&cBansos belum tersedia."
                ).replace("&", "§")
            );
            player.closeInventory();
            return;
        }

        List<ItemStack> rewards = getRewards();

        if (rewards.isEmpty()) {
            player.sendMessage("§cHadiah bansos belum dikonfigurasi.");
            return;
        }

        if (!hasEnoughSpace(player, rewards)) {
            player.sendMessage(
                getConfig().getString(
                    "messages.no-space",
                    "&cRuang inventaris tidak cukup."
                ).replace("&", "§")
            );
            return;
        }

        for (ItemStack reward : rewards) {
            player.getInventory().addItem(reward);
        }

        pd.onlineSeconds = 0L;
        pd.lastClaim = System.currentTimeMillis();

        saveData();

        player.closeInventory();

        player.sendMessage(
            getConfig().getString(
                "messages.claimed",
                "&aBansos berhasil diklaim!"
            ).replace("&", "§")
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
          
