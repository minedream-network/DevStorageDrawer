package com.teenkung.devstoragedrawer.persistence;

import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.domain.DrawerValidationException;
import org.bukkit.inventory.ItemStack;

/** Serializes only the persistent primitive payload; the PDC adapter is {@link DrawerPdcCodec}. */
public final class DrawerStateCodec {
    private final DrawerTemplateDecodeCache templates;

    public DrawerStateCodec() { this(new DrawerTemplateDecodeCache()); }
    DrawerStateCodec(final DrawerTemplateDecodeCache templates) { this.templates = java.util.Objects.requireNonNull(templates); }

    public DrawerStatePayload encode(final DrawerState state) {
        final byte[] templateBytes = state.serializedTemplate();
        return new DrawerStatePayload(
                state.schemaVersion(),
                state.tierId(),
                templateBytes,
                state.hiddenCount(),
                state.expectedMirrorCount(),
                state.capacitySnapshot(),
                state.proxyJournal().orElse(null),
                state.displayLink()
        );
    }

    public DrawerState decode(final DrawerStatePayload payload) {
        if (payload.schemaVersion() > DrawerState.CURRENT_SCHEMA_VERSION) {
            throw new DrawerValidationException(
                    "Drawer schema " + payload.schemaVersion() + " is newer than this plugin supports"
            );
        }
        final byte[] templateBytes = payload.templateBytes();
        final ItemStack template;
        if (templateBytes == null) {
            template = null;
        } else if (templateBytes.length == 0) {
            throw new DrawerValidationException("Drawer template bytes must not be empty");
        } else {
            try {
                template = this.templates.decode(templateBytes);
            } catch (final RuntimeException exception) {
                throw new DrawerValidationException("Drawer template bytes cannot be decoded", exception);
            }
        }
        return DrawerState.restored(
                payload.schemaVersion(),
                payload.tierId(),
                template,
                payload.hiddenCount(),
                payload.expectedMirrorCount(),
                payload.capacitySnapshot(),
                payload.proxyJournal(),
                payload.displayLink()
        );
    }
}
