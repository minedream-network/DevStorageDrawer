package com.teenkung.devstoragedrawer.interaction;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.domain.DrawerJournalKind;
import com.teenkung.devstoragedrawer.domain.DrawerJournalPhase;
import com.teenkung.devstoragedrawer.domain.DrawerProxyJournal;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.hopper.DrawerProxyInventory;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Barrel;
import org.bukkit.inventory.ItemStack;

/** Guards stock while vanilla executes a container move, then accounts its actual slot delta. */
final class DrawerNativeInventoryMove {
    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;

    DrawerNativeInventoryMove(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers) {
        this.context = context;
        this.hoppers = hoppers;
    }

    Ticket prepare(final DrawerPagedInventory.Ready ready, final UUID player,
            final ItemStack template, final int limit, final int visibleBefore) {
        if (limit == 0) return null;
        final DrawerState original = ready.state().migrateToReservedMirror(ready.stock().count())
                .withStoredTotalAndExpectedMirrorCount(ready.total(), ready.stock().count());
        DrawerState baseline = original;
        if (!baseline.hasTemplate()) {
            final var tier = context.tier(baseline.tierId()).orElse(null);
            if (tier == null || limit < 0) return null;
            baseline = baseline.initializeCapacityForFirstItem(tier.tier(), template).withTemplate(template);
        }
        if (!baseline.matchesTemplate(template)) return null;
        final long target = Math.addExact(ready.total(), limit);
        if (target < 0 || target > baseline.capacitySnapshot()) return null;
        final var journal = new DrawerProxyJournal(UUID.randomUUID(), DrawerJournalKind.GUARDED_PLAYER_TRANSFER,
                DrawerJournalPhase.PREPARED, player, ready.total(), target, ready.stock().count(),
                Math.min(ready.stock().count(), target), System.currentTimeMillis());
        if (!context.repository().save(ready.barrel(), baseline.withProxyJournal(journal))) return null;
        final var ticket = new Ticket(ready.barrel().getLocation(), original, baseline, template.clone(), journal,
                limit, visibleBefore);
        hoppers.beginNativePlayerTransfer(journal.operationId());
        return ticket;
    }

    boolean complete(final Ticket ticket, final int visibleAfter) {
        try { return commit(ticket, visibleAfter); }
        finally { hoppers.endNativePlayerTransfer(ticket.journal().operationId()); }
    }

    private boolean commit(final Ticket ticket, final int visibleAfter) {
        final int delta = visibleAfter - ticket.visibleBefore();
        final int min = Math.min(0, ticket.limit());
        final int max = Math.max(0, ticket.limit());
        if (visibleAfter < 0 || delta < min || delta > max) return unresolved(ticket, "Unexpected native slot delta " + delta);
        try {
            final Barrel barrel = context.blocks().barrel(ticket.location().getBlock()).orElse(null);
            if (barrel == null || !(context.repository().read(barrel) instanceof DrawerStateReadResult.Valid valid)
                    || valid.state().proxyJournal().filter(j -> j.operationId().equals(ticket.journal().operationId())).isEmpty()) {
                return unresolved(ticket, "The transfer journal is no longer available");
            }
            final var stock = context.blocks().inspect(barrel, valid.state());
            if (!stock.matchesTemplate() || stock.count() != ticket.journal().physicalBefore()) {
                return unresolved(ticket, "The physical mirror changed during a native move");
            }
            if (delta == 0) {
                // A later listener cancelled the move, or vanilla found no space. No items moved.
                if (!context.repository().save(barrel, ticket.original())) return unresolved(ticket, "Could not clear unused intent");
                hoppers.queueRebalance(barrel);
                return true;
            }
            final long total = Math.addExact(ticket.journal().totalBefore(), delta);
            final long physical = Math.min(ticket.journal().physicalBefore(), total);
            final var journal = new DrawerProxyJournal(ticket.journal().operationId(), DrawerJournalKind.GUARDED_PLAYER_TRANSFER,
                    DrawerJournalPhase.PREPARED, ticket.journal().ownerId(), ticket.journal().totalBefore(), total,
                    ticket.journal().physicalBefore(), physical, ticket.journal().createdAtEpochMillis());
            if (delta != ticket.limit() && !context.repository().save(barrel, ticket.baseline().withProxyJournal(journal))) {
                return unresolved(ticket, "Could not persist actual move intent");
            }
            final long removed = ticket.journal().physicalBefore() - physical;
            if (removed > 0 && !DrawerProxyInventory.remove(barrel, ticket.template(), removed)) {
                return unresolved(ticket, "Could not debit physical stock after native delivery");
            }
            // Native delivery already happened. Failures must retain the guard, never restore spent stock.
            final Barrel fresh = context.blocks().barrel(ticket.location().getBlock()).orElse(null);
            final DrawerState after = ticket.baseline().withStoredTotalAndExpectedMirrorCount(total, physical);
            if (fresh == null || !context.repository().save(fresh, after.withProxyJournal(journal.withPhase(DrawerJournalPhase.APPLIED)))) {
                return unresolved(ticket, "Could not persist actual stock after native delivery");
            }
            final DrawerState committed = total == 0 ? after.withoutTemplate() : after;
            final Barrel finalSnapshot = context.blocks().barrel(ticket.location().getBlock()).orElse(null);
            if (finalSnapshot == null || !context.repository().save(finalSnapshot, committed)) {
                return unresolved(ticket, "Could not finalize native delivery");
            }
            try {
                context.notifyCommitted(finalSnapshot, committed, physical,
                        delta > 0 ? DrawerChangeCause.PLAYER_DEPOSIT : DrawerChangeCause.PLAYER_WITHDRAW);
                hoppers.queueRebalance(finalSnapshot);
            } catch (final RuntimeException exception) {
                context.logger().warning("Native drawer move saved but refresh failed: " + exception.getMessage());
            }
            return true;
        } catch (final RuntimeException exception) {
            return unresolved(ticket, exception.getMessage());
        }
    }

    private boolean unresolved(final Ticket ticket, final String reason) {
        context.logger().warning("Native drawer transfer requires reconciliation at " + ticket.location()
                + " operation=" + ticket.journal().operationId() + ": " + reason);
        return false;
    }

    record Ticket(Location location, DrawerState original, DrawerState baseline, ItemStack template,
            DrawerProxyJournal journal, int limit, int visibleBefore) { }
}
