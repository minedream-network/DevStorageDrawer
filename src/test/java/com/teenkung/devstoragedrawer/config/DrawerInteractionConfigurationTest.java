package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DrawerInteractionConfigurationTest {

    @Test
    void bundledConfigurationDisablesShortcutWithdrawalsWithoutDisablingAutomation() {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/config.yml"));
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            final var configuration = YamlConfiguration.loadConfiguration(reader);

            assertFalse(DrawerConfigurationService.parseInteraction(configuration).shortcutWithdrawalsEnabled());
            assertFalse(DrawerConfigurationService.parseInteraction(configuration).shortcutDepositsEnabled());
            assertTrue(configuration.getBoolean("automation.enabled"));
        } catch (final java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void missingShortcutSwitchFailsClosedAndExplicitTrueReenablesIt() {
        final var configuration = new YamlConfiguration();
        configuration.set("interaction.bulk-deposit-window-ticks", 10);
        assertFalse(DrawerConfigurationService.parseInteraction(configuration).shortcutWithdrawalsEnabled());
        assertFalse(DrawerConfigurationService.parseInteraction(configuration).shortcutDepositsEnabled());

        configuration.set("interaction.shortcut-withdrawals-enabled", true);
        configuration.set("interaction.shortcut-deposits-enabled", true);
        assertTrue(DrawerConfigurationService.parseInteraction(configuration).shortcutWithdrawalsEnabled());
        assertTrue(DrawerConfigurationService.parseInteraction(configuration).shortcutDepositsEnabled());
    }
}
