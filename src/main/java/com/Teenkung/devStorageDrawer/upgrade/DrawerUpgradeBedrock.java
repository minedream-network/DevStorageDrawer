package com.teenkung.devstoragedrawer.upgrade;

import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerUpgradeMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

/** Optional Floodgate/Cumulus presentation isolated from the Java-dialog implementation. */
final class DrawerUpgradeBedrock {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    static boolean isPlayer(final Player player) {
        try {
            final FloodgateApi api = FloodgateApi.getInstance();
            return api != null && api.isFloodgatePlayer(player.getUniqueId());
        } catch (final RuntimeException | LinkageError exception) {
            return false;
        }
    }

    static boolean show(final Player player, final DrawerMessages messages, final DrawerUpgradeMenu menu,
            final Map<String, Object> values, final boolean confirmation, final boolean maximum,
            final Consumer<String> response) {
        try {
            final DrawerUpgradeMenu.BedrockPage page = confirmation
                    ? menu.bedrock().confirmation() : menu.bedrock().main();
            final String title = plain(messages, expand(menu, page.title(), values, maximum), values);
            final String content = plain(messages, expand(menu, page.content(), values, maximum), values);
            final SimpleForm.Builder form = SimpleForm.builder().title(title).content(content);
            final List<String> actions = new ArrayList<>();
            if (confirmation) {
                add(form, page.button("confirm"), messages, menu, values, maximum); actions.add("confirm");
                add(form, page.button("back"), messages, menu, values, maximum); actions.add("back");
            } else {
                if (!maximum) { add(form, page.button("upgrade"), messages, menu, values, false); actions.add("upgrade"); }
                add(form, page.button("close"), messages, menu, values, maximum); actions.add("close");
            }
            form.validResultHandler(result -> {
                final int index = result.clickedButtonId();
                if (index >= 0 && index < actions.size()) response.accept(actions.get(index));
            }).closedOrInvalidResultHandler(ignored -> response.accept("close"));
            final FloodgateApi api = FloodgateApi.getInstance();
            return api != null && api.sendForm(player.getUniqueId(), form.build());
        } catch (final RuntimeException | LinkageError exception) {
            return false;
        }
    }

    private static void add(final SimpleForm.Builder form, final DrawerUpgradeMenu.BedrockButton button,
            final DrawerMessages messages, final DrawerUpgradeMenu menu, final Map<String, Object> values,
            final boolean maximum) {
        final String label = plain(messages, expand(menu, button.label(), values, maximum), values);
        final String description = plain(messages, expand(menu, button.description(), values, maximum), values);
        final String text = description.isBlank() ? label : label + "\n" + description;
        if (button.imageValue().isBlank()) form.button(text);
        else form.button(text, button.imageType().equals("url") ? FormImage.Type.URL : FormImage.Type.PATH,
                button.imageValue());
    }

    static String expand(final DrawerUpgradeMenu menu, final String raw, final Map<String, Object> values,
            final boolean maximum) {
        return raw.replace("{capacity_label}", menu.capacityLabel())
                .replace("{increase_label}", maximum ? "" : menu.increaseLabel())
                .replace("{upgrade_preview}", maximum ? menu.maximumLabel() : menu.upgradePreview());
    }

    private static String plain(final DrawerMessages messages, final String raw, final Map<String, Object> values) {
        return PLAIN.serialize(messages.rawComponent(raw, values));
    }

    private DrawerUpgradeBedrock() { }
}
