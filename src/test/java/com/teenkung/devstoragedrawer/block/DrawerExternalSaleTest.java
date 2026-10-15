package com.teenkung.devstoragedrawer.block;

import com.teenkung.devstoragedrawer.api.DrawerSaleApi;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import com.teenkung.devstoragedrawer.domain.*;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.interaction.DrawerWithdrawalProtection;
import com.teenkung.devstoragedrawer.persistence.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import com.teenkung.devstoragedrawer.receipt.DrawerWithdrawalCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DrawerExternalSaleTest {
    private static ItemStack item(int initial) {
        ItemStack item = mock(ItemStack.class);
        AtomicInteger amount = new AtomicInteger(initial);
        // Paper 26 Material predicates need a server registry; mock that boundary only.
        when(item.getType()).thenReturn(mock(Material.class));
        when(item.getAmount()).thenAnswer(call -> amount.get());
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.isEmpty()).thenAnswer(call -> amount.get() == 0);
        when(item.isSimilar(any())).thenReturn(true);
        when(item.clone()).thenAnswer(call -> item(amount.get()));
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        return item;
    }

    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final Location location = mock(Location.class);
        final Block block = mock(Block.class);
        final Barrel barrel = mock(Barrel.class);
        final Player player = mock(Player.class);
        final DrawerRuntimeContext context = mock(DrawerRuntimeContext.class);
        final DrawerStateRepository repository = mock(DrawerStateRepository.class);
        final DrawerBlockAccess blocks = mock(DrawerBlockAccess.class);
        final DrawerHopperBridge hoppers = mock(DrawerHopperBridge.class);
        final SingleItemDrawerStorage storage = new SingleItemDrawerStorage();
        final AtomicReference<DrawerState> state = new AtomicReference<>();
        final DrawerWithdrawalProtection protection = mock(DrawerWithdrawalProtection.class);
        final ItemStack[] physical = new ItemStack[27];
        final DrawerSaleService api;

        Fixture(long total, int mirror) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(org.bukkit.entity.Entity.class))).thenReturn(true);
            when(location.getWorld()).thenReturn(mock(World.class));
            when(location.getBlock()).thenReturn(block);
            when(block.getLocation()).thenReturn(location);
            when(barrel.getLocation()).thenReturn(location);
            when(barrel.getBlock()).thenReturn(block);
            when(context.blocks()).thenReturn(blocks);
            when(context.repository()).thenReturn(repository);
            when(context.storage()).thenReturn(storage);
            when(context.logger()).thenReturn(Logger.getLogger("drawer-sale-test"));
            DrawerSettings settings = mock(DrawerSettings.class);
            when(settings.interaction()).thenReturn(new DrawerSettings.Interaction(1, true));
            DrawerSettings.Automation automation = mock(DrawerSettings.Automation.class);
            when(automation.outputProxySlots()).thenReturn(26);
            when(settings.automation()).thenReturn(automation);
            when(context.settings()).thenReturn(settings);
            when(blocks.barrel(block)).thenReturn(Optional.of(barrel));
            when(blocks.isDrawer(block)).thenReturn(true);
            when(repository.isDrawer(barrel)).thenReturn(true);
            when(player.hasPermission("devstoragedrawer.use")).thenReturn(true);
            when(player.isOnline()).thenReturn(true);
            when(protection.canWithdraw(player, barrel)).thenReturn(true);
            Directional direction = mock(Directional.class);
            when(direction.getFacing()).thenReturn(BlockFace.NORTH);
            when(barrel.getBlockData()).thenReturn(direction);
            state.set(DrawerState.restored(2, "tier", item(1), total, mirror, Long.MAX_VALUE, null, DrawerDisplayLink.none()));
            if (mirror > 0) physical[1] = item(mirror);
            Inventory inventory = mock(Inventory.class);
            when(barrel.getInventory()).thenReturn(inventory);
            when(inventory.getSize()).thenReturn(27);
            when(inventory.getContents()).thenAnswer(call -> physical.clone());
            when(inventory.getItem(anyInt())).thenAnswer(call -> physical[(int)call.getArgument(0)]);
            doAnswer(call -> { physical[(int)call.getArgument(0)] = call.getArgument(1); return null; }).when(inventory).setItem(anyInt(), any());
            doAnswer(call -> { java.util.Arrays.fill(physical, null); return null; }).when(inventory).clear();
            when(blocks.inspect(eq(barrel), any())).thenAnswer(call -> new DrawerBlockAccess.PhysicalStock(mirror(), true));
            when(repository.read(barrel)).thenAnswer(call -> new DrawerStateReadResult.Valid(state.get()));
            when(repository.save(eq(barrel), any())).thenAnswer(call -> { state.set(call.getArgument(1)); return true; });
            api = new DrawerSaleService(context, new DrawerSellWandBridge(context, hoppers), protection);
        }
        long mirror() { return java.util.Arrays.stream(physical).filter(java.util.Objects::nonNull).mapToLong(ItemStack::getAmount).sum(); }
        long total() { return state.get().totalForPhysical(mirror()); }
        DrawerSaleApi.Probe probe() { return api.probeSaleTarget(player, block, BlockFace.NORTH); }
        @Override public void close() { bukkit.close(); }
    }

    @Test void sellsLongHiddenStockWithOneTemplateAndBoundedCount() {
        try (Fixture f = new Fixture(5_000_000_000L, 64)) {
            DrawerSaleApi.SaleResult result = f.api.withExternalSale(f.location, f.probe(), 1_000_000L, UUID.randomUUID(), (template, count) -> {
                assertEquals(1, template.getAmount());
                assertEquals(1_000_000L, count);
                assertTrue(f.state.get().hasPendingProxyJournal());
                return DrawerSaleApi.PaymentOutcome.PAID;
            });
            assertEquals(DrawerSaleApi.SaleResult.SOLD, result);
            assertEquals(4_999_000_000L, f.total());
            assertFalse(f.state.get().hasPendingProxyJournal());
            // One fresh read/scan for the click and one fresh read/scan after the audit wait.
            verify(f.repository, times(2)).read(f.barrel);
            verify(f.blocks, times(2)).inspect(eq(f.barrel), any());
            // Hidden-only debit: reuse the snapshot until payment; reacquire after payment.
            verify(f.blocks, times(3)).barrel(f.block);
            verify(f.repository, times(3)).save(eq(f.barrel), any());
        }
    }
    @Test void rejectedPaymentRestoresAllStockAndPhysicalMirror() {
        try (Fixture f = new Fixture(100_000, 64)) {
            assertEquals(DrawerSaleApi.SaleResult.REJECTED, f.api.withExternalSale(f.location, f.probe(), Long.MAX_VALUE,
                UUID.randomUUID(), (item, count) -> DrawerSaleApi.PaymentOutcome.REJECTED));
            assertEquals(100_000, f.total());
            assertEquals(64, f.mirror());
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }
    @Test void unknownPaymentRetainsJournalAndRejectsRepeatedClick() {
        try (Fixture f = new Fixture(100_000, 64)) {
            DrawerSaleApi.Probe expected = f.probe();
            UUID id = UUID.randomUUID();
            assertEquals(DrawerSaleApi.SaleResult.RECONCILIATION_REQUIRED, f.api.withExternalSale(f.location, expected, 1000,
                id, (item, count) -> DrawerSaleApi.PaymentOutcome.UNKNOWN));
            assertEquals(id, f.state.get().proxyJournal().orElseThrow().operationId());
            assertTrue(f.storage.reconcile(f.state.get(), f.mirror()).requiresManualRecovery());
            assertEquals(DrawerSaleApi.Status.UNAVAILABLE, f.probe().status());
            assertEquals(DrawerSaleApi.SaleResult.STOCK_CHANGED, f.api.withExternalSale(f.location, expected, 1000,
                id, (item, count) -> { fail("No second payment"); return null; }));
            assertEquals(99_000, f.total());
        }
    }
    @Test void thrownPaymentDoesNotRestoreOrRetry() {
        try (Fixture f = new Fixture(100_000, 64)) {
            assertEquals(DrawerSaleApi.SaleResult.RECONCILIATION_REQUIRED, f.api.withExternalSale(f.location, f.probe(), 1000,
                UUID.randomUUID(), (item, count) -> { throw new IllegalStateException("credited then threw"); }));
            assertEquals(99_000, f.total());
            assertTrue(f.state.get().hasPendingProxyJournal());
        }
    }
    @Test void hopperChangeBeforeCommitRejectsQuoteWithoutPayment() {
        try (Fixture f = new Fixture(100_000, 64)) {
            DrawerSaleApi.Probe expected = f.probe();
            f.physical[1].setAmount(63);
            assertEquals(DrawerSaleApi.SaleResult.STOCK_CHANGED, f.api.withExternalSale(f.location, expected, 1000,
                UUID.randomUUID(), (item, count) -> { fail("Stale quote"); return null; }));
            assertEquals(99_999, f.total());
        }
    }
    @Test void hopperInputDuringAuditSellsTheQuotedStockAndLeavesNewArrivals() {
        try (Fixture f = new Fixture(1000, 64)) {
            final DrawerSaleApi.Probe quoted = f.probe();
            // The hopper has inserted real items, but the delayed reconciler has not run yet.
            f.physical[0] = item(8);
            final AtomicInteger payments = new AtomicInteger();
            assertEquals(DrawerSaleApi.SaleResult.SOLD, f.api.withExternalSale(f.location, quoted, Long.MAX_VALUE,
                UUID.randomUUID(), (template, amount) -> {
                    assertEquals(1000, amount);
                    payments.incrementAndGet();
                    return DrawerSaleApi.PaymentOutcome.PAID;
                }));
            assertEquals(1, payments.get());
            assertEquals(8, f.total());
            assertEquals(8, f.mirror());
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }
    @Test void reconciledInputDuringAuditStillHonorsTheQuotedPerUseLimit() {
        try (Fixture f = new Fixture(5_000_000_000L, 64)) {
            final DrawerSaleApi.Probe quoted = f.probe();
            f.state.set(f.state.get().withStoredTotal(5_000_000_128L));
            assertEquals(DrawerSaleApi.SaleResult.SOLD, f.api.withExternalSale(f.location, quoted, 1_000_000L,
                UUID.randomUUID(), (template, amount) -> {
                    assertEquals(1_000_000L, amount);
                    return DrawerSaleApi.PaymentOutcome.PAID;
                }));
            assertEquals(4_999_000_128L, f.total());
            assertEquals(64, f.mirror());
        }
    }
    @Test void rejectedSaleRestoresTheQuoteAndEveryItemThatArrivedDuringAudit() {
        try (Fixture f = new Fixture(1000, 64)) {
            final DrawerSaleApi.Probe quoted = f.probe();
            f.physical[0] = item(8);
            assertEquals(DrawerSaleApi.SaleResult.REJECTED, f.api.withExternalSale(f.location, quoted, Long.MAX_VALUE,
                UUID.randomUUID(), (template, amount) -> {
                    assertEquals(1000, amount);
                    return DrawerSaleApi.PaymentOutcome.REJECTED;
                }));
            assertEquals(1008, f.total());
            assertEquals(72, f.mirror());
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }
    @Test void uncertainPaymentRetainsTheFreshBeforeAndAfterTotals() {
        try (Fixture f = new Fixture(1000, 64)) {
            final DrawerSaleApi.Probe quoted = f.probe();
            f.physical[0] = item(8);
            assertEquals(DrawerSaleApi.SaleResult.RECONCILIATION_REQUIRED,
                f.api.withExternalSale(f.location, quoted, Long.MAX_VALUE, UUID.randomUUID(),
                    (template, amount) -> DrawerSaleApi.PaymentOutcome.UNKNOWN));
            assertEquals(8, f.total());
            final DrawerProxyJournal journal = f.state.get().proxyJournal().orElseThrow();
            assertEquals(1008, journal.totalBefore());
            assertEquals(8, journal.totalAfter());
            assertEquals(DrawerSaleApi.Status.UNAVAILABLE, f.probe().status());
        }
    }
    @Test void aDifferentItemStillRejectsTheQuoteEvenWhenItsTotalHasGrown() {
        try (Fixture f = new Fixture(1000, 64)) {
            final DrawerSaleApi.Probe quoted = f.probe();
            final ItemStack changed = mock(ItemStack.class);
            when(changed.getType()).thenReturn(mock(Material.class));
            when(changed.getAmount()).thenReturn(1);
            when(changed.clone()).thenReturn(changed);
            when(changed.isSimilar(any())).thenReturn(false);
            f.state.set(f.state.get().withTemplate(changed).withStoredTotal(1008));
            assertEquals(DrawerSaleApi.SaleResult.STOCK_CHANGED,
                f.api.withExternalSale(f.location, quoted, Long.MAX_VALUE, UUID.randomUUID(),
                    (template, amount) -> { fail("A changed item must not be paid"); return null; }));
            assertEquals(1008, f.total());
            assertFalse(f.state.get().hasPendingProxyJournal());
        }
    }
    @Test void permissionFacingAndProtectionAreChecked() {
        try (Fixture f = new Fixture(1000, 64)) {
            assertEquals(DrawerSaleApi.Status.DENIED, f.api.probeSaleTarget(f.player, f.block, BlockFace.SOUTH).status());
            when(f.player.hasPermission(anyString())).thenReturn(false);
            assertEquals(DrawerSaleApi.Status.DENIED, f.probe().status());
            verify(f.repository, never()).read(any());
            verify(f.blocks, never()).inspect(any(), any());
            when(f.player.hasPermission(anyString())).thenReturn(true);
            when(f.protection.canWithdraw(f.player, f.barrel)).thenReturn(false);
            assertEquals(DrawerSaleApi.Status.DENIED, f.probe().status());
        }
    }
    @Test void permissionRevokedAfterProbePreventsSale() {
        try (Fixture f = new Fixture(1000, 64)) {
            final DrawerSaleApi.Probe expected = f.probe();
            when(f.player.hasPermission("devstoragedrawer.use")).thenReturn(false);
            clearInvocations(f.repository, f.blocks);
            assertEquals(DrawerSaleApi.SaleResult.UNAVAILABLE, f.api.withExternalSale(f.location, expected, 100,
                    UUID.randomUUID(), (item, count) -> { fail("Revoked sale must not pay"); return null; }));
            verify(f.repository, never()).read(any());
            verify(f.repository, never()).save(any(), any());
            assertEquals(1000, f.total());
        }
    }
    @Test void protectionRevokedAfterProbePreventsSale() {
        try (Fixture f = new Fixture(1000, 64)) {
            final DrawerSaleApi.Probe expected = f.probe();
            when(f.protection.canWithdraw(f.player, f.barrel)).thenReturn(false);
            clearInvocations(f.repository, f.blocks);
            assertEquals(DrawerSaleApi.SaleResult.UNAVAILABLE, f.api.withExternalSale(f.location, expected, 100,
                    UUID.randomUUID(), (item, count) -> { fail("Protected sale must not pay"); return null; }));
            verify(f.repository, never()).read(any());
            verify(f.repository, never()).save(any(), any());
            assertEquals(1000, f.total());
        }
    }
    @Test void stockOnlyQuoteCannotBypassCommitAuthorization() {
        try (Fixture f = new Fixture(1000, 64)) {
            final DrawerSaleApi.Probe snapshot = f.probe();
            final DrawerSaleApi.Probe unauthenticated = new DrawerSaleApi.Probe(
                    snapshot.status(), snapshot.template(), snapshot.amount());
            clearInvocations(f.repository, f.blocks);
            assertEquals(DrawerSaleApi.SaleResult.UNAVAILABLE,
                    f.api.withExternalSale(f.location, unauthenticated, 100, UUID.randomUUID(),
                            (item, count) -> { fail("A stock-only quote must not pay"); return null; }));
            verify(f.repository, never()).read(any());
            verify(f.repository, never()).save(any(), any());
            assertEquals(1000, f.total());
        }
    }
    @Test void rotatedDrawerAfterProbePreventsSale() {
        try (Fixture f = new Fixture(1000, 64)) {
            final DrawerSaleApi.Probe expected = f.probe();
            final Directional rotated = mock(Directional.class);
            when(rotated.getFacing()).thenReturn(BlockFace.SOUTH);
            when(f.barrel.getBlockData()).thenReturn(rotated);
            clearInvocations(f.repository, f.blocks);
            assertEquals(DrawerSaleApi.SaleResult.UNAVAILABLE, f.api.withExternalSale(f.location, expected, 100,
                    UUID.randomUUID(), (item, count) -> { fail("Rotated drawer must not pay"); return null; }));
            verify(f.repository, never()).read(any());
            verify(f.repository, never()).save(any(), any());
            assertEquals(1000, f.total());
        }
    }
    @Test void wrongRegionDoesNotReadOrDebitStock() {
        try (Fixture f = new Fixture(1000, 64)) {
            DrawerSaleApi.Probe expected = f.probe();
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
            assertEquals(DrawerSaleApi.Status.UNAVAILABLE, f.probe().status());
            assertEquals(DrawerSaleApi.SaleResult.UNAVAILABLE, f.api.withExternalSale(f.location, expected, 100,
                UUID.randomUUID(), (item, count) -> { fail("Wrong region"); return null; }));
            assertEquals(1000, f.total());
        }
    }
    @Test void reentrantSaleIsBlockedWhilePaymentRuns() {
        try (Fixture f = new Fixture(1000, 64)) {
            DrawerSaleApi.Probe expected = f.probe();
            assertEquals(DrawerSaleApi.SaleResult.SOLD, f.api.withExternalSale(f.location, expected, 100,
                UUID.randomUUID(), (item, count) -> {
                    assertEquals(DrawerSaleApi.SaleResult.STOCK_CHANGED, f.api.withExternalSale(f.location, expected, 100,
                        UUID.randomUUID(), (other, amount) -> { fail("Reentrant payment"); return null; }));
                    return DrawerSaleApi.PaymentOutcome.PAID;
                }));
            assertEquals(900, f.total());
        }
    }
    @Test void failedFinalSavePreservesEvidenceAfterPayment() {
        try (Fixture f = new Fixture(1000, 64)) {
            when(f.repository.save(eq(f.barrel), any())).thenAnswer(call -> {
                DrawerState next = call.getArgument(1);
                if (!next.hasPendingProxyJournal()) return false;
                f.state.set(next); return true;
            });
            assertEquals(DrawerSaleApi.SaleResult.RECONCILIATION_REQUIRED, f.api.withExternalSale(f.location, f.probe(), 100,
                UUID.randomUUID(), (item, count) -> DrawerSaleApi.PaymentOutcome.PAID));
            assertTrue(f.state.get().hasPendingProxyJournal());
        }
    }
    @Test void preparedHiddenOnlySaleAlsoRequiresManualRecovery() {
        try (Fixture f = new Fixture(1000, 64)) {
            DrawerState prepared = f.state.get().withProxyJournal(new DrawerProxyJournal(UUID.randomUUID(),
                DrawerJournalKind.GUARDED_EXTERNAL_SALE, DrawerJournalPhase.PREPARED, null, 1000, 900, 64, 64, 1));
            DrawerJournalReconciliation recovered = f.storage.reconcile(prepared, 64);
            assertTrue(recovered.requiresManualRecovery());
            assertSame(prepared, recovered.state());
        }
    }
    @Test void staleServiceReferenceCannotSellAfterDisable() {
        try (Fixture f = new Fixture(1000, 64)) {
            DrawerSaleApi.Probe expected = f.probe();
            f.api.disable();
            assertEquals(DrawerSaleApi.Status.UNAVAILABLE, f.probe().status());
            assertEquals(DrawerSaleApi.SaleResult.UNAVAILABLE, f.api.withExternalSale(f.location, expected, 100,
                UUID.randomUUID(), (item, count) -> { fail("Disabled service"); return null; }));
            assertEquals(1000, f.total());
        }
    }
    @Test void uncertainSaleBlocksEventlessHopperInputAndOutput() {
        try (Fixture f = new Fixture(1000, 64)) {
            f.api.withExternalSale(f.location, f.probe(), 100, UUID.randomUUID(),
                (item, count) -> DrawerSaleApi.PaymentOutcome.UNKNOWN);
            DrawerHopperBridge bridge = new DrawerHopperBridge(f.context, mock(DrawerWithdrawalCoordinator.class));
            for (var type : HopperInventorySearchEvent.ContainerType.values()) {
                HopperInventorySearchEvent event = mock(HopperInventorySearchEvent.class);
                when(event.getSearchBlock()).thenReturn(f.block);
                when(event.getContainerType()).thenReturn(type);
                bridge.onHopperExternalSaleGuard(event);
                verify(event).setInventory(null);
            }
        }
    }
    @Test void rejectedHiddenOnlyDebitRestoresWithoutRebuildingMirror() {
        try (Fixture f = new Fixture(100_000, 64)) {
            assertEquals(DrawerSaleApi.SaleResult.REJECTED, f.api.withExternalSale(f.location, f.probe(), 1000,
                UUID.randomUUID(), (item, count) -> DrawerSaleApi.PaymentOutcome.REJECTED));
            assertEquals(100_000, f.total());
            assertEquals(64, f.mirror());
            assertFalse(f.state.get().hasPendingProxyJournal());
            verify(f.barrel.getInventory(), never()).clear();
            verify(f.barrel.getInventory(), never()).setItem(anyInt(), any());
        }
    }
    @Test void unknownPaymentCanBeProbedWithoutRepeatedMirrorScansOrRecovery() {
        try (Fixture f = new Fixture(100_000, 64)) {
            f.api.withExternalSale(f.location, f.probe(), 1000, UUID.randomUUID(),
                (item, count) -> DrawerSaleApi.PaymentOutcome.UNKNOWN);
            clearInvocations(f.blocks, f.repository, f.hoppers);
            for (int click = 0; click < 20; click++) assertEquals(DrawerSaleApi.Status.UNAVAILABLE, f.probe().status());
            verify(f.repository, times(20)).read(f.barrel);
            verify(f.blocks, never()).inspect(any(), any());
            verify(f.hoppers, never()).recoverAndRebalance(any());
        }
    }
}
