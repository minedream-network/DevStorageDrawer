package com.teenkung.devstoragedrawer.upgrade;

import java.util.UUID;

/** Small economy boundary kept independent from Vault for focused transaction tests. */
interface DrawerEconomy {
    boolean ready();
    boolean withdraw(UUID playerId, double amount);
    boolean deposit(UUID playerId, double amount);
}
