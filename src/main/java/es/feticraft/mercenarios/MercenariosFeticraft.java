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
import org.bukkit.block.Block;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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
    private NamespacedKey contractKey;
    private NamespacedKey timeLeftKey;
    
    private final Map<UUID, Boolean> mercenaryStays = new HashMap<>(); 
    private final Map<UUID, String> mercenaryRoles = new HashMap<>(); 
    private final Map<UUID, Inventory> mercenaryInventories = new HashMap<>(); 
    private final Map<UUID, Integer> mercenaryLevels = new HashMap<>();
    private final Map<UUID, Integer> mercenaryXp = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();

    private String survivalWorldName;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        contractKey = new NamespacedKey(this, "mercenary_contract_item");
        timeLeftKey = new NamespacedKey(this, "mercenary_time_left");

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
                st.execute("CREATE TABLE IF NOT EXISTS mercenaries (player_uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, npc_id INTEGER NOT NULL, time_remaining INTEGER NOT NULL, status TEXT NOT NULL, level INTEGER DEFAULT 1, xp INTEGER DEFAULT 0, role TEXT DEFAULT 'FIGHTER')");
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
            } else if (args[0].equalsIgnoreCase("guardar") || args[0].equalsIgnoreCase("item")) {
                storeMercenaryToItem(player);
                return true;
            }
        }

        message(player, "&eUso de comandos:");
        message(player, "&f/mercenario contratar &7- Abre el menú de contratación");
        message(player, "&f/mercenario tiempo &7- Muestra el tiempo y nivel restante");
        message(player, "&f/mercenario reaparecer &7- Vuelve a hacer aparecer a tu mercenario");
        message(player, "&f/mercenario guardar &7- Guarda tu mercenario activo en un tótem (congela el tiempo)");
        return true;
    }

    private void openHireMenu(Player player) {
        UUID uuid = player.getUniqueId();

        if (!player.getWorld().getName().equalsIgnoreCase(survivalWorldName)) {
            message(player, "&cSolo puedes contratar mercenarios en el mundo de supervivencia (&e" + survivalWorldName + "&c).");
            return;
        }

        try (PreparedStatement ps = database.prepareStatement("SELECT time_remaining, status FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long timeRemaining = rs.getLong("time_remaining");
                    if (timeRemaining > 0) {
                        message(player, "&cYa tienes un contrato registrado. Guárdalo o úsalo antes de comprar otro.");
                        return;
                    }
                }
            }
        } catch (SQLException ignored) {}

        Inventory gui = Bukkit.createInventory(null, 9, color("&8Contratar Mercenario"));

        gui.setItem(2, createGuiItem(Material.CLOCK, "&e1 Hora de Contrato", 3600L, 2000.0));
        gui.setItem(4, createGuiItem(Material.GOLD_INGOT, "&e6 Horas de Contrato", 21600L, 10000.0));
        gui.setItem(6, createGuiItem(Material.EMERALD, "&e12 Horas de Contrato", 43200L, 18000.0));
        gui.setItem(8, createGuiItem(Material.NETHER_STAR, "&e24 Horas de Contrato", 86400L, 30000.0));

        player.openInventory(gui);
    }

    private ItemStack createGuiItem(Material mat, String name, long seconds, double price) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> loreList = new ArrayList<>();
            loreList.add(color("&7Precio: &a" + price + "$"));
            loreList.add(color("&7¡Haz clic para comprar tu tótem!"));
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

        ItemStack summonItem = createSummonTotem(durationSeconds);
        player.getInventory().addItem(summonItem);

        try (PreparedStatement ps = database.prepareStatement("INSERT OR REPLACE INTO mercenaries(player_uuid, player_name, npc_id, time_remaining, status, level, xp, role) VALUES(?,?,?,?, 'PENDING', 1, 0, 'FIGHTER')")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, player.getName());
            ps.setInt(3, -1);
            ps.setLong(4, durationSeconds);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Error guardando contrato pendiente: " + e.getMessage());
        }

        player.closeInventory();
        message(player, "&a¡Has comprado tu contrato! Se te ha entregado un &eTótem de Invocación &aen el inventario.");
    }

    private ItemStack createSummonTotem(long timeRemainingSeconds) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            long hours = timeRemainingSeconds / 3600;
            long minutes = (timeRemainingSeconds % 3600) / 60;

            meta.setDisplayName(color("&a&lTótem de Mercenario"));
            List<String> lore = new ArrayList<>();
            lore.add(color("&7Tiempo restante: &e" + hours + "h " + minutes + "m"));
            lore.add(color("&7(El tiempo está pausado en el tótem)"));
            lore.add(color("&7Haz clic derecho en el suelo para invocarlo."));
            meta.setLore(lore);

            meta.getPersistentDataContainer().set(contractKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(timeLeftKey, PersistentDataType.LONG, timeRemainingSeconds);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void storeMercenaryToItem(Player player) {
        UUID uuid = player.getUniqueId();

        // Buscar si tiene un NPC activo en el mundo a través de Citizens
        NPC foundNpc = null;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.data().has("owner") && npc.data().getString("owner").equals(uuid.toString())) {
                foundNpc = npc;
                break;
            }
        }

        try (PreparedStatement ps = database.prepareStatement("SELECT time_remaining FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long timeRemaining = rs.getLong("time_remaining");

                    if (foundNpc != null) {
                        foundNpc.destroy();
                    }

                    ItemStack totem = createSummonTotem(timeRemaining);
                    player.getInventory().addItem(totem);

                    try (PreparedStatement updatePs = database.prepareStatement("UPDATE mercenaries SET status='PENDING', npc_id=-1 WHERE player_uuid=?")) {
                        updatePs.setString(1, uuid.toString());
                        updatePs.executeUpdate();
                    }

                    mercenaryStays.remove(uuid);
                    mercenaryRoles.remove(uuid);

                    message(player, "&a¡Has guardado a tu mercenario! Su tiempo ha quedado &epausado &aen el tótem.");
                    return;
                }
            }
        } catch (SQLException e) {
            getLogger().severe("Error al guardar mercenario: " + e.getMessage());
        }

        message(player, "&cNo tienes ningún mercenario activo en el mundo para guardar.");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item.getType() == Material.AIR || !item.hasItemMeta()) return;
        if (!item.getItemMeta().getPersistentDataContainer().has(contractKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        UUID uuid = player.getUniqueId();

        if (!player.getWorld().getName().equalsIgnoreCase(survivalWorldName)) {
            message(player, "&cSolo puedes invocar a tu mercenario en el mundo de supervivencia (&e" + survivalWorldName + "&c).");
            return;
        }

        // Comprobar si ya tiene un NPC en el mundo
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.data().has("owner") && npc.data().getString("owner").equals(uuid.toString())) {
                message(player, "&cYa tienes un mercenario activo en el mundo.");
                return;
            }
        }

        long itemTimeRemaining = item.getItemMeta().getPersistentDataContainer().getOrDefault(timeLeftKey, PersistentDataType.LONG, 0L);

        if (itemTimeRemaining <= 0) {
            message(player, "&cEste contrato ha expirado.");
            item.setAmount(0);
            return;
        }

        item.setAmount(item.getAmount() - 1);

        Location loc = player.getLocation();
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, color("&aMercenario de " + player.getName()));
        npc.data().set("owner", uuid.toString());
        npc.spawn(loc);

        try (PreparedStatement updatePs = database.prepareStatement("UPDATE mercenaries SET npc_id=?, status='ACTIVE', time_remaining=? WHERE player_uuid=?")) {
            updatePs.setInt(1, npc.getId());
            updatePs.setLong(2, itemTimeRemaining);
            updatePs.setString(3, uuid.toString());
            updatePs.executeUpdate();
        } catch (SQLException ignored) {}

        mercenaryStays.put(uuid, false);
        mercenaryRoles.put(uuid, "FIGHTER");
        mercenaryInventories.put(uuid, Bukkit.createInventory(null, 27, color("&8Equipo de " + player.getName())));
        mercenaryLevels.put(uuid, 1);
        mercenaryXp.put(uuid, 0);

        message(player, "&a¡Has invocado a tu mercenario! El temporizador ha vuelto a correr.");
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damaged = event.getEntity();
        if (!CitizensAPI.getNPCRegistry().isNPC(damaged)) return;

        NPC npc = CitizensAPI.getNPCRegistry().getNPC(damaged);
        if (!npc.data().has("owner")) return;

        UUID ownerUuid = UUID.fromString(npc.data().getString("owner"));
        Inventory inv = mercenaryInventories.get(ownerUuid);
        if (inv != null) {
            double damage = event.getDamage();
            double defenseMultiplier = 1.0;

            for (int i = 18; i < 22; i++) {
                ItemStack armor = inv.getItem(i);
                if (armor != null) {
                    String name = armor.getType().name();
                    if (name.contains("NETHERITE")) defenseMultiplier -= 0.20;
                    else if (name.contains("DIAMOND")) defenseMultiplier -= 0.15;
                    else if (name.contains("IRON")) defenseMultiplier -= 0.10;
                    else if (name.contains("CHAINMAIL") || name.contains("GOLD") || name.contains("LEATHER")) defenseMultiplier -= 0.05;
                }
            }

            if (defenseMultiplier < 0.2) defenseMultiplier = 0.2;
            event.setDamage(damage * defenseMultiplier);
        }
    }

    private void checkMercenaryTime(Player player) {
        UUID uuid = player.getUniqueId();
        try (PreparedStatement ps = database.prepareStatement("SELECT time_remaining, status, level, xp, role FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long timeRemaining = rs.getLong("time_remaining");
                    int level = rs.getInt("level");
                    int xp = rs.getInt("xp");
                    String role = rs.getString("role");

                    if (timeRemaining > 0) {
                        long hours = timeRemaining / 3600;
                        long minutes = (timeRemaining % 3600) / 60;
                        String roleName = role.equals("MINER") ? "Minero ⛏️" : (role.equals("LUMBERJACK") ? "Talador 🪓" : "Luchador ⚔️");
                        
                        message(player, "&eEstadísticas del Mercenario:");
                        message(player, "&f- Rol: &b" + roleName);
                        message(player, "&f- Nivel: &a" + level + " &7(XP: " + xp + "/100)");
                        message(player, "&f- Tiempo restante: &e" + hours + "h " + minutes + "m");
                        return;
                    }
                }
            }
        } catch (SQLException ignored) {}

        message(player, "&cNo tienes ningún contrato de mercenario registrado.");
    }

    private void respawnMercenary(Player player) {
        UUID uuid = player.getUniqueId();

        if (!player.getWorld().getName().equalsIgnoreCase(survivalWorldName)) {
            message(player, "&cSolo puedes hacer reaparecer a tu mercenario en el mundo de supervivencia.");
            return;
        }

        try (PreparedStatement ps = database.prepareStatement("SELECT time_remaining FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long timeRemaining = rs.getLong("time_remaining");
                    if (timeRemaining > 0) {
                        // Destruir anterior si existe
                        for (NPC npc : CitizensAPI.getNPCRegistry()) {
                            if (npc.data().has("owner") && npc.data().getString("owner").equals(uuid.toString())) {
                                npc.destroy();
                            }
                        }

                        int level = getOrLoadLevel(uuid);
                        NPC newNpc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, color("&aMercenario de " + player.getName()));
                        newNpc.data().set("owner", uuid.toString());
                        newNpc.spawn(player.getLocation());

                        try (PreparedStatement updatePs = database.prepareStatement("UPDATE mercenaries SET npc_id=? WHERE player_uuid=?")) {
                            updatePs.setInt(1, newNpc.getId());
                            updatePs.setString(2, uuid.toString());
                            updatePs.executeUpdate();
                        }

                        message(player, "&a¡Tu mercenario ha reaparecido a tu lado!");
                        return;
                    }
                }
            }
        } catch (SQLException ignored) {}

        message(player, "&cNo tienes ningún contrato registrado para reaparecer.");
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
        Entity clicked = event.getRightClicked();
        if (!CitizensAPI.getNPCRegistry().isNPC(clicked)) return;

        NPC npc = CitizensAPI.getNPCRegistry().getNPC(clicked);
        if (!npc.data().has("owner")) return;

        UUID ownerUuid = UUID.fromString(npc.data().getString("owner"));
        if (!ownerUuid.equals(player.getUniqueId())) return;

        event.setCancelled(true);

        long now = System.currentTimeMillis();
        if (clickCooldown.containsKey(player.getUniqueId()) && now - clickCooldown.get(player.getUniqueId()) < 250) return;
        clickCooldown.put(player.getUniqueId(), now);

        if (player.isSneaking()) {
            openControlMenu(player, npc);
            return;
        }

        Inventory inv = mercenaryInventories.computeIfAbsent(player.getUniqueId(), k -> Bukkit.createInventory(null, 27, color("&8Equipo de " + player.getName())));
        player.openInventory(inv);
        message(player, "&7Abriendo el inventario de equipamiento de tu mercenario...");
    }

    private void openControlMenu(Player player, NPC npc) {
        Inventory gui = Bukkit.createInventory(null, 9, color("&8Gestión de Mercenario"));

        boolean isStaying = mercenaryStays.getOrDefault(player.getUniqueId(), false);
        String role = mercenaryRoles.getOrDefault(player.getUniqueId(), "FIGHTER");

        try (PreparedStatement ps = database.prepareStatement("SELECT role FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    role = rs.getString("role");
                    mercenaryRoles.put(player.getUniqueId(), role);
                }
            }
        } catch (SQLException ignored) {}

        ItemStack stayItem = new ItemStack(isStaying ? Material.RED_CONCRETE : Material.GREEN_CONCRETE);
        setItemMeta(stayItem, isStaying ? "&cEstado: Quieto" : "&aEstado: Siguiendo", "&7Haz clic para alternar el comportamiento.");
        gui.setItem(1, stayItem);

        Material roleMat = role.equals("MINER") ? Material.IRON_PICKAXE : (role.equals("LUMBERJACK") ? Material.IRON_AXE : Material.DIAMOND_SWORD);
        String roleName = role.equals("MINER") ? "Minero ⛏️" : (role.equals("LUMBERJACK") ? "Talador 🪓" : "Luchador ⚔️");
        ItemStack roleItem = new ItemStack(roleMat);
        setItemMeta(roleItem, "&bRol: " + roleName, "&7Clic para cambiar de trabajo.");
        gui.setItem(3, roleItem);

        ItemStack gearItem = new ItemStack(Material.IRON_CHESTPLATE);
        setItemMeta(gearItem, "&eInventario y Armadura", "&7Haz clic para abrir su equipo y herramientas.");
        gui.setItem(5, gearItem);

        ItemStack storeItem = new ItemStack(Material.TOTEM_OF_UNDYING);
        setItemMeta(storeItem, "&aGuardar en Tótem", "&7Devuelve el NPC a tu inventario y pausa el tiempo.");
        gui.setItem(7, storeItem);

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

        if (title.equals(color("&8Contratar Mercenario"))) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;

            int slot = event.getSlot();
            if (slot == 2) processHire(player, 3600L, 2000.0);
            else if (slot == 4) processHire(player, 21600L, 10000.0);
            else if (slot == 6) processHire(player, 43200L, 18000.0);
            else if (slot == 8) processHire(player, 86400L, 30000.0);
            return;
        }

        if (title.equals(color("&8Gestión de Mercenario"))) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            UUID uuid = player.getUniqueId();

            if (event.getSlot() == 1) {
                boolean currentStay = mercenaryStays.getOrDefault(uuid, false);
                boolean newStay = !currentStay;
                mercenaryStays.put(uuid, newStay);

                for (NPC npc : CitizensAPI.getNPCRegistry()) {
                    if (npc.data().has("owner") && npc.data().getString("owner").equals(uuid.toString())) {
                        if (newStay) {
                            message(player, "&eTu mercenario se ha quedado &fquieto&e.");
                            if (npc.isSpawned()) npc.getNavigator().cancelNavigation();
                        } else {
                            message(player, "&aTu mercenario ha vuelto a &fseguirte&a.");
                        }
                        break;
                    }
                }
                player.closeInventory();
            } else if (event.getSlot() == 3) {
                String currentRole = mercenaryRoles.getOrDefault(uuid, "FIGHTER");
                try (PreparedStatement ps = database.prepareStatement("SELECT role FROM mercenaries WHERE player_uuid=?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) currentRole = rs.getString("role");
                    }
                } catch (SQLException ignored) {}

                String newRole = "FIGHTER";
                if (currentRole.equals("FIGHTER")) newRole = "MINER";
                else if (currentRole.equals("MINER")) newRole = "LUMBERJACK";
                else if (currentRole.equals("LUMBERJACK")) newRole = "FIGHTER";

                mercenaryRoles.put(uuid, newRole);

                try (PreparedStatement ps = database.prepareStatement("UPDATE mercenaries SET role=? WHERE player_uuid=?")) {
                    ps.setString(1, newRole);
                    ps.setString(2, uuid.toString());
                    ps.executeUpdate();
                } catch (SQLException ignored) {}

                String rName = newRole.equals("MINER") ? "Minero ⛏️" : (newRole.equals("LUMBERJACK") ? "Talador 🪓" : "Luchador ⚔️");
                message(player, "&a¡Nuevo rol asignado: &e" + rName + "&a! Recuerda ponerle la herramienta requerida en su inventario.");
                player.closeInventory();
            } else if (event.getSlot() == 5) {
                player.closeInventory();
                Inventory inv = mercenaryInventories.computeIfAbsent(uuid, k -> Bukkit.createInventory(null, 27, color("&8Equipo de " + player.getName())));
                player.openInventory(inv);
                message(player, "&7Abriendo el inventario de equipamiento...");
            } else if (event.getSlot() == 7) {
                player.closeInventory();
                storeMercenaryToItem(player);
            }
            return;
        }
    }

    private void processMercenaryAIAndTimers() {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.data().has("owner")) continue;

            UUID uuid = UUID.fromString(npc.data().getString("owner"));
            Player player = Bukkit.getPlayer(uuid);

            // Descontar tiempo en base de datos
            try (PreparedStatement ps = database.prepareStatement("SELECT time_remaining, role FROM mercenaries WHERE player_uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long timeRemaining = rs.getLong("time_remaining") - 1L;
                        String role = rs.getString("role");

                        if (timeRemaining <= 0) {
                            expireMercenary(uuid, npc.getId());
                            continue;
                        } else {
                            try (PreparedStatement updatePs = database.prepareStatement("UPDATE mercenaries SET time_remaining=? WHERE player_uuid=?")) {
                                updatePs.setLong(1, timeRemaining);
                                updatePs.setString(2, uuid.toString());
                                updatePs.executeUpdate();
                            }
                        }

                        if (player != null && player.isOnline() && npc.isSpawned()) {
                            if (!player.getWorld().getName().equalsIgnoreCase(survivalWorldName)) {
                                npc.despawn();
                                continue;
                            }

                            boolean isStaying = mercenaryStays.getOrDefault(uuid, false);
                            Navigator navigator = npc.getNavigator();

                            if (isStaying) {
                                if (navigator.isNavigating()) navigator.cancelNavigation();
                                continue;
                            }

                            Inventory inv = mercenaryInventories.get(uuid);

                            // Seguir al jugador si está lejos
                            if (!navigator.isNavigating() && player.getWorld().equals(npc.getStoredLocation().getWorld()) &&
                                    player.getLocation().distanceSquared(npc.getStoredLocation()) > 16.0) {
                                navigator.setTarget(player, false);
                            }

                            if (role.equals("FIGHTER")) {
                                boolean hasSword = false;
                                if (inv != null) {
                                    for (ItemStack content : inv.getContents()) {
                                        if (content != null && content.getType().name().contains("SWORD")) {
                                            hasSword = true;
                                            break;
                                        }
                                    }
                                }
                                if (!hasSword) continue;

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
                                        double weaponBonus = 2.0;
                                        if (inv != null) {
                                            ItemStack handItem = inv.getItem(0);
                                            if (handItem != null && handItem.getType().name().contains("SWORD")) {
                                                String type = handItem.getType().name();
                                                if (type.contains("NETHERITE")) weaponBonus = 8.0;
                                                else if (type.contains("DIAMOND")) weaponBonus = 7.0;
                                                else if (type.contains("IRON")) weaponBonus = 6.0;
                                                else if (type.contains("STONE")) weaponBonus = 5.0;
                                                else weaponBonus = 4.0;
                                            }
                                        }
                                        target.damage(weaponBonus + (level * 1.0), npcEntity);
                                    }
                                }
                            } else if (role.equals("MINER")) {
                                boolean hasPickaxe = false;
                                if (inv != null) {
                                    for (ItemStack content : inv.getContents()) {
                                        if (content != null && content.getType().name().contains("PICKAXE")) {
                                            hasPickaxe = true;
                                            break;
                                        }
                                    }
                                }
                                if (!hasPickaxe) continue;

                                Location npcLoc = npc.getStoredLocation();
                                Block blockToMine = null;
                                for (int x = -3; x <= 3; x++) {
                                    for (int y = -2; y <= 2; y++) {
                                        for (int z = -3; z <= 3; z++) {
                                            Block b = npcLoc.clone().add(x, y, z).getBlock();
                                            if (b.getType().name().contains("ORE")) {
                                                blockToMine = b;
                                                break;
                                            }
                                        }
                                    }
                                }

                                if (blockToMine != null) {
                                    navigator.setTarget(blockToMine.getLocation());
                                    if (npcLoc.distanceSquared(blockToMine.getLocation()) <= 9.0) {
                                        Material minedType = blockToMine.getType();
                                        blockToMine.setType(Material.AIR);
                                        if (inv != null) inv.addItem(new ItemStack(minedType, 1));
                                        addXpToMercenary(uuid, 5);
                                    }
                                }
                            } else if (role.equals("LUMBERJACK")) {
                                boolean hasAxe = false;
                                if (inv != null) {
                                    for (ItemStack content : inv.getContents()) {
                                        if (content != null && content.getType().name().contains("AXE") && !content.getType().name().contains("PICKAXE")) {
                                            hasAxe = true;
                                            break;
                                        }
                                    }
                                }
                                if (!hasAxe) continue;

                                Location npcLoc = npc.getStoredLocation();
                                Block blockToCut = null;
                                for (int x = -3; x <= 3; x++) {
                                    for (int y = 0; y <= 4; y++) {
                                        for (int z = -3; z <= 3; z++) {
                                            Block b = npcLoc.clone().add(x, y, z).getBlock();
                                            if (b.getType().name().contains("LOG") || b.getType().name().contains("STEM")) {
                                                blockToCut = b;
                                                break;
                                            }
                                        }
                                    }
                                }

                                if (blockToCut != null) {
                                    navigator.setTarget(blockToCut.getLocation());
                                    if (npcLoc.distanceSquared(blockToCut.getLocation()) <= 9.0) {
                                        Material cutType = blockToCut.getType();
                                        blockToCut.setType(Material.AIR);
                                        if (inv != null) inv.addItem(new ItemStack(cutType, 1));
                                        addXpToMercenary(uuid, 5);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (SQLException ignored) {}
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player || dead.hasMetadata("NPC")) return;

        if (dead instanceof Monster) {
            Entity killer = dead.getKiller();
            if (killer == null) {
                for (NPC npc : CitizensAPI.getNPCRegistry()) {
                    if (npc.data().has("owner") && npc.isSpawned() && npc.getStoredLocation().getWorld().equals(dead.getWorld())) {
                        if (npc.getStoredLocation().distanceSquared(dead.getLocation()) <= 16.0) {
                            addXpToMercenary(UUID.fromString(npc.data().getString("owner")), 20);
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

        try (PreparedStatement ps = database.prepareStatement("UPDATE mercenaries SET status='EXPIRED', time_remaining=0 WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Error expirando mercenario: " + e.getMessage());
        }

        mercenaryStays.remove(uuid);
        mercenaryInventories.remove(uuid);
        mercenaryLevels.remove(uuid);
        mercenaryXp.remove(uuid);
        mercenaryRoles.remove(uuid);

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            message(player, "&cEl contrato de tu mercenario ha expirado.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        mercenaryStays.remove(uuid);
        mercenaryRoles.remove(uuid);
    }

    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    private void message(Player player, String text) { player.sendMessage(color("&8[&6Mercenarios&8] " + text)); }

    @Override
    public void onDisable() {
        if (database != null) {
            try { if (!database.isClosed()) database.close(); } catch (SQLException ignored) {}
        }
    }
}
