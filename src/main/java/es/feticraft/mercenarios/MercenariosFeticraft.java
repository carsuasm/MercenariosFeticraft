package es.feticraft.mercenarios;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MercenariosFeticraft extends JavaPlugin implements Listener, CommandExecutor {
    private Economy economy;
    private Connection database;
    private NamespacedKey mercenaryKey;
    
    private final Map<UUID, Integer> activeMercenaries = new HashMap<>(); 
    private final Map<UUID, Boolean> mercenaryStays = new HashMap<>(); 
    private final Map<UUID, Inventory> mercenaryInventories = new HashMap<>(); 
    private final Map<UUID, Integer> mercenaryLevels = new HashMap<>();
    private final Map<UUID, Integer> mercenaryXp = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();

    private String survivalWorldName;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        mercenaryKey = new NamespacedKey(this, "mercenary_owner");

        if (!setupEconomy() || !openDatabase()) {
            getLogger().severe("No se pudo iniciar MercenariosFeticraft: revisa Vault y la base de datos.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("¡Citizens no está instalado! El plugin se deshabilitará.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("mercenario") != null) {
            getCommand("mercenario").setExecutor(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::processMercenaryAIAndTimers, 20L, 20L);

        getLogger().info("MercenariosFeticraft iniciado correctamente.");
    }

    private void loadConfigValues() {
        reloadConfig();
        survivalWorldName = getConfig().getString("survival-world", "world");
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) return false;
        economy = provider.getProvider();
        return economy != null;
    }

    private boolean openDatabase() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) return false;
            database = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "mercenarios.db").getAbsolutePath());
            try (Statement st = database.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("CREATE TABLE IF NOT EXISTS mercenaries (player_uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, npc_id INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL, level INTEGER DEFAULT 1, xp INTEGER DEFAULT 0)");
            }
            return true;
        } catch (Exception e) {
            getLogger().severe("Error abriendo base de datos SQLite: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Este comando solo puede usarlo un jugador.");
            return true;
        }

        if (!player.hasPermission("mercenarios.use")) {
            message(player, "&cNo tienes permiso para usar mercenarios.");
            return true;
        }

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("contratar")) {
                openHireMenu(player);
                return true;
            } else if (args[0].equalsIgnoreCase("tiempo") || args[0].equalsIgnoreCase("info")) {
                checkMercenaryTime(player);
                return true;
            } else if (args[0].equalsIgnoreCase("reaparecer") || args[0].equalsIgnoreCase("revivir")) {
                respawnMercenary(player);
                return true;
            }
        }

        message(player, "&eUso de comandos:");
        message(player, "&f/mercenario contratar &7- Abre el menú de contratación");
        message(player, "&f/mercenario tiempo &7- Muestra el tiempo y nivel restante");
        message(player, "&f/mercenario reaparecer &7- Vuelve a hacer aparecer a tu mercenario");
        return true;
    }

    private void openHireMenu(Player player) {
        UUID uuid = player.getUniqueId();

        if (!player.getWorld().getName().equalsIgnoreCase(survivalWorldName)) {
            message(player, "&cSolo puedes contratar y usar mercenarios en el mundo de supervivencia (&e" + survivalWorldName + "&c).");
            return;
        }

        try (PreparedStatement ps = database.prepareStatement("SELECT npc_id, expires_at, status FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    String status = rs.getString("status");
                    int npcId = rs.getInt("npc_id");

                    if (status.equalsIgnoreCase("ACTIVE") && expiresAt > System.currentTimeMillis()) {
                        NPC existingNpc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (existingNpc != null) {
                            message(player, "&cYa tienes un mercenario activo. Usa &e/mercenario reaparecer &csi no lo ves.");
                            return;
                        } else {
                            try (PreparedStatement delPs = database.prepareStatement("UPDATE mercenaries SET status='EXPIRED' WHERE player_uuid=?")) {
                                delPs.setString(1, uuid.toString());
                                delPs.executeUpdate();
                            }
                        }
                    }
                }
            }
        } catch (SQLException ignored) {}

        Inventory gui = Bukkit.createInventory(null, 9, color("&8Contratar Mercenario"));

        gui.setItem(2, createGuiItem(Material.CLOCK, "&e1 Hora de Contrato", "&7Precio: &a2,000$"));
        gui.setItem(4, createGuiItem(Material.GOLD_INGOT, "&e6 Horas de Contrato", "&7Precio: &a10,000$"));
        gui.setItem(6, createGuiItem(Material.EMERALD, "&e12 Horas de Contrato", "&7Precio: &a18,000$"));
        gui.setItem(8, createGuiItem(Material.NETHER_STAR, "&e24 Horas de Contrato", "&7Precio: &a30,000$"));

        player.openInventory(gui);
    }

    private ItemStack createGuiItem(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> loreList = new ArrayList<>();
            loreList.add(color(lore));
            loreList.add(color("&7¡Haz clic para confirmar!"));
            meta.setLore(loreList);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void processHire(Player player, long durationSeconds, double price) {
        UUID uuid = player.getUniqueId();

        if (!economy.has(player, price)) {
            message(player, "&cNo tienes suficiente dinero. Necesitas &e" + price + "$&c.");
            player.closeInventory();
            return;
        }

        if (!economy.withdrawPlayer(player, price).transactionSuccess()) {
            message(player, "&cHubo un error al realizar el pago.");
            player.closeInventory();
            return;
        }

        Location loc = player.getLocation();
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, color("&aMercenario Nivel 1 de " + player.getName()));
        npc.spawn(loc);

        int npcId = npc.getId();
        long expiresAt = System.currentTimeMillis() + (durationSeconds * 1000L);

        try (PreparedStatement ps = database.prepareStatement("INSERT OR REPLACE INTO mercenaries(player_uuid, player_name, npc_id, expires_at, status, level, xp) VALUES(?,?,?,?, 'ACTIVE', 1, 0)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, player.getName());
            ps.setInt(3, npcId);
            ps.setLong(4, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Error guardando mercenario: " + e.getMessage());
            npc.destroy();
            economy.depositPlayer(player, price);
            message(player, "&cError crítico. Se te ha reembolsado el dinero.");
            player.closeInventory();
            return;
        }

        activeMercenaries.put(uuid, npcId);
        mercenaryStays.put(uuid, false);
        mercenaryInventories.put(uuid, Bukkit.createInventory(null, 27, color("&8Equipo de " + player.getName())));
        mercenaryLevels.put(uuid, 1);
        mercenaryXp.put(uuid, 0);
        
        player.closeInventory();
        message(player, "&a¡Has contratado a tu mercenario con éxito!");
    }

    private void checkMercenaryTime(Player player) {
        UUID uuid = player.getUniqueId();
        try (PreparedStatement ps = database.prepareStatement("SELECT expires_at, status, level, xp FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString("status").equalsIgnoreCase("ACTIVE")) {
                    long expiresAt = rs.getLong("expires_at");
                    long timeLeft = expiresAt - System.currentTimeMillis();
                    int level = rs.getInt("level");
                    int xp = rs.getInt("xp");

                    if (timeLeft > 0) {
                        long hours = timeLeft / (1000 * 60 * 60);
                        long minutes = (timeLeft / (1000 * 60)) % 60;
                        message(player, "&eEstadísticas del Mercenario:");
                        message(player, "&f- Nivel: &a" + level + " &7(XP: " + xp + "/100)");
                        message(player, "&f- Tiempo restante: &e" + hours + "h " + minutes + "m");
                        return;
                    }
                }
            }
        } catch (SQLException ignored) {}

        message(player, "&cNo tienes ningún mercenario contratado en este momento.");
    }

    private void respawnMercenary(Player player) {
        UUID uuid = player.getUniqueId();

        if (!player.getWorld().getName().equalsIgnoreCase(survivalWorldName)) {
            message(player, "&cSolo puedes hacer reaparecer a tu mercenario en el mundo de supervivencia.");
            return;
        }

        try (PreparedStatement ps = database.prepareStatement("SELECT npc_id, expires_at, status FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString("status").equalsIgnoreCase("ACTIVE")) {
                    long expiresAt = rs.getLong("expires_at");
                    if (expiresAt > System.currentTimeMillis()) {
                        int oldNpcId = rs.getInt("npc_id");
                        NPC oldNpc = CitizensAPI.getNPCRegistry().getById(oldNpcId);
                        if (oldNpc != null) {
                            oldNpc.destroy();
                        }

                        int level = getOrLoadLevel(uuid);
                        NPC newNpc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, color("&aMercenario Nivel " + level + " de " + player.getName()));
                        newNpc.spawn(player.getLocation());

                        try (PreparedStatement updatePs = database.prepareStatement("UPDATE mercenaries SET npc_id=? WHERE player_uuid=?")) {
                            updatePs.setInt(1, newNpc.getId());
                            updatePs.setString(2, uuid.toString());
                            updatePs.executeUpdate();
                        }

                        activeMercenaries.put(uuid, newNpc.getId());
                        message(player, "&a¡Tu mercenario ha reaparecido a tu lado!");
                        return;
                    }
                }
            }
        } catch (SQLException ignored) {}

        message(player, "&cNo tienes ningún contrato activo para reaparecer.");
    }

    private int getOrLoadLevel(UUID uuid) {
        if (mercenaryLevels.containsKey(uuid)) return mercenaryLevels.get(uuid);
        try (PreparedStatement ps = database.prepareStatement("SELECT level FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int lvl = rs.getInt("level");
                    mercenaryLevels.put(uuid, lvl);
                    return lvl;
                }
            }
        } catch (SQLException ignored) {}
        return 1;
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();
        if (clickCooldown.containsKey(uuid) && now - clickCooldown.get(uuid) < 250) return;
        clickCooldown.put(uuid, now);

        Entity clicked = event.getRightClicked();
        if (!CitizensAPI.getNPCRegistry().isNPC(clicked)) return;

        NPC npc = CitizensAPI.getNPCRegistry().getNPC(clicked);
        boolean isOwner = false;

        if (activeMercenaries.containsKey(uuid) && activeMercenaries.get(uuid) == npc.getId()) {
            isOwner = true;
        } else {
            try (PreparedStatement ps = database.prepareStatement("SELECT 1 FROM mercenaries WHERE player_uuid=? AND npc_id=? AND status='ACTIVE'")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, npc.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        isOwner = true;
                        activeMercenaries.put(uuid, npc.getId());
                    }
                }
            } catch (SQLException ignored) {}
        }

        if (isOwner) {
            event.setCancelled(true);

            // Shift + Clic derecho -> Abre el panel de control principal desde el NPC
            if (player.isSneaking()) {
                openControlMenu(player);
                return;
            }

            // Clic derecho normal -> Abre su inventario de equipamiento (armaduras y armas)
            Inventory inv = mercenaryInventories.computeIfAbsent(uuid, k -> Bukkit.createInventory(null, 27, color("&8Equipo de " + player.getName())));
            player.openInventory(inv);
            message(player, "&7Abriendo el inventario de equipamiento de tu mercenario...");
        }
    }

    private void openControlMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, color("&8Gestión de Mercenario"));

        boolean isStaying = mercenaryStays.getOrDefault(player.getUniqueId(), false);
        
        // Botón 1 (Slot 3): Cambiar Estado Seguir/Quieto
        ItemStack stayItem = new ItemStack(isStaying ? Material.RED_CONCRETE : Material.GREEN_CONCRETE);
        setItemMeta(stayItem, isStaying ? "&cEstado: Quieto" : "&aEstado: Siguiendo", "&7Haz clic para alternar el comportamiento.");
        gui.setItem(3, stayItem);

        // Botón 2 (Slot 5): Abrir inventario de equipamiento directamente desde la gestión
        ItemStack gearItem = new ItemStack(Material.IRON_CHESTPLATE);
        setItemMeta(gearItem, "&bInventario y Armadura", "&7Haz clic para abrir su equipo.");
        gui.setItem(5, gearItem);

        player.openInventory(gui);
    }

    private void setItemMeta(ItemStack item, String name, String lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(java.util.Collections.singletonList(color(lore)));
            item.setItemMeta(meta);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        // 1. Menú de Contratación
        if (title.equals(color("&8Contratar Mercenario"))) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getSlot();
            if (slot == 2) {
                processHire(player, 3600L, 2000.0);       // 1 Hora - 2000$
            } else if (slot == 4) {
                processHire(player, 21600L, 10000.0);     // 6 Horas - 10000$
            } else if (slot == 6) {
                processHire(player, 43200L, 18000.0);     // 12 Horas - 18000$
            } else if (slot == 8) {
                processHire(player, 86400L, 30000.0);     // 24 Horas - 30000$
            }
            return;
        }

        // 2. Menú de Gestión y Configuración del NPC
        if (title.equals(color("&8Gestión de Mercenario"))) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            UUID uuid = player.getUniqueId();

            if (event.getSlot() == 3) {
                boolean currentStay = mercenaryStays.getOrDefault(uuid, false);
                boolean newStay = !currentStay;
                mercenaryStays.put(uuid, newStay);

                if (newStay) {
                    message(player, "&eTu mercenario se ha quedado &fquieto&e.");
                    NPC npc = CitizensAPI.getNPCRegistry().getById(activeMercenaries.get(uuid));
                    if (npc != null && npc.isSpawned()) {
                        npc.getNavigator().cancelNavigation();
                    }
                } else {
                    message(player, "&aTu mercenario ha vuelto a &fseguirte&a.");
                }
                player.closeInventory();
            } else if (event.getSlot() == 5) {
                player.closeInventory();
                Inventory inv = mercenaryInventories.computeIfAbsent(uuid, k -> Bukkit.createInventory(null, 27, color("&8Equipo de " + player.getName())));
                player.openInventory(inv);
                message(player, "&7Abriendo el inventario de equipamiento...");
            }
            return;
        }

        // 3. Inventario de equipamiento
        if (title.startsWith(color("&8Equipo de "))) {
            return;
        }
    }

    private void processMercenaryAIAndTimers() {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, Integer> entry : new HashMap<>(activeMercenaries).entrySet()) {
            UUID uuid = entry.getKey();
            int npcId = entry.getValue();

            try (PreparedStatement ps = database.prepareStatement("SELECT expires_at FROM mercenaries WHERE player_uuid=? AND status='ACTIVE'")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && now >= rs.getLong("expires_at")) {
                        expireMercenary(uuid, npcId);
                        continue;
                    }
                }
            } catch (SQLException e) {
                getLogger().warning("Error en temporizador: " + e.getMessage());
            }

            Player player = Bukkit.getPlayer(uuid);
            NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);

            if (player != null && player.isOnline() && npc != null) {
                if (!player.getWorld().getName().equalsIgnoreCase(survivalWorldName)) {
                    if (npc.isSpawned()) npc.despawn();
                    continue;
                }

                if (!npc.isSpawned()) {
                    npc.spawn(player.getLocation());
                }

                boolean isStaying = mercenaryStays.getOrDefault(uuid, false);
                Navigator navigator = npc.getNavigator();

                if (isStaying) {
                    if (navigator.isNavigating()) {
                        navigator.cancelNavigation();
                    }
                    continue;
                }

                // Combate exclusivo contra monstruos hostiles
                LivingEntity target = null;
                for (Entity entity : npc.getStoredLocation().getWorld().getEntities()) {
                    if (entity instanceof Monster && !(entity.hasMetadata("NPC"))) {
                        if (entity.getLocation().distanceSquared(npc.getStoredLocation()) <= 64.0) {
                            target = (LivingEntity) entity;
                            break;
                        }
                    }
                }

                if (target != null) {
                    navigator.setTarget(target, true);
                    if (npc.getEntity() instanceof LivingEntity npcEntity && npcEntity.getLocation().distanceSquared(target.getLocation()) <= 4.0) {
                        int level = getOrLoadLevel(uuid);
                        
                        double weaponBonus = 1.0;
                        Inventory inv = mercenaryInventories.get(uuid);
                        if (inv != null) {
                            ItemStack handItem = inv.getItem(0);
                            if (handItem != null) {
                                String typeName = handItem.getType().name();
                                if (typeName.contains("NETHERITE_SWORD")) weaponBonus = 8.0;
                                else if (typeName.contains("DIAMOND_SWORD")) weaponBonus = 7.0;
                                else if (typeName.contains("IRON_SWORD")) weaponBonus = 6.0;
                                else if (typeName.contains("STONE_SWORD")) weaponBonus = 5.0;
                                else if (typeName.contains("WOODEN_SWORD") || typeName.contains("GOLD_SWORD")) weaponBonus = 4.0;
                            }
                        }

                        double damage = weaponBonus + (level * 1.0);
                        target.damage(damage, npcEntity);
                    }
                } else {
                    if (player.getWorld().equals(npc.getStoredLocation().getWorld()) &&
                            player.getLocation().distanceSquared(npc.getStoredLocation()) > 16.0) {
                        navigator.setTarget(player, false);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player || dead.hasMetadata("NPC")) return;

        if (dead instanceof Monster) {
            Entity killer = dead.getKiller();
            if (killer == null) {
                for (Map.Entry<UUID, Integer> entry : activeMercenaries.entrySet()) {
                    NPC npc = CitizensAPI.getNPCRegistry().getById(entry.getValue());
                    if (npc != null && npc.isSpawned() && npc.getStoredLocation().getWorld().equals(dead.getWorld())) {
                        if (npc.getStoredLocation().distanceSquared(dead.getLocation()) <= 16.0) {
                            addXpToMercenary(entry.getKey(), 20);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void addXpToMercenary(UUID uuid, int amount) {
        int currentXp = mercenaryXp.getOrDefault(uuid, 0) + amount;
        int currentLevel = getOrLoadLevel(uuid);

        if (currentXp >= 100) {
            currentLevel++;
            currentXp = 0;
            mercenaryLevels.put(uuid, currentLevel);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                message(player, "&a¡Tu mercenario ha subido al &eNivel " + currentLevel + "&a!");
            }
        }
        mercenaryXp.put(uuid, currentXp);

        try (PreparedStatement ps = database.prepareStatement("UPDATE mercenaries SET level=?, xp=? WHERE player_uuid=?")) {
            ps.setInt(1, currentLevel);
            ps.setInt(2, currentXp);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    private void expireMercenary(UUID uuid, int npcId) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc != null) npc.destroy();

        try (PreparedStatement ps = database.prepareStatement("UPDATE mercenaries SET status='EXPIRED' WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Error expirando mercenario: " + e.getMessage());
        }

        activeMercenaries.remove(uuid);
        mercenaryStays.remove(uuid);
        mercenaryInventories.remove(uuid);
        mercenaryLevels.remove(uuid);
        mercenaryXp.remove(uuid);

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            message(player, "&cEl contrato de tu mercenario ha expirado.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!player.getWorld().getName().equalsIgnoreCase(survivalWorldName)) return;

        try (PreparedStatement ps = database.prepareStatement("SELECT npc_id, expires_at, level, xp FROM mercenaries WHERE player_uuid=? AND status='ACTIVE'")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    if (expiresAt > System.currentTimeMillis()) {
                        int npcId = rs.getInt("npc_id");
                        int level = rs.getInt("level");
                        mercenaryLevels.put(uuid, level);
                        mercenaryXp.put(uuid, rs.getInt("xp"));

                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null) {
                            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, color("&aMercenario Nivel " + level + " de " + player.getName()));
                            npc.spawn(player.getLocation());
                            try (PreparedStatement updatePs = database.prepareStatement("UPDATE mercenaries SET npc_id=? WHERE player_uuid=?")) {
                                updatePs.setInt(1, npc.getId());
                                updatePs.setString(2, uuid.toString());
                                updatePs.executeUpdate();
                            }
                            npcId = npc.getId();
                        } else if (!npc.isSpawned()) {
                            npc.spawn(player.getLocation());
                        }
                        activeMercenaries.put(uuid, npcId);
                        mercenaryStays.put(uuid, false);
                    } else {
                        expireMercenary(uuid, rs.getInt("npc_id"));
                    }
                }
            }
        } catch (SQLException e) {
            getLogger().warning("Error cargando mercenario al entrar: " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Integer npcId = activeMercenaries.remove(uuid);
        mercenaryStays.remove(uuid);
        if (npcId != null) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
            if (npc != null && npc.isSpawned()) {
                npc.despawn();
            }
        }
    }

    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    private void message(Player player, String text) { player.sendMessage(color("&8[&6Mercenarios&8] " + text)); }

    @Override
    public void onDisable() {
        for (int npcId : activeMercenaries.values()) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
            if (npc != null && npc.isSpawned()) {
                npc.despawn();
            }
        }
        if (database != null) {
            try { if (!database.isClosed()) database.close(); } catch (SQLException ignored) {}
        }
    }
}
