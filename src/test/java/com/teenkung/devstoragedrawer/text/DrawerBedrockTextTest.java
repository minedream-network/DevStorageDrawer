package com.teenkung.devstoragedrawer.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.teenkung.devstoragedrawer.config.DrawerMessages;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

class DrawerBedrockTextTest {
    @Test
    void serializesColorsAndSupportedDecorationsForCumulus() {
        final Component component = Component.text("Menu", TextColor.color(0x74CCFF), TextDecoration.BOLD);

        assertEquals("\u00A7b\u00A7lMenu\u00A7r", DrawerBedrockText.serialize(component));
    }

    @Test
    void rendersMiniMessageAndEscapedPlaceholdersWithoutLeakingFormatting() {
        final DrawerMessages messages = new DrawerMessages(Map.of());
        final Component component = messages.rawComponent(
                "<gold><bold>Drawer {tier}</bold> <green>{amount}</green>",
                Map.of("tier", "<red>Tier 2", "amount", 1_024)
        );

        final String rendered = DrawerBedrockText.serialize(component);
        assertTrue(rendered.startsWith("\u00A76\u00A7lDrawer <red>Tier 2"));
        assertTrue(rendered.contains("\u00A7a1,024\u00A7r"));
        assertFalse(rendered.contains("<gold>"));
        assertTrue(rendered.endsWith("\u00A7r"));
    }

    @Test
    void resetsAStyledLabelBeforeASeparatelyRenderedDescription() {
        final String label = DrawerBedrockText.serialize(
                Component.text("Back", NamedTextColor.BLACK, TextDecoration.BOLD));
        final String description = DrawerBedrockText.serialize(
                Component.text("Description", NamedTextColor.BLACK));

        assertEquals("\u00A70\u00A7lBack\u00A7r\n\u00A70Description\u00A7r", label + "\n" + description);
    }

    @Test
    void removesUnsupportedBedrockOnlyTemplateTagsBeforeMiniMessageParsing() {
        assertEquals("<green>Grass </green>", DrawerBedrockText.sanitizeMiniMessage(
                "<green>Grass <sprite:blocks:block/grass_block_top></green>"));
        assertEquals("Top killer: ", DrawerBedrockText.sanitizeMiniMessage(
                "Top killer: \\<head:%ajlb_lb_statistic_mob_kills_1_alltime_name%>"));
    }
}
