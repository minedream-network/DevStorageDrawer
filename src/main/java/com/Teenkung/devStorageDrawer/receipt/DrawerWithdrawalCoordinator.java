package com.teenkung.devstoragedrawer.receipt;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.block.DrawerBlockAccess;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.domain.DrawerItemIdentity;
import com.teenkung.devstoragedrawer.domain.DrawerJournalKind;
import com.teenkung.devstoragedrawer.domain.DrawerJournalReconciliation;
import com.teenkung.devstoragedrawer.domain.DrawerJournalPhase;
import com.teenkung.devstoragedrawer.domain.DrawerProxyJournal;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.domain.DrawerStorageTransaction;
import com.teenkung.devstoragedrawer.domain.DrawerWithdrawalPlan;
import com.teenkung.devstoragedrawer.hopper.DrawerProxyInventory;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Coordinates drawer journals, the fsync-backed receipt ledger, and idempotent player delivery.
 * Bukkit state is touched only on region/entity schedulers; ledger mutation is async-only.
 */
public final class DrawerWithdrawalCoordinator implements Listener {
    private static final NamespacedKey CLAIMED_RECEIPTS = new NamespacedKey(
            "devstoragedrawer",
            "claimed_withdrawal_receipts"
    );

    private final DrawerRuntimeContext context;
    private final WithdrawalReceiptStore store;
    private final Set<UUID> inFlightOperations = ConcurrentHashMap.newKeySet();
    /**
     * Process-local proof that this exact operation cleared its fully applied drawer journal.
     * A DELIVERABLE ledger record is written before that clear, so it is not sufficient proof by
     * itself after a restart. A persisted player claim marker is the only restart-safe substitute.
     */
    private final Set<UUID> verifiedForDelivery = ConcurrentHashMap.newKeySet();
    private final Set<UUID> uncertainPlayerSaves = ConcurrentHashMap.newKeySet();
    private Consumer<Barrel> rebalanceRequester = barrel -> { };
    private boolean requesterConfigured;

    public DrawerWithdrawalCoordinator(
            final DrawerRuntimeContext context,
            final WithdrawalReceiptStore store
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.store = Objects.requireNonNull(store, "store");
    }

    public void setRebalanceRequester(final Consumer<Barrel> requester) {
        if (requesterConfigured) {
            throw new IllegalStateException("Withdrawal rebalance requester is already configured");
        }
        this.rebalanceRequester = Objects.requireNonNull(requester, "requester");
        this.requesterConfigured = true;
    }

    /** Starts a durable withdrawal while called on the drawer's owning region. */
    public boolean begin(
            final Player player,
            final Barrel barrel,
            final DrawerStorageTransaction transaction
    ) {
        final DrawerWithdrawalPlan plan = DrawerWithdrawalPlan.forTransaction(
                transaction,
                player.getUniqueId(),
                System.currentTimeMillis()
        );
        if (!context.repository().save(barrel, plan.journaledState())) {
            return false;
        }
        inFlightOperations.add(plan.journal().operationId());

        final Location location = barrel.getLocation();
        final WithdrawalReceipt receipt = new WithdrawalReceipt(
                plan.journal().operationId(),
                player.getUniqueId(),
                barrel.getWorld().getUID(),
                barrel.getX(),
                barrel.getY(),
                barrel.getZ(),
                transaction.itemTemplate().orElseThrow().serializeAsBytes(),
                transaction.acceptedAmount(),
                WithdrawalReceiptStatus.PREPARED,
                plan.journal().createdAtEpochMillis()
        );
        context.execution().runAsync(task -> {
            try {
                store.prepare(receipt);
                context.execution().executeAt(location, () -> applyPrepared(player, location, transaction, plan));
            } catch (final IOException exception) {
                context.logger().severe("Could not persist withdrawal receipt " + receipt.operationId() + ": "
                        + exception.getMessage());
                context.execution().executeAt(location, () -> rollbackPrepared(location, plan.journal(), player));
            }
        });
        return true;
    }

