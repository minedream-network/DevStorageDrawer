package com.teenkung.devstoragedrawer.config;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.teenkung.devstoragedrawer.domain.DrawerTier;
import com.teenkung.devstoragedrawer.persistence.DrawerPdcKeys;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class DrawerTierDefinitionTest {

    @Test
    void createsTaggedDrawerItemWithVisualGlintAndNoRealEnchantment() {
        final ItemMeta meta = mock(ItemMeta.class);
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        final var definition = new DrawerTierDefinition(
                new DrawerTier("tier_1", "Tier 1", 32), Component.text("Drawer"), List.of(), null, null, true
        );

        try (var construction = mockConstruction(ItemStack.class,
                (item, context) -> when(item.getItemMeta()).thenReturn(meta))) {
            final ItemStack created = definition.createItem();

            assertSame(construction.constructed().getFirst(), created);
            verify(meta).setEnchantmentGlintOverride(true);
            verify(meta, never()).addEnchant(any(), anyInt(), anyBoolean());
            verify(pdc).set(DrawerPdcKeys.TIER_ID, PersistentDataType.STRING, "tier_1");
            verify(created).setItemMeta(meta);
        }
    }

    @Test
    void configuredFalseSuppressesTheVisualGlint() {
        final ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        final var definition = new DrawerTierDefinition(
                new DrawerTier("tier_1", "Tier 1", 32), Component.text("Drawer"), List.of(), null, null, false
        );

        try (var ignored = mockConstruction(ItemStack.class,
                (item, context) -> when(item.getItemMeta()).thenReturn(meta))) {
            definition.createItem();
            verify(meta).setEnchantmentGlintOverride(false);
        }
    }
}
