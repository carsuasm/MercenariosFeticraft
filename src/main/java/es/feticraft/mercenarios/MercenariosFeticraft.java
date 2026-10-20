package es.feticraft.mercenarios;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
    
    // Almacena en memoria los jugadores conectados que tienen un mercenario activo
    private final Map<UUID, Integer> activeMercenaries = new HashMap<>(); // UUID Jugador -> ID del NPC en Citizens

    private static final double HIRE_PRICE = 500.0; // Precio configurable base
    private static final long HIRE_DURATION_SECONDS = 86400L; // 24 horas de alquiler por defecto

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

        // Tarea periódica cada segundo: descuenta tiempo de alquiler SOLO a jugadores conectados
        Bukkit.getScheduler().runTaskTimer(this, this::processOnlineMercenaryTimers, 20L, 20L);

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
            message(player, "&cNo tienes permiso para contratar mercenarios.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("contratar")) {
            hireMercenary(player);
            return true;
        }

        message(player, "&eUso del comando: &f/mercenario contratar");
        return true;
    }

    private void hireMercenary(Player player) {
        UUID uuid = player.getUniqueId();

        // 1. Verificar si ya tiene uno activo
        try (PreparedStatement ps = database.prepareStatement("SELECT expires_at FROM mercenaries WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    if (expiresAt > System.currentTimeMillis()) {
                        message(player, "&cYa tienes un mercenario contratado activamente.");
                        return;
                    }
                }
            }
        } catch (SQLException e) {
            message(player, "&cError al comprobar el estado del mercenario.");
            return;
        }

        // 2. Comprobar dinero
        if (!economy.has(player, HIRE_PRICE)) {
            message(player, "&cNo tienes suficiente dinero. El precio es &e" + HIRE_PRICE + "$&c.");
            return;
        }

        // 3. Cobrar y crear NPC con Citizens
        if (!economy.withdrawPlayer(player, HIRE_PRICE).transactionSuccess()) {
            message(player, "&cHubo un error al realizar el pago.");
            return;
        }

        Location loc = player.getLocation();
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, color("&aMercenario de " + player.getName()));
        npc.spawn(loc);

        int npcId = npc.getId();
        long expiresAt = System.currentTimeMillis() + (HIRE_DURATION_SECONDS * 1000L);

        // 4. Guardar en Base de Datos
        try (PreparedStatement ps = database.prepareStatement("INSERT OR REPLACE INTO mercenaries(player_uuid, player_name, npc_id, expires_at, status) VALUES(?,?,?,?, 'ACTIVE')")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, player.getName());
            ps.setInt(3, npcId);
            ps.setLong(4, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Error guardando mercenario: " + e.getMessage());
            npc.destroy();
            economy.depositPlayer(player, HIRE_PRICE); // Reembolso de seguridad
            message(player, "&cError crítico al registrar el mercenario. Se te ha reembolsado el dinero.");
            return;
        }

        activeMercenaries.put(uuid, npcId);
        message(player, "&a¡Has contratado con éxito a tu mercenario! Te acompañará durante 24 horas de juego activo.");
    }

    private void processOnlineMercenaryTimers() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Integer> entry : new HashMap<>(activeMercenaries).entrySet()) {
            UUID uuid = entry.getKey();
            int npcId = entry.getValue();

            try (PreparedStatement ps = database.prepareStatement("SELECT expires_at FROM mercenaries WHERE player_uuid=? AND status='ACTIVE'")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long expiresAt = rs.getLong("expires_at");
                        if (now >= expiresAt) {
                            // El tiempo ha expirado
                            expireMercenary(uuid, npcId);
                        }
                    }
                }
            } catch (SQLException e) {
                getLogger().warning("Error procesando temporizador de mercenario: " + e.getMessage());
            }
        }
    }

    private void expireMercenary(UUID uuid, int npcId) {
        // Eliminar NPC del mundo
        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc != null) {
            npc.destroy();
        }

        // Actualizar base de datos
        try (PreparedStatement ps = database.prepareStatement("UPDATE mercenaries SET status='EXPIRED' WHERE player_uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Error actualizando caducidad de mercenario: " + e.getMessage());
        }

        activeMercenaries.remove(uuid);

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            message(player, "&cEl contrato de tu mercenario ha expirado.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Al entrar, comprobamos si tiene un mercenario activo en la base de datos
        try (PreparedStatement ps = database.prepareStatement("SELECT npc_id, expires_at FROM mercenaries WHERE player_uuid=? AND status='ACTIVE'")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long expiresAt = rs.getLong("expires_at");
                    if (expiresAt > System.currentTimeMillis()) {
                        int npcId = rs.getInt("npc_id");
                        // Verificamos si el NPC existe en Citizens, si no, lo re-creamos
                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null) {
                            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, color("&aMercenario de " + player.getName()));
                            npc.spawn(player.getLocation());
                            // Actualizamos el ID si cambió
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
                    } else {
                        expireMercenary(uuid, rs.getInt("npc_id"));
                    }
                }
            }
        } catch (SQLException e) {
            getLogger().warning("Error al cargar mercenario al conectar: " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Integer npcId = activeMercenaries.remove(uuid);
        if (npcId != null) {
            // Al desconectarse el propietario, el NPC desaparece del mundo pero guarda su estado
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
        // Desovar todos los mercenarios activos al apagar el servidor
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
