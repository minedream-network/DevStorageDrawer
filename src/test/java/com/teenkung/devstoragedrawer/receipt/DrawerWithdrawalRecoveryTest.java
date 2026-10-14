package com.teenkung.devstoragedrawer.receipt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.block.DrawerBlockAccess;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.domain.*;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class DrawerWithdrawalRecoveryTest {
    @TempDir Path temporaryDirectory;

    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final DrawerRuntimeContext context = mock(DrawerRuntimeContext.class);
        final DrawerStateRepository repository = mock(DrawerStateRepository.class);
        final DrawerBlockAccess blocks = mock(DrawerBlockAccess.class);
        final FoliaExecution execution = mock(FoliaExecution.class);
        final Logger logger = mock(Logger.class);
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final Barrel barrel = mock(Barrel.class);
        final Player player = mock(Player.class);
        final UUID owner = UUID.randomUUID();
        final UUID operation = UUID.randomUUID();
        final UUID worldId = UUID.randomUUID();
        final Location location = new Location(world, 1, 2, 3);
        final AtomicReference<DrawerState> state = new AtomicReference<>();
        final AtomicInteger resumed = new AtomicInteger();
        final WithdrawalReceiptStore store;
        final DrawerWithdrawalCoordinator coordinator;

        @SuppressWarnings("unchecked")
        Fixture(final Path file) {
            when(world.getUID()).thenReturn(worldId);
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            when(barrel.getLocation()).thenReturn(location);
            when(context.repository()).thenReturn(repository);
            when(context.blocks()).thenReturn(blocks);
            when(context.storage()).thenReturn(new SingleItemDrawerStorage());
            when(context.execution()).thenReturn(execution);
            when(context.logger()).thenReturn(logger);
            when(blocks.barrel(block)).thenReturn(Optional.of(barrel));
            when(repository.isDrawer(barrel)).thenReturn(true);
            when(repository.read(barrel)).thenAnswer(call -> new DrawerStateReadResult.Valid(state.get()));
            when(repository.save(eq(barrel), any())).thenAnswer(call -> { state.set(call.getArgument(1)); return true; });
            when(blocks.inspect(eq(barrel), any())).thenReturn(new DrawerBlockAccess.PhysicalStock(0, true));
            doAnswer(call -> { ((Consumer<ScheduledTask>) call.getArgument(0)).accept(null); return null; })
                    .when(execution).runAsync(any());
            doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                    .when(execution).executeAt(any(), any());
            doAnswer(call -> { ((Consumer<ScheduledTask>) call.getArgument(1)).accept(null); return null; })
                    .when(execution).runOnEntity(any(), any(), any());
            when(player.isOnline()).thenReturn(true);
            when(player.getUniqueId()).thenReturn(owner);
            final ItemStack item = mock(ItemStack.class);
            when(item.getType()).thenReturn(mock(Material.class));
            when(item.getAmount()).thenReturn(1);
            when(item.getMaxStackSize()).thenReturn(64);
            when(item.clone()).thenReturn(item);
            final DrawerProxyJournal journal = new DrawerProxyJournal(operation, DrawerJournalKind.PLAYER_WITHDRAWAL,
                    DrawerJournalPhase.APPLIED, owner, 100, 84, 0, 0, 123);
            state.set(DrawerState.restored(2, "tier", item, 84, 0, 10000, journal, DrawerDisplayLink.none()));
            store = new WithdrawalReceiptStore(file);
            coordinator = new DrawerWithdrawalCoordinator(context, store);
        }

        WithdrawalReceipt receipt() {
            return new WithdrawalReceipt(operation, owner, worldId, 1, 2, 3,
                    new byte[] {1, 2, 3}, 16, WithdrawalReceiptStatus.PREPARED, 123);
        }
        void recover() { coordinator.recover(barrel, state.get(), 0, resumed::incrementAndGet); }
        @Override public void close() { bukkit.close(); }
    }

    @Test void missingAppliedReceiptCannotBeRecreatedOrDeliverItems() {
        try (Fixture f = new Fixture(temporaryDirectory.resolve("receipts.dat"))) {
            final DrawerState before = f.state.get();
            f.recover();
            assertSame(before, f.state.get());
            assertTrue(f.state.get().hasPendingProxyJournal());
            assertTrue(f.store.snapshot().isEmpty());
            assertEquals(0, f.resumed.get());
            verifyNoInteractions(f.execution);
            verify(f.logger).severe(contains("durable receipt is missing"));
        }
    }

    @Test void aCompletedReceiptSurvivesAnOldAppliedChunkWithoutAnotherDelivery() throws IOException {
        try (Fixture f = new Fixture(temporaryDirectory.resolve("receipts.dat"))) {
            f.store.prepare(f.receipt());
            f.store.markDeliverable(f.operation);
            f.store.completeAll(List.of(f.operation));
            f.store.load(); // The ledger is newer than the stale APPLIED world snapshot.
            f.recover();
            assertFalse(f.state.get().hasPendingProxyJournal());
            assertEquals(84, f.state.get().storedTotal());
            assertEquals(1, f.resumed.get());
            assertEquals(WithdrawalReceiptStatus.COMPLETED, f.store.find(f.operation).orElseThrow().status());
            assertTrue(f.store.forOwner(f.owner).isEmpty());
            final PlayerJoinEvent join = mock(PlayerJoinEvent.class);
            when(join.getPlayer()).thenReturn(f.player);
            f.coordinator.onPlayerJoin(join);
            verify(f.player, never()).getInventory();
            verify(f.player, never()).saveData();
        }
    }

    @Test void aStalePreparedJournalCannotEraseACompletedReceipt() throws IOException {
        try (Fixture f = new Fixture(temporaryDirectory.resolve("receipts.dat"))) {
            f.store.prepare(f.receipt());
            f.store.markDeliverable(f.operation);
            f.store.completeAll(List.of(f.operation));
            f.state.set(f.state.get().withStoredTotalAndExpectedMirrorCount(100, 0)
                    .withProxyJournal(f.state.get().proxyJournal().orElseThrow().withPhase(DrawerJournalPhase.PREPARED)));
            f.recover();
            assertTrue(f.state.get().hasPendingProxyJournal());
            assertEquals(0, f.resumed.get());
            assertEquals(WithdrawalReceiptStatus.COMPLETED, f.store.find(f.operation).orElseThrow().status());
            verify(f.logger).severe(contains("Could not roll back withdrawal receipt"));
        }
    }

    @Test void anExistingPreparedReceiptStillCompletesItsAppliedWithdrawal() throws IOException {
        try (Fixture f = new Fixture(temporaryDirectory.resolve("receipts.dat"))) {
            f.store.prepare(f.receipt());
            f.recover();
            assertFalse(f.state.get().hasPendingProxyJournal());
            assertEquals(84, f.state.get().storedTotal());
            assertEquals(1, f.resumed.get());
            assertEquals(WithdrawalReceiptStatus.DELIVERABLE, f.store.find(f.operation).orElseThrow().status());
        }
    }
}
