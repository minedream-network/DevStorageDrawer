package com.teenkung.devstoragedrawer.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.teenkung.devstoragedrawer.config.DrawerDestroyMenu;
import com.teenkung.devstoragedrawer.config.DrawerMessages;
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

class DrawerDestroyBedrockFormTextTest {
    @Test
    void generatedSimpleFormContainsBedrockFormattingCodesInsteadOfMiniMessageTags() throws Exception {
        final DrawerDestroyMenu menu;
        try (var input = getClass().getClassLoader().getResourceAsStream("menus.yml")) {
            final var yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            menu = DrawerDestroyMenu.parse(yaml.getConfigurationSection("destroy"));
        }
        final Map<String, Object> values = Map.of(
                "amount", 1_024, "world", "world", "x", 1, "y", 64, "z", 2);
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
            assertTrue(DrawerDestroyBedrock.show(player, new DrawerMessages(Map.of()), menu, values,
                    ignored -> { }));
        }

        final SimpleForm form = (SimpleForm) sent.get();
        assertTrue(form.title().startsWith("\u00A7c\u00A7l"));
        assertTrue(form.content().contains("\u00A77This drawer holds \u00A7f1,024\u00A77"));
        assertTrue(form.buttons().getFirst().text().startsWith("\u00A7c\u00A7l"));
        assertTrue(form.buttons().getFirst().text().contains("\n\u00A7eDrop"));
        assertFalse(form.title().contains("<red>"));
        assertFalse(form.content().contains("<red>"));
        assertTrue(form.buttons().stream().noneMatch(button -> button.text().contains("<red>")));
    }
}
