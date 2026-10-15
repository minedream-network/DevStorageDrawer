package com.teenkung.devstoragedrawer.text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Converts Adventure formatting into the section codes understood by Bedrock forms. */
public final class DrawerBedrockText {
    private static final char LEGACY_MARKER = '\u00A7';
    private static final String RESET = "\u00A7r";
    private static final ComponentFlattener FLATTENER = ComponentFlattener.basic();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern UNSUPPORTED_BEDROCK_TAG = Pattern.compile(
            "(?i)\\\\?</?(?:sprite|head)(?::[^<>]*)?>"
    );
    private static final EffectiveStyle EMPTY_STYLE = new EffectiveStyle(null, false, false, false);
    private static final List<BedrockColor> COLORS = List.of(
            color('0', 0x000000),
            color('1', 0x0000AA),
            color('2', 0x00AA00),
            color('3', 0x00AAAA),
            color('4', 0xAA0000),
            color('5', 0xAA00AA),
            color('6', 0xFFAA00),
            color('7', 0xAAAAAA),
            color('8', 0x555555),
            color('9', 0x5555FF),
            color('a', 0x55FF55),
            color('b', 0x55FFFF),
            color('c', 0xFF5555),
            color('d', 0xFF55FF),
            color('e', 0xFFFF55),
            color('f', 0xFFFFFF),
            color('g', 0xDDD605),
            color('h', 0xE3D4D1),
            color('i', 0xCECACA),
            color('j', 0x443A3B),
            color('m', 0x971607),
            color('n', 0xB4684D),
            color('p', 0xDEB12D),
            color('q', 0x11A036),
            color('s', 0x2CBAA8),
            color('t', 0x21497B),
            color('u', 0x9A5CC6),
            color('v', 0xEB7214)
    );

    public static String sanitizeMiniMessage(final String input) {
        if (input == null || input.isEmpty()) return "";
        return UNSUPPORTED_BEDROCK_TAG.matcher(input).replaceAll("");
    }

    /** Serializes supported colors and decorations, falling back to visible plain text if needed. */
    public static String serialize(final Component component) {
        try {
            return serializeFormatted(component);
        } catch (final RuntimeException | LinkageError ignored) {
            return PLAIN.serialize(component);
        }
    }

    private static String serializeFormatted(final Component component) {
        final StringBuilder output = new StringBuilder();
        final Deque<EffectiveStyle> styles = new ArrayDeque<>();
        styles.push(EMPTY_STYLE);
        final EffectiveStyle[] rendered = {EMPTY_STYLE};

        FLATTENER.flatten(component, new FlattenerListener() {
            @Override
            public void pushStyle(final Style style) {
                styles.push(styles.peek().apply(style));
            }

            @Override
            public void component(final String text) {
                final EffectiveStyle target = styles.peek();
                appendTransition(rendered[0], target, output);
                appendText(text, target, output);
                rendered[0] = target;
            }

            @Override
            public void popStyle(final Style style) {
                styles.pop();
            }
        });
        if (!output.isEmpty() && rendered[0].hasFormatting()) output.append(RESET);
        return output.toString();
    }

    private static void appendTransition(final EffectiveStyle current, final EffectiveStyle target,
            final StringBuilder output) {
        final boolean colorChanged = current.colorCode() != target.colorCode();
        final boolean decorationRemoved = current.hasDecorationMissingFrom(target);

        if (colorChanged) {
            if (current.hasDecorations()) output.append(RESET);
            if (target.colorCode() == null) {
                if (!current.hasDecorations() && current.colorCode() != null) output.append(RESET);
            } else {
                appendCode(target.colorCode(), output);
            }
            target.appendDecorations(output);
            return;
        }

        if (decorationRemoved) {
            output.append(RESET);
            if (target.colorCode() != null) appendCode(target.colorCode(), output);
            target.appendDecorations(output);
            return;
        }

        if (!current.obfuscated() && target.obfuscated()) appendCode('k', output);
        if (!current.bold() && target.bold()) appendCode('l', output);
        if (!current.italic() && target.italic()) appendCode('o', output);
    }

    private static void appendText(final String text, final EffectiveStyle style, final StringBuilder output) {
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            output.append(character);
            if (character == '\n' && index + 1 < text.length()) style.appendAll(output);
        }
    }

    private static void appendCode(final char code, final StringBuilder output) {
        output.append(LEGACY_MARKER).append(code);
    }

    private static Character nearestColor(final TextColor color) {
        if (color == null) return null;
        final int rgb = color.value();
        BedrockColor nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (final BedrockColor candidate : COLORS) {
            final long distance = candidate.distanceSquared(rgb);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest == null ? null : nearest.code();
    }

    private static BedrockColor color(final char code, final int rgb) {
        return new BedrockColor(code, rgb);
    }

    private static boolean resolve(final TextDecoration.State state, final boolean inherited) {
        return switch (state) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> inherited;
        };
    }

    private record BedrockColor(char code, int rgb) {
        private long distanceSquared(final int otherRgb) {
            final long red = ((rgb >> 16) & 0xFF) - ((otherRgb >> 16) & 0xFF);
            final long green = ((rgb >> 8) & 0xFF) - ((otherRgb >> 8) & 0xFF);
            final long blue = (rgb & 0xFF) - (otherRgb & 0xFF);
            return red * red + green * green + blue * blue;
        }
    }

    private record EffectiveStyle(Character colorCode, boolean obfuscated, boolean bold, boolean italic) {
        private EffectiveStyle apply(final Style style) {
            final Character resolvedColor = style.color() == null ? colorCode : nearestColor(style.color());
            return new EffectiveStyle(
                    resolvedColor,
                    resolve(style.decoration(TextDecoration.OBFUSCATED), obfuscated),
                    resolve(style.decoration(TextDecoration.BOLD), bold),
                    resolve(style.decoration(TextDecoration.ITALIC), italic)
            );
        }

        private boolean hasDecorations() {
            return obfuscated || bold || italic;
        }

        private boolean hasFormatting() {
            return colorCode != null || hasDecorations();
        }

        private boolean hasDecorationMissingFrom(final EffectiveStyle target) {
            return (obfuscated && !target.obfuscated)
                    || (bold && !target.bold)
                    || (italic && !target.italic);
        }

        private void appendDecorations(final StringBuilder output) {
            if (obfuscated) appendCode('k', output);
            if (bold) appendCode('l', output);
            if (italic) appendCode('o', output);
        }

        private void appendAll(final StringBuilder output) {
            if (colorCode != null) appendCode(colorCode, output);
            appendDecorations(output);
        }
    }

    private DrawerBedrockText() { }
}
