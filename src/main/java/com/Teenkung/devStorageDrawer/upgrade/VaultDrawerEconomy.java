package com.teenkung.devstoragedrawer.upgrade;

import java.util.Objects;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Resolves Vault lazily so the Core economy bridge may register after this plugin enables. */
final class VaultDrawerEconomy implements DrawerEconomy {
    private final JavaPlugin plugin;

    VaultDrawerEconomy(final JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean ready() {
        return provider() != null;
    }

    @Override
    public boolean withdraw(final UUID playerId, final double amount) {
        final Economy economy = provider();
        if (economy == null) return false;
        final OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerId);
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    @Override
    public boolean deposit(final UUID playerId, final double amount) {
        final Economy economy = provider();
        if (economy == null) return false;
        final OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerId);
        return economy.depositPlayer(player, amount).transactionSuccess();
    }

    private Economy provider() {
        final RegisteredServiceProvider<Economy> registration = plugin.getServer()
                .getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }
}
