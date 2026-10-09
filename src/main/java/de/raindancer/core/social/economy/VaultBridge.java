package de.raindancer.core.social.economy;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EconomyBridge} for Vault. Only ever constructed when Vault is installed — every Vault type
 * is linked from here, so a server without it never loads one.
 */
public final class VaultBridge implements EconomyBridge {

    private final Server server;
    private final Map<Economy, VaultEconomyExport> exports = new ConcurrentHashMap<>();
    private volatile VaultEconomyImport imported;

    public VaultBridge(Server server) {
        this.server = server;
    }

    /** Whether Vault is installed — asked by name, so nothing of Vault's is loaded to find out. */
    public static boolean vaultPresent(Server server) {
        return server.getPluginManager().getPlugin("Vault") != null;
    }

    @Override
    public void exported(Plugin owner, Economy economy) {
        VaultEconomyExport export = new VaultEconomyExport(economy,
                name -> de.raindancer.core.platform.command.PlayerTargets.byRealName(server, name).map(p -> p.getUniqueId()));
        VaultEconomyExport before = exports.put(economy, export);
        if (before != null) {
            server.getServicesManager().unregister(before);
        }
        // Highest, so a Rain economy wins over an EssentialsX that also registered: the server installed a
        // Rain economy on purpose, and two answering plugins split every balance.
        server.getServicesManager().register(net.milkbowl.vault.economy.Economy.class, export, owner,
                ServicePriority.Highest);
    }

    @Override
    public void retracted(Economy economy) {
        VaultEconomyExport export = exports.remove(economy);
        if (export != null) {
            server.getServicesManager().unregister(export);
        }
    }

    @Override
    public Optional<Economy> foreign() {
        for (RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> each
                : server.getServicesManager().getRegistrations(net.milkbowl.vault.economy.Economy.class)) {
            net.milkbowl.vault.economy.Economy vault = each.getProvider();
            if (vault instanceof VaultEconomyExport || !vault.isEnabled()) {
                continue;
            }
            VaultEconomyImport known = imported;
            if (known == null || known.vault() != vault) {
                known = new VaultEconomyImport(vault, (UUID id) -> server.getOfflinePlayer(id));
                imported = known;
            }
            return Optional.of(known);
        }
        return Optional.empty();
    }

    /** Everything this bridge registered with Vault, taken back — Core shutting down. */
    public void close() {
        exports.values().forEach(server.getServicesManager()::unregister);
        exports.clear();
    }
}
