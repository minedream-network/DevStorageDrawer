package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DrawerBedrockConfigurationTest {
    @TempDir Path directory;

    private DrawerConfigurationService configuration() throws Exception {
        return configuration(Map.of());
    }

    private DrawerConfigurationService configuration(final Map<String, Object> overrides) throws Exception {
        final var plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("drawer-bedrock-config-test"));
        final var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        yaml.set("automation.hopper-transfer-amount", 1);
        overrides.forEach(yaml::set);
        when(plugin.getConfig()).thenReturn(yaml);
        for (final String file : new String[] {"tiers.yml", "messages.yml"}) {
            try (final var resource = getClass().getResourceAsStream("/" + file)) {
                Files.copy(resource, directory.resolve(file));
            }
        }
        when(plugin.getResource("messages.yml")).thenAnswer(call -> getClass().getResourceAsStream("/messages.yml"));
        return new DrawerConfigurationService(plugin);
    }

    @Test void bundledDefaultsEnableOptionalFloodgateTextHolograms() throws Exception {
        final var configuration = configuration();
        configuration.load();
        final var bedrock = configuration.settings().bedrock();
        assertTrue(bedrock.enabled());
        assertFalse(bedrock.required());
        assertFalse(bedrock.armorStandShowItem());
        assertTrue(bedrock.armorStandShowName());
        assertEquals(1.35, bedrock.hologramHeight());
        assertEquals(0.25, bedrock.hologramLineSpacing());
    }

    @Test void legacyConstructorKeepsTheSameHologramDefaults() {
        final var bedrock = new DrawerSettings.Bedrock(true, false, true, true, true, true);
        assertEquals(1.35, bedrock.hologramHeight());
        assertEquals(0.25, bedrock.hologramLineSpacing());
    }

    @Test void customPositionsReachTheRuntimeAndAnExistingDisabledFlagIsPreserved() throws Exception {
        final var configuration = configuration(Map.of("bedrock.enabled", false,
                "bedrock.hologram.height", 2.0, "bedrock.hologram.line-spacing", 0.4));
        configuration.load();
        assertFalse(configuration.settings().bedrock().enabled());
        assertEquals(2.0, configuration.settings().bedrock().hologramHeight());
        assertEquals(0.4, configuration.settings().bedrock().hologramLineSpacing());
    }

    @Test void validatesFiniteAndBoundedHologramPositions() {
        for (final double height : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0.49, 4.01}) {
            final var error = assertThrows(IllegalArgumentException.class,
                    () -> new DrawerSettings.Bedrock(true, false, false, true, true, true, height, 0.25));
            assertTrue(error.getMessage().contains("bedrock.hologram.height"));
        }
        for (final double spacing : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, 0.09, 1.01}) {
            final var error = assertThrows(IllegalArgumentException.class,
                    () -> new DrawerSettings.Bedrock(true, false, false, true, true, true, 1.35, spacing));
            assertTrue(error.getMessage().contains("bedrock.hologram.line-spacing"));
        }
    }
}
