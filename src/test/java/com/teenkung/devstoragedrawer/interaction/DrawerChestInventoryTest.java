package com.teenkung.devstoragedrawer.interaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.block.DrawerBlockAccess;
import com.teenkung.devstoragedrawer.block.DrawerRuntime;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.domain.*;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.*;
import com.teenkung.devstoragedrawer.receipt.DrawerWithdrawalCoordinator;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DrawerChestInventoryTest {
    static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final Map<ItemStack, String> identities = new IdentityHashMap<>();
        final DrawerRuntimeContext context = mock(DrawerRuntimeContext.class);
        final DrawerBlockAccess blocks = mock(DrawerBlockAccess.class);
        final DrawerStateRepository repository = mock(DrawerStateRepository.class);
        final DrawerHopperBridge hoppers = mock(DrawerHopperBridge.class);
        final DrawerWithdrawalProtection protection = mock(DrawerWithdrawalProtection.class);
        final FoliaExecution execution = mock(FoliaExecution.class);
        final DrawerSettings settings = mock(DrawerSettings.class);
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final Barrel barrel = mock(Barrel.class);
        final Player player = mock(Player.class);
        final Inventory top = mock(Inventory.class);
        final PlayerInventory bottom = mock(PlayerInventory.class);
        final InventoryView view = mock(InventoryView.class);
        final Location location = new Location(world, 0, 64, 0);
        final ItemStack[] slots = new ItemStack[27];
        final ItemStack[] playerSlots = new ItemStack[36];
        final ItemStack[] pageSlots = new ItemStack[54];
        final Inventory pageInventory = mock(Inventory.class);
        final AtomicReference<DrawerPageHolder> page = new AtomicReference<>();
        final org.mockito.MockedConstruction<ItemStack> buttons;
        final AtomicReference<ItemStack> cursor = new AtomicReference<>();
        final AtomicReference<DrawerState> state = new AtomicReference<>();
        final List<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> nextTick = new ArrayList<>();
        final SingleItemDrawerStorage storage = new SingleItemDrawerStorage(false);
        final DrawerChestInventory menu;
        boolean failSave;

        Fixture(final long total, final int physical, final long capacity) {
            buttons = mockConstruction(ItemStack.class, (button, creation) -> {
                final String identity = "button-" + creation.arguments().getFirst();
                identities.put(button, identity);
                when(button.getType()).thenReturn(mock(Material.class));
                when(button.getAmount()).thenReturn(1);
                when(button.getMaxStackSize()).thenReturn(64);
                when(button.isSimilar(any())).thenAnswer(call -> identity.equals(identities.get(call.getArgument(0))));
                when(button.clone()).thenAnswer(call -> item(identity, 1, 64));
            });
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54),
                    any(net.kyori.adventure.text.Component.class))).thenAnswer(call -> {
                page.set(call.getArgument(0)); return pageInventory;
            });
            when(pageInventory.getHolder(false)).thenAnswer(call -> page.get());
            when(pageInventory.getSize()).thenReturn(54);
            when(pageInventory.getItem(anyInt())).thenAnswer(call -> pageSlots[(int) call.getArgument(0)]);
            doAnswer(call -> { pageSlots[(int) call.getArgument(0)] = call.getArgument(1); return null; })
                    .when(pageInventory).setItem(anyInt(), any());
            doAnswer(call -> { Arrays.fill(pageSlots, null); return null; }).when(pageInventory).clear();
            doAnswer(call -> {
                when(view.getTopInventory()).thenReturn(call.getArgument(0)); return view;
            }).when(player).openInventory(any(Inventory.class));
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            when(block.getLocation()).thenReturn(location);
            when(barrel.getLocation()).thenReturn(location);
            when(barrel.getBlock()).thenReturn(block);
            when(barrel.getInventory()).thenReturn(top);
            when(top.getHolder(false)).thenReturn(barrel);
            when(top.getSize()).thenReturn(27);
            when(top.getMaxStackSize()).thenReturn(64);
            when(top.getContents()).thenAnswer(call -> slots.clone());
            when(top.getItem(anyInt())).thenAnswer(call -> slots[(int) call.getArgument(0)]);
            when(top.getViewers()).thenReturn(List.of(player));
            doAnswer(call -> { slots[(int) call.getArgument(0)] = call.getArgument(1); return null; })
                    .when(top).setItem(anyInt(), any());
            doAnswer(call -> { System.arraycopy(call.getArgument(0), 0, slots, 0, 27); return null; })
                    .when(top).setContents(any(ItemStack[].class));
            doAnswer(call -> { Arrays.fill(slots, null); return null; }).when(top).clear();
            when(view.getTopInventory()).thenReturn(top);
            when(view.getBottomInventory()).thenReturn(bottom);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(location.clone().add(0, 0, 1));
            when(player.isOnline()).thenReturn(true);
            when(player.getInventory()).thenReturn(bottom);
            when(player.getOpenInventory()).thenReturn(view);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.hasPermission("devstoragedrawer.use")).thenReturn(true);
            when(player.getItemOnCursor()).thenAnswer(call -> cursor.get());
            doAnswer(call -> { cursor.set(call.getArgument(0)); return null; }).when(player).setItemOnCursor(any());
            when(bottom.getItem(anyInt())).thenAnswer(call -> playerSlots[(int) call.getArgument(0)]);
            when(bottom.getStorageContents()).thenAnswer(call -> playerSlots.clone());
            when(bottom.getMaxStackSize()).thenReturn(64);
            doAnswer(call -> { System.arraycopy(call.getArgument(0), 0, playerSlots, 0, 36); return null; })
                    .when(bottom).setStorageContents(any(ItemStack[].class));
            doAnswer(call -> { playerSlots[(int) call.getArgument(0)] = call.getArgument(1); return null; })
                    .when(bottom).setItem(anyInt(), any());
            when(protection.canWithdraw(player, barrel)).thenReturn(true);
            when(context.blocks()).thenReturn(blocks);
            when(context.repository()).thenReturn(repository);
            when(context.storage()).thenReturn(storage);
            when(context.execution()).thenReturn(execution);
            doAnswer(call -> { nextTick.add(call.getArgument(1)); return null; })
                    .when(execution).runOnEntity(eq(player), any(), any());
            when(context.settings()).thenReturn(settings);
            when(settings.interaction()).thenReturn(new DrawerSettings.Interaction(10, false));
            when(settings.storageMenu()).thenReturn(new DrawerSettings.StorageMenu(4));
            when(context.logger()).thenReturn(Logger.getLogger("drawer-chest-test"));
            when(context.messages()).thenReturn(new DrawerMessages(Map.of(
                    "drawer.full", "Full", "drawer.wrong-item", "Wrong item", "drawer.inventory-busy", "Busy",
                    "general.no-permission", "Denied", "general.configuration-error", "Failed",
                    "drawer.menu-too-far", "Too far")));
            final DrawerSettings.Automation automation = mock(DrawerSettings.Automation.class);
            when(automation.outputProxySlots()).thenReturn(26);
            when(automation.fallbackPollIntervalTicks()).thenReturn(10L);
            when(automation.rebalanceDelayTicks()).thenReturn(1L);
            when(settings.automation()).thenReturn(automation);
            when(blocks.barrel(block)).thenReturn(Optional.of(barrel));
            when(repository.isDrawer(barrel)).thenReturn(true);
            when(repository.read(barrel)).thenAnswer(call -> new DrawerStateReadResult.Valid(state.get()));
            when(repository.save(eq(barrel), any())).thenAnswer(call -> {
                if (failSave) return false;
                state.set(call.getArgument(1)); return true;
            });
            when(blocks.inspect(eq(barrel), any())).thenAnswer(call -> {
                final DrawerState current = call.getArgument(1);
                return new DrawerBlockAccess.PhysicalStock(mirror(), Arrays.stream(slots).filter(Objects::nonNull)
                        .allMatch(current::matchesTemplate));
            });
            state.set(DrawerState.restored(2, "tier", total == 0 ? null : item("stone", 1, 64), total,
                    physical, capacity, null, DrawerDisplayLink.none()));
            if (physical > 0) slots[1] = item("stone", physical, 64);
            menu = new DrawerChestInventory(context, hoppers, protection);
        }
        ItemStack item(final String identity, final int initial, final int stackSize) {
            final ItemStack item = mock(ItemStack.class);
            final AtomicInteger amount = new AtomicInteger(initial);
            identities.put(item, identity);
            when(item.getType()).thenReturn(mock(Material.class));
            when(item.getAmount()).thenAnswer(call -> amount.get());
            when(item.getMaxStackSize()).thenReturn(stackSize);
            when(item.isSimilar(any())).thenAnswer(call -> identity.equals(identities.get(call.getArgument(0))));
            when(item.clone()).thenAnswer(call -> item(identity, amount.get(), stackSize));
            doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
            return item;
        }
        InventoryClickEvent click(final int rawSlot, final InventoryAction action) {
            final InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getView()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getAction()).thenReturn(action);
            when(event.getRawSlot()).thenReturn(rawSlot);
            when(event.getSlot()).thenReturn(rawSlot < 27 ? rawSlot : rawSlot - 27);
            when(event.getClickedInventory()).thenReturn(rawSlot < 27 ? top : bottom);
            when(event.getCurrentItem()).thenAnswer(call -> rawSlot < 27 ? slots[rawSlot] : playerSlots[rawSlot - 27]);
            when(event.getCursor()).thenAnswer(call -> cursor.get());
            when(event.getHotbarButton()).thenReturn(-1);
            return event;
        }
        InventoryDragEvent drag(final Map<Integer, ItemStack> replacements) {
            final InventoryDragEvent event = mock(InventoryDragEvent.class);
            when(event.getView()).thenReturn(view);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getOldCursor()).thenAnswer(call -> cursor.get());
            when(event.getRawSlots()).thenReturn(replacements.keySet());
            when(event.getNewItems()).thenReturn(replacements);
            return event;
        }
        long mirror() { return Arrays.stream(slots).filter(Objects::nonNull).mapToLong(ItemStack::getAmount).sum(); }
        long total() { return state.get().totalForPhysical(mirror()); }
        void reconcile() { state.set(storage.reconcile(state.get(), mirror()).state()); }
        void tick() {
            final var tasks = List.copyOf(nextTick);
            nextTick.clear();
            tasks.forEach(task -> task.accept(null));
        }
        @Override public void close() { buttons.close(); bukkit.close(); }
    }

    @Test void opensLargePagedStorageAndStillGuardsNativeContainerOpenEvents() {
        try (Fixture f = new Fixture(1000, 64, 10000)) {
            f.menu.open(f.player, f.barrel);
            verify(f.player).openInventory(f.pageInventory);
            final InventoryOpenEvent event = mock(InventoryOpenEvent.class);
            when(event.getInventory()).thenReturn(f.top);
            when(event.getPlayer()).thenReturn(f.player);
            f.menu.opening(event);
            verify(event, never()).setCancelled(true);
        }
    }
    @Test void disabledShortcutWithdrawalsStillAllowTheStorageGuiToOpen() {
        try (Fixture f = new Fixture(1000, 64, 10000)) {
            when(f.settings.interaction()).thenReturn(new DrawerSettings.Interaction(false, 10, false));
            final ItemStack held = f.item("stone", 64, 64);
            when(f.bottom.getItemInMainHand()).thenReturn(held);
            doAnswer(call -> {
                final java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task = call.getArgument(1);
                task.accept(null); return null;
            }).when(f.execution).runOnEntity(eq(f.player), any(), any());
            final var listener = new DrawerInteractionListener(f.context, f.hoppers,
                    mock(DrawerWithdrawalCoordinator.class), DrawerRuntime.DrawerDisplayTargetResolver.none(), f.protection);
            final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(f.player);
            when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
            when(event.getClickedBlock()).thenReturn(f.block);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);
            listener.onPlayerInteract(event);
            verify(f.player).openInventory(f.pageInventory);
            assertEquals(64, held.getAmount());
            assertEquals(1000, f.total());
            verify(f.repository, never()).save(any(), any());
        }
    }

    @Test void disabledShortcutWithdrawalsDoNotArmAReleasedLeftClick() {
        try (Fixture f = new Fixture(1000, 64, 10000)) {
            when(f.settings.interaction()).thenReturn(new DrawerSettings.Interaction(false, 10, false));
            final var withdrawals = mock(DrawerWithdrawalCoordinator.class);
            final var listener = new DrawerInteractionListener(f.context, f.hoppers, withdrawals,
                    DrawerRuntime.DrawerDisplayTargetResolver.none(), f.protection);
            final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(f.player);
            when(event.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);
            when(event.getClickedBlock()).thenReturn(f.block);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);

            listener.onPlayerInteract(event);
            final BlockDamageAbortEvent released = mock(BlockDamageAbortEvent.class);
            when(released.getPlayer()).thenReturn(f.player);
            when(released.getBlock()).thenReturn(f.block);
            listener.onBlockDamageAbort(released);

            verify(event, never()).setCancelled(true);
            verify(f.player, never()).openInventory(any(Inventory.class));
            verify(f.repository, never()).save(any(), any());
            verifyNoInteractions(withdrawals);
        }
    }

    @Test void enabledShortcutWithdrawalsStillBeginTheReleasedLeftClickTransfer() {
        try (Fixture f = new Fixture(1000, 64, 10000)) {
            when(f.settings.interaction()).thenReturn(new DrawerSettings.Interaction(true, 10, false));
            when(f.block.getWorld()).thenReturn(f.world);
            when(f.block.getX()).thenReturn(0);
            when(f.block.getY()).thenReturn(64);
            when(f.block.getZ()).thenReturn(0);
            final var withdrawals = mock(DrawerWithdrawalCoordinator.class);
            final var listener = new DrawerInteractionListener(f.context, f.hoppers, withdrawals,
                    DrawerRuntime.DrawerDisplayTargetResolver.none(), f.protection);
            final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(f.player);
            when(event.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);
            when(event.getClickedBlock()).thenReturn(f.block);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);
            listener.onPlayerInteract(event);

            final BlockDamageAbortEvent released = mock(BlockDamageAbortEvent.class);
            when(released.getPlayer()).thenReturn(f.player);
            when(released.getBlock()).thenReturn(f.block);
            listener.onBlockDamageAbort(released);

            verify(withdrawals).begin(eq(f.player), eq(f.barrel),
                    argThat(transaction -> transaction.accepted() && transaction.acceptedAmount() == 1L));
        }
    }
    @Test void nativePickupAndSplittingDebitOnlyTheItemsActuallyRemoved() {
        for (final var action : List.of(InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE)) {
            try (Fixture f = new Fixture(1000, 64, 10000)) {
                final var event = f.click(1, action);
                f.menu.click(event);
                verify(event, never()).setCancelled(true);
                assertEquals(1000, f.total(), "No synthetic grant happens before vanilla moves the slot");
                final int taken = action == InventoryAction.PICKUP_ALL ? 64 : action == InventoryAction.PICKUP_HALF ? 32 : 1;
                f.slots[1] = taken == 64 ? null : f.item("stone", 64 - taken, 64);
                f.cursor.set(f.item("stone", taken, 64));
                f.reconcile();
                assertEquals(1000 - taken, f.total());
                assertEquals(1000, f.total() + f.cursor.get().getAmount());
                verify(f.hoppers).queueRebalance(f.barrel);
            }
        }
    }
    @Test void shiftClickDepositsAndNormalCursorPlacesAreNotCreditedTwice() {
        for (final boolean shift : List.of(false, true)) {
            try (Fixture f = new Fixture(1000, 64, 10000)) {
                f.cursor.set(f.item("stone", 20, 64));
                f.playerSlots[9] = f.item("stone", 20, 64);
                final var event = f.click(shift ? 36 : 0,
                        shift ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.PLACE_ALL);
                f.menu.click(event);
                verify(event, never()).setCancelled(true);
                assertEquals(1000, f.total());
                f.slots[0] = f.item("stone", 20, 64);
                if (shift) f.playerSlots[9] = null; else f.cursor.set(null);
                f.reconcile(); f.reconcile();
                assertEquals(1020, f.total());
            }
        }
    }
    @Test void dragDistributesRealCursorItemsAndRespectsLogicalCapacity() {
        try (Fixture f = new Fixture(1000, 64, 10000)) {
            f.cursor.set(f.item("stone", 8, 64));
            final var moved = Map.of(0, f.item("stone", 4, 64), 2, f.item("stone", 4, 64));
            final var event = f.drag(moved);
            f.menu.drag(event);
            verify(event, never()).setCancelled(true);
            moved.forEach((slot, item) -> f.slots[slot] = item);
            f.cursor.set(null);
            f.reconcile();
            assertEquals(1008, f.total());
        }
        try (Fixture f = new Fixture(1000, 64, 1005)) {
            f.cursor.set(f.item("stone", 8, 64));
            final var event = f.drag(Map.of(0, f.item("stone", 4, 64), 2, f.item("stone", 4, 64)));
            f.menu.drag(event);
            verify(event).setCancelled(true);
            assertEquals(1000, f.total());
            assertEquals(8, f.cursor.get().getAmount());
        }
    }
    @Test void hotbarAndOffhandSwapsUseOnlyMatchingItems() {
        for (final boolean offhand : List.of(false, true)) {
            for (final String identity : List.of("stone", "stone-with-different-data")) {
                try (Fixture f = new Fixture(1000, 64, 1000)) {
                    final ItemStack offered = f.item(identity, 16, 64);
                    f.playerSlots[0] = offered;
                    when(f.bottom.getItemInOffHand()).thenReturn(offered);
                    final var event = f.click(1, InventoryAction.HOTBAR_SWAP);
                    when(event.getHotbarButton()).thenReturn(0);
                    when(event.getClick()).thenReturn(offhand ? ClickType.SWAP_OFFHAND : ClickType.NUMBER_KEY);
                    f.menu.click(event);
                    if (identity.equals("stone")) verify(event, never()).setCancelled(true);
                    else verify(event).setCancelled(true);
                }
            }
        }
    }
    @Test void wrongCursorShiftAndDragItemsRemainWithThePlayer() {
        try (Fixture f = new Fixture(1000, 64, 10000)) {
            final ItemStack wrong = f.item("dirt", 64, 64);
            f.cursor.set(wrong); f.playerSlots[9] = wrong;
            final var cursor = f.click(0, InventoryAction.PLACE_ALL);
            final var shift = f.click(36, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            final var drag = f.drag(Map.of(0, wrong));
            f.menu.click(cursor); f.menu.click(shift); f.menu.drag(drag);
            verify(cursor).setCancelled(true); verify(shift).setCancelled(true); verify(drag).setCancelled(true);
            assertSame(wrong, f.cursor.get()); assertSame(wrong, f.playerSlots[9]);
            assertEquals(1000, f.total());
        }
    }
    @Test void partialCursorAndShiftDepositsLeaveTheExcessInTheirOriginalSource() {
        for (final boolean shift : List.of(false, true)) {
            try (Fixture f = new Fixture(1000, 64, 1010)) {
                f.cursor.set(f.item("stone", 64, 64));
                f.playerSlots[9] = f.item("stone", 64, 64);
                final var event = f.click(shift ? 36 : 0,
                        shift ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.PLACE_ALL);
                f.menu.click(event);
                verify(event).setCancelled(true);
                assertEquals(1010, f.total());
                assertEquals(74, f.mirror());
                assertEquals(54, shift ? f.playerSlots[9].getAmount() : f.cursor.get().getAmount());
                f.reconcile();
                assertEquals(1010, f.total());
            }
        }
    }
    @Test void failedPartialDepositRestoresBothTheSlotAndThePlayerSource() {
        for (final boolean shift : List.of(false, true)) {
            try (Fixture f = new Fixture(1000, 64, 1010)) {
                f.failSave = true;
                f.cursor.set(f.item("stone", 64, 64)); f.playerSlots[9] = f.item("stone", 64, 64);
                f.menu.click(f.click(shift ? 36 : 0,
                        shift ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.PLACE_ALL));
                assertEquals(1000, f.total()); assertEquals(64, f.mirror());
                assertEquals(64, shift ? f.playerSlots[9].getAmount() : f.cursor.get().getAmount());
            }
        }
    }
    @Test void aFullDrawerStillAllowsWithdrawalsAndRejectsAdditionalDeposits() {
        try (Fixture f = new Fixture(1000, 64, 1000)) {
            f.cursor.set(f.item("stone", 64, 64));
            final var place = f.click(0, InventoryAction.PLACE_ALL);
            final var take = f.click(1, InventoryAction.PICKUP_ALL);
            f.menu.click(place); f.menu.click(take);
            verify(place).setCancelled(true); verify(take, never()).setCancelled(true);
            assertEquals(1000, f.total()); assertEquals(64, f.cursor.get().getAmount());
        }
    }
    @Test void firstGuiDepositInitializesTheNativeStackBasedCapacityWithoutPrecredit() {
        try (Fixture f = new Fixture(0, 0, 27)) {
            final DrawerTierDefinition tier = mock(DrawerTierDefinition.class);
            when(tier.tier()).thenReturn(new DrawerTier("tier", "Tier", 27, ""));
            when(f.context.tier("tier")).thenReturn(Optional.of(tier));
            f.cursor.set(f.item("stone", 16, 16));
            final var event = f.click(0, InventoryAction.PLACE_ALL);
            f.menu.click(event);
            verify(event, never()).setCancelled(true);
            assertEquals(0, f.total()); assertEquals(432, f.state.get().capacitySnapshot());
            f.slots[0] = f.item("stone", 16, 16); f.cursor.set(null); f.reconcile();
            assertEquals(16, f.total());
        }
    }
    @Test void journalLocksBlockChestTransfersButAllowBottomInventoryOrganization() {
        for (final var kind : List.of(DrawerJournalKind.PLAYER_WITHDRAWAL,
                DrawerJournalKind.GUARDED_EXTERNAL_SALE, DrawerJournalKind.WORLD_DROP)) {
            try (Fixture f = new Fixture(1000, 64, 10000)) {
                f.state.set(f.state.get().withProxyJournal(new DrawerProxyJournal(UUID.randomUUID(), kind,
                        DrawerJournalPhase.PREPARED, f.player.getUniqueId(), 1000, 900, 64, 64, 1)));
                final var take = f.click(1, InventoryAction.PICKUP_ALL);
                final var own = f.click(27, InventoryAction.PICKUP_ALL);
                f.menu.click(take); f.menu.click(own);
                verify(take).setCancelled(true); verify(own, never()).setCancelled(true);
                verify(f.hoppers, never()).queueRebalance(any());
            }
        }
    }
    @Test void permissionsProtectionAndRegionOwnershipAreCheckedBeforeStockAccess() {
        for (int denied = 0; denied < 3; denied++) {
            try (Fixture f = new Fixture(1000, 64, 10000)) {
                if (denied == 0) when(f.player.hasPermission(anyString())).thenReturn(false);
                if (denied == 1) when(f.protection.canWithdraw(f.player, f.barrel)).thenReturn(false);
                if (denied == 2) f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
                final var event = f.click(1, InventoryAction.PICKUP_ALL);
                f.menu.click(event);
                verify(event).setCancelled(true);
                verify(f.repository, never()).read(any());
                verify(f.blocks, never()).inspect(any(), any());
            }
        }
    }
    @Test void forgedInventoriesCannotTurnCopiesIntoWithdrawableDrawerStock() {
        try (Fixture f = new Fixture(1000, 64, 10000)) {
            final Inventory fake = mock(Inventory.class);
            when(fake.getHolder(false)).thenReturn(f.barrel);
            when(fake.getSize()).thenReturn(27);
            when(f.view.getTopInventory()).thenReturn(fake);
            final var event = f.click(1, InventoryAction.PICKUP_ALL);
            f.menu.click(event);
            verify(event).setCancelled(true);
            final InventoryOpenEvent opening = mock(InventoryOpenEvent.class);
            when(opening.getInventory()).thenReturn(fake); when(opening.getPlayer()).thenReturn(f.player);
            f.menu.opening(opening); verify(opening).setCancelled(true);
        }
    }
    @Test void nativeSlotDropCollectAndShiftWithdrawalRemainAvailableButCreativeCloningDoesNot() {
        try (Fixture f = new Fixture(1000, 64, 10000)) {
            for (final var action : List.of(InventoryAction.DROP_ONE_SLOT, InventoryAction.DROP_ALL_SLOT,
                    InventoryAction.COLLECT_TO_CURSOR, InventoryAction.MOVE_TO_OTHER_INVENTORY)) {
                final var event = f.click(1, action);
                f.menu.click(event); verify(event, never()).setCancelled(true);
            }
            final var clone = f.click(1, InventoryAction.CLONE_STACK);
            f.menu.click(clone); verify(clone).setCancelled(true);
        }
    }
    @Test void openingAChestDoesNotReplaceAPlayersMatchingSlotArrangement() {
        try (Fixture f = new Fixture(128, 64, 10000)) {
            f.slots[1] = null; f.slots[0] = f.item("stone", 64, 64); f.slots[7] = f.item("stone", 64, 64);
            f.state.set(f.state.get().withExpectedMirrorCount(128));
            final var bridge = new DrawerHopperBridge(f.context, mock(DrawerWithdrawalCoordinator.class));
            bridge.recoverAndRebalance(f.barrel);
            assertNotNull(f.slots[0]); assertNotNull(f.slots[7]);
            verify(f.top, never()).clear();
            verify(f.top, never()).setItem(anyInt(), any());
        }
    }
    @Test void bundlesCannotBypassTheDrawersItemTypeOrCapacity() {
        for (final boolean mixed : List.of(false, true)) {
            try (Fixture f = new Fixture(1000, 64, 10000)) {
                final ItemStack bundle = f.item("bundle", 1, 1);
                final BundleMeta meta = mock(BundleMeta.class);
                when(bundle.getItemMeta()).thenReturn(meta);
                final var contents = List.of(f.item("stone", 8, 64),
                        f.item(mixed ? "dirt" : "stone", 4, 64));
                when(meta.getItems()).thenReturn(contents);
                f.cursor.set(bundle);
                final var event = f.click(0, InventoryAction.PLACE_FROM_BUNDLE);
                f.menu.click(event);
                if (mixed) verify(event).setCancelled(true); else verify(event, never()).setCancelled(true);
                assertEquals(1000, f.total());
            }
        }
        try (Fixture f = new Fixture(1000, 64, 1005)) {
            final ItemStack bundle = f.item("bundle", 1, 1);
            final BundleMeta meta = mock(BundleMeta.class);
            when(bundle.getItemMeta()).thenReturn(meta);
            final var contents = List.of(f.item("stone", 8, 64));
            when(meta.getItems()).thenReturn(contents);
            f.cursor.set(bundle);
            final var event = f.click(0, InventoryAction.PLACE_FROM_BUNDLE);
            f.menu.click(event); verify(event).setCancelled(true);
            assertSame(bundle, f.cursor.get()); assertEquals(1000, f.total());
        }
    }
    @Test void storedBundleContentsMustBeChangedAfterTakingTheBundleOut() {
        try (Fixture f = new Fixture(1000, 64, 10000)) {
            for (final var action : List.of(InventoryAction.PICKUP_FROM_BUNDLE,
                    InventoryAction.PLACE_ALL_INTO_BUNDLE, InventoryAction.PLACE_SOME_INTO_BUNDLE)) {
                final var event = f.click(1, action);
                f.menu.click(event); verify(event).setCancelled(true);
            }
        }
    }
}
