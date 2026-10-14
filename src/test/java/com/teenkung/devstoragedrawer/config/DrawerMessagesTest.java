package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class DrawerMessagesTest {
    @Test void unusedPlaceholderValuesAreNotFormattedAndUsedValuesRemainEscaped() {
        final var messages = new DrawerMessages(Map.of("prefix", "<gray>[drawer] ",
                "entry", "<gold><name> <amount>/<amount>", "title", "<gray>Storage"));
        final Object unused = new Object() {
            @Override public String toString() { throw new AssertionError("Unused value should not be formatted"); }
        };
        final var serializer = PlainTextComponentSerializer.plainText();
        assertEquals("Storage", serializer.serialize(messages.dialogComponent("title", Map.of("unused", unused))));
        assertEquals("[drawer] <red>literal 12/12", serializer.serialize(messages.component("entry",
                Map.of("unused", unused, "name", "<red>literal", "amount", 12))));
    }
}
