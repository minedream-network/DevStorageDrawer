package com.teenkung.devstoragedrawer.block;

import com.teenkung.devstoragedrawer.domain.DrawerJournalKind;
import com.teenkung.devstoragedrawer.domain.DrawerJournalPhase;
import com.teenkung.devstoragedrawer.domain.DrawerProxyJournal;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Drops native stacks in bounded region-owned batches, retaining all undelivered stock in the barrel. */
final class DrawerBreakDrops {
    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;
    private final Map<DrawerBreakTicket.BlockKey, Operation> operations = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    DrawerBreakDrops(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers) {
        this.context = context;
        this.hoppers = hoppers;
    }

    boolean isInProgress(final Barrel barrel) {
        return operations.containsKey(key(barrel.getLocation()));
    }

    void begin(final Player player, final Barrel barrel, final DrawerState state, final ItemStack drawerItem) {
        if (isInProgress(barrel)) return;
        if (state.hasPendingProxyJournal()) {
            throw new IllegalStateException("Cannot break a drawer with an unresolved transfer");
        }
        final Operation operation = new Operation(UUID.randomUUID(), player, barrel.getLocation(), drawerItem.clone());
        if (!enabled || operations.putIfAbsent(key(operation.location()), operation) != null) {
            return;
        }
        hoppers.unwatch(barrel);
        advance(operation, barrel, state);
    }

