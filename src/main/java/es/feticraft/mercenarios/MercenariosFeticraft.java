package es.feticraft.mercenarios;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.event.NPCClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MercenariosFeticraft extends JavaPlugin implements Listener, CommandExecutor {
    private Economy economy;
    private Connection database;
    private NamespacedKey mercenaryKey;
    
    private final Map<UUID, Integer> activeMercenaries = new HashMap<>(); // UUID Jugador -> ID del NPC
    private final Map<UUID, Boolean> mercenaryStays = new HashMap<>(); // UUID Jugador -> Si está quieto (true) o siguiendo (false)
    private final Map<UUID, Inventory> mercenaryInventories = new HashMap<>(); // UUID Jugador -> Inventario virtual

    private static final double HIRE_PRICE = 500.0;
    private static final long HIRE_DURATION_SECONDS = 86400L; // 24 horas

    @Override
    public void onEnable() {
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
                st.execute("CREATE TABLE IF NOT EXISTS mercenaries (player_uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, npc_id INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL)");
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

        if (args.length > 0 && args[0].equalsIgnoreCase("contratar")) {
            hireMercenary(player);
            return true;
        }

        message(player, "&eUso: &f/mercenario contratar");
        return true;
    }

    private void hireMercenary(Player player) {
        UUID uuid = player.getUniqueId();

        try (PreparedStatement ps = database.prepareStatement("SELECT expires_at FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong("expires_at") > System.currentTimeMillis()) {
                    message(player, "&cYa tienes un mercenario contratado activamente.");
                    return;
                }
            }
        } catch (SQLException e) {
            message(player, "&cError al comprobar el estado.");
            return;
        }

        if (!economy.has(player, HIRE_PRICE)) {
            message(player, "&cNo tienes suficiente dinero. Necesitas &e" + HIRE_PRICE + "$&c.");
            return;
        }

        if (!economy.withdrawPlayer(player, HIRE_PRICE).transactionSuccess()) {
            message(player, "&cHubo un error al realizar el pago.");
            return;
        }

        Location loc = player.getLocation();
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, color("&aMercenario de " + player.getName()));
        npc.spawn(loc);

        int npcId = npc.getId();
        long expiresAt = System.currentTimeMillis() + (HIRE_DURATION_SECONDS * 1000L);

        try (PreparedStatement ps = database.prepareStatement("INSERT OR REPLACE INTO mercenaries(player_uuid, player_name, npc_id, expires_at, status) VALUES(?,?,?,?, 'ACTIVE')")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, player.getName());
            ps.setInt(3, npcId);
            ps.setLong(4, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Error guardando mercenario: " + e.getMessage());
            npc.destroy();
            economy.depositPlayer(player, HIRE_PRICE);
            message(player, "&cError crítico. Se te ha reembolsado el dinero.");
            return;
        }

        activeMercenaries.put(uuid, npcId);
        mercenaryStays.put(uuid, false);
        mercenaryInventories.put(uuid, Bukkit.createInventory(null, 9, color("&8Equipo de " + player.getName())));
        
        message(player, "&a¡Has contratado a tu mercenario con éxito!");
    }

    // --- NUEVO: Interacción directa con el NPC haciendo clic ---
    @EventHandler
    public void onNPCClick(NPCClickEvent event) {
        Player player = event.getClicker();
        UUID uuid = player.getUniqueId();
        NPC npc = event.getNPC();

        // Comprobamos si este NPC es el mercenario de este jugador
        if (activeMercenaries.containsKey(uuid) && activeMercenaries.get(uuid) == npc.getId()) {
            event.setCancelled(true);

            // Si está agachado (Shift + Clic derecho) -> Cambia el estado de Quieto / Seguir
            if (player.isSneaking()) {
                boolean currentStay = mercenaryStays.getOrDefault(uuid, false);
                boolean newStay = !currentStay;
                mercenaryStays.put(uuid, newStay);

                if (newStay) {
                    message(player, "&eTu mercenario se ha quedado &fquieto&e.");
                } else {
                    message(player, "&aTu mercenario ha vuelto a &fseguirte&a.");
                }
            } else {
                // Clic derecho normal -> Abre su inventario de equipamiento
                Inventory inv = mercenaryInventories.computeIfAbsent(uuid, k -> Bukkit.createInventory(null, 9, color("&8Equipo de " + player.getName())));
                player.openInventory(inv);
                message(player, "&7Abriendo el equipo de tu mercenario...");
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().startsWith(color("&8Equipo de "))) {
            // Permite modificar los objetos del inventario del mercenario libremente
            return;
        }
    }

    private void processMercenaryAIAndTimers() {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, Integer> entry : new HashMap<>(activeMercenaries).entrySet()) {
            UUID uuid = entry.getKey();
            int npcId = entry.getValue();

            // 1. Comprobar caducidad
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

            // 2. IA de Combate y Seguimiento
            Player player = Bukkit.getPlayer(uuid);
            NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);

            if (player != null && player.isOnline() && npc != null && npc.isSpawned()) {
                boolean isStaying = mercenaryStays.getOrDefault(uuid, false);
                Navigator navigator = npc.getNavigator();

                // Buscar objetivos hostiles cercanos en radio de 8 bloques
                LivingEntity target = null;
                for (Entity entity : npc.getStoredLocation().getWorld().getEntities()) {
                    if (entity instanceof LivingEntity living && !(entity instanceof Player) && !(entity.hasMetadata("NPC"))) {
                        if (living.getLocation().distanceSquared(npc.getStoredLocation()) <= 64.0) {
                            target = living;
                            break;
                        }
                    }
                }

                if (target != null) {
                    navigator.setTarget(target, true);
                } else if (!isStaying) {
                    if (player.getWorld().equals(npc.getStoredLocation().getWorld()) &&
                            player.getLocation().distanceSquared(npc.getStoredLocation()) > 16.0) {
                        navigator.setTarget(player, false);
                    }
                }
            }
        }
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

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            message(player, "&cEl contrato de tu mercenario ha expirado.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        try (PreparedStatement ps = database.prepareStatement("SELECT npc_id, expires_at FROM mercenaries WHERE player_uuid=? AND status='ACTIVE'")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    if (expiresAt > System.currentTimeMillis()) {
                        int npcId = rs.getInt("npc_id");
                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null) {
                            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, color("&aMercenario de " + player.getName()));
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
