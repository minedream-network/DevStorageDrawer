package com.teenkung.devstoragedrawer.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Immutable message catalogue. Placeholder values are intentionally escaped as plain text. */
public final class DrawerMessages {
    private final Map<String, String> rawMessages;
    private final MiniMessage miniMessage;

    public DrawerMessages(final Map<String, String> rawMessages) {
        this.rawMessages = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(rawMessages, "rawMessages")));
        this.miniMessage = MiniMessage.miniMessage();
    }

    public Component component(final String key) {
        return component(key, Map.of());
    }

    public Component component(final String key, final Map<String, ?> placeholders) {
        return deserialize(key, placeholders, true);
    }

    /** Renders dialog titles, bodies and buttons without the chat prefix. */
    public Component dialogComponent(final String key, final Map<String, ?> placeholders) {
        return deserialize(key, placeholders, false);
    }

    public Component dialogComponent(final String key) {
        return dialogComponent(key, Map.of());
    }

    /** Renders trusted menu configuration while escaping all placeholder values as plain text. */
    public Component rawComponent(final String configured, final Map<String, ?> placeholders) {
        String raw = configured == null ? "" : configured;
        for (final Map.Entry<String, ?> entry : placeholders.entrySet()) {
            final String token = "{" + entry.getKey() + "}";
            if (!raw.contains(token)) continue;
            raw = raw.replace(token, miniMessage.serialize(Component.text(String.valueOf(entry.getValue()))));
        }
        return miniMessage.deserialize(raw)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private Component deserialize(final String key, final Map<String, ?> placeholders, final boolean withPrefix) {
        final String prefix = withPrefix ? rawMessages.getOrDefault("prefix", "") : "";
        String raw = prefix + rawMessages.getOrDefault(key, "<red>Missing message: " + key);
        for (final Map.Entry<String, ?> entry : placeholders.entrySet()) {
            final String token = "<" + entry.getKey() + ">";
            if (!raw.contains(token)) continue;
            raw = raw.replace(token, miniMessage.serialize(
                    Component.text(String.valueOf(entry.getValue()))
            ));
        }
        return miniMessage.deserialize(raw)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
