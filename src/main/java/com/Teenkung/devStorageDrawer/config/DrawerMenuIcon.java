package com.teenkung.devstoragedrawer.config;

import org.bukkit.Material;

/** Fixed navigation controls; stock slots and click actions cannot be changed by icon configuration. */
public enum DrawerMenuIcon {
    PREVIOUS("previous", Material.ARROW),
    NEXT("next", Material.ARROW),
    FIRST("first", Material.GRAY_STAINED_GLASS_PANE),
    LAST("last", Material.GRAY_STAINED_GLASS_PANE),
    PAGE("page", Material.PAPER),
    UPGRADE("upgrade", Material.ANVIL),
    SEPARATOR("separator", Material.BLACK_STAINED_GLASS_PANE);

    private final String id;
    private final Material fallback;

    DrawerMenuIcon(final String id, final Material fallback) {
        this.id = id;
        this.fallback = fallback;
    }

    public String id() { return id; }
    public Material fallback() { return fallback; }
    public String path() { return "storage-menu.items." + id + ".material"; }
}
