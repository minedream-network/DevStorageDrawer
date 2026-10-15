package com.teenkung.devstoragedrawer.interaction;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.block.DrawerBlockAccess;
import com.teenkung.devstoragedrawer.block.DrawerRuntime;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.domain.DrawerInvariantViolationException;
import com.teenkung.devstoragedrawer.domain.DrawerJournalKind;
import com.teenkung.devstoragedrawer.domain.DrawerJournalPhase;
import com.teenkung.devstoragedrawer.domain.DrawerProxyJournal;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.domain.DrawerStorageTransaction;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.receipt.DrawerWithdrawalCoordinator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Player-facing single-item drawer interaction and ordinary container controls over real stock. */
public final class DrawerInteractionListener implements Listener {

    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;
    private final DrawerWithdrawalCoordinator withdrawals;
    private final DrawerRuntime.DrawerDisplayTargetResolver displayTargetResolver;
    private final DrawerWithdrawalProtection withdrawalProtection;
    private final DrawerUpgradeOpener upgradeOpener;
    private final DrawerChestInventory chestInventory;
    private final Map<UUID, BulkDepositArm> bulkDepositArms = new ConcurrentHashMap<>();
    private final Map<UUID, PendingBlockWithdrawal> pendingBlockWithdrawals = new ConcurrentHashMap<>();

    public DrawerInteractionListener(
            final DrawerRuntimeContext context,
            final DrawerHopperBridge hoppers,
            final DrawerWithdrawalCoordinator withdrawals,
            final DrawerRuntime.DrawerDisplayTargetResolver displayTargetResolver,
            final DrawerWithdrawalProtection withdrawalProtection
    ) {
        this(context, hoppers, withdrawals, displayTargetResolver, withdrawalProtection, (player, barrel) -> { });
    }