    /** A completed batch can be unlocked after a clean interruption; a PREPARED batch is uncertain. */
    DrawerState recoverInterrupted(final Barrel barrel, final DrawerState state) {
        final DrawerProxyJournal journal = state.proxyJournal().orElse(null);
        if (isInProgress(barrel) || journal == null || journal.kind() != DrawerJournalKind.WORLD_DROP
                || journal.phase() != DrawerJournalPhase.APPLIED || !state.usesReservedMirror()
                || state.storedTotal() != journal.totalAfter() || state.expectedMirrorCount() != 0L
                || journal.physicalTarget() != 0L) {
            return state;
        }
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, state);
        if (!stock.matchesTemplate() || stock.count() != 0L) {
            return state;
        }
        DrawerState recovered = state.withoutProxyJournal();
        if (recovered.storedTotal() == 0L) {
            recovered = recovered.withoutTemplate();
        }
        return context.repository().save(barrel, recovered) ? recovered : state;
    }

    void pause(final Chunk chunk) {
        operations.entrySet().removeIf(entry -> entry.getKey().worldId().equals(chunk.getWorld().getUID())
                && (entry.getKey().x() >> 4) == chunk.getX() && (entry.getKey().z() >> 4) == chunk.getZ());
    }

    void disable() {
        enabled = false;
        operations.clear();
    }

    private void advance(final Operation operation, final Barrel barrel, final DrawerState state) {
        final List<Drop> spawned = new ArrayList<>();
        ItemStack[] physicalBefore = null;
        DrawerState baseline = null;
        boolean prepared = false;
        try {
            final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, state);
            if (!stock.matchesTemplate()) {
                throw new IllegalStateException("The barrel inventory changed during breaking");
            }
            final long total = state.totalForPhysical(stock.count());
            baseline = state.migrateToReservedMirror(stock.count())
                    .withStoredTotalAndExpectedMirrorCount(total, stock.count()).withoutProxyJournal();
            if (total == 0L) {
                finish(operation, barrel, baseline.withoutTemplate());
                return;
            }
            final ItemStack template = baseline.requireTemplate();
            final int stackSize = template.getMaxStackSize();
            if (stackSize < 1) {
                throw new IllegalStateException("The stored item's stack size is invalid");
            }
            final long batch = Math.min(total, (long) stackSize * context.settings().breaking().stacksPerTick());
            final long remaining = total - batch;
            final DrawerProxyJournal journal = new DrawerProxyJournal(operation.id(), DrawerJournalKind.WORLD_DROP,
                    DrawerJournalPhase.PREPARED, operation.player().getUniqueId(), total, remaining,
                    stock.count(), 0L, System.currentTimeMillis());
            physicalBefore = cloneContents(barrel.getInventory().getContents());
            if (!context.repository().save(barrel, baseline.withProxyJournal(journal))) {
                throw new IllegalStateException("Could not prepare the item-drop journal");
            }
            prepared = true;

            long toSpawn = batch;
            while (toSpawn > 0L) {
                final ItemStack stack = template.clone();
                final int amount = (int) Math.min((long) stackSize, toSpawn);
                stack.setAmount(amount);
                spawn(operation, stack, spawned);
                toSpawn -= amount;
            }
            // Spawn listeners may remove an earlier entity while a later stack is being spawned.
            spawned.forEach(DrawerBreakDrops::validate);
            final Barrel live = current(operation);
            requireJournal(live, journal, DrawerJournalPhase.PREPARED);
            live.getInventory().clear();
            // BlockState.update also restores its captured inventory. Save a snapshot taken
            // after clearing the live mirror so the next batch cannot count old stock again.
            final Barrel cleared = current(operation);
            requireJournal(cleared, journal, DrawerJournalPhase.PREPARED);
            final DrawerState applied = baseline.withStoredTotalAndExpectedMirrorCount(remaining, 0L)
                    .withProxyJournal(journal.withPhase(DrawerJournalPhase.APPLIED));
            if (!context.repository().save(cleared, applied)) {
                throw new IllegalStateException("Could not save remaining drawer stock");
            }
            // Pickup is enabled only once the matching debit has been saved. These remain normal
            // items, with no parcel metadata, owner lock, or custom stack size.
            spawned.forEach(drop -> drop.entity().setPickupDelay(10));
            prepared = false;
            if (remaining == 0L) {
                finish(operation, cleared, applied.withoutProxyJournal().withoutTemplate());
            } else {
                context.execution().runLaterAt(operation.location(), 1L, task -> continueOperation(operation));
            }
        } catch (final RuntimeException exception) {
            if (prepared) {
                spawned.forEach(drop -> drop.entity().remove());
                rollback(operation, baseline, physicalBefore);
            }
            fail(operation, exception);
        }
    }

    private void continueOperation(final Operation operation) {
        if (!enabled || operations.get(key(operation.location())) != operation) {
            return;
        }
        try {
            final Barrel barrel = current(operation);
            final DrawerState state = read(barrel);
            final DrawerProxyJournal journal = state.proxyJournal().orElseThrow();
            if (!journal.operationId().equals(operation.id()) || journal.kind() != DrawerJournalKind.WORLD_DROP
                    || journal.phase() != DrawerJournalPhase.APPLIED || state.storedTotal() != journal.totalAfter()
                    || state.expectedMirrorCount() != 0L) {
                throw new IllegalStateException("The drawer's break journal changed");
            }
            advance(operation, barrel, state);
        } catch (final RuntimeException exception) {
            fail(operation, exception);
        }
    }

    private void finish(final Operation operation, final Barrel barrel, final DrawerState empty) {
        final List<Drop> spawned = new ArrayList<>();
        boolean removed = false;
        try {
            final DrawerProxyJournal journal = new DrawerProxyJournal(operation.id(), DrawerJournalKind.WORLD_DROP,
                    DrawerJournalPhase.PREPARED, operation.player().getUniqueId(), 0L, 0L, 0L, 0L,
                    System.currentTimeMillis());
            if (!context.repository().save(barrel, empty.withProxyJournal(journal))) {
                throw new IllegalStateException("Could not prepare the empty drawer break");
            }
            spawn(operation, operation.drawerItem(), spawned);
            final Barrel live = current(operation);
            requireJournal(live, journal, DrawerJournalPhase.PREPARED);
            live.getInventory().clear();
            live.getBlock().setType(Material.AIR, false);
            removed = live.getBlock().getType() == Material.AIR;
            if (!removed) {
                throw new IllegalStateException("The empty drawer block could not be removed");
            }
            operations.remove(key(operation.location()), operation);
            spawned.forEach(drop -> drop.entity().setPickupDelay(10));
        } catch (final RuntimeException exception) {
            if (!removed) {
                spawned.forEach(drop -> drop.entity().remove());
                rollback(operation, empty, new ItemStack[barrel.getInventory().getSize()]);
            }
            fail(operation, exception);
            return;
        }
        try {
            context.removeRendersAt(barrel);
        } catch (final RuntimeException exception) {
            context.logger().warning("Could not remove drawer display at " + operation.location() + ": "
                    + exception.getMessage());
        }
        resync(operation);
    }

    private void spawn(final Operation operation, final ItemStack stack, final List<Drop> spawned) {
        final Location location = operation.location().clone().add(0.5D, 0.5D, 0.5D);
        final Item entity = location.getWorld().dropItem(location, stack.clone(), item -> {
            // Capture the entity before any ItemSpawnEvent listener runs, including listeners that throw.
            spawned.add(new Drop(item, stack.clone()));
            // Mark this as a native player-thrown item before ItemSpawnEvent. Drop-to-inventory
            // plugins conventionally leave player-thrown items alone, so Drawer retains full
            // ownership of its guarded world-drop transaction without a plugin API dependency.
            item.setThrower(operation.player().getUniqueId());
            item.setPickupDelay(Integer.MAX_VALUE);
        });
        if (entity == null) {
            throw new IllegalStateException("The server returned no dropped item entity");
        }
        validate(new Drop(entity, stack));
    }

    private static void validate(final Drop drop) {
        if (!drop.entity().isValid()) {
            throw new IllegalStateException("Item spawn was rejected or its entity was removed");
        }
        final ItemStack actual = drop.entity().getItemStack();
        if (actual.getAmount() != drop.expected().getAmount() || !actual.isSimilar(drop.expected())) {
            throw new IllegalStateException("A spawn listener changed the dropped item's amount or data");
        }
    }

    private void requireJournal(final Barrel barrel, final DrawerProxyJournal expected, final DrawerJournalPhase phase) {
        final Operation operation = operations.get(key(barrel.getLocation()));
        if (!enabled || operation == null || !operation.id().equals(expected.operationId())) {
            throw new IllegalStateException("The drawer break was interrupted");
        }
        final DrawerProxyJournal actual = read(barrel).proxyJournal().orElseThrow();
        if (!actual.equals(expected.withPhase(phase))) {
            throw new IllegalStateException("The drawer changed while its items were spawning");
        }
    }

    private Barrel current(final Operation operation) {
        final Barrel barrel = context.blocks().barrel(operation.location().getBlock()).orElseThrow(
                () -> new IllegalStateException("The drawer block is no longer present"));
        if (!context.repository().isDrawer(barrel)) {
            throw new IllegalStateException("The block is no longer a drawer");
        }
        return barrel;
    }

    private DrawerState read(final Barrel barrel) {
        if (context.repository().read(barrel) instanceof DrawerStateReadResult.Valid valid) {
            return valid.state();
        }
        throw new IllegalStateException("The drawer state is invalid");
    }

    private void rollback(final Operation operation, final DrawerState baseline, final ItemStack[] physicalBefore) {
        try {
            final Barrel live = current(operation);
            final DrawerProxyJournal journal = read(live).proxyJournal().orElseThrow();
            if (!journal.operationId().equals(operation.id()) || journal.kind() != DrawerJournalKind.WORLD_DROP) {
                throw new IllegalStateException("The pending drop journal changed before rollback");
            }
            live.getInventory().setContents(physicalBefore);
            final Barrel restored = current(operation);
            if (!context.repository().save(restored, baseline.withoutProxyJournal())) {
                throw new IllegalStateException("Could not restore the drawer stock");
            }
        } catch (final RuntimeException exception) {
            context.logger().severe("Drawer item-drop rollback requires recovery at " + operation.location()
                    + ": " + exception.getMessage());
        }
    }

    private void fail(final Operation operation, final RuntimeException exception) {
        operations.remove(key(operation.location()), operation);
        final Barrel barrel = context.blocks().barrel(operation.location().getBlock()).orElse(null);
        if (barrel != null && context.repository().read(barrel) instanceof DrawerStateReadResult.Valid valid) {
            recoverInterrupted(barrel, valid.state());
            hoppers.watch(barrel);
        }
        context.logger().warning("Stopped drawer break at " + operation.location() + ": " + exception.getMessage()
                + "; undelivered contents remain in the drawer");
        context.execution().runOnEntity(operation.player(), task -> operation.player().sendMessage(
                context.messages().component("drawer.break-unavailable")), () -> { });
        resync(operation);
    }

    private void resync(final Operation operation) {
        context.execution().runLaterAt(operation.location(), 1L, task -> {
            final var actual = operation.location().getBlock().getBlockData().clone();
            context.execution().runOnEntity(operation.player(), ignored -> operation.player().sendBlockChange(
                    operation.location(), actual), () -> { });
        });
    }

    private static ItemStack[] cloneContents(final ItemStack[] contents) {
        final ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static DrawerBreakTicket.BlockKey key(final Location location) {
        return new DrawerBreakTicket.BlockKey(location.getWorld().getUID(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ());
    }

    private record Operation(UUID id, Player player, Location location, ItemStack drawerItem) { }
    private record Drop(Item entity, ItemStack expected) { }
}
