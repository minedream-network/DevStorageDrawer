package com.teenkung.devstoragedrawer.config;

import com.teenkung.devstoragedrawer.domain.DrawerValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Validated Java-dialog and Bedrock-form presentation for drawer upgrades. */
public record DrawerUpgradeMenu(
        Dialog main,
        Dialog confirmation,
        String maximumLabel,
        String upgradePreview,
        String capacityLabel,
        String increaseLabel,
        Bedrock bedrock
) {
    public DrawerUpgradeMenu {
        Objects.requireNonNull(main, "main");
        Objects.requireNonNull(confirmation, "confirmation");
        Objects.requireNonNull(maximumLabel, "maximumLabel");
        Objects.requireNonNull(upgradePreview, "upgradePreview");
        Objects.requireNonNull(capacityLabel, "capacityLabel");
        Objects.requireNonNull(increaseLabel, "increaseLabel");
        Objects.requireNonNull(bedrock, "bedrock");
        main.validate("dialogs.main-menu.dialog", Set.of("upgrade"), "close");
        confirmation.validate("dialogs.upgrade-confirm.dialog", Set.of("confirm"), "back");
    }

    public static DrawerUpgradeMenu parse(final ConfigurationSection root) {
        final Dialog main = parseDialog(requireSection(root, "dialogs.main-menu.dialog"));
        final Dialog confirmation = parseDialog(requireSection(root, "dialogs.upgrade-confirm.dialog"));
        return new DrawerUpgradeMenu(
                main,
                confirmation,
                string(root, "maximum-label"),
                string(root, "upgrade-preview"),
                string(root, "capacity-label"),
                string(root, "increase-label"),
                parseBedrock(requireSection(root, "bedrock"))
        );
    }

    public static DrawerUpgradeMenu defaults() {
        final Dialog main = new Dialog("multi-action", "<gold><bold>✦ Drawer {tier}</bold>", "", true,
                false, "close", 1,
                List.of(new Body("text", "<yellow>{capacity_label}</yellow><br>{upgrade_preview}", 340)),
                List.of(new Button(true, "upgrade", "<green>✦ Upgrade to {next}</green>", "", 220, "upgrade")),
                new Button(true, "close", "<red>✕ Close</red>", "", 120, "close"));
        final Dialog confirm = new Dialog("multi-action", "<gold>Confirm drawer upgrade</gold>", "", true,
                false, "close", 2,
                List.of(new Body("text", "<yellow>{tier} → {next}</yellow><br>{capacity_label}<br>{increase_label}", 340)),
                List.of(new Button(true, "confirm", "<green>✓ Confirm · {cost}</green>", "", 150, "confirm")),
                new Button(true, "back", "<gray>← Back</gray>", "", 140, "back"));
        final BedrockPage bedrockMain = new BedrockPage("✦ Drawer {tier}", "{capacity_label}\n{upgrade_preview}",
                Map.of("upgrade", new BedrockButton("✦ Upgrade to {next}", "Cost {cost}", "", ""),
                        "close", new BedrockButton("✕ Close", "", "", "")));
        final BedrockPage bedrockConfirm = new BedrockPage("✦ Confirm drawer upgrade",
                "{tier} → {next}\n{capacity_label}\n{increase_label}",
                Map.of("confirm", new BedrockButton("✓ Confirm · {cost}", "", "", ""),
                        "back", new BedrockButton("← Back", "", "", "")));
        return new DrawerUpgradeMenu(main, confirm, "<gold>✦ Maximum tier", "<gold>➜ {next} · {cost}",
                "<aqua>Capacity <white>{stored}/{capacity}</white>",
                "<green>New capacity <white>{next_capacity}</white> (+{increase})</green>",
                new Bedrock(true, bedrockMain, bedrockConfirm));
    }

    private static Dialog parseDialog(final ConfigurationSection section) {
        final List<Body> bodies = new ArrayList<>();
        for (final Map<?, ?> value : section.getMapList("body")) {
            bodies.add(new Body(mapString(value, "type", "text"), mapString(value, "text", ""),
                    mapInt(value, "width", 350)));
        }
        final List<Button> buttons = new ArrayList<>();
        for (final Map<?, ?> value : section.getMapList("buttons")) {
            buttons.add(parseButton(value));
        }
        final ConfigurationSection exit = requireSection(section, "exit-button");
        return new Dialog(
                section.getString("type", "multi-action"),
                string(section, "title"),
                section.getString("external-title", ""),
                section.getBoolean("can-close-with-escape", true),
                section.getBoolean("pause", false),
                section.getString("after-action", "close"),
                section.getInt("columns", 1),
                bodies,
                buttons,
                new Button(exit.getBoolean("enabled", true), string(exit, "id"), string(exit, "label"),
                        exit.getString("tooltip", ""), exit.getInt("width", 150), string(exit, "action"))
        );
    }

    private static Button parseButton(final Map<?, ?> value) {
        return new Button(mapBoolean(value, "enabled", true), mapString(value, "id", ""),
                mapString(value, "label", ""), mapString(value, "tooltip", ""),
                mapInt(value, "width", 150), mapString(value, "action", ""));
    }

    private static Bedrock parseBedrock(final ConfigurationSection section) {
        return new Bedrock(
                section.getBoolean("enabled", true),
                parseBedrockPage(requireSection(section, "main-menu"), List.of("upgrade", "close")),
                parseBedrockPage(requireSection(section, "upgrade-confirm"), List.of("confirm", "back"))
        );
    }

    private static BedrockPage parseBedrockPage(final ConfigurationSection section, final List<String> actions) {
        final Map<String, BedrockButton> buttons = new LinkedHashMap<>();
        for (final String action : actions) {
            final ConfigurationSection configured = requireSection(section, action);
            final ConfigurationSection image = configured.getConfigurationSection("image");
            buttons.put(action, new BedrockButton(
                    string(configured, "label"),
                    configured.getString("description", ""),
                    image == null ? "" : image.getString("type", ""),
                    image == null ? "" : image.getString("value", "")
            ));
        }
        return new BedrockPage(string(section, "title"), string(section, "content"), buttons);
    }

    public record Dialog(
            String type,
            String title,
            String externalTitle,
            boolean canCloseWithEscape,
            boolean pause,
            String afterAction,
            int columns,
            List<Body> body,
            List<Button> buttons,
            Button exitButton
    ) {
        public Dialog {
            type = safe(type);
            title = safe(title);
            externalTitle = safe(externalTitle);
            afterAction = safe(afterAction);
            body = List.copyOf(Objects.requireNonNull(body, "body"));
            buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
            Objects.requireNonNull(exitButton, "exitButton");
        }

        public List<Button> visibleButtons() {
            return buttons.stream().filter(Button::enabled).toList();
        }

        public Button button(final String action) {
            if (normalize(exitButton.action()).equals(normalize(action))) return exitButton;
            return buttons.stream().filter(button -> normalize(button.action()).equals(normalize(action)))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing drawer upgrade action " + action));
        }

        private void validate(final String path, final Set<String> expectedActions, final String exitAction) {
            if (!normalize(type).equals("multi-action")) {
                throw new DrawerValidationException(path + ".type must be multi-action");
            }
            if (title.isBlank()) throw new DrawerValidationException(path + ".title must not be blank");
            if (columns < 1 || columns > 5) {
                throw new DrawerValidationException(path + ".columns must be between 1 and 5");
            }
            if (!Set.of("close", "none", "wait", "wait-for-response").contains(normalize(afterAction))) {
                throw new DrawerValidationException(path + ".after-action is invalid");
            }
            for (int index = 0; index < body.size(); index++) body.get(index).validate(path + ".body[" + index + "]");
            final Set<String> actions = new HashSet<>();
            final Set<String> ids = new HashSet<>();
            for (int index = 0; index < buttons.size(); index++) {
                final Button button = buttons.get(index);
                button.validate(path + ".buttons[" + index + "]");
                if (!ids.add(button.id())) throw new DrawerValidationException(path + " has duplicate id " + button.id());
                if (!actions.add(normalize(button.action()))) {
                    throw new DrawerValidationException(path + " has duplicate action " + button.action());
                }
            }
            if (!actions.equals(expectedActions)) {
                throw new DrawerValidationException(path + ".buttons actions must be exactly " + expectedActions);
            }
            exitButton.validate(path + ".exit-button");
            if (!normalize(exitButton.action()).equals(exitAction)) {
                throw new DrawerValidationException(path + ".exit-button.action must be " + exitAction);
            }
            if (ids.contains(exitButton.id())) throw new DrawerValidationException(path + " has duplicate exit id");
            if (!canCloseWithEscape && visibleButtons().isEmpty() && !exitButton.enabled()) {
                throw new DrawerValidationException(path + " must retain a visible close path");
            }
        }
    }

    public record Body(String type, String text, int width) {
        public Body {
            type = safe(type);
            text = safe(text);
        }

        private void validate(final String path) {
            if (!normalize(type).equals("text")) throw new DrawerValidationException(path + ".type must be text");
            if (width < 20 || width > 1024) throw new DrawerValidationException(path + ".width must be 20..1024");
        }
    }

    public record Button(boolean enabled, String id, String label, String tooltip, int width, String action) {
        public Button {
            id = safe(id);
            label = safe(label);
            tooltip = safe(tooltip);
            action = normalize(action);
        }

        private void validate(final String path) {
            if (id.isBlank() || label.isBlank() || action.isBlank()) {
                throw new DrawerValidationException(path + " requires id, label and action");
            }
            if (width < 20 || width > 1024) throw new DrawerValidationException(path + ".width must be 20..1024");
        }
    }

    public record Bedrock(boolean enabled, BedrockPage main, BedrockPage confirmation) {
        public Bedrock {
            Objects.requireNonNull(main, "main");
            Objects.requireNonNull(confirmation, "confirmation");
        }
    }

    public record BedrockPage(String title, String content, Map<String, BedrockButton> buttons) {
        public BedrockPage {
            title = safe(title);
            content = safe(content);
            buttons = Map.copyOf(Objects.requireNonNull(buttons, "buttons"));
            if (title.isBlank()) throw new DrawerValidationException("Bedrock upgrade title must not be blank");
        }

        public BedrockButton button(final String action) {
            final BedrockButton result = buttons.get(action);
            if (result == null) throw new IllegalArgumentException("Missing Bedrock drawer action " + action);
            return result;
        }
    }

    public record BedrockButton(String label, String description, String imageType, String imageValue) {
        public BedrockButton {
            label = safe(label);
            description = safe(description);
            imageType = normalize(imageType);
            imageValue = safe(imageValue).trim();
            if (label.isBlank()) throw new DrawerValidationException("Bedrock upgrade button label must not be blank");
            if (!imageType.isEmpty() && !Set.of("path", "url").contains(imageType)) {
                throw new DrawerValidationException("Bedrock upgrade image type must be path, url, or empty");
            }
            if (!imageValue.isEmpty() && imageType.isEmpty()) {
                throw new DrawerValidationException("Bedrock upgrade image type is required when value is set");
            }
        }
    }

    private static ConfigurationSection requireSection(final ConfigurationSection parent, final String path) {
        final ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) throw new DrawerValidationException("Missing configuration section: " + path);
        return section;
    }

    private static String string(final ConfigurationSection section, final String path) {
        final String value = section.getString(path);
        if (value == null) throw new DrawerValidationException("Missing configuration value: " + path);
        return value;
    }

    private static String mapString(final Map<?, ?> map, final String key, final String fallback) {
        final Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int mapInt(final Map<?, ?> map, final String key, final int fallback) {
        final Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean mapBoolean(final Map<?, ?> map, final String key, final boolean fallback) {
        final Object value = map.get(key);
        return value instanceof Boolean result ? result : fallback;
    }

    private static String normalize(final String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String safe(final String value) {
        return value == null ? "" : value;
    }
}
