package com.teenkung.devstoragedrawer.config;

import java.util.Optional;

/** One configured, strictly increasing drawer-tier upgrade step. */
public record DrawerUpgradePath(String nextTierId, double cost, String permission) {
    public DrawerUpgradePath {
        if (nextTierId == null || nextTierId.isBlank()) {
            throw new IllegalArgumentException("Drawer upgrade next-tier must not be blank");
        }
        nextTierId = nextTierId.trim();
        if (!Double.isFinite(cost) || cost < 0D) {
            throw new IllegalArgumentException("Drawer upgrade cost must be a finite non-negative number");
        }
        permission = permission == null || permission.isBlank() ? null : permission.trim();
    }

    public Optional<String> optionalPermission() {
        return Optional.ofNullable(permission);
    }
}
