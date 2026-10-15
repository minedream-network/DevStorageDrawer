package com.teenkung.devstoragedrawer.block;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.domain.DrawerDisplayLink;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.domain.SingleItemDrawerStorage;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DrawerBreakConfirmationTest {
    @Test void confirmedBreakWaitsForProtectionListeners() throws ReflectiveOperationException {
        final EventHandler handler = DrawerBlockListener.class
                .getMethod("onBreak", BlockBreakEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }

    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final DrawerRuntimeContext context = mock(DrawerRuntimeContext.class);
        final DrawerBlockAccess blocks = mock(DrawerBlockAccess.class);
        final DrawerStateRepository repository = mock(DrawerStateRepository.class);
        final DrawerHopperBridge hoppers = mock(DrawerHopperBridge.class);
        final DrawerBreakDrops drops = mock(DrawerBreakDrops.class);
        final FoliaExecution execution = mock(FoliaExecution.class);
        final DrawerTierDefinition tier = mock(DrawerTierDefinition.class);
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final Barrel barrel = mock(Barrel.class);
        final Player player = mock(Player.class);
        final UUID owner = UUID.randomUUID();
        final UUID worldId = UUID.randomUUID();
        final Location location = new Location(world, -20, 43, -2);
        final AtomicReference<DrawerState> state = new AtomicReference<>();
        final AtomicLong physical = new AtomicLong(64);
        final Map<UUID, DrawerBreakTicket> tickets = new ConcurrentHashMap<>();
        final DrawerBlockListener listener;
        boolean serverVeto;

        Fixture() {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            when(world.getUID()).thenReturn(worldId);
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            when(barrel.getLocation()).thenReturn(location);
            when(barrel.getWorld()).thenReturn(world);
            when(barrel.getX()).thenReturn(-20);
            when(barrel.getY()).thenReturn(43);
            when(barrel.getZ()).thenReturn(-2);
            when(blocks.barrel(block)).thenReturn(Optional.of(barrel));
            when(repository.isDrawer(barrel)).thenReturn(true);
            when(repository.read(barrel)).thenAnswer(call -> new DrawerStateReadResult.Valid(state.get()));
            when(blocks.inspect(eq(barrel), any())).thenAnswer(call -> new DrawerBlockAccess.PhysicalStock(physical.get(), true));
            when(context.blocks()).thenReturn(blocks);
            when(context.repository()).thenReturn(repository);
            when(context.execution()).thenReturn(execution);
            when(context.storage()).thenReturn(new SingleItemDrawerStorage());
            when(context.tier("tier")).thenReturn(Optional.of(tier));
            when(context.logger()).thenReturn(Logger.getLogger("drawer-confirm-test"));
            when(context.messages()).thenReturn(new DrawerMessages(Map.of(
                    "drawer.break-changed", "Changed", "drawer.break-unavailable", "Unavailable")));
            when(tier.createItem()).thenReturn(mock(ItemStack.class));
            when(player.getUniqueId()).thenReturn(owner);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(location.clone().add(0, 0, 1));
            when(player.isOnline()).thenReturn(true);
            final ItemStack template = mock(ItemStack.class);
            when(template.getType()).thenReturn(mock(Material.class));
            when(template.getAmount()).thenReturn(1);
            when(template.getMaxStackSize()).thenReturn(64);
            when(template.clone()).thenReturn(template);
            when(template.serializeAsBytes()).thenReturn(new byte[] {1, 2, 3});
            state.set(DrawerState.restored(2, "tier", template, 1000, 64, 10000, null, DrawerDisplayLink.none()));
            when(drops.recoverInterrupted(eq(barrel), any())).thenAnswer(call -> call.getArgument(1));
            listener = new DrawerBlockListener(context, hoppers, drops, tickets);
            when(player.breakBlock(block)).thenAnswer(call -> {
                // The server emits a new event and applies other protection plugins first.
                // A cancelled event is not dispatched to this ignoreCancelled listener.
                if (!serverVeto) listener.onBreak(new BlockBreakEvent(block, player));
                return false; // Guarded drawer drops cancel the vanilla block destruction.
            });
        }
        DrawerBreakTicket request() {
            final BlockBreakEvent event = new BlockBreakEvent(block, player);
            listener.onBreak(event);
            assertTrue(event.isCancelled());
            final DrawerBreakTicket ticket = tickets.get(owner);
            assertNotNull(ticket);
            return ticket;
        }
        @Override public void close() { bukkit.close(); }
    }

    @Test void oneConfirmationImmediatelyStartsDropsAndCannotBeReplayed() {
        try (Fixture f = new Fixture()) {
            final DrawerBreakTicket ticket = f.request();
            verify(f.drops, never()).begin(any(), any(), any(), any());
            f.listener.confirmBreak(f.player, ticket);
            f.listener.confirmBreak(f.player, ticket);
            verify(f.player, times(1)).breakBlock(f.block);
            verify(f.drops, times(1)).begin(eq(f.player), eq(f.barrel), eq(f.state.get()), any());
            assertTrue(ticket.wasConsumed());
            assertTrue(f.tickets.isEmpty());
            // Only the original dialog was queued; confirmation never queues a second one.
            verify(f.execution, times(1)).runOnEntity(eq(f.player), any(), any());
        }
    }

    @Test void repeatedBreakEventsDoNotOpenAnotherPendingDialog() {
        try (Fixture f = new Fixture()) {
            final DrawerBreakTicket first = f.request();
            assertSame(first, f.request());
            verify(f.execution, times(1)).runOnEntity(eq(f.player), any(), any());
        }
    }

    @Test void breakingAgainAfterDismissingTheShownDialogCanOpenAFreshConfirmation() {
        try (Fixture f = new Fixture()) {
            final DrawerBreakTicket dismissed = f.request();
            dismissed.markDialogShown(); // Escape does not send a custom cancel-button callback.
            final DrawerBreakTicket current = f.request();
            assertNotSame(dismissed, current);
            f.listener.confirmBreak(f.player, dismissed);
            assertSame(current, f.tickets.get(f.owner));
            verify(f.player, never()).breakBlock(any());
            f.listener.confirmBreak(f.player, current);
            verify(f.player, times(1)).breakBlock(f.block);
            verify(f.drops, times(1)).begin(eq(f.player), eq(f.barrel), eq(f.state.get()), any());
        }
    }

    @Test void confirmationRechecksNormalServerBreakProtection() {
        try (Fixture f = new Fixture()) {
            final DrawerBreakTicket ticket = f.request();
            f.serverVeto = true;
            f.listener.confirmBreak(f.player, ticket);
            verify(f.player).breakBlock(f.block);
            verify(f.drops, never()).begin(any(), any(), any(), any());
            assertFalse(ticket.wasConsumed());
            assertTrue(f.tickets.isEmpty());
        }
    }

    @Test void cosmeticRepairsAndSameTotalMirrorRepackDoNotRequireAnotherConfirmation() {
        try (Fixture f = new Fixture()) {
            final DrawerBreakTicket ticket = f.request();
            f.physical.set(128);
            f.state.set(f.state.get().withStoredTotalAndExpectedMirrorCount(1000, 128)
                    .withDisplayLink(new DrawerDisplayLink(UUID.randomUUID(), null, null, null, null)));
            f.listener.confirmBreak(f.player, ticket);
            verify(f.drops).begin(eq(f.player), eq(f.barrel), eq(f.state.get()), any());
            verify(f.execution, times(1)).runOnEntity(eq(f.player), any(), any());
        }
    }

    @Test void hopperQuantityChangesUseTheLatestStockWithOneConfirmation() {
        for (boolean persisted : new boolean[] {true, false}) {
            try (Fixture f = new Fixture()) {
                final DrawerBreakTicket ticket = f.request();
                // Exercise both a completed rebalance and the vanilla transfer awaiting rebalance.
                if (persisted) f.state.set(f.state.get().withStoredTotalAndExpectedMirrorCount(1001, 65));
                f.physical.set(65);
                f.listener.confirmBreak(f.player, ticket);
                verify(f.drops).begin(eq(f.player), eq(f.barrel), eq(f.state.get()), any());
                assertEquals(1001, f.state.get().totalForPhysical(f.physical.get()));
                assertTrue(ticket.wasConsumed());
                verify(f.execution, times(1)).runOnEntity(eq(f.player), any(), any());
            }
        }
    }

    @Test void changedItemIdentityIsRejectedWithoutOpeningASecondDialog() {
        try (Fixture f = new Fixture()) {
            final DrawerBreakTicket ticket = f.request();
            final ItemStack replacement = mock(ItemStack.class);
            when(replacement.getType()).thenReturn(mock(Material.class));
            when(replacement.getAmount()).thenReturn(1);
            when(replacement.clone()).thenReturn(replacement);
            when(replacement.serializeAsBytes()).thenReturn(new byte[] {4, 5, 6});
            f.state.set(DrawerState.restored(2, "tier", replacement, 1000, 64, 10000, null, DrawerDisplayLink.none()));
            f.listener.confirmBreak(f.player, ticket);
            verify(f.drops, never()).begin(any(), any(), any(), any());
            verify(f.execution, times(1)).runOnEntity(eq(f.player), any(), any());
            assertTrue(f.tickets.isEmpty());
        }
    }

    @Test void removalOrReplacementOfTheDrawerCannotBreakTheNewBlock() {
        try (Fixture f = new Fixture()) {
            final DrawerBreakTicket ticket = f.request();
            when(f.blocks.barrel(f.block)).thenReturn(Optional.empty());
            f.listener.confirmBreak(f.player, ticket);
            verify(f.player, never()).breakBlock(any());
            verify(f.drops, never()).begin(any(), any(), any(), any());
        }
    }

    @Test void movingAwayOrIntoAnotherRegionInvalidatesConfirmation() {
        for (boolean movedAway : new boolean[] {true, false}) {
            try (Fixture f = new Fixture()) {
                final DrawerBreakTicket ticket = f.request();
                if (movedAway) when(f.player.getLocation()).thenReturn(f.location.clone().add(20, 0, 0));
                else f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
                f.listener.confirmBreak(f.player, ticket);
                verify(f.player, never()).breakBlock(any());
                verify(f.drops, never()).begin(any(), any(), any(), any());
                assertTrue(f.tickets.isEmpty());
            }
        }
    }

    @Test void foreignExpiredAndDisabledCallbacksCannotAuthorizeABreak() {
        try (Fixture f = new Fixture()) {
            final DrawerBreakTicket ticket = f.request();
            final Player foreign = mock(Player.class);
            when(foreign.getUniqueId()).thenReturn(UUID.randomUUID());
            f.listener.confirmBreak(foreign, ticket);
            assertSame(ticket, f.tickets.get(f.owner));
            final DrawerBreakTicket expired = new DrawerBreakTicket(f.owner, ticket.block(), "old", System.currentTimeMillis() - 61_000L);
            f.tickets.put(f.owner, expired);
            f.listener.confirmBreak(f.player, expired);
            final DrawerBreakTicket current = f.request();
            f.listener.disable();
            f.listener.confirmBreak(f.player, current);
            verify(f.player, never()).breakBlock(any());
            verify(foreign, never()).breakBlock(any());
            verify(f.drops, never()).begin(any(), any(), any(), any());
        }
    }
}
