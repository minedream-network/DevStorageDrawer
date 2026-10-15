package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class DrawerDestroyMenuTest {
    @Test
    void bundledMenuProvidesJavaAndBedrockConfirmationActions() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("menus.yml")) {
            assertTrue(input != null, "menus.yml must be bundled");
            final var yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            final DrawerDestroyMenu menu = DrawerDestroyMenu.parse(yaml.getConfigurationSection("destroy"));

            assertEquals("multi-action", menu.confirmation().type());
            assertEquals("confirm", menu.confirmation().buttons().getFirst().action());
            assertEquals("cancel", menu.confirmation().exitButton().action());
            assertTrue(menu.bedrock().enabled());
            assertEquals("confirm", menu.bedrock().confirmation().buttons().keySet().stream()
                    .filter("confirm"::equals).findFirst().orElseThrow());
            assertTrue(menu.expand("{warning}", false).contains("{amount}"));
        }
    }
}
