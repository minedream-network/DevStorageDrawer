package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class DrawerConfigurationLayoutTest {
    @Test
    void versionTwoHasOneSchemaVersionSourceAndOneTierUpgradeSource() throws Exception {
        final var global = resource("config.yml");
        final var tiers = resource("tiers.yml");
        final var menus = resource("menus.yml");
        final var messages = resource("messages.yml");

        assertEquals(2, global.getInt("config-version"));
        assertNotNull(global.getConfigurationSection("upgrade"));
        assertNull(global.getConfigurationSection("upgrade.dialogs"));
        assertNotNull(global.getConfigurationSection("destroy"));
        assertNull(global.getConfigurationSection("destroy.dialogs"));
        assertFalse(tiers.contains("config-version"));
        assertFalse(menus.contains("config-version"));
        assertFalse(messages.contains("config-version"));
        assertNull(tiers.getConfigurationSection("upgrade"));
        assertNotNull(tiers.getConfigurationSection("tiers.tier_1.upgrade"));
        assertNotNull(menus.getConfigurationSection("storage-menu"));
        assertNotNull(menus.getConfigurationSection("upgrade.dialogs"));
        assertNotNull(menus.getConfigurationSection("destroy.dialogs"));
        assertNull(DrawerConfigurationLayoutTest.class.getClassLoader().getResource("upgrade.yml"));
        assertNull(DrawerConfigurationLayoutTest.class.getClassLoader().getResource("upgrade-menu.yml"));
        assertNull(DrawerConfigurationLayoutTest.class.getClassLoader().getResource("destroy-menu.yml"));
    }

    @Test
    void rejectsASecondSchemaVersionSource() {
        final var secondary = new YamlConfiguration();
        secondary.set("config-version", 1);

        assertThrows(com.teenkung.devstoragedrawer.domain.DrawerValidationException.class,
                () -> DrawerConfigurationService.rejectLocalConfigVersion(secondary, "tiers.yml"));
        assertThrows(com.teenkung.devstoragedrawer.domain.DrawerValidationException.class,
                () -> DrawerConfigurationService.rejectLocalConfigVersion(secondary, "menus.yml"));
        assertThrows(com.teenkung.devstoragedrawer.domain.DrawerValidationException.class,
                () -> DrawerConfigurationService.rejectLocalConfigVersion(secondary, "messages.yml"));
    }

    private static YamlConfiguration resource(final String name) throws Exception {
        try (var input = DrawerConfigurationLayoutTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name + " must be bundled");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }
}