    /** Returns whether this server is still advancing the drawer's durable withdrawal protocol. */
    public boolean isWithdrawalInProgress(final Barrel barrel) {
        final DrawerStateReadResult read = context.repository().read(barrel);
        return read instanceof DrawerStateReadResult.Valid valid
                && valid.state().proxyJournal()
                .filter(journal -> journal.kind() == DrawerJournalKind.PLAYER_WITHDRAWAL)
                .map(journal -> inFlightOperations.contains(journal.operationId()))
                .orElse(false);
    }

    /** Routes a player-withdrawal journal through its receipt protocol during chunk repair. */
    public void recover(
            final Barrel barrel,
            final DrawerState state,
            final long observedPhysical,
            final Runnable resume
    ) {
        final DrawerProxyJournal journal = state.proxyJournal().orElse(null);
        if (journal == null || journal.kind() != DrawerJournalKind.PLAYER_WITHDRAWAL) {
            throw new IllegalArgumentException("Withdrawal recovery requires a player-withdrawal journal");
        }
        // A live operation has already persisted its PREPARED journal and is waiting for its
        // receipt fsync. Treating that journal as a crash here would remove the receipt while the
        // original operation is still about to commit it.
        if (inFlightOperations.contains(journal.operationId())) {
            return;
        }
        final DrawerJournalReconciliation reconciliation = context.storage().reconcile(state, observedPhysical);
        if (reconciliation.requiresManualRecovery()
                || reconciliation.resolution() == DrawerJournalReconciliation.Resolution.PARTIALLY_APPLIED) {
            context.logger().warning("Quarantined unresolved withdrawal journal " + journal.operationId()
                    + " at " + barrel.getLocation());
            return;
        }
        if (reconciliation.resolution() == DrawerJournalReconciliation.Resolution.ROLLED_BACK) {
            persistRollback(barrel.getLocation(), journal, reconciliation.state(), resume);
            return;
        }

        final WithdrawalReceipt receipt = store.find(journal.operationId()).orElse(null);
        if (receipt == null) {
            // An older chunk save may retain APPLIED after the player received the items.
            // Missing delivery evidence cannot prove that issuing a replacement is safe.
            context.logger().severe("Quarantined withdrawal " + journal.operationId()
                    + " at " + barrel.getLocation() + " because its durable receipt is missing;"
                    + " delivery must be verified before manual recovery");
            return;
        }
        if (!receiptMatchesJournal(receipt, journal, barrel.getLocation())) {
            context.logger().severe("Quarantined committed withdrawal " + journal.operationId()
                    + " because its durable receipt does not match the drawer journal or location");
            return;
        }
        persistCommit(barrel.getLocation(), journal, resume);
    }

    private static boolean receiptMatchesJournal(
            final WithdrawalReceipt receipt,
            final DrawerProxyJournal journal,
            final Location location
    ) {
        return receipt.ownerId().equals(journal.ownerId())
                && journal.totalBefore() > journal.totalAfter()
                && receipt.count() == journal.totalBefore() - journal.totalAfter()
                && receipt.createdAtEpochMillis() == journal.createdAtEpochMillis()
                && location.getWorld() != null
                && receipt.worldId().equals(location.getWorld().getUID())
                && receipt.blockX() == location.getBlockX()
                && receipt.blockY() == location.getBlockY()
                && receipt.blockZ() == location.getBlockZ();
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        recoverPreparedFor(player);
    }

