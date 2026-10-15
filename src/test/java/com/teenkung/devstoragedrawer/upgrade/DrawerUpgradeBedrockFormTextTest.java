package com.teenkung.devstoragedrawer.upgrade;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerUpgradeMenu;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.api.Test;

class DrawerUpgradeBedrockFormTextTest {
    @Test
    void generatedSimpleFormContainsBedrockFormattingCodesInsteadOfMiniMessageTags() throws Exception {
        final DrawerUpgradeMenu menu;
        try (var input = getClass().getClassLoader().getResourceAsStream("menus.yml")) {
            final var yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            menu = DrawerUpgradeMenu.parse(yaml.getConfigurationSection("upgrade"));
        }
        final Map<String, Object> values = Map.ofEntries(
                Map.entry("tier", "Tier 1"), Map.entry("next", "Tier 2"),
                Map.entry("cost", 1_000), Map.entry("stored", 64), Map.entry("capacity", 2_048),
                Map.entry("next_capacity", 4_096), Map.entry("increase", 2_048),
                Map.entry("world", "world"), Map.entry("x", 1), Map.entry("y", 64), Map.entry("z", 2)
        );
        final UUID playerId = UUID.randomUUID();
        final Player player = mock(Player.class);
        final FloodgateApi api = mock(FloodgateApi.class);
        final AtomicReference<Form> sent = new AtomicReference<>();
        when(player.getUniqueId()).thenReturn(playerId);
        when(api.sendForm(eq(playerId), any(Form.class))).thenAnswer(call -> {
            sent.set(call.getArgument(1));
            return true;
        });

        try (var floodgate = mockStatic(FloodgateApi.class)) {
            floodgate.when(FloodgateApi::getInstance).thenReturn(api);
            assertTrue(DrawerUpgradeBedrock.show(player, new DrawerMessages(Map.of()), menu, values,
                    false, false, ignored -> { }));
        }

        final SimpleForm form = (SimpleForm) sent.get();
        assertTrue(form.title().startsWith("\u00A76\u00A7l"));
        assertTrue(form.content().contains("\u00A7bCapacity"));
        assertTrue(form.buttons().getFirst().text().startsWith("\u00A7a\u00A7l"));
        assertTrue(form.buttons().getFirst().text().contains("\n\u00A7ePrice"));
        assertFalse(form.title().contains("<gold>"));
        assertFalse(form.content().contains("<gold>"));
        assertTrue(form.buttons().stream().noneMatch(button -> button.text().contains("<gold>")));
    }
}
