package com.teenkung.devstoragedrawer.block;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import com.teenkung.devstoragedrawer.domain.*;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.HopperInventorySearchEvent;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DrawerBreakDropsTest {
    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final Map<ItemStack, String> identities = new IdentityHashMap<>();
        final DrawerRuntimeContext context = mock(DrawerRuntimeContext.class);
        final DrawerStateRepository repository = mock(DrawerStateRepository.class);
        final DrawerBlockAccess blocks = mock(DrawerBlockAccess.class);
        final DrawerHopperBridge hoppers = mock(DrawerHopperBridge.class);
        final FoliaExecution execution = mock(FoliaExecution.class);
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final Barrel barrel = mock(Barrel.class);
        final Player player = mock(Player.class);
        final Inventory inventory = mock(Inventory.class);
        final Location location = new Location(world, -20, 43, -2);
        final AtomicReference<DrawerState> state = new AtomicReference<>();
        final AtomicReference<Material> blockType = new AtomicReference<>(Material.BARREL);
        final Deque<Consumer<ScheduledTask>> scheduled = new ArrayDeque<>();
        final List<Item> entities = new ArrayList<>();
        final ItemStack[] physical = new ItemStack[27];
        final List<DrawerState> saves = new ArrayList<>();
        final Map<Barrel, Snapshot> snapshots = new IdentityHashMap<>();
        final DrawerBreakDrops drops;
        boolean snapshotWrites;
        int rejectSpawn = -1;
        boolean rejectDrawer;
        boolean failAppliedSave;
        Consumer<Item> spawnObserver = item -> { };

        Fixture(final long total, final int stackSize) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            when(block.getLocation()).thenReturn(location);
            when(block.getType()).thenAnswer(call -> blockType.get());
            when(block.getBlockData()).thenReturn(mock(BlockData.class));
            doAnswer(call -> { blockType.set(call.getArgument(0)); return null; }).when(block).setType(any(), anyBoolean());
            when(barrel.getLocation()).thenReturn(location);
            when(barrel.getWorld()).thenReturn(world);
            when(barrel.getBlock()).thenReturn(block);
            when(barrel.getInventory()).thenReturn(inventory);
            when(inventory.getSize()).thenReturn(27);
            when(inventory.getContents()).thenAnswer(call -> physical.clone());
            doAnswer(call -> { Arrays.fill(physical, null); return null; }).when(inventory).clear();
            doAnswer(call -> { System.arraycopy(call.getArgument(0), 0, physical, 0, 27); return null; })
                    .when(inventory).setContents(any(ItemStack[].class));
            when(context.blocks()).thenReturn(blocks);
            when(context.repository()).thenReturn(repository);
            when(context.execution()).thenReturn(execution);
            when(context.storage()).thenReturn(new SingleItemDrawerStorage());
            when(context.logger()).thenReturn(Logger.getLogger("drawer-break-test"));
            when(context.messages()).thenReturn(new DrawerMessages(Map.of("drawer.break-unavailable", "Stopped")));
            final DrawerSettings settings = mock(DrawerSettings.class);
            when(settings.breaking()).thenReturn(new DrawerSettings.Breaking(2));
            when(context.settings()).thenReturn(settings);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(blocks.barrel(block)).thenAnswer(call -> blockType.get() == Material.BARREL
                    ? Optional.of(snapshotWrites ? snapshot() : barrel) : Optional.empty());
            when(blocks.inspect(any(Barrel.class), any())).thenAnswer(call -> {
                final DrawerState current = call.getArgument(1);
                return new DrawerBlockAccess.PhysicalStock(mirror(), Arrays.stream(physical)
                        .filter(Objects::nonNull).allMatch(current::matchesTemplate));
            });
            when(repository.isDrawer(any(Barrel.class))).thenReturn(true);
            when(repository.read(any(Barrel.class))).thenAnswer(call -> {
                final Snapshot snapshot = snapshots.get(call.getArgument(0));
                return new DrawerStateReadResult.Valid(snapshot == null ? state.get() : snapshot.state.get());
            });
            when(repository.save(any(Barrel.class), any())).thenAnswer(call -> {
                final DrawerState next = call.getArgument(1);
                saves.add(next);
                if (failAppliedSave && next.proxyJournal().filter(j -> j.phase() == DrawerJournalPhase.APPLIED).isPresent()) {
                    return false;
                }
                final Snapshot snapshot = snapshots.get(call.getArgument(0));
                if (snapshot != null) {
                    // Real BlockState.update writes the captured inventory back, not just PDC.
                    System.arraycopy(copies(snapshot.inventory), 0, physical, 0, physical.length);
                    snapshot.state.set(next);
                }
                state.set(next);
                return true;
            });
            doAnswer(call -> { scheduled.add(call.getArgument(2)); return null; })
                    .when(execution).runLaterAt(any(), anyLong(), any());
            when(world.dropItem(any(), any(), any())).thenAnswer(call -> {
                final ItemStack stack = call.getArgument(1);
                final Item entity = mock(Item.class);
                final boolean[] valid = {true};
                final ItemStack[] contents = {stack.clone()};
                when(entity.isValid()).thenAnswer(ignored -> valid[0]);
                when(entity.getItemStack()).thenAnswer(ignored -> contents[0]);
                doAnswer(ignored -> { contents[0] = ignored.getArgument(0); return null; })
                        .when(entity).setItemStack(any());
                doAnswer(ignored -> { valid[0] = false; return null; }).when(entity).remove();
                entities.add(entity);
                final Consumer<Item> initializer = call.getArgument(2);
                initializer.accept(entity);
                assertTrue(state.get().hasPendingProxyJournal(), "Spawn must run under the transfer guard");
                verify(entity).setPickupDelay(Integer.MAX_VALUE);
                spawnObserver.accept(entity);
                if (entities.size() == rejectSpawn || (rejectDrawer && identities.get(stack).equals("drawer"))) {
                    valid[0] = false;
                }
                return entity;
            });
            final int mirror = (int) Math.min(total, stackSize);
            state.set(DrawerState.restored(2, "tier", total == 0 ? null : item("content", 1, stackSize),
                    total, mirror, Long.MAX_VALUE, null, DrawerDisplayLink.none()));
            if (mirror > 0) physical[1] = item("content", mirror, stackSize);
            drops = new DrawerBreakDrops(context, hoppers);
        }

        void useSnapshots() {
            snapshotWrites = true;
            snapshots.put(barrel, new Snapshot(copies(physical), new AtomicReference<>(state.get())));
        }
        Barrel snapshot() {
            final Barrel captured = mock(Barrel.class);
            when(captured.getLocation()).thenReturn(location);
            when(captured.getWorld()).thenReturn(world);
            when(captured.getBlock()).thenReturn(block);
            when(captured.getInventory()).thenReturn(inventory);
            snapshots.put(captured, new Snapshot(copies(physical), new AtomicReference<>(state.get())));
            return captured;
        }
        ItemStack[] copies(ItemStack[] contents) {
            return Arrays.stream(contents).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        }
        record Snapshot(ItemStack[] inventory, AtomicReference<DrawerState> state) { }

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
        void begin() { drops.begin(player, barrel, state.get(), item("drawer", 1, 64)); }
        long mirror() { return Arrays.stream(physical).filter(Objects::nonNull).mapToLong(ItemStack::getAmount).sum(); }
        long total() { return state.get().totalForPhysical(mirror()); }
        List<Integer> contentAmounts() { return entities.stream().filter(Item::isValid).map(Item::getItemStack)
                .filter(item -> identities.get(item).equals("content")).map(ItemStack::getAmount).toList(); }
        long dropped() { return contentAmounts().stream().mapToLong(Integer::longValue).sum(); }
        void tick() { scheduled.removeFirst().accept(null); }
        void drain() { for (int i = 0; !scheduled.isEmpty() && i < 100; i++) tick(); assertTrue(scheduled.isEmpty()); }
        @Override public void close() { bukkit.close(); }
    }

    @Test void nativeStacksIncludeTheRemainderAndTheDrawerDropsLast() {
        try (Fixture f = new Fixture(130, 64)) {
            f.begin();
            assertEquals(List.of(64, 64), f.contentAmounts());
            assertEquals(2, f.total());
            assertEquals(0, f.mirror());
            assertTrue(f.drops.isInProgress(f.barrel));
            assertEquals(Material.BARREL, f.blockType.get());
            f.drain();
            assertEquals(List.of(64, 64, 2), f.contentAmounts());
            assertEquals(130, f.dropped());
            assertEquals("drawer", f.identities.get(f.entities.getLast().getItemStack()));
            assertEquals(Material.AIR, f.blockType.get());
            assertFalse(f.drops.isInProgress(f.barrel));
            f.entities.forEach(entity -> { verify(entity, never()).setOwner(any()); verify(entity, never()).setGlowing(anyBoolean()); });
        }
    }

    @Test void respectsSmallAndUnstackableItems() {
        for (int size : List.of(1, 16)) {
            try (Fixture f = new Fixture(size * 2L + 1L, size)) {
                f.begin(); f.drain();
                assertEquals(List.of(size, size, 1), f.contentAmounts());
            }
        }
    }

    @Test void realSnapshotWritebackCannotRestoreTheMirrorBetweenDropBatches() {
        try (Fixture f = new Fixture(200, 64)) {
            f.useSnapshots();
            f.begin();
            assertEquals(128, f.dropped());
            assertEquals(0, f.mirror(), "APPLIED must save the post-clear inventory snapshot");
            assertEquals(72, f.total());
            f.drain();
            assertEquals(200, f.dropped());
            assertEquals(Material.AIR, f.blockType.get());
        }
    }

    @Test void failedDebitRollbackPersistsTheRestoredInventorySnapshot() {
        try (Fixture f = new Fixture(200, 64)) {
            f.useSnapshots();
            f.failAppliedSave = true;
            f.begin(); f.drain();
            assertEquals(0, f.dropped());
            assertEquals(64, f.mirror());
            assertEquals(200, f.total());
            assertFalse(f.state.get().hasPendingProxyJournal());
            f.failAppliedSave = false;
            // A new break starts from a fresh BlockState, just as a second BlockBreakEvent does.
            f.drops.begin(f.player, f.snapshot(), f.state.get(), f.item("drawer", 1, 64));
            f.drain();
            assertEquals(200, f.dropped());
        }
    }

    @Test void rejectedFirstBatchRestoresPhysicalAndHiddenStockAndRemovesAllPartialDrops() {
        try (Fixture f = new Fixture(130, 64)) {
            f.rejectSpawn = 2;
            f.begin(); f.drain();
            assertEquals(130, f.total());
            assertEquals(64, f.mirror());
            assertEquals(0, f.dropped());
            assertFalse(f.state.get().hasPendingProxyJournal());
            assertEquals(Material.BARREL, f.blockType.get());
            verify(f.hoppers).watch(f.barrel);
            f.rejectSpawn = -1;
            f.begin(); f.drain();
            assertEquals(130, f.dropped());
        }
    }

    @Test void laterFailureKeepsCompletedDropsAndOnlyTheUndeliveredRemainder() {
        try (Fixture f = new Fixture(200, 64)) {
            f.rejectSpawn = 4;
            f.begin(); f.drain();
            assertEquals(128, f.dropped());
            assertEquals(72, f.total());
            assertEquals(0, f.mirror());
            assertFalse(f.state.get().hasPendingProxyJournal());
            assertEquals(Material.BARREL, f.blockType.get());
            f.rejectSpawn = -1;
            f.begin(); f.drain();
            assertEquals(200, f.dropped());
            assertEquals(Material.AIR, f.blockType.get());
        }
    }

    @Test void failedDebitRemovesDropsBeforeRestoringStock() {
        try (Fixture f = new Fixture(130, 64)) {
            f.failAppliedSave = true;
            f.begin(); f.drain();
            assertEquals(130, f.total());
            assertEquals(64, f.mirror());
            assertEquals(0, f.dropped());
            assertEquals(Material.BARREL, f.blockType.get());
        }
    }

    @Test void removedEarlierDropOrChangedAmountRollsBackTheEntireBatch() {
        for (boolean removeEarlier : List.of(true, false)) {
            try (Fixture f = new Fixture(130, 64)) {
                f.spawnObserver = entity -> {
                    if (removeEarlier && f.entities.size() == 2) f.entities.getFirst().remove();
                    if (!removeEarlier) entity.getItemStack().setAmount(63);
                };
                f.begin(); f.drain();
                assertEquals(130, f.total());
                assertEquals(0, f.dropped());
            }
        }
    }

    @Test void failedDrawerDropKeepsAnEmptyDrawerWithoutRecreatingItsDeliveredContents() {
        try (Fixture f = new Fixture(130, 64)) {
            f.rejectDrawer = true;
            f.begin(); f.drain();
            assertEquals(130, f.dropped());
            assertEquals(0, f.total());
            assertFalse(f.state.get().hasTemplate());
            assertFalse(f.state.get().hasPendingProxyJournal());
            assertEquals(Material.BARREL, f.blockType.get());
        }
    }

    @Test void duplicateBreakAndHopperRecoveryCannotReplayAnActiveBatch() {
        try (Fixture f = new Fixture(200, 64)) {
            f.begin(); f.begin();
            assertEquals(128, f.dropped());
            assertEquals(72, f.total());
            assertTrue(f.context.storage().reconcile(f.state.get(), 0).requiresManualRecovery());
            assertSame(f.state.get(), f.drops.recoverInterrupted(f.barrel, f.state.get()));
            f.drain();
            assertEquals(200, f.dropped());
        }
    }

    @Test void disableAndRestartKeepTheCompletedBatchAndResumeOnlyRemainingStock() {
        try (Fixture f = new Fixture(200, 64)) {
            f.begin(); f.drops.disable(); f.drain();
            final DrawerBreakDrops restarted = new DrawerBreakDrops(f.context, f.hoppers);
            final DrawerState recovered = restarted.recoverInterrupted(f.barrel, f.state.get());
            assertEquals(72, recovered.storedTotal());
            assertFalse(recovered.hasPendingProxyJournal());
            assertEquals(128, f.dropped());
            restarted.begin(f.player, f.barrel, recovered, f.item("drawer", 1, 64));
            f.drain();
            assertEquals(200, f.dropped());
        }
    }

    @Test void preparedWorldDropCannotBeGuessedFromPhysicalStockAfterAnInterruption() {
        try (Fixture f = new Fixture(200, 64)) {
            final DrawerState prepared = f.state.get().withProxyJournal(new DrawerProxyJournal(UUID.randomUUID(),
                    DrawerJournalKind.WORLD_DROP, DrawerJournalPhase.PREPARED, f.player.getUniqueId(),
                    200, 72, 64, 0, 1));
            f.state.set(prepared);
            assertSame(prepared, f.drops.recoverInterrupted(f.barrel, prepared));
            assertTrue(f.context.storage().reconcile(prepared, 64).requiresManualRecovery());
            assertTrue(f.context.storage().reconcile(prepared, 0).requiresManualRecovery());
            assertEquals(0, f.dropped());
        }
    }

    @Test void eventlessHopperInputAndOutputAreBlockedDuringWorldDrops() {
        try (Fixture f = new Fixture(200, 64)) {
            f.begin();
            final var bridge = new DrawerHopperBridge(f.context,
                    mock(com.teenkung.devstoragedrawer.receipt.DrawerWithdrawalCoordinator.class));
            for (final var type : HopperInventorySearchEvent.ContainerType.values()) {
                final HopperInventorySearchEvent event = mock(HopperInventorySearchEvent.class);
                when(event.getSearchBlock()).thenReturn(f.block);
                when(event.getContainerType()).thenReturn(type);
                bridge.onHopperExternalSaleGuard(event);
                verify(event).setInventory(null);
            }
        }
    }

    @Test void emptyDrawerOnlyDropsTheDrawerItem() {
        try (Fixture f = new Fixture(0, 64)) {
            f.begin(); f.drain();
            assertEquals(0, f.dropped());
            assertEquals(1, f.entities.size());
            assertEquals(Material.AIR, f.blockType.get());
        }
    }

    @Test void hugeTotalsStayBoundedWithoutAllocatingAllStacksAtOnce() {
        try (Fixture f = new Fixture(5_000_000_000L, 64)) {
            f.begin();
            assertEquals(2, f.entities.size());
            assertEquals(128, f.dropped());
            assertEquals(4_999_999_872L, f.total());
            f.drops.disable();
            f.drain();
            assertEquals(128, f.dropped());
        }
    }

    @Test void chunkUnloadPausesDroppingAndLeavesRecoverableStock() {
        try (Fixture f = new Fixture(200, 64)) {
            f.begin();
            final Chunk chunk = mock(Chunk.class);
            when(chunk.getWorld()).thenReturn(f.world);
            when(chunk.getX()).thenReturn(-20 >> 4);
            when(chunk.getZ()).thenReturn(-2 >> 4);
            f.drops.pause(chunk);
            f.drain();
            assertEquals(128, f.dropped());
            assertEquals(72, f.total());
            assertFalse(f.drops.isInProgress(f.barrel));
            assertFalse(f.drops.recoverInterrupted(f.barrel, f.state.get()).hasPendingProxyJournal());
        }
    }
}
