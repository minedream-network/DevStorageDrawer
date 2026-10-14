package com.teenkung.devstoragedrawer.interaction;

import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;

/** Opens the optional upgrade presentation for a freshly revalidated drawer. */
@FunctionalInterface
public interface DrawerUpgradeOpener {
    void open(Player player, Barrel barrel);
}
