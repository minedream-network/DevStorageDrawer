package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DrawerUpgradePathTest {
    @Test
    void normalizesOptionalPermission() {
        final DrawerUpgradePath path = new DrawerUpgradePath(" tier_2 ", 1000D, " drawer.vip ");
        assertEquals("tier_2", path.nextTierId());
        assertEquals("drawer.vip", path.optionalPermission().orElseThrow());
    }

    @Test
    void permitsFreeUpgradeAndRejectsInvalidMoney() {
        assertTrue(new DrawerUpgradePath("tier_2", 0D, "").optionalPermission().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new DrawerUpgradePath("tier_2", Double.NaN, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new DrawerUpgradePath("tier_2", -1D, ""));
    }
}
