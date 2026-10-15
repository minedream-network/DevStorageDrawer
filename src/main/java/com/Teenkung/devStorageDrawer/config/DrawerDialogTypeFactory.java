package com.teenkung.devstoragedrawer.config;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import java.util.Objects;

/** Builds a valid Paper dialog type even when dynamic filtering removes every primary action. */
public final class DrawerDialogTypeFactory {
    private DrawerDialogTypeFactory() { }

    public static DialogType actionsOrNotice(
            final List<ActionButton> actions,
            final ActionButton exit,
            final int columns
    ) {
        Objects.requireNonNull(actions, "actions");
        if (usesNotice(actions)) {
            return exit == null ? DialogType.notice() : DialogType.notice(exit);
        }
        return DialogType.multiAction(actions, exit, columns);
    }

    static boolean usesNotice(final List<?> actions) {
        return Objects.requireNonNull(actions, "actions").isEmpty();
    }
}
