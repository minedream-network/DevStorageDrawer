package com.teenkung.devstoragedrawer.interaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.domain.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class DrawerPagedInventoryTest {
    private DrawerChestInventoryTest.Fixture open(long total, int mirror, long capacity) {
        final var f = new DrawerChestInventoryTest.Fixture(total, mirror, capacity);
        f.menu.open(f.player, f.barrel);
        when(f.view.countSlots()).thenReturn(90);
        when(f.view.getItem(anyInt())).thenAnswer(call -> {
            final int raw = call.getArgument(0);
            return raw < 54 ? f.pageSlots[raw] : f.playerSlots[raw - 54];
        });
        doAnswer(call -> {
            final int raw = call.getArgument(0);
            if (raw < 54) f.pageSlots[raw] = call.getArgument(1);
            else f.playerSlots[raw - 54] = call.getArgument(1);
            return null;
        }).when(f.view).setItem(anyInt(), any());
        return f;
    }

    private InventoryClickEvent click(DrawerChestInventoryTest.Fixture f, int slot, InventoryAction action) {
        final InventoryClickEvent event = mock(InventoryClickEvent.class);
        final var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(event.isCancelled()).thenAnswer(call -> cancelled.get());
        doAnswer(call -> { cancelled.set(call.getArgument(0)); return null; }).when(event).setCancelled(anyBoolean());
        when(event.getView()).thenReturn(f.view);
        when(event.getWhoClicked()).thenReturn(f.player);
        when(event.getRawSlot()).thenReturn(slot);
        when(event.getSlot()).thenReturn(slot < 54 ? slot : slot - 54);
        when(event.getAction()).thenReturn(action);
        when(event.getClick()).thenReturn(action == InventoryAction.PICKUP_HALF ? ClickType.RIGHT : ClickType.LEFT);
        when(event.getCurrentItem()).thenAnswer(call -> slot < 54 ? f.pageSlots[slot] : f.playerSlots[slot - 54]);
        when(event.getCursor()).thenAnswer(call -> f.cursor.get());
        when(event.getClickedInventory()).thenReturn(slot < 54 ? f.pageInventory : f.bottom);
        when(event.getHotbarButton()).thenReturn(-1);
        return event;
    }

    private static int count(ItemStack item) { return item == null ? 0 : item.getAmount(); }
    private static ItemStack stack(ItemStack item, int amount) {
        if (amount == 0) return null;
        final ItemStack copy = item.clone(); copy.setAmount(amount); return copy;
    }
    private static int add(ItemStack[] inventory, ItemStack item, int wanted) {
        int left = wanted;
        for (int pass = 0; pass < 2; pass++) for (int slot = 0; slot < inventory.length && left > 0; slot++) {
            final ItemStack existing = inventory[slot];
            if ((existing != null) != (pass == 0) || existing != null && !existing.isSimilar(item)) continue;
            final int moved = Math.min(left, item.getMaxStackSize() - count(existing));
            inventory[slot] = stack(item, count(existing) + moved); left -= moved;
        }
        return wanted - left;
    }

    /** Emulate only vanilla's slot/cursor phase, which happens AFTER the Bukkit event returns. */
    private void handle(DrawerChestInventoryTest.Fixture f, InventoryClickEvent event) {
        f.menu.click(event);
        f.menu.observed(event);
        if (!event.isCancelled()) {
            final int raw = event.getRawSlot();
            final ItemStack shown = raw < 45 ? f.pageSlots[raw] : null;
            final ItemStack cursor = f.cursor.get();
            switch (event.getAction()) {
                case PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE -> {
                    final int wanted = event.getAction() == InventoryAction.PICKUP_HALF ? (count(shown) + 1) / 2
                            : event.getAction() == InventoryAction.PICKUP_ONE ? 1 : count(shown);
                    final int moved = Math.min(wanted, shown.getMaxStackSize() - count(cursor));
                    f.pageSlots[raw] = stack(shown, count(shown) - moved);
                    f.cursor.set(stack(shown, count(cursor) + moved));
                }
                case PLACE_ALL, PLACE_SOME, PLACE_ONE -> {
                    final int wanted = event.getAction() == InventoryAction.PLACE_ONE ? 1 : count(cursor);
                    final int moved = Math.min(wanted, cursor.getMaxStackSize() - count(shown));
                    f.pageSlots[raw] = stack(cursor, count(shown) + moved);
                    f.cursor.set(stack(cursor, count(cursor) - moved));
                }
                case MOVE_TO_OTHER_INVENTORY -> {
                    if (raw < 45) {
                        final int moved = add(f.playerSlots, shown, count(shown));
                        f.pageSlots[raw] = stack(shown, count(shown) - moved);
                    } else {
                        final ItemStack source = f.playerSlots[raw - 54];
                        final ItemStack[] page = Arrays.copyOf(f.pageSlots, 45);
                        final int moved = add(page, source, count(source));
                        System.arraycopy(page, 0, f.pageSlots, 0, 45);
                        f.playerSlots[raw - 54] = stack(source, count(source) - moved);
                    }
                }
                case HOTBAR_SWAP -> {
                    final int slot = event.getHotbarButton();
                    f.pageSlots[raw] = f.playerSlots[slot];
                    f.playerSlots[slot] = shown;
                }
                case COLLECT_TO_CURSOR -> {
                    int total = count(cursor);
                    for (int pass = 0; pass < 2 && total < cursor.getMaxStackSize(); pass++) {
                        for (int slot = 0; slot < 81 && total < cursor.getMaxStackSize(); slot++) {
                            final ItemStack candidate = slot < 45 ? f.pageSlots[slot] : f.playerSlots[slot - 45];
                            if (candidate == null || !candidate.isSimilar(cursor)
                                    || pass == 0 && count(candidate) == candidate.getMaxStackSize()) continue;
                            final int moved = Math.min(cursor.getMaxStackSize() - total, count(candidate));
                            if (slot < 45) f.pageSlots[slot] = stack(candidate, count(candidate) - moved);
                            else f.playerSlots[slot - 45] = stack(candidate, count(candidate) - moved);
                            total += moved;
                        }
                    }
                    f.cursor.set(stack(cursor, total));
                }
                default -> { }
            }
        }
        f.tick();
    }

    private void handleDrag(DrawerChestInventoryTest.Fixture f, InventoryDragEvent event) {
        final var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(event.isCancelled()).thenAnswer(call -> cancelled.get());
        doAnswer(call -> { cancelled.set(call.getArgument(0)); return null; }).when(event).setCancelled(anyBoolean());
        final ItemStack old = event.getOldCursor();
        when(event.getOldCursor()).thenReturn(old);
        // Paper has already installed the new carried item at the time it calls listeners.
        f.cursor.set(event.getCursor());
        f.menu.drag(event);
        f.menu.observed(event);
        if (!event.isCancelled()) event.getNewItems().forEach((raw, item) -> f.view.setItem(raw, item.clone()));
        else f.cursor.set(old);
        f.tick();
    }

    @Test void pagesCoverFullLogicalStockWithoutAllocatingOtherPages() {
        try (var f = open(100000, 64, 200000)) {
            assertEquals(35, f.page.get().pages);
            assertEquals(54, f.pageSlots.length);
            assertEquals(2880, Arrays.stream(f.pageSlots).limit(45).mapToInt(ItemStack::getAmount).sum());
            assertEquals(64, f.mirror());
            handle(f, click(f, 53, InventoryAction.PICKUP_ALL));
            assertEquals(1, f.page.get().page);
            verify(f.player, times(1)).openInventory(any(org.bukkit.inventory.Inventory.class));
        }
    }

    @Test void cursorPickupDebitsStockAndKeepsTheHoleAndNeighbourSlotsStable() {
        try (var f = open(1000, 64, 10000)) {
            final ItemStack neighbour = f.pageSlots[1];
            final var event = click(f, 0, InventoryAction.PICKUP_ALL);
            handle(f, event);
            verify(event, never()).setCancelled(true);
            assertEquals(64, f.cursor.get().getAmount());
            assertEquals(936, f.total());
            assertEquals(64, f.mirror());
            assertNull(f.pageSlots[0]);
            assertSame(neighbour, f.pageSlots[1]);
            assertFalse(f.state.get().hasPendingProxyJournal());
            assertEquals("stone", f.identities.get(f.cursor.get()));
        }
    }

    @Test void rightClickSplitsToTheRealCursor() {
        try (var f = open(1000, 64, 10000)) {
            handle(f, click(f, 0, InventoryAction.PICKUP_HALF));
            assertEquals(32, f.cursor.get().getAmount());
            assertEquals(32, f.pageSlots[0].getAmount());
            assertEquals(968, f.total());
        }
    }

    @Test void stalePreviewCannotWithdrawStockAlreadyTakenByAnotherViewerOrSellTool() {
        try (var f = open(1000, 64, 10000)) {
            f.slots[1].setAmount(20);
            f.state.set(f.state.get().withStoredTotalAndExpectedMirrorCount(20, 20));
            handle(f, click(f, 0, InventoryAction.PICKUP_ALL));
            assertEquals(20, f.cursor.get().getAmount());
            assertEquals(0, f.total());
            assertEquals(0, f.mirror());
            assertFalse(f.state.get().hasTemplate());
        }
    }

    @Test void arrivalsThroughHopperAreIncludedWithoutWaitingForMirrorRebalance() {
        try (var f = open(1000, 64, 10000)) {
            f.slots[2] = f.item("stone", 4, 64);
            handle(f, click(f, 0, InventoryAction.PICKUP_ALL));
            assertEquals(940, f.total());
            assertEquals(68, f.mirror());
            assertEquals(64, f.cursor.get().getAmount());
        }
    }

    @Test void doubleClickCannotCollectAStaleProjectionAfterAnotherViewerEmptiesTheDrawer() {
        try (var f = open(64, 64, 10000)) {
            Arrays.fill(f.slots, null);
            f.state.set(f.state.get().withStoredTotalAndExpectedMirrorCount(0, 0).withoutTemplate());
            f.cursor.set(f.item("stone", 1, 64));
            f.playerSlots[0] = f.item("stone", 5, 64);
            final var event = click(f, 54, InventoryAction.COLLECT_TO_CURSOR);
            handle(f, event);
            assertTrue(event.isCancelled(), "Stale projected stock must never enter vanilla collection");
            assertEquals(6, f.cursor.get().getAmount(), "Only real player inventory items may be collected");
            assertNull(f.playerSlots[0]);
            assertEquals(0, f.total());
            assertTrue(Arrays.stream(f.pageSlots).limit(45).allMatch(Objects::isNull));
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }

    @Test void doubleClickCannotCollectTheOldItemAfterAnotherViewerChangesTheDrawerIdentity() {
        try (var f = open(64, 64, 10000)) {
            Arrays.fill(f.slots, null);
            f.slots[1] = f.item("dirt", 32, 64);
            f.state.set(f.state.get().withTemplate(f.slots[1]).withStoredTotalAndExpectedMirrorCount(32, 32));
            f.cursor.set(f.item("stone", 1, 64));
            f.playerSlots[0] = f.item("stone", 5, 64);
            final var event = click(f, 54, InventoryAction.COLLECT_TO_CURSOR);
            handle(f, event);
            assertTrue(event.isCancelled());
            assertEquals(6, f.cursor.get().getAmount());
            assertEquals(32, f.total());
            assertEquals("dirt", f.identities.get(f.pageSlots[0]));
            assertEquals(32, f.pageSlots[0].getAmount());
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }

    @Test void doubleClickOnAStaleCountCanCollectOnlyTheRemainingLogicalStock() {
        try (var f = open(64, 64, 10000)) {
            f.slots[1].setAmount(20);
            f.state.set(f.state.get().withStoredTotalAndExpectedMirrorCount(20, 20));
            f.cursor.set(f.item("stone", 1, 64));
            final var event = click(f, 54, InventoryAction.COLLECT_TO_CURSOR);
            handle(f, event);
            assertTrue(event.isCancelled());
            assertEquals(21, f.cursor.get().getAmount());
            assertEquals(0, f.total());
            assertEquals(0, f.mirror());
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }

    @Test void cursorDepositFitsLogicalCapacityAndLeavesRemainder() {
        try (var f = open(1000, 64, 1010)) {
            f.cursor.set(f.item("stone", 32, 64));
            handle(f, click(f, 15, InventoryAction.PLACE_ALL));
            assertEquals(1010, f.total());
            assertEquals(22, f.cursor.get().getAmount());
            assertEquals(50, f.pageSlots[15].getAmount());
            assertEquals(64, f.mirror());
        }
    }

    @Test void shiftingIntoAFullPageBehavesLikeAFullChestWithoutConsumingTheSource() {
        try (var f = open(100000, 64, 200000)) {
            f.playerSlots[0] = f.item("stone", 64, 64);
            handle(f, click(f, 54, InventoryAction.MOVE_TO_OTHER_INVENTORY));
            assertEquals(64, f.playerSlots[0].getAmount());
            assertEquals(100000, f.total());
        }
    }

    @Test void fullLastPageProvidesAnEmptyNextPageForCursorDepositsWhenCapacityRemains() {
        try (var f = open(2880, 64, 10000)) {
            assertEquals(2, f.page.get().pages);
            handle(f, click(f, 53, InventoryAction.PICKUP_ALL));
            assertEquals(1, f.page.get().page);
            assertTrue(Arrays.stream(f.pageSlots).limit(45).allMatch(Objects::isNull));
            f.cursor.set(f.item("stone", 64, 64));
            handle(f, click(f, 0, InventoryAction.PLACE_ALL));
            assertNull(f.cursor.get());
            assertEquals(2944, f.total());
            assertEquals(64, f.pageSlots[0].getAmount());
            assertEquals(1, DrawerPageHolder.pages(2880, 2880, 64));
        }
    }

    @Test void shiftWithdrawalTakesOnlyTheAmountThatFitsThePlayerInventory() {
        try (var f = open(1000, 64, 10000)) {
            for (int i = 0; i < 36; i++) f.playerSlots[i] = f.item("other", 64, 64);
            f.playerSlots[0] = f.item("stone", 60, 64);
            handle(f, click(f, 0, InventoryAction.MOVE_TO_OTHER_INVENTORY));
            assertEquals(64, f.playerSlots[0].getAmount());
            assertEquals(996, f.total());
            assertEquals(60, f.pageSlots[0].getAmount());
        }
    }

    @Test void wrongMetadataItemAndNavigationIconsCannotEnterOrLeaveStorage() {
        try (var f = open(1000, 64, 10000)) {
            final ItemStack wrong = f.item("different-pdc", 10, 64);
            f.cursor.set(wrong);
            handle(f, click(f, 16, InventoryAction.PLACE_ALL));
            assertSame(wrong, f.cursor.get());
            f.cursor.set(null);
            handle(f, click(f, 49, InventoryAction.MOVE_TO_OTHER_INVENTORY));
            assertNull(f.cursor.get());
            assertEquals(1000, f.total());
            assertTrue(Arrays.stream(f.playerSlots).allMatch(Objects::isNull));
        }
    }

    @Test void saveFailureDoesNotGrantPreviewOrConsumeCursorDeposit() {
        try (var f = open(1000, 64, 10000)) {
            f.failSave = true;
            handle(f, click(f, 0, InventoryAction.PICKUP_ALL));
            assertNull(f.cursor.get());
            final ItemStack source = f.item("stone", 32, 64);
            f.cursor.set(source);
            handle(f, click(f, 16, InventoryAction.PLACE_ALL));
            assertEquals(32, f.cursor.get().getAmount());
            assertEquals(1000, f.total());
        }
    }

    @Test void dragCommitsDrawerAndBottomSlotsAndCursorExactlyOnce() {
        try (var f = open(1000, 64, 10000)) {
            f.cursor.set(f.item("stone", 32, 64));
            final ItemStack left = f.item("stone", 2, 64);
            final Map<Integer, ItemStack> replacements = Map.of(16, f.item("stone", 10, 64),
                    17, f.item("stone", 10, 64), 54, f.item("stone", 10, 64));
            final var event = f.drag(replacements);
            when(event.getCursor()).thenReturn(left);
            handleDrag(f, event);
            verify(event, never()).setCancelled(true);
            assertEquals(1020, f.total());
            assertEquals(2, f.cursor.get().getAmount());
            assertEquals(10, f.playerSlots[0].getAmount());
            assertEquals(10, f.pageSlots[16].getAmount());
            assertEquals(10, f.pageSlots[17].getAmount());
        }
    }

    @Test void dragAcrossFooterIsRejectedWithoutTouchingRealItems() {
        try (var f = open(1000, 64, 10000)) {
            final ItemStack cursor = f.item("stone", 10, 64);
            f.cursor.set(cursor);
            final var event = f.drag(Map.of(48, f.item("stone", 5, 64)));
            handleDrag(f, event);
            assertSame(cursor, f.cursor.get());
            assertEquals(1000, f.total());
        }
    }

    @Test void shrinkingStockClampsPageInTheExistingWindow() {
        try (var f = open(100000, 64, 200000)) {
            handle(f, click(f, 53, InventoryAction.PICKUP_ALL));
            assertEquals(1, f.page.get().page);
            f.state.set(f.state.get().withStoredTotalAndExpectedMirrorCount(64, 64));
            handle(f, click(f, 53, InventoryAction.PICKUP_ALL));
            assertEquals(0, f.page.get().page);
            assertEquals(1, f.page.get().pages);
            verify(f.player, times(1)).openInventory(any(org.bukkit.inventory.Inventory.class));
        }
    }

    @Test void closedOrForeignRegionViewsCannotChangeStock() {
        try (var f = open(1000, 64, 10000)) {
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(org.bukkit.Location.class))).thenReturn(false);
            clearInvocations(f.repository);
            handle(f, click(f, 0, InventoryAction.PICKUP_ALL));
            verifyNoInteractions(f.repository);
            assertNull(f.cursor.get());
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(org.bukkit.Location.class))).thenReturn(true);
            final var close = mock(InventoryCloseEvent.class);
            when(close.getInventory()).thenReturn(f.pageInventory);
            f.menu.close(close);
            handle(f, click(f, 0, InventoryAction.PICKUP_ALL));
            assertEquals(1000, f.total());
            assertNull(f.cursor.get());
        }
    }

    @Test void paginationArithmeticHandlesLongCountsAndNativeStackSizes() {
        assertEquals(1, DrawerPageHolder.pages(0, 64));
        assertEquals(1, DrawerPageHolder.pages(2880, 64));
        assertEquals(2, DrawerPageHolder.pages(2881, 64));
        assertEquals(2, DrawerPageHolder.pages(721, 16));
        assertEquals(2, DrawerPageHolder.pages(46, 1));
        final long pages = DrawerPageHolder.pages(Long.MAX_VALUE, 64);
        assertTrue(pages > Integer.MAX_VALUE);
        final int last = DrawerPageHolder.pageAmount(Long.MAX_VALUE, pages - 1, 64);
        assertTrue(last > 0 && last <= 2880);
    }

    @Test void firstDepositUsesTheItemsNativeStackSizeAndInitializesTemplate() {
        try (var f = open(0, 0, 640)) {
            final var definition = new com.teenkung.devstoragedrawer.config.DrawerTierDefinition(
                    new DrawerTier("tier", "Tier", 10), net.kyori.adventure.text.Component.text("Tier"), List.of(), null);
            when(f.context.tier("tier")).thenReturn(Optional.of(definition));
            f.cursor.set(f.item("pearl", 12, 16));
            handle(f, click(f, 0, InventoryAction.PLACE_ALL));
            assertEquals(12, f.total());
            assertEquals(160, f.state.get().capacitySnapshot());
            assertEquals("pearl", f.identities.get(f.state.get().requireTemplate()));
            assertNull(f.cursor.get());
            assertEquals(12, f.pageSlots[0].getAmount());
        }
    }

    @Test void reentrantPickupBeforeVanillaRunsCannotFinalizeOrSpendTheFirstIntent() {
        try (var f = open(1000, 64, 10000)) {
            final var first = click(f, 0, InventoryAction.PICKUP_ALL);
            f.menu.click(first);
            assertFalse(first.isCancelled());
            final var reentrant = click(f, 0, InventoryAction.PICKUP_ALL);
            f.menu.click(reentrant);
            assertTrue(reentrant.isCancelled());
            assertNull(f.cursor.get());
            assertEquals(1000, f.total());
            f.menu.observed(first);
            f.pageSlots[0] = null;
            f.cursor.set(f.item("stone", 64, 64));
            f.tick();
            assertEquals(64, f.cursor.get().getAmount());
            assertEquals(936, f.total());
        }
    }

    @Test void failedFinalSaveRetainsDeliveryEvidenceAndBlocksFurtherPickupAndRecovery() {
        try (var f = open(1000, 64, 10000)) {
            final var writes = new java.util.concurrent.atomic.AtomicInteger();
            when(f.repository.save(eq(f.barrel), any())).thenAnswer(call -> {
                if (writes.incrementAndGet() == 3) return false;
                f.state.set(call.getArgument(1)); return true;
            });
            handle(f, click(f, 0, InventoryAction.PICKUP_HALF));
            assertEquals(32, f.cursor.get().getAmount());
            assertEquals(968, f.total());
            assertEquals(DrawerJournalKind.GUARDED_PLAYER_TRANSFER, f.state.get().proxyJournal().orElseThrow().kind());
            handle(f, click(f, 1, InventoryAction.MOVE_TO_OTHER_INVENTORY));
            assertTrue(Arrays.stream(f.playerSlots).allMatch(Objects::isNull));
            final var reconciliation = f.storage.reconcile(f.state.get(), f.mirror());
            assertTrue(reconciliation.requiresManualRecovery());
            assertEquals(DrawerJournalReconciliation.Resolution.PLAYER_TRANSFER_UNRESOLVED, reconciliation.resolution());
            final var bridge = new com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge(f.context,
                    mock(com.teenkung.devstoragedrawer.receipt.DrawerWithdrawalCoordinator.class));
            for (var type : HopperInventorySearchEvent.ContainerType.values()) {
                final var search = mock(HopperInventorySearchEvent.class);
                when(search.getSearchBlock()).thenReturn(f.block);
                when(search.getContainerType()).thenReturn(type);
                bridge.onHopperExternalSaleGuard(search);
                verify(search).setInventory(null);
            }
        }
    }

    @Test void failedAppliedSaveAfterNativeDeliveryQuarantinesTheDebitWithoutRestoringSpentStock() {
        try (var f = open(20, 20, 10000)) {
            final var writes = new java.util.concurrent.atomic.AtomicInteger();
            when(f.repository.save(eq(f.barrel), any())).thenAnswer(call -> {
                if (writes.incrementAndGet() == 2) return false;
                f.state.set(call.getArgument(1)); return true;
            });
            handle(f, click(f, 0, InventoryAction.PICKUP_ALL));
            assertEquals(20, f.cursor.get().getAmount());
            assertEquals(0, f.mirror());
            assertTrue(f.state.get().hasPendingProxyJournal());
            assertTrue(f.storage.reconcile(f.state.get(), f.mirror()).requiresManualRecovery());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void backgroundRefreshChangesOnlyAffectedSlotsAndNeverReopensTheWindow() {
        try (var f = open(1000, 64, 10000)) {
            final var delayed = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
            verify(f.execution).runLaterOnEntity(eq(f.player), eq(4L), delayed.capture(), any());
            doAnswer(call -> {
                final java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task = call.getArgument(1);
                task.accept(null); return null;
            }).when(f.execution).runOnEntity(eq(f.player), any(), any());
            clearInvocations(f.pageInventory);
            f.state.set(f.state.get().withStoredTotalAndExpectedMirrorCount(1005, 64));
            delayed.getValue().accept(null);
            // New stock fills a free tail slot, leaving all existing full stacks untouched.
            verify(f.pageInventory, never()).setItem(eq(0), any());
            verify(f.pageInventory, never()).clear();
            assertEquals(1005, Arrays.stream(f.pageSlots).limit(45).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum());
            verify(f.player, times(1)).openInventory(any(org.bukkit.inventory.Inventory.class));
        }
    }

    @Test void disablingClearsProjectedItemsBeforeListenersStopProtectingTheView() {
        try (var f = open(1000, 64, 10000)) {
            f.menu.disable();
            assertTrue(Arrays.stream(f.pageSlots).allMatch(Objects::isNull));
            assertEquals(1000, f.total());
            assertEquals(64, f.mirror());
            handle(f, click(f, 0, InventoryAction.PICKUP_ALL));
            assertNull(f.cursor.get());
        }
    }

    @Test void cancelledSlotDropRestoresStockAndSuccessfulDropDebitsExactlyOnce() {
        try (var f = open(1000, 64, 10000)) {
            final var rejected = mock(org.bukkit.entity.Item.class);
            when(f.world.dropItem(any(), any(ItemStack.class))).thenReturn(rejected);
            handle(f, click(f, 0, InventoryAction.DROP_ALL_SLOT));
            assertEquals(1000, f.total());
            assertFalse(f.state.get().hasPendingProxyJournal());
            final var spawned = mock(org.bukkit.entity.Item.class);
            when(spawned.isValid()).thenReturn(true);
            final ItemStack dropped = f.item("stone", 1, 64);
            when(spawned.getItemStack()).thenReturn(dropped);
            when(f.world.dropItem(any(), any(ItemStack.class))).thenReturn(spawned);
            handle(f, click(f, 0, InventoryAction.DROP_ONE_SLOT));
            assertEquals(999, f.total());
            assertFalse(f.state.get().hasPendingProxyJournal());
            verify(spawned, never()).remove();
        }
    }

    @Test void shiftNavigationJumpsToFirstAndLastPages() {
        try (var f = open(100000, 64, 200000)) {
            final var last = click(f, 53, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            when(last.isShiftClick()).thenReturn(true);
            handle(f, last);
            assertEquals(34, f.page.get().page);
            assertEquals(2080, Arrays.stream(f.pageSlots).limit(45).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum());
            final var first = click(f, 45, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            when(first.isShiftClick()).thenReturn(true);
            handle(f, first);
            assertEquals(0, f.page.get().page);
            assertEquals(100000, f.total());
        }
    }

    @Test void hotbarSwapExchangesOnlyTheNetLogicalCount() {
        try (var f = open(1000, 64, 10000)) {
            f.playerSlots[0] = f.item("stone", 10, 64);
            final var event = click(f, 0, InventoryAction.HOTBAR_SWAP);
            when(event.getHotbarButton()).thenReturn(0);
            handle(f, event);
            assertEquals(64, f.playerSlots[0].getAmount());
            assertEquals(10, f.pageSlots[0].getAmount());
            assertEquals(946, f.total());
            assertNull(f.cursor.get());
        }
    }

    @Test void doubleClickUsesVanillasPartialStackOrderAndAccountsOnlyTheNativeDrawerDelta() {
        try (var f = open(1000, 64, 10000)) {
            f.playerSlots[0] = f.item("stone", 60, 64);
            f.cursor.set(f.item("stone", 3, 64));
            handle(f, click(f, 54, InventoryAction.COLLECT_TO_CURSOR));
            assertEquals(64, f.cursor.get().getAmount());
            assertEquals(39, f.playerSlots[0].getAmount());
            assertEquals(960, f.total());
        }
    }

    @Test void nativePlacementDoesNotCancelOrManuallyTouchTheCursorAndChosenSlot() {
        try (var f = open(1000, 64, 10000)) {
            f.cursor.set(f.item("stone", 32, 64));
            clearInvocations(f.player, f.pageInventory);
            final var event = click(f, 20, InventoryAction.PLACE_ALL);
            f.menu.click(event);
            assertFalse(event.isCancelled());
            assertNull(f.pageSlots[20]);
            assertEquals(32, f.cursor.get().getAmount());
            verify(f.player, never()).setItemOnCursor(any());
            verify(f.pageInventory, never()).setItem(anyInt(), any());
            assertTrue(f.state.get().hasPendingProxyJournal());
            // Only vanilla applies these changes, after all event listeners return.
            f.menu.observed(event);
            f.pageSlots[20] = f.item("stone", 32, 64);
            f.cursor.set(null);
            f.tick();
            assertEquals(1032, f.total());
            assertEquals(32, f.pageSlots[20].getAmount());
            assertFalse(f.state.get().hasPendingProxyJournal());
            verify(f.pageInventory, never()).setItem(anyInt(), any());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void placementAtChosenSlotAndFullPageWithdrawalHoleSurviveRefresh() {
        try (var f = open(100000, 64, 200000)) {
            handle(f, click(f, 0, InventoryAction.PICKUP_ALL));
            assertNull(f.pageSlots[0]);
            final var delayed = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
            verify(f.execution).runLaterOnEntity(eq(f.player), eq(4L), delayed.capture(), any());
            clearInvocations(f.pageInventory);
            delayed.getValue().accept(null);
            f.tick();
            assertNull(f.pageSlots[0]);
            verify(f.pageInventory, never()).setItem(eq(0), any());
            assertEquals(99936, f.total());
        }
        try (var f = open(1000, 64, 10000)) {
            f.cursor.set(f.item("stone", 32, 64));
            handle(f, click(f, 20, InventoryAction.PLACE_ALL));
            final ItemStack placed = f.pageSlots[20];
            final var delayed = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
            verify(f.execution).runLaterOnEntity(eq(f.player), eq(4L), delayed.capture(), any());
            clearInvocations(f.pageInventory);
            delayed.getValue().accept(null);
            f.tick();
            assertSame(placed, f.pageSlots[20]);
            verify(f.pageInventory, never()).setItem(anyInt(), any());
        }
    }

    @Test void aLaterListenerCanCancelNativePlacementWithoutConsumingOrCreditingTheSource() {
        try (var f = open(1000, 64, 10000)) {
            f.cursor.set(f.item("stone", 32, 64));
            final var event = click(f, 20, InventoryAction.PLACE_ALL);
            f.menu.click(event);
            assertFalse(event.isCancelled());
            event.setCancelled(true);
            f.menu.observed(event);
            f.tick();
            assertNull(f.pageSlots[20]);
            assertEquals(32, f.cursor.get().getAmount());
            assertEquals(1000, f.total());
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }

    @Test void rapidNativeClicksInTheSameTickSettlePreviousMovementWithoutCancellingTheNext() {
        try (var f = open(1000, 64, 10000)) {
            final var pickup = click(f, 0, InventoryAction.PICKUP_ALL);
            f.menu.click(pickup);
            f.menu.observed(pickup);
            f.pageSlots[0] = null;
            f.cursor.set(f.item("stone", 64, 64));
            final var place = click(f, 20, InventoryAction.PLACE_ALL);
            f.menu.click(place);
            assertFalse(place.isCancelled());
            assertEquals(936, f.total());
            f.menu.observed(place);
            f.pageSlots[20] = f.cursor.get();
            f.cursor.set(null);
            f.tick();
            assertEquals(1000, f.total());
            assertEquals(64, f.pageSlots[20].getAmount());
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }

    @Test void clickingADisabledPageArrowDoesNotPackOrMovePlacedItems() {
        try (var f = open(1000, 64, 10000)) {
            handle(f, click(f, 0, InventoryAction.PICKUP_ALL));
            handle(f, click(f, 20, InventoryAction.PLACE_ALL));
            assertNull(f.pageSlots[0]);
            final ItemStack placed = f.pageSlots[20];
            handle(f, click(f, 45, InventoryAction.PICKUP_ALL));
            assertNull(f.pageSlots[0]);
            assertSame(placed, f.pageSlots[20]);
        }
    }

    @Test void laterCancelledDragRestoresVanillasCursorWithoutASecondManualDeposit() {
        try (var f = open(1000, 64, 10000)) {
            final ItemStack old = f.item("stone", 32, 64);
            f.cursor.set(old);
            final var event = f.drag(Map.of(20, f.item("stone", 16, 64)));
            when(event.getOldCursor()).thenReturn(old);
            final ItemStack remainder = f.item("stone", 16, 64);
            when(event.getCursor()).thenReturn(remainder);
            f.cursor.set(remainder); // Paper changes the carried item before firing a drag event.
            f.menu.drag(event);
            verify(event, never()).setCancelled(true);
            assertNull(f.pageSlots[20]);
            event.setCancelled(true);
            f.menu.observed(event);
            f.cursor.set(old); // Vanilla's DENY branch restores its original carried item.
            f.tick();
            assertEquals(32, f.cursor.get().getAmount());
            assertEquals(1000, f.total());
            assertNull(f.pageSlots[20]);
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }

    @Test void failedNativeValidationCancelsPlacementBeforeVanillaCanConsumeTheCursor() {
        try (var f = open(0, 0, 10000)) {
            when(f.context.tier("tier")).thenThrow(new IllegalArgumentException("Invalid first-item capacity"));
            f.cursor.set(f.item("stone", 32, 64));
            final var event = click(f, 20, InventoryAction.PLACE_ALL);
            handle(f, event);
            assertTrue(event.isCancelled());
            assertEquals(32, f.cursor.get().getAmount());
            assertNull(f.pageSlots[20]);
            assertEquals(0, f.total());
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }

    @Test void failedNativeDragValidationLetsVanillaRestoreTheOldCarriedItem() {
        try (var f = open(0, 0, 10000)) {
            when(f.context.tier("tier")).thenThrow(new IllegalArgumentException("Invalid first-item capacity"));
            f.cursor.set(f.item("stone", 32, 64));
            final var event = f.drag(Map.of(20, f.item("stone", 16, 64)));
            final ItemStack remaining = f.item("stone", 16, 64);
            when(event.getCursor()).thenReturn(remaining);
            handleDrag(f, event);
            assertTrue(event.isCancelled());
            assertEquals(32, f.cursor.get().getAmount());
            assertNull(f.pageSlots[20]);
            assertEquals(0, f.total());
        }
    }

    @Test void hopperRepairWaitsQuietlyForLiveNativeMovementAndReportsAnAbandonedIntent() {
        try (var f = open(1000, 64, 10000)) {
            final var logger = mock(java.util.logging.Logger.class);
            when(f.context.logger()).thenReturn(logger);
            f.menu.click(click(f, 0, InventoryAction.PICKUP_ALL));
            final var operation = f.state.get().proxyJournal().orElseThrow().operationId();
            final var bridge = new com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge(f.context,
                    mock(com.teenkung.devstoragedrawer.receipt.DrawerWithdrawalCoordinator.class));
            bridge.beginNativePlayerTransfer(operation);
            bridge.recoverAndRebalance(f.barrel);
            verify(logger, never()).warning(anyString());
            assertTrue(f.state.get().hasPendingProxyJournal());
            assertEquals(1000, f.total());
            bridge.endNativePlayerTransfer(operation);
            bridge.recoverAndRebalance(f.barrel);
            verify(logger).warning(contains("requires reconciliation"));
            assertEquals(1000, f.total());
            bridge.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    @Test void fullPageFooterRefreshAllocatesNoNewStockItemsAndAChangedSlotAllocatesOnlyOne() {
        try (var f = open(100000, 64, 200000)) {
            final var delayed = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
            verify(f.execution).runLaterOnEntity(eq(f.player), eq(4L), delayed.capture(), any());
            f.state.set(f.state.get().withStoredTotal(100005));
            final long before = f.identities.values().stream().filter("stone"::equals).count();
            delayed.getValue().accept(null);
            assertEquals(before, f.identities.values().stream().filter("stone"::equals).count());
            verify(f.execution, never()).runOnEntity(eq(f.player), any(), any());
        }
        try (var f = open(1000, 64, 10000)) {
            final var delayed = org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
            verify(f.execution).runLaterOnEntity(eq(f.player), eq(4L), delayed.capture(), any());
            f.state.set(f.state.get().withStoredTotal(1005));
            final long before = f.identities.values().stream().filter("stone"::equals).count();
            delayed.getValue().accept(null);
            assertEquals(before + 1, f.identities.values().stream().filter("stone"::equals).count());
            assertEquals(1005, Arrays.stream(f.pageSlots).limit(45).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum());
        }
    }

    @Test void oneCompletionTaskAccountsABurstOfNativeMovesWithoutDroppingAnyIntent() {
        try (var f = open(1000, 64, 10000)) {
            final var pickup = click(f, 0, InventoryAction.PICKUP_ALL);
            f.menu.click(pickup); f.menu.observed(pickup);
            f.pageSlots[0] = null; f.cursor.set(f.item("stone", 64, 64));
            for (int slot = 20; slot < 30; slot++) {
                final var place = click(f, slot, InventoryAction.PLACE_ONE);
                f.menu.click(place); f.menu.observed(place);
                assertFalse(place.isCancelled());
                f.pageSlots[slot] = f.item("stone", 1, 64);
                f.cursor.set(stack(f.cursor.get(), f.cursor.get().getAmount() - 1));
            }
            verify(f.execution, times(1)).runOnEntity(eq(f.player), any(), any());
            f.tick();
            assertEquals(946, f.total());
            assertEquals(54, f.cursor.get().getAmount());
            assertFalse(f.state.get().hasPendingProxyJournal());
            for (int slot = 20; slot < 30; slot++) assertEquals(1, f.pageSlots[slot].getAmount());
        }
    }
}