    private void applyPrepared(
            final Player player,
            final Location location,
            final DrawerStorageTransaction transaction,
            final DrawerWithdrawalPlan plan
    ) {
        final Barrel barrel = liveJournaledBarrel(location, plan.journal().operationId());
        if (barrel == null) {
            removeReceiptAsync(plan.journal().operationId());
            message(player, "general.configuration-error", Map.of());
            return;
        }
        final DrawerStateReadResult read = context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            finishOperation(plan.journal().operationId());
            message(player, "general.configuration-error", Map.of());
            return;
        }
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, valid.state());
        if (!stock.matchesTemplate() || stock.count() != transaction.physicalBefore()) {
            rollbackPrepared(location, plan.journal(), player);
            return;
        }

        final long physicalTaken = transaction.physicalBefore() - transaction.physicalAfter();
        if (physicalTaken > 0L && !DrawerProxyInventory.remove(
                barrel,
                transaction.itemTemplate().orElseThrow(),
                physicalTaken
        )) {
            rollbackPrepared(location, plan.journal(), player);
            return;
        }
        // Reacquire after proxy removal; saving the older Barrel snapshot could otherwise restore
        // the inventory contents it held before DrawerProxyInventory.remove.
        final Barrel afterRemoval = context.blocks().barrel(location.getBlock()).orElse(null);
        final boolean appliedSaved = afterRemoval != null
                && context.repository().save(afterRemoval, plan.appliedJournaledState());
        if (!appliedSaved && physicalTaken == 0L) {
            rollbackPrepared(location, plan.journal(), player);
            return;
        }
        if (!appliedSaved) {
            context.logger().warning("Withdrawal " + plan.journal().operationId()
                    + " changed proxy stock before its APPLIED phase could be saved; recovery will complete it");
        }
        persistCommit(location, plan.journal(), () -> {
            final Barrel live = context.blocks().barrel(location.getBlock()).orElse(null);
            if (live != null) {
                rebalanceRequester.accept(live);
            }
        });
    }

    private void persistCommit(
            final Location location,
            final DrawerProxyJournal journal,
            final Runnable resume
    ) {
        context.execution().runAsync(task -> {
            try {
                store.markDeliverable(journal.operationId());
                context.execution().executeAt(location, () -> {
                    try {
                        final Barrel barrel = liveJournaledBarrel(location, journal.operationId());
                        if (barrel == null) {
                            return;
                        }
                        final DrawerStateReadResult read = context.repository().read(barrel);
                        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
                            return;
                        }
                        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, valid.state());
                        if (!stock.matchesTemplate()) {
                            return;
                        }
                        final DrawerJournalReconciliation fresh = context.storage().reconcile(valid.state(), stock.count());
                        if (fresh.requiresManualRecovery()
                                || fresh.resolution() == DrawerJournalReconciliation.Resolution.ROLLED_BACK
                                || fresh.resolution() == DrawerJournalReconciliation.Resolution.PARTIALLY_APPLIED) {
                            context.logger().severe("Withdrawal " + journal.operationId()
                                    + " became inconsistent after its receipt was made deliverable");
                            return;
                        }
                        final DrawerState resolved = fresh.state();
                        if (context.repository().save(barrel, resolved)) {
                            verifiedForDelivery.add(journal.operationId());
                            context.notifyCommitted(barrel, resolved, stock.count(), DrawerChangeCause.PLAYER_WITHDRAW);
                            resume.run();
                            final Player owner = Bukkit.getPlayer(journal.ownerId());
                            if (owner != null) {
                                attemptDelivery(owner, journal.operationId());
                            }
                        }
                    } finally {
                        finishOperation(journal.operationId());
                    }
                });
            } catch (final IOException exception) {
                finishOperation(journal.operationId());
                context.logger().severe("Could not make withdrawal receipt " + journal.operationId()
                        + " deliverable: " + exception.getMessage());
            }
        });
    }

    private void persistRollback(
            final Location location,
            final DrawerProxyJournal journal,
            final DrawerState rolledBackState,
            final Runnable resume
    ) {
        context.execution().runAsync(task -> {
            try {
                store.remove(journal.operationId());
                context.execution().executeAt(location, () -> {
                    try {
                        final Barrel barrel = liveJournaledBarrel(location, journal.operationId());
                        if (barrel != null && context.repository().save(barrel, rolledBackState)) {
                            final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, rolledBackState);
                            context.notifyCommitted(barrel, rolledBackState, stock.count(), DrawerChangeCause.RECOVERY);
                            resume.run();
                        }
                    } finally {
                        finishOperation(journal.operationId());
                    }
                });
            } catch (final IOException exception) {
                finishOperation(journal.operationId());
                context.logger().severe("Could not roll back withdrawal receipt " + journal.operationId()
                        + ": " + exception.getMessage());
            }
        });
    }

    private void rollbackPrepared(
            final Location location,
            final DrawerProxyJournal journal,
            final Player player
    ) {
        final Barrel barrel = liveJournaledBarrel(location, journal.operationId());
        if (barrel == null) {
            removeReceiptAsync(journal.operationId());
            message(player, "general.configuration-error", Map.of());
            return;
        }
        final DrawerStateReadResult read = context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            finishOperation(journal.operationId());
            message(player, "general.configuration-error", Map.of());
            return;
        }
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, valid.state());
        final DrawerJournalReconciliation reconciliation = context.storage().reconcile(valid.state(), stock.count());
        if (reconciliation.resolution() != DrawerJournalReconciliation.Resolution.ROLLED_BACK) {
            // This path needs normal recovery to finish the protocol, so it must no longer be
            // treated as the live operation that originally requested the rollback.
            finishOperation(journal.operationId());
            recover(barrel, valid.state(), stock.count(), () -> rebalanceRequester.accept(barrel));
            return;
        }
        persistRollback(location, journal, reconciliation.state(), () -> message(
                player,
                "general.configuration-error",
                Map.of()
        ));
    }

    private void recoverPreparedFor(final Player player) {
        for (final WithdrawalReceipt receipt : store.forOwner(player.getUniqueId())) {
            if (receipt.status() != WithdrawalReceiptStatus.PREPARED
                    && receipt.status() != WithdrawalReceiptStatus.DELIVERABLE) {
                continue;
            }
            final World world = Bukkit.getWorld(receipt.worldId());
            if (world == null) {
                continue;
            }
            final Location location = new Location(world, receipt.blockX(), receipt.blockY(), receipt.blockZ());
            context.execution().executeAt(location, () -> {
                final Barrel barrel = context.blocks().barrel(location.getBlock()).orElse(null);
                if (barrel == null || !context.repository().isDrawer(barrel)) {
                    context.logger().severe("Kept withdrawal receipt " + receipt.operationId()
                            + " because its drawer is unavailable; run /drawers repair after restoring the block");
                    return;
                }
                final DrawerStateReadResult read = context.repository().read(barrel);
                if (!(read instanceof DrawerStateReadResult.Valid valid)) {
                    return;
                }
                final DrawerProxyJournal journal = valid.state().proxyJournal().orElse(null);
                if (journal == null) {
                    if (receipt.status() == WithdrawalReceiptStatus.DELIVERABLE) {
                        // DELIVERABLE is persisted before the journal is cleared. After a restart,
                        // a journal-free chunk can be either the committed state or an older
                        // pre-debit snapshot. Only a persisted player claim may finish it safely.
                        attemptDelivery(player, receipt.operationId());
                    } else {
                        context.logger().severe("Kept prepared withdrawal receipt " + receipt.operationId()
                                + " because its drawer journal is missing");
                    }
                    return;
                }
                if (journal.kind() != DrawerJournalKind.PLAYER_WITHDRAWAL
                        || !journal.operationId().equals(receipt.operationId())) {
                    context.logger().severe("Kept withdrawal receipt " + receipt.operationId()
                            + " because the drawer has a different pending journal");
                    return;
                }
                final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, valid.state());
                recover(barrel, valid.state(), stock.count(), () -> rebalanceRequester.accept(barrel));
            });
        }
    }

    private void attemptDelivery(final Player player, final UUID operationId) {
        if (uncertainPlayerSaves.contains(operationId)) {
            return;
        }
        context.execution().runOnEntity(player,
                task -> deliverOnEntityThread(player, operationId), () -> { });
    }

    private void deliverOnEntityThread(final Player player, final UUID operationId) {
        if (!player.isOnline() || uncertainPlayerSaves.contains(operationId)) {
            return;
        }
        final boolean mayIssueItems = verifiedForDelivery.contains(operationId);
        final List<WithdrawalReceipt> deliverable = store.forOwner(player.getUniqueId()).stream()
                .filter(receipt -> receipt.status() == WithdrawalReceiptStatus.DELIVERABLE
                        && receipt.operationId().equals(operationId))
                .toList();
        if (deliverable.isEmpty()) {
            return;
        }

        final PersistentDataContainer pdc = player.getPersistentDataContainer();
        final String claimedBefore = pdc.get(CLAIMED_RECEIPTS, PersistentDataType.STRING);
        final Set<UUID> claimed = parseClaimed(claimedBefore);
        final ItemStack[] inventoryBefore = mayIssueItems
                ? cloneContents(player.getInventory().getStorageContents()) : null;
        final List<UUID> acknowledgements = new ArrayList<>();
        long deliveredCount = 0L;
        boolean quarantinedForMissingProof = false;
        boolean startedSave = false;
        try {
            for (final WithdrawalReceipt receipt : deliverable) {
                if (claimed.contains(receipt.operationId())) {
                    acknowledgements.add(receipt.operationId());
                    continue;
                }
                if (!mayIssueItems) {
                    quarantinedForMissingProof = true;
                    context.logger().severe("Quarantined deliverable withdrawal " + operationId + " for "
                            + player.getName() + " because neither a cleared journal in this server process nor a"
                            + " persisted player claim proves that issuing the items is safe");
                    continue;
                }
                final ItemStack template = ItemStack.deserializeBytes(receipt.templateBytes());
                DrawerItemIdentity.requireStorageCandidate(template, "Withdrawal receipt template");
                if (availableSpace(player.getInventory(), template) < receipt.count()) {
                    continue;
                }
                giveExact(player.getInventory(), template, receipt.count());
                claimed.add(receipt.operationId());
                acknowledgements.add(receipt.operationId());
                deliveredCount += receipt.count();
            }
            if (acknowledgements.isEmpty()) {
                if (!quarantinedForMissingProof) {
                    message(player, "drawer.withdrawal-pending", Map.of());
                }
                return;
            }
            writeClaimed(pdc, claimed);
            // Inventory and the idempotency marker are persisted in the same playerdata save.
            startedSave = true;
            player.saveData();
        } catch (final RuntimeException exception) {
            if (startedSave) {
                uncertainPlayerSaves.add(operationId);
                context.logger().severe("Quarantined withdrawal " + operationId + " for " + player.getName()
                        + " after an uncertain playerdata save: " + exception.getMessage());
            } else {
                if (inventoryBefore != null) {
                    player.getInventory().setStorageContents(inventoryBefore);
                }
                restoreClaimed(pdc, claimedBefore);
                context.logger().severe("Could not deliver withdrawal receipts to " + player.getName()
                        + ": " + exception.getMessage());
            }
            return;
        }

        final long delivered = deliveredCount;
        context.execution().runAsync(task -> {
            try {
                store.completeAll(acknowledgements);
                verifiedForDelivery.removeAll(acknowledgements);
                context.execution().runOnEntity(player, ignored -> {
                    final Set<UUID> remainingClaims = parseClaimed(
                            player.getPersistentDataContainer().get(CLAIMED_RECEIPTS, PersistentDataType.STRING)
                    );
                    remainingClaims.removeAll(acknowledgements);
                    writeClaimed(player.getPersistentDataContainer(), remainingClaims);
                    if (delivered > 0L) {
                        message(player, "drawer.withdrawn", Map.of("amount", delivered));
                    }
                }, () -> { });
            } catch (final IOException exception) {
                context.logger().severe("Could not acknowledge delivered withdrawal receipts for "
                        + player.getName() + ": " + exception.getMessage());
            }
        });
    }

    private Barrel liveJournaledBarrel(final Location location, final UUID operationId) {
        final Barrel barrel = context.blocks().barrel(location.getBlock()).orElse(null);
        if (barrel == null || !context.repository().isDrawer(barrel)) {
            return null;
        }
        final DrawerStateReadResult read = context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            return null;
        }
        return valid.state().proxyJournal()
                .filter(journal -> journal.operationId().equals(operationId))
                .map(ignored -> barrel)
                .orElse(null);
    }

    private void removeReceiptAsync(final UUID operationId) {
        context.execution().runAsync(task -> {
            try {
                store.remove(operationId);
            } catch (final IOException exception) {
                context.logger().warning("Could not remove withdrawal receipt " + operationId + ": "
                        + exception.getMessage());
            } finally {
                finishOperation(operationId);
            }
        });
    }

    private void finishOperation(final UUID operationId) {
        inFlightOperations.remove(operationId);
    }

    private void message(final Player player, final String key, final Map<String, ?> placeholders) {
        context.execution().runOnEntity(
                player,
                task -> player.sendMessage(context.messages().component(key, placeholders)),
                () -> { }
        );
    }

    private static Set<UUID> parseClaimed(final String raw) {
        final Set<UUID> claimed = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return claimed;
        }
        for (final String token : raw.split(",")) {
            try {
                claimed.add(UUID.fromString(token));
            } catch (final IllegalArgumentException ignored) {
                // A malformed private marker is discarded on the next successful delivery.
            }
        }
        return claimed;
    }

    private static void writeClaimed(final PersistentDataContainer pdc, final Collection<UUID> claimed) {
        if (claimed.isEmpty()) {
            pdc.remove(CLAIMED_RECEIPTS);
        } else {
            pdc.set(CLAIMED_RECEIPTS, PersistentDataType.STRING, claimed.stream()
                    .map(UUID::toString)
                    .sorted()
                    .reduce((left, right) -> left + "," + right)
                    .orElseThrow());
        }
    }

    private static void restoreClaimed(final PersistentDataContainer pdc, final String raw) {
        if (raw == null) {
            pdc.remove(CLAIMED_RECEIPTS);
        } else {
            pdc.set(CLAIMED_RECEIPTS, PersistentDataType.STRING, raw);
        }
    }

    private static long availableSpace(final PlayerInventory inventory, final ItemStack template) {
        long available = 0L;
        final int maximum = template.getMaxStackSize();
        for (final ItemStack current : inventory.getStorageContents()) {
            if (current == null || current.getType().isAir()) {
                available += maximum;
            } else if (current.isSimilar(template)) {
                available += Math.max(0, maximum - current.getAmount());
            }
        }
        return available;
    }

    private static void giveExact(final PlayerInventory inventory, final ItemStack template, long amount) {
        final ItemStack[] contents = inventory.getStorageContents();
        final int maximum = template.getMaxStackSize();
        for (int slot = 0; slot < contents.length && amount > 0L; slot++) {
            final ItemStack current = contents[slot];
            if (current == null || current.getType().isAir()) {
                final int placed = (int) Math.min(amount, (long) maximum);
                final ItemStack stack = template.clone();
                stack.setAmount(placed);
                contents[slot] = stack;
                amount -= placed;
            } else if (current.isSimilar(template) && current.getAmount() < maximum) {
                final int placed = (int) Math.min(amount, (long) (maximum - current.getAmount()));
                current.setAmount(current.getAmount() + placed);
                amount -= placed;
            }
        }
        if (amount != 0L) {
            throw new IllegalStateException("Inventory capacity changed during withdrawal receipt delivery");
        }
        inventory.setStorageContents(contents);
    }

    private static ItemStack[] cloneContents(final ItemStack[] contents) {
        final ItemStack[] clone = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            clone[index] = contents[index] == null ? null : contents[index].clone();
        }
        return clone;
    }
}
