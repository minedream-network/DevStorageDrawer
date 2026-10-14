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
        try (var input = getClass().getClassLoader().getResourceAsStream("upgrade-menu.yml")) {
            assertTrue(input != null, "upgrade-menu.yml must be bundled");
            final var yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            final DrawerUpgradeMenu menu = DrawerUpgradeMenu.parse(yaml);

            assertEquals("multi-action", menu.main().type());
            assertEquals("upgrade", menu.main().buttons().getFirst().action());
            assertEquals("close", menu.main().exitButton().action());
            assertEquals("confirm", menu.confirmation().buttons().getFirst().action());
            assertEquals("back", menu.confirmation().exitButton().action());
            assertTrue(menu.bedrock().enabled());
        }
    }
}
