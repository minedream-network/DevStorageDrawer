package com.teenkung.devstoragedrawer.command;

import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import com.teenkung.devstoragedrawer.upgrade.DrawerUpgradeService;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Narrow runtime surface used by the administrative command. */
public interface DrawerAdminFacade {
    void validateReload(DrawerSettings settings);

    void reload(DrawerSettings settings, DrawerMessages messages);

    void repairLoadedDrawers();

    void migrateTargetedDrawer(Player player);

    boolean upgradeLedgerAvailable();

    List<DrawerUpgradeService.PendingUpgrade> pendingUpgradeOperations();

    boolean resolveUpgradeOperation(UUID id, DrawerUpgradeService.Resolution resolution);
}