    public DrawerInteractionListener(
            final DrawerRuntimeContext context,
            final DrawerHopperBridge hoppers,
            final DrawerWithdrawalCoordinator withdrawals,
            final DrawerRuntime.DrawerDisplayTargetResolver displayTargetResolver,
            final DrawerWithdrawalProtection withdrawalProtection,
            final DrawerUpgradeOpener upgradeOpener
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.hoppers = Objects.requireNonNull(hoppers, "hoppers");
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals");
        this.displayTargetResolver = Objects.requireNonNull(displayTargetResolver, "displayTargetResolver");
        this.withdrawalProtection = Objects.requireNonNull(withdrawalProtection, "withdrawalProtection");
        this.upgradeOpener = Objects.requireNonNull(upgradeOpener, "upgradeOpener");
        this.chestInventory = new DrawerChestInventory(context, hoppers, withdrawalProtection, this.upgradeOpener);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        final Barrel barrel = this.context.blocks().barrel(clicked).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Do not cancel the attack. Releasing it produces BlockDamageAbortEvent and withdraws;
            // completing it produces BlockBreakEvent and discards the pending withdrawal.
            if (!shortcutWithdrawalsEnabled()) {
                this.pendingBlockWithdrawals.remove(event.getPlayer().getUniqueId());
                return;
            }
            if (!canUse(event.getPlayer())) {
                return;
            }
            if (this.context.settings().interaction().requireFacingFace() && !isFacingFace(barrel, event.getBlockFace())) {
                return;
            }
            if (!canWithdrawWith(barrel, heldItem(event.getPlayer(), event.getHand()))) {
                return;
            }
            armBlockWithdrawal(event.getPlayer(), barrel, event.getPlayer().isSneaking());
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            deny(event, null);
            return;
        }
        if (!canUse(event.getPlayer())) {
            deny(event, "general.no-permission");
            return;
        }
        if (this.context.settings().interaction().requireFacingFace() && !isFacingFace(barrel, event.getBlockFace())) {
            // Vanilla does not open a container while the player is sneaking. Let a sneaking
            // player with an item use the side normally so blocks can be placed against drawers.
            if (event.getPlayer().isSneaking() && isUsableItem(heldItem(event.getPlayer(), event.getHand()))) {
                return;
            }
            deny(event, "drawer.interaction-face-only");
            return;
        }
        if (event.getPlayer().isSneaking()
                && isUsableItem(heldItem(event.getPlayer(), event.getHand()))
                && !shortcutDepositsEnabled()) {
            this.bulkDepositArms.remove(event.getPlayer().getUniqueId());
            return;
        }
        deny(event, null);
        rightClick(event.getPlayer(), barrel, event.getHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDisplayUse(final PlayerInteractAtEntityEvent event) {
        final Barrel barrel = this.displayTargetResolver.resolve(event.getRightClicked()).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        if (!canUse(event.getPlayer())) {
            message(event.getPlayer(), "general.no-permission");
            return;
        }
        if (event.getPlayer().isSneaking()
                && isUsableItem(heldItem(event.getPlayer(), event.getHand()))
                && !shortcutDepositsEnabled()) {
            this.bulkDepositArms.remove(event.getPlayer().getUniqueId());
            return;
        }
        rightClick(event.getPlayer(), barrel, event.getHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDisplayAttack(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        final Barrel barrel = this.displayTargetResolver.resolve(event.getEntity()).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return;
        }
        event.setCancelled(true);
        if (!shortcutWithdrawalsEnabled()) {
            this.pendingBlockWithdrawals.remove(player.getUniqueId());
            return;
        }
        if (!canUse(player)) {
            message(player, "general.no-permission");
            return;
        }
        if (!canWithdrawWith(barrel, player.getInventory().getItemInMainHand())) {
            return;
        }
        withdraw(player, barrel, player.isSneaking());
    }

    /** A released quick attack is a withdrawal; a held attack continues toward BlockBreakEvent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamageAbort(final BlockDamageAbortEvent event) {
        final Player player = event.getPlayer();
        final PendingBlockWithdrawal pending = this.pendingBlockWithdrawals.remove(player.getUniqueId());
        if (pending == null || !pending.matches(event.getBlock()) || pending.expired()) {
            return;
        }
        if (!shortcutWithdrawalsEnabled()) {
            return;
        }
        final Barrel barrel = this.context.blocks().barrel(event.getBlock()).orElse(null);
        if (barrel != null && this.context.repository().isDrawer(barrel)) {
            withdraw(player, barrel, pending.bulk());
        }
    }

    /** A completed break must never leave a delayed withdrawal able to rewrite the barrel. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreakIntent(final BlockBreakEvent event) {
        final PendingBlockWithdrawal pending = this.pendingBlockWithdrawals.get(event.getPlayer().getUniqueId());
        if (pending != null && pending.matches(event.getBlock())) {
            this.pendingBlockWithdrawals.remove(event.getPlayer().getUniqueId(), pending);
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final UUID playerId = event.getPlayer().getUniqueId();
        this.pendingBlockWithdrawals.remove(playerId);
        this.bulkDepositArms.remove(playerId);
        this.chestInventory.quit(playerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(final InventoryOpenEvent event) {
        this.chestInventory.opening(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMenuClick(final InventoryClickEvent event) {
        this.chestInventory.click(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMenuDrag(final InventoryDragEvent event) {
        this.chestInventory.drag(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMenuClickObserved(final InventoryClickEvent event) {
        this.chestInventory.observed(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMenuDragObserved(final InventoryDragEvent event) {
        this.chestInventory.observed(event);
    }

    @EventHandler
    public void onMenuClose(final InventoryCloseEvent event) {
        this.chestInventory.close(event);
    }

    public void disable() { this.chestInventory.disable(); }

    private void rightClick(final Player player, final Barrel barrel, final EquipmentSlot hand) {
        final ItemStack held = heldItem(player, hand);
        if (player.isSneaking() && isUsableItem(held) && !shortcutDepositsEnabled()) {
            this.bulkDepositArms.remove(player.getUniqueId());
            return;
        }
        final ReadyDrawer ready = readyDrawer(barrel);
        if (ready == null) {
            if (!withdrawals.isWithdrawalInProgress(barrel)) {
                messageInvalidDrawer(player, barrel);
            }
            return;
        }
        final Barrel readyBarrel = ready.barrel();
        DrawerState state = ready.state();
        final DrawerBlockAccess.PhysicalStock stock = ready.stock();
        if (!player.isSneaking()) {
            this.context.execution().runOnEntity(player, task -> openMenu(player, ready), () -> { });
            return;
        }
        if (!isUsableItem(held)) {
            this.bulkDepositArms.remove(player.getUniqueId());
            this.context.execution().runOnEntity(player,
                    task -> this.upgradeOpener.open(player, readyBarrel), () -> { });
            return;
        }
        try {
            if (isBulkDepositArmed(player, readyBarrel, state, held) && isUsableItem(held)) {
                depositMatchingInventory(player, readyBarrel, state, stock.count(), true);
                this.bulkDepositArms.remove(player.getUniqueId());
                return;
            }
            if (!state.hasTemplate()) {
                if (!isUsableItem(held)) {
                    message(player, "drawer.empty");
                    return;
                }
                depositHeld(player, readyBarrel, state, stock.count(), hand, held);
                return;
            }
            if (isUsableItem(held)) {
                if (state.matchesTemplate(held)) {
                    depositHeld(player, readyBarrel, state, stock.count(), hand, held);
                }
                return;
            }
            depositMatchingInventory(player, readyBarrel, state, stock.count(), true);
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            message(player, "general.configuration-error");
            this.context.logger().warning("Rejected drawer deposit at " + readyBarrel.getLocation() + ": " + exception.getMessage());
        }
    }

    private void depositHeld(
            final Player player,
            final Barrel barrel,
            DrawerState state,
            final long physicalCount,
            final EquipmentSlot hand,
            final ItemStack held
    ) {
        final DrawerTierDefinition tier = this.context.tier(state.tierId()).orElse(null);
        if (tier == null) {
            messageInvalidDrawer(player, barrel);
            return;
        }
        final DrawerState originalState = state;
        if (!state.hasTemplate()) {
            state = state.initializeCapacityForFirstItem(tier.tier(), held).withTemplate(held);
        }
        final DrawerStorageTransaction transaction = this.context.storage().deposit(
                state, physicalCount, held, held.getAmount()
        );
        if (!transaction.accepted()) {
            reportRejected(player, transaction);
            return;
        }
        final ItemStack originalHeld = held.clone();
        if (!commitPlayerDeposit(player, barrel, originalState, state, transaction.stateAfter(), physicalCount,
                () -> consumeHeld(player, hand, (int) transaction.acceptedAmount()),
                () -> setHeld(player, hand, originalHeld.clone()))) {
            return;
        }
        message(player, "drawer.deposited", Map.of("amount", transaction.acceptedAmount()));
        armBulkDeposit(player, barrel, transaction.stateAfter());
    }

    private void depositMatchingInventory(
            final Player player,
            final Barrel barrel,
            DrawerState state,
            final long physicalCount,
            final boolean allMatchingStacks
    ) {
        if (!state.hasTemplate()) {
            message(player, "drawer.no-matching-items");
            return;
        }
        final DrawerState originalState = state;
        long acceptedTotal = 0L;
        final ItemStack template = state.requireTemplate();
        final PlayerInventory inventory = player.getInventory();
        final Map<Integer, Integer> removals = new LinkedHashMap<>();
        final Map<Integer, ItemStack> originals = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            final ItemStack candidate = inventory.getItem(slot);
            if (!state.matchesTemplate(candidate)) {
                continue;
            }
            final int originalAmount = candidate.getAmount();
            final DrawerStorageTransaction transaction = this.context.storage().deposit(
                    state,
                    physicalCount,
                    candidate,
                    originalAmount
            );
            if (!transaction.accepted()) {
                break;
            }
            removals.put(slot, (int) transaction.acceptedAmount());
            state = transaction.stateAfter();
            acceptedTotal += transaction.acceptedAmount();
            if (!allMatchingStacks || transaction.acceptedAmount() < originalAmount) {
                break;
            }
        }
        if (acceptedTotal == 0L) {
            message(player, "drawer.no-matching-items");
            return;
        }
        for (final Map.Entry<Integer, Integer> removal : removals.entrySet()) {
            final ItemStack candidate = inventory.getItem(removal.getKey());
            if (candidate == null || !candidate.isSimilar(template) || candidate.getAmount() < removal.getValue()) {
                this.context.logger().warning("Player inventory changed during drawer deposit for " + player.getName());
                message(player, "general.configuration-error");
                return;
            }
            originals.put(removal.getKey(), candidate.clone());
        }
        final DrawerState after = state;
        if (!commitPlayerDeposit(player, barrel, originalState, originalState, after, physicalCount,
                () -> {
                    for (final Map.Entry<Integer, Integer> removal : removals.entrySet()) {
                        final ItemStack candidate = inventory.getItem(removal.getKey());
                        candidate.setAmount(candidate.getAmount() - removal.getValue());
                        inventory.setItem(removal.getKey(), candidate.getAmount() <= 0 ? null : candidate);
                    }
                },
                () -> restoreInventorySlots(inventory, originals))) {
            return;
        }
        message(player, "drawer.deposited", Map.of("amount", acceptedTotal, "item", template.getType().translationKey()));
    }

    /** Records the cross-playerdata/chunk transfer before touching either inventory. */
    private boolean commitPlayerDeposit(final Player player, final Barrel barrel, final DrawerState original,
            final DrawerState prepared, final DrawerState after, final long physicalCount,
            final Runnable consume, final Runnable restore) {
        final DrawerProxyJournal journal = new DrawerProxyJournal(
                UUID.randomUUID(), DrawerJournalKind.GUARDED_PLAYER_DEPOSIT, DrawerJournalPhase.PREPARED,
                player.getUniqueId(), prepared.totalForPhysical(physicalCount), after.totalForPhysical(physicalCount),
                physicalCount, physicalCount, System.currentTimeMillis());
        final DrawerState intent = prepared.migrateToReservedMirror(physicalCount).withProxyJournal(journal);
        try {
            if (!this.context.repository().save(barrel, intent)) {
                message(player, "general.configuration-error");
                return false;
            }
        } catch (final RuntimeException exception) {
            context.logger().severe("Could not prepare quick-deposit " + journal.operationId()
                    + " at " + barrel.getLocation() + ": " + exception.getMessage());
            message(player, "general.configuration-error");
            return false;
        }
        boolean consumed = false;
        boolean credited = false;
        try {
            consumed = true;
            consume.run();
            if (!this.context.repository().save(barrel, after.migrateToReservedMirror(physicalCount)
                    .withProxyJournal(journal.withPhase(DrawerJournalPhase.APPLIED)))) {
                throw new IllegalStateException("Could not persist quick-deposit credit");
            }
            credited = true;
            // The debit is saved with the player's inventory before the drawer intent is released.
            player.saveData();
            if (!this.context.repository().save(barrel, after.migrateToReservedMirror(physicalCount))) {
                throw new IllegalStateException("Could not release quick-deposit intent");
            }
        } catch (final RuntimeException exception) {
            if (!credited) {
                final DrawerJournalPhase observed = depositPhase(barrel, journal.operationId());
                if (observed == DrawerJournalPhase.PREPARED) {
                    if (consumed) {
                        try {
                            restore.run();
                        } catch (final RuntimeException restoreFailure) {
                            context.logger().severe("Quick-deposit " + journal.operationId()
                                    + " could not restore player inventory: " + restoreFailure.getMessage());
                            message(player, "general.configuration-error");
                            return false;
                        }
                    }
                    try {
                        if (!this.context.repository().save(barrel, original)) {
                            context.logger().severe("Quick-deposit " + journal.operationId()
                                    + " could not clear its prepared drawer intent at " + barrel.getLocation());
                        }
                    } catch (final RuntimeException rollbackFailure) {
                        context.logger().severe("Quick-deposit " + journal.operationId()
                                + " could not clear its prepared drawer intent at " + barrel.getLocation()
                                + ": " + rollbackFailure.getMessage());
                    }
                } else {
                    context.logger().severe("Quarantined uncertain quick-deposit " + journal.operationId()
                            + " at " + barrel.getLocation() + " after credit attempt: " + exception.getMessage());
                }
            } else {
                // A saveData exception may happen after playerdata was written. Never restore an
                // already-credited transfer; keep its operation id for manual reconciliation.
                context.logger().severe("Quarantined credited quick-deposit " + journal.operationId()
                        + " at " + barrel.getLocation() + ": " + exception.getMessage());
            }
            message(player, "general.configuration-error");
            return false;
        }
        try {
            this.context.notifyCommitted(barrel, after, physicalCount, DrawerChangeCause.PLAYER_DEPOSIT);
            this.hoppers.queueRebalance(barrel);
        } catch (final RuntimeException exception) {
            context.logger().warning("Quick-deposit " + journal.operationId()
                    + " committed but refresh failed: " + exception.getMessage());
        }
        return true;
    }

    private DrawerJournalPhase depositPhase(final Barrel barrel, final UUID operationId) {
        try {
            final Barrel current = this.context.blocks().barrel(barrel.getBlock()).orElse(null);
            if (current == null) return null;
            final DrawerStateReadResult read = this.context.repository().read(current);
            if (read instanceof DrawerStateReadResult.Valid valid) {
                return valid.state().proxyJournal()
                        .filter(pending -> pending.operationId().equals(operationId)
                                && pending.kind() == DrawerJournalKind.GUARDED_PLAYER_DEPOSIT)
                        .map(DrawerProxyJournal::phase).orElse(null);
            }
        } catch (final RuntimeException ignored) {
            // An unreadable drawer cannot prove that returning the player's items is safe.
        }
        return null;
    }

    private void withdraw(final Player player, final Barrel barrel, final boolean bulk) {
        if (!this.withdrawalProtection.canWithdraw(player, barrel)) {
            message(player, "general.no-permission");
            return;
        }
        final ReadyDrawer ready = readyDrawer(barrel);
        if (ready == null) {
            if (!withdrawals.isWithdrawalInProgress(barrel)) {
                messageInvalidDrawer(player, barrel);
            }
            return;
        }
        final Barrel readyBarrel = ready.barrel();
        final DrawerState state = ready.state();
        final DrawerBlockAccess.PhysicalStock stock = ready.stock();
        try {
            final long total = state.totalForPhysical(stock.count());
            if (total == 0L) {
                message(player, "drawer.empty");
                return;
            }
            final ItemStack template = state.requireTemplate();
            final long wanted = Math.min(
                    bulk ? Math.min(total, (long) template.getMaxStackSize()) : 1L,
                    availableSpace(player.getInventory(), template)
            );
            if (wanted <= 0L) {
                message(player, "drawer.inventory-full");
                return;
            }
            final DrawerStorageTransaction transaction = this.context.storage().withdraw(state, stock.count(), wanted);
            if (!transaction.accepted()) {
                reportRejected(player, transaction);
                return;
            }
            if (!this.withdrawals.begin(player, readyBarrel, transaction)) {
                message(player, "general.configuration-error");
                return;
            }
        } catch (final DrawerInvariantViolationException | IllegalArgumentException exception) {
            message(player, "general.configuration-error");
            this.context.logger().warning("Rejected drawer withdrawal at " + readyBarrel.getLocation() + ": " + exception.getMessage());
        }
    }

    private void openMenu(final Player player, final ReadyDrawer ready) {
        this.chestInventory.open(player, ready.barrel());
    }

    private void armBlockWithdrawal(final Player player, final Barrel barrel, final boolean bulk) {
        this.pendingBlockWithdrawals.put(
                player.getUniqueId(),
                new PendingBlockWithdrawal(barrel.getLocation(), bulk, System.currentTimeMillis() + 5_000L)
        );
    }

    /** Performs one synchronous region-owned recovery before treating a drawer as invalid. */
    private ReadyDrawer readyDrawer(final Barrel original) {
        Barrel current = original;
        for (int attempt = 0; attempt < 2; attempt++) {
            final DrawerStateReadResult read = this.context.repository().read(current);
            if (read instanceof DrawerStateReadResult.Valid valid) {
                final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(current, valid.state());
                if (stock.matchesTemplate() && !valid.state().hasPendingProxyJournal()) {
                    return new ReadyDrawer(current, valid.state(), stock);
                }
            }
            if (attempt == 0) {
                this.hoppers.recoverAndRebalance(current);
                current = this.context.blocks().barrel(original.getBlock()).orElse(null);
                if (current == null || !this.context.repository().isDrawer(current)) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean isBulkDepositArmed(final Player player, final Barrel barrel, final DrawerState state, final ItemStack held) {
        final BulkDepositArm arm = this.bulkDepositArms.get(player.getUniqueId());
        return arm != null
                && arm.expiresAtMillis() >= System.currentTimeMillis()
                && arm.matches(barrel, state)
                && (!isUsableItem(held) || state.matchesTemplate(held));
    }

    private void armBulkDeposit(final Player player, final Barrel barrel, final DrawerState state) {
        if (!state.hasTemplate()) {
            return;
        }
        this.bulkDepositArms.put(
                player.getUniqueId(),
                new BulkDepositArm(barrel.getLocation(), state.requireTemplate(), System.currentTimeMillis()
                        + this.context.settings().interaction().bulkDepositWindowTicks() * 50L)
        );
    }

    private static boolean isFacingFace(final Barrel barrel, final BlockFace clickedFace) {
        return barrel.getBlockData() instanceof Directional directional && directional.getFacing() == clickedFace;
    }

    private boolean canUse(final Player player) {
        return player.hasPermission("devstoragedrawer.use");
    }

    private boolean shortcutWithdrawalsEnabled() {
        return this.context.settings().interaction().shortcutWithdrawalsEnabled();
    }

    private boolean shortcutDepositsEnabled() {
        return this.context.settings().interaction().shortcutDepositsEnabled();
    }

    private boolean canWithdrawWith(final Barrel barrel, final ItemStack held) {
        if (!isUsableItem(held)) {
            return true;
        }
        final DrawerStateReadResult read = this.context.repository().read(barrel);
        return read instanceof DrawerStateReadResult.Valid valid && valid.state().matchesTemplate(held);
    }

    private void deny(final PlayerInteractEvent event, final String messageKey) {
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (messageKey != null) {
            message(event.getPlayer(), messageKey);
        }
    }

    private void reportRejected(final Player player, final DrawerStorageTransaction transaction) {
        switch (transaction.status()) {
            case EMPTY -> message(player, "drawer.empty");
            case FULL -> message(player, "drawer.full");
            case ITEM_MISMATCH -> message(player, "drawer.wrong-item");
            default -> message(player, "general.configuration-error");
        }
    }

    private void message(final Player player, final String key) {
        player.sendMessage(this.context.messages().component(key));
    }

    private void message(final Player player, final String key, final Map<String, ?> placeholders) {
        player.sendMessage(this.context.messages().component(key, placeholders));
    }

    private void messageInvalidDrawer(final Player player, final Barrel barrel) {
        player.sendMessage(this.context.messages().component("drawer.invalid", Map.of("reason", invalidReason(barrel))));
    }

    private String invalidReason(final Barrel barrel) {
        final DrawerStateReadResult read = this.context.repository().read(barrel);
        if (read instanceof DrawerStateReadResult.Absent) {
            return "missing persisted drawer state";
        }
        if (read instanceof DrawerStateReadResult.Corrupt corrupt) {
            return shorten("corrupt persisted state: " + corrupt.reason());
        }
        final DrawerState state = ((DrawerStateReadResult.Valid) read).state();
        final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(barrel, state);
        if (!stock.matchesTemplate()) {
            return "barrel mirror contains an item different from the drawer template";
        }
        if (state.hasPendingProxyJournal()) {
            return "a crash-recovery transfer is still pending";
        }
        return "drawer state changed while recovery was running";
    }

    private static String shorten(final String reason) {
        return reason.length() <= 160 ? reason : reason.substring(0, 157) + "...";
    }

    private static ItemStack heldItem(final Player player, final EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
    }

    private static boolean isUsableItem(final ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }

    private static void consumeHeld(final Player player, final EquipmentSlot hand, final int amount) {
        final ItemStack item = heldItem(player, hand);
        item.setAmount(item.getAmount() - amount);
        if (item.getAmount() <= 0) {
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        }
    }

    private static void setHeld(final Player player, final EquipmentSlot hand, final ItemStack original) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(original);
        } else {
            player.getInventory().setItemInMainHand(original);
        }
    }

    private static long availableSpace(final PlayerInventory inventory, final ItemStack template) {
        final int max = Math.min(template.getMaxStackSize(), inventory.getMaxStackSize());
        long free = 0L;
        for (final ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
                free += max;
            } else if (stack.isSimilar(template)) {
                free += Math.max(0, max - stack.getAmount());
            }
        }
        return free;
    }

    private static void restoreInventorySlots(final PlayerInventory inventory, final Map<Integer, ItemStack> originals) {
        for (final Map.Entry<Integer, ItemStack> original : originals.entrySet()) {
            inventory.setItem(original.getKey(), original.getValue().clone());
        }
    }

    private record ReadyDrawer(Barrel barrel, DrawerState state, DrawerBlockAccess.PhysicalStock stock) {
    }

    private record BulkDepositArm(Location location, ItemStack template, long expiresAtMillis) {
        BulkDepositArm {
            location = location.clone();
            template = template.clone();
        }

        boolean matches(final Barrel barrel, final DrawerState state) {
            return this.location.getWorld() != null
                    && this.location.getWorld().equals(barrel.getWorld())
                    && this.location.getBlockX() == barrel.getX()
                    && this.location.getBlockY() == barrel.getY()
                    && this.location.getBlockZ() == barrel.getZ()
                    && state.matchesTemplate(this.template);
        }
    }

    private record PendingBlockWithdrawal(Location location, boolean bulk, long expiresAtMillis) {
        private PendingBlockWithdrawal {
            location = location.clone();
        }

        private boolean matches(final Block block) {
            return location.getWorld() == block.getWorld()
                    && location.getBlockX() == block.getX()
                    && location.getBlockY() == block.getY()
                    && location.getBlockZ() == block.getZ();
        }

        private boolean expired() {
            return expiresAtMillis < System.currentTimeMillis();
        }
    }
}
