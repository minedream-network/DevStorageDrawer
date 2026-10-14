package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DrawerStorageMenuConfigurationTest {
    @Test void oldConfigsKeepEveryVanillaIcon() {
        final var menu = DrawerConfigurationService.parseStorageMenu(new YamlConfiguration());
        assertEquals(4, menu.refreshTicks());
        for (final var icon : DrawerMenuIcon.values()) assertEquals(icon.fallback().name(), menu.material(icon));
    }

    @Test void parsesProviderKeysWithNestedIdsAndKeepsTheirCase() throws Exception {
        final var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                storage-menu:
                  refresh-ticks: 6
                  items:
                    next:
                      material: " itemsadder:my_pack:next_page "
                    page:
                      material: "mmoitems:MISCELLANEOUS:MENU_ICON"
                """);
        final var menu = DrawerConfigurationService.parseStorageMenu(yaml);
        assertEquals(6, menu.refreshTicks());
        assertEquals("itemsadder:my_pack:next_page", menu.material(DrawerMenuIcon.NEXT));
        assertEquals("mmoitems:MISCELLANEOUS:MENU_ICON", menu.material(DrawerMenuIcon.PAGE));
        assertEquals("ARROW", menu.material(DrawerMenuIcon.PREVIOUS));
        assertThrows(UnsupportedOperationException.class, () -> menu.materials().clear());
    }

    @Test void rejectsEmptyAndNonStringMaterialsWithTheirConfigPath() {
        final var yaml = new YamlConfiguration();
        for (final Object invalid : new Object[] {" ", 123, Map.of("nested", "invalid")}) {
            yaml.set(DrawerMenuIcon.NEXT.path(), invalid);
            final var error = assertThrows(IllegalArgumentException.class,
                    () -> DrawerConfigurationService.parseStorageMenu(yaml));
            assertTrue(error.getMessage().contains(DrawerMenuIcon.NEXT.path()));
        }
    }
}
