package com.teenkung.devstoragedrawer.interaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.config.DrawerMenuIcon;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import io.github.projectunified.uniitem.all.AllItemProvider;
import io.github.projectunified.uniitem.api.ItemKey;
import java.util.Map;
import java.util.function.Consumer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DrawerMenuIconsIntegrationTest {
    @Test void customButtonsKeepNavigationAndCannotBePickedUp() {
        try (final var f = new DrawerChestInventoryTest.Fixture(3000, 64, 10000);
                final var providers = mockConstruction(AllItemProvider.class, (provider, construction) -> {
                    when(provider.normalize(any(ItemKey.class))).thenAnswer(call -> call.getArgument(0));
                    when(provider.item(any(ItemKey.class))).thenAnswer(call ->
                            f.item("custom-" + call.<ItemKey>getArgument(0).id(), 1, 64));
                })) {
            when(f.settings.storageMenu()).thenReturn(new DrawerSettings.StorageMenu(4,
                    Map.of(DrawerMenuIcon.NEXT, "nexo:next", DrawerMenuIcon.PAGE, "nexo:page")));
            f.menu.open(f.player, f.barrel);
            assertEquals("custom-next", f.identities.get(f.pageSlots[53]));
            assertEquals("custom-page", f.identities.get(f.pageSlots[49]));
            final var click = mock(InventoryClickEvent.class);
            when(click.getView()).thenReturn(f.view);
            when(click.getWhoClicked()).thenReturn(f.player);
            when(click.getRawSlot()).thenReturn(53);
            when(click.getAction()).thenReturn(InventoryAction.PICKUP_ALL);
            f.menu.click(click);
            verify(click).setCancelled(true);
            assertEquals(1, f.page.get().page);
            assertNull(f.cursor.get());
            assertEquals(3000, f.state.get().storedTotal());
            verify(f.repository, never()).save(any(), any());
            assertEquals(1, providers.constructed().size());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void reloadRebuildsOpenMenuFooterWithoutRepaintingUnchangedStock() {
        try (final var f = new DrawerChestInventoryTest.Fixture(3000, 64, 10000);
                final var providers = mockConstruction(AllItemProvider.class, (provider, construction) -> {
                    when(provider.normalize(any(ItemKey.class))).thenAnswer(call -> call.getArgument(0));
                    when(provider.item(any(ItemKey.class))).thenAnswer(call ->
                            f.item("custom-" + call.<ItemKey>getArgument(0).id() + "-v" + construction.getCount(), 1, 64));
                })) {
            when(f.settings.storageMenu()).thenReturn(new DrawerSettings.StorageMenu(4,
                    Map.of(DrawerMenuIcon.NEXT, "nexo:next", DrawerMenuIcon.SEPARATOR, "nexo:separator")));
            f.menu.open(f.player, f.barrel);
            assertEquals("custom-next-v1", f.identities.get(f.pageSlots[53]));
            final ArgumentCaptor<Consumer<ScheduledTask>> refresh = ArgumentCaptor.forClass(Consumer.class);
            verify(f.execution).runLaterOnEntity(eq(f.player), eq(4L), refresh.capture(), any());
            when(f.settings.storageMenu()).thenReturn(new DrawerSettings.StorageMenu(4,
                    Map.of(DrawerMenuIcon.NEXT, "nexo:next", DrawerMenuIcon.SEPARATOR, "nexo:separator")));
            clearInvocations(f.pageInventory);
            refresh.getValue().accept(null);
            assertEquals("custom-next-v2", f.identities.get(f.pageSlots[53]));
            assertEquals("custom-separator-v2", f.identities.get(f.pageSlots[46]));
            verify(f.pageInventory, never()).setItem(intThat(slot -> slot < 45), any());
            verify(f.player, times(1)).openInventory(any(org.bukkit.inventory.Inventory.class));
            assertEquals(3000, f.state.get().storedTotal());
            assertEquals(2, providers.constructed().size());
        }
    }
}
