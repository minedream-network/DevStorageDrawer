package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class DrawerUpgradeMenuTest {
    @Test
    void bundledMenuUsesRedstoneUpgraderDialogShape() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("menus.yml")) {
            assertTrue(input != null, "menus.yml must be bundled");
            final var yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            final DrawerUpgradeMenu menu = DrawerUpgradeMenu.parse(yaml.getConfigurationSection("upgrade"));

            assertEquals("multi-action", menu.main().type());
            assertEquals("upgrade", menu.main().buttons().getFirst().action());
            assertEquals("close", menu.main().exitButton().action());
            assertEquals("confirm", menu.confirmation().buttons().getFirst().action());
            assertEquals("back", menu.confirmation().exitButton().action());
            assertTrue(menu.bedrock().enabled());
            assertTrue(DrawerDialogTypeFactory.usesNotice(menu.main().visibleButtons().stream()
                    .filter(button -> !button.action().equals("upgrade"))
                    .toList()), "maximum-tier filtering must use a notice instead of an empty multi-action");
        }
    }
}
