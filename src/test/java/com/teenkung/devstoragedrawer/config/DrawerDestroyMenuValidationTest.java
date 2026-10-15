package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.teenkung.devstoragedrawer.domain.DrawerValidationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class DrawerDestroyMenuValidationTest {

    @Test
    void rejectsReplacingTheConfirmActionWithAnUnknownAction() {
        final var yaml = validMenu();
        yaml.set("dialogs.destroy-confirm.dialog.buttons", java.util.List.of(
                java.util.Map.of("id", "confirm", "label", "Destroy", "action", "delete-anything", "width", 150)));

        assertThrows(DrawerValidationException.class, () -> DrawerDestroyMenu.parse(yaml));
    }

    @Test
    void rejectsMissingBedrockCancelButton() {
        final var yaml = validMenu();
        yaml.set("bedrock.destroy-confirm.cancel", null);

        assertThrows(DrawerValidationException.class, () -> DrawerDestroyMenu.parse(yaml));
    }

    private static YamlConfiguration validMenu() {
        final var yaml = new YamlConfiguration();
        yaml.set("warning", "Contains {amount} items");
        yaml.set("empty-warning", "Empty");
        yaml.set("dialogs.destroy-confirm.dialog.type", "multi-action");
        yaml.set("dialogs.destroy-confirm.dialog.title", "Destroy?");
        yaml.set("dialogs.destroy-confirm.dialog.columns", 2);
        yaml.set("dialogs.destroy-confirm.dialog.body", java.util.List.of(
                java.util.Map.of("type", "text", "text", "{warning}", "width", 300)));
        yaml.set("dialogs.destroy-confirm.dialog.buttons", java.util.List.of(
                java.util.Map.of("id", "confirm", "label", "Destroy", "action", "confirm", "width", 150)));
        yaml.set("dialogs.destroy-confirm.dialog.exit-button.id", "cancel");
        yaml.set("dialogs.destroy-confirm.dialog.exit-button.label", "Keep");
        yaml.set("dialogs.destroy-confirm.dialog.exit-button.action", "cancel");
        yaml.set("dialogs.destroy-confirm.dialog.exit-button.width", 150);
        yaml.set("bedrock.enabled", true);
        yaml.set("bedrock.destroy-confirm.title", "Destroy?");
        yaml.set("bedrock.destroy-confirm.content", "{warning}");
        yaml.set("bedrock.destroy-confirm.confirm.label", "Destroy");
        yaml.set("bedrock.destroy-confirm.cancel.label", "Keep");
        return yaml;
    }
}
