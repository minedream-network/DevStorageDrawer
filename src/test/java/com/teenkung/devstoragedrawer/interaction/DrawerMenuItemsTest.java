package com.teenkung.devstoragedrawer.interaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.config.DrawerMenuIcon;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import io.github.projectunified.uniitem.api.ItemKey;
import io.github.projectunified.uniitem.api.ItemProvider;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

class DrawerMenuItemsTest {
    private static ItemStack item(final int count, final ItemMeta meta) {
        final ItemStack item = mock(ItemStack.class);
        final Material material = mock(Material.class);
        final AtomicInteger amount = new AtomicInteger(count);
        when(item.getType()).thenReturn(material);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.getAmount()).thenAnswer(call -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        when(item.clone()).thenAnswer(call -> item(amount.get(), meta));
        return item;
    }

    private static DrawerSettings.StorageMenu settings(final String key) {
        return new DrawerSettings.StorageMenu(4, Map.of(DrawerMenuIcon.NEXT, key));
    }

    @Test void preservesProviderMetadataAndCachesDefensiveOneItemCopies() {
        final var provider = mock(ItemProvider.class);
        final var source = item(8, mock(ItemMeta.class));
        when(provider.normalize(any(ItemKey.class))).thenAnswer(call -> call.getArgument(0));
        when(provider.item(any(ItemKey.class))).thenReturn(source);
        final var icons = new DrawerMenuItems(() -> mock(Logger.class), () -> provider, material -> item(1, null));
        final var menu = settings("itemsadder:my_pack:next_page");
        final var first = icons.create(menu, DrawerMenuIcon.NEXT);
        first.setAmount(42);
        final var second = icons.create(menu, DrawerMenuIcon.NEXT);
        assertNotSame(first, second);
        assertEquals(1, second.getAmount());
        assertEquals(8, source.getAmount());
        assertSame(source.getItemMeta(), second.getItemMeta());
        verify(provider, times(1)).item(new ItemKey("itemsadder", "my_pack:next_page"));
    }

    @Test void missingProviderFallsBackAndWarnsOnlyOnceUntilReload() {
        final var provider = mock(ItemProvider.class);
        when(provider.normalize(any(ItemKey.class))).thenAnswer(call -> call.getArgument(0));
        final var logger = mock(Logger.class);
        final var builds = new AtomicInteger();
        final var icons = new DrawerMenuItems(() -> logger, () -> { builds.incrementAndGet(); return provider; },
                material -> { assertEquals(Material.ARROW, material); return item(1, null); });
        final var firstSettings = settings("nexo:missing");
        icons.create(firstSettings, DrawerMenuIcon.NEXT);
        icons.create(firstSettings, DrawerMenuIcon.NEXT);
        verify(logger, times(1)).warning(contains("storage-menu.items.next.material"));
        assertEquals(1, builds.get());
        icons.create(settings("nexo:missing"), DrawerMenuIcon.NEXT);
        assertEquals(2, builds.get());
        verify(logger, times(2)).warning(contains("nexo:missing"));
    }

    @Test void providerReloadRebuildsPreviouslyMissingItem() {
        final var missing = mock(ItemProvider.class);
        final var loaded = mock(ItemProvider.class);
        when(missing.normalize(any(ItemKey.class))).thenAnswer(call -> call.getArgument(0));
        when(loaded.normalize(any(ItemKey.class))).thenAnswer(call -> call.getArgument(0));
        final var model = mock(ItemMeta.class);
        final var loadedItem = item(1, model);
        when(loaded.item(any(ItemKey.class))).thenReturn(loadedItem);
        final var builds = new AtomicInteger();
        final var icons = new DrawerMenuItems(() -> mock(Logger.class),
                () -> builds.getAndIncrement() == 0 ? missing : loaded, material -> item(1, null));
        assertNull(icons.create(settings("nexo:next"), DrawerMenuIcon.NEXT).getItemMeta());
        assertSame(model, icons.create(settings("nexo:next"), DrawerMenuIcon.NEXT).getItemMeta());
    }

    @Test void incompatibleProviderCannotPreventMenuRendering() {
        final var provider = mock(ItemProvider.class);
        when(provider.normalize(any(ItemKey.class))).thenThrow(new NoClassDefFoundError("optional-provider"));
        final var logger = mock(Logger.class);
        final var icons = new DrawerMenuItems(() -> logger, () -> provider, material -> item(1, null));
        assertEquals(1, icons.create(settings("nexo:next"), DrawerMenuIcon.NEXT).getAmount());
        verify(logger).warning(contains("NoClassDefFoundError"));
    }

    @Test void vanillaNamespacedMaterialDoesNotInitializeCustomProviders() {
        final var diamond = mock(Material.class);
        when(diamond.isItem()).thenReturn(true);
        try (final var materials = mockStatic(Material.class)) {
            materials.when(() -> Material.matchMaterial("minecraft:diamond")).thenReturn(diamond);
            final var icons = new DrawerMenuItems(() -> mock(Logger.class),
                    () -> { throw new AssertionError("Vanilla must not use a custom provider"); },
                    material -> { assertSame(diamond, material); return item(1, null); });
            assertEquals(1, icons.create(settings("minecraft:diamond"), DrawerMenuIcon.NEXT).getAmount());
        }
    }

    @Test void airAndMalformedKeysUseVisibleFallbacks() {
        final var logger = mock(Logger.class);
        final var provider = mock(ItemProvider.class);
        when(provider.normalize(any(ItemKey.class))).thenAnswer(call -> call.getArgument(0));
        final var air = item(1, null);
        when(air.getType().isAir()).thenReturn(true);
        when(provider.item(any(ItemKey.class))).thenReturn(air);
        final var icons = new DrawerMenuItems(() -> logger, () -> provider, material -> item(1, null));
        for (final String key : new String[] {"nexo:air", "nexo:", ":missing", "UNKNOWN_MATERIAL"}) {
            assertEquals(1, icons.create(settings(key), DrawerMenuIcon.NEXT).getAmount());
        }
        verify(logger, times(4)).warning(anyString());
    }
}
