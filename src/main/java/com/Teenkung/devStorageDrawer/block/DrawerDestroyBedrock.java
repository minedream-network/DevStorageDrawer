package com.teenkung.devstoragedrawer.block;

import com.teenkung.devstoragedrawer.config.DrawerDestroyMenu;
import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.text.DrawerBedrockText;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

/** Optional Floodgate/Cumulus destroy confirmation isolated from the Java dialog path. */
final class DrawerDestroyBedrock {
    static boolean isPlayer(final Player player) {
        try {
            final FloodgateApi api = FloodgateApi.getInstance();
            return api != null && api.isFloodgatePlayer(player.getUniqueId());
        } catch (final RuntimeException | LinkageError exception) {
            return false;
        }
    }

    static boolean show(final Player player, final DrawerMessages messages, final DrawerDestroyMenu menu,
            final Map<String, Object> values, final Consumer<String> response) {
        try {
            final DrawerDestroyMenu.BedrockPage configured = menu.bedrock().confirmation();
            final boolean empty = ((Number) values.getOrDefault("amount", 0L)).longValue() == 0L;
            final SimpleForm.Builder form = SimpleForm.builder()
                    .title(bedrockText(messages, menu.expand(configured.title(), empty), values))
                    .content(bedrockText(messages, menu.expand(configured.content(), empty), values));
            add(form, configured.button("confirm"), messages, menu, values, empty);
            add(form, configured.button("cancel"), messages, menu, values, empty);
            form.validResultHandler(result -> response.accept(result.clickedButtonId() == 0 ? "confirm" : "cancel"))
                    .closedOrInvalidResultHandler(ignored -> response.accept("cancel"));
            final FloodgateApi api = FloodgateApi.getInstance();
            return api != null && api.sendForm(player.getUniqueId(), form.build());
        } catch (final RuntimeException | LinkageError exception) {
            return false;
        }
    }

    private static void add(final SimpleForm.Builder form, final DrawerDestroyMenu.BedrockButton button,
            final DrawerMessages messages, final DrawerDestroyMenu menu, final Map<String, Object> values,
            final boolean empty) {
        final String label = bedrockText(messages, menu.expand(button.label(), empty), values);
        final String description = bedrockText(messages, menu.expand(button.description(), empty), values);
        final String text = description.isBlank() ? label : label + "\n" + description;
        if (button.imageValue().isBlank()) {
            form.button(text);
        } else {
            form.button(text, button.imageType().equals("url") ? FormImage.Type.URL : FormImage.Type.PATH,
                    button.imageValue());
        }
    }

    private static String bedrockText(final DrawerMessages messages, final String raw,
            final Map<String, Object> values) {
        return DrawerBedrockText.serialize(messages.rawComponent(
                DrawerBedrockText.sanitizeMiniMessage(raw), values));
    }

    private DrawerDestroyBedrock() { }
}
