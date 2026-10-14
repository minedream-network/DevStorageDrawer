package com.teenkung.devstoragedrawer.interaction;

import com.teenkung.devstoragedrawer.config.DrawerMenuIcon;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import io.github.projectunified.uniitem.all.AllItemProvider;
import io.github.projectunified.uniitem.api.ItemKey;
import io.github.projectunified.uniitem.api.ItemProvider;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Bounded icon prototypes, rebuilt on reload and cloned before applying menu text. */
final class DrawerMenuItems {
    private final Supplier<Logger> logger;
    private final Supplier<ItemProvider> providers;
    private final Function<Material, ItemStack> vanilla;
    private final Map<DrawerMenuIcon, ItemStack> prototypes = new EnumMap<>(DrawerMenuIcon.class);
    private DrawerSettings.StorageMenu snapshot;
    private ItemProvider provider;

    DrawerMenuItems(final Supplier<Logger> logger) {
        this(logger, AllItemProvider::new, ItemStack::new);
    }

    DrawerMenuItems(final Supplier<Logger> logger, final Supplier<ItemProvider> providers,
            final Function<Material, ItemStack> vanilla) {
        this.logger = logger;
        this.providers = providers;
        this.vanilla = vanilla;
    }

    // Serialize lazy provider initialization and cache access between Folia player regions.
    synchronized ItemStack create(final DrawerSettings.StorageMenu settings, final DrawerMenuIcon icon) {
        if (snapshot != settings) {
            snapshot = settings;
            prototypes.clear();
            provider = null;
        }
        return prototypes.computeIfAbsent(icon, ignored -> resolve(settings, icon)).clone();
    }

    private ItemStack resolve(final DrawerSettings.StorageMenu settings, final DrawerMenuIcon icon) {
        final String configured = settings.material(icon);
        if (configured.equals(icon.fallback().name())) return vanilla.apply(icon.fallback());
        String failure = "unknown material, unavailable provider, or missing item";
        try {
            // Only strip the vanilla namespace. Other namespaces belong to UniItems providers.
            final boolean vanillaKey = !configured.contains(":") || configured.startsWith("minecraft:");
            final Material material = vanillaKey ? Material.matchMaterial(configured) : null;
            if (material != null && material.isItem() && !material.isAir()) {
                return vanilla.apply(material);
            }
            if (!vanillaKey) {
                final ItemKey key = ItemKey.fromString(configured);
                if (!key.type().isBlank() && !key.id().isBlank()) {
                    if (provider == null) provider = providers.get();
                    final ItemStack resolved = provider.item(provider.normalize(key));
                    if (resolved != null && !resolved.getType().isAir() && resolved.getAmount() > 0) {
                        final ItemStack prototype = resolved.clone();
                        prototype.setAmount(1);
                        return prototype;
                    }
                }
            }
        } catch (final RuntimeException | LinkageError exception) {
            failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        // The cached fallback also bounds warnings to once per control per reload.
        logger.get().warning("Cannot resolve " + icon.path() + "='" + configured + "' (" + failure
                + "); using " + icon.fallback().name() + ". Check the provider/item and run /drawers reload.");
        return vanilla.apply(icon.fallback());
    }
}
