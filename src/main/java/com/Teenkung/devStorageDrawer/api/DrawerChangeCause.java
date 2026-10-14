package com.teenkung.devstoragedrawer.api;

/** Reason reported with a committed drawer-state change. */
public enum DrawerChangeCause {
    PLACED,
    PLAYER_DEPOSIT,
    PLAYER_WITHDRAW,
    AUTOMATION,
    RECOVERY,
    MIGRATION,
    UPGRADE,
    EXTERNAL_SALE,
    API_DEPOSIT,
    API_WITHDRAW,
    SYSTEM
}
