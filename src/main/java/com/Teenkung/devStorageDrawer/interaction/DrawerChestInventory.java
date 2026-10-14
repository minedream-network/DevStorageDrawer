package com.teenkung.devstoragedrawer.interaction;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.block.DrawerBlockAccess;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.domain.DrawerItemIdentity;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

/** Opens paged logical storage and guards native container access to real mirror items. */
final class DrawerChestInventory {
    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;
    private final DrawerWithdrawalProtection protection;
    private final DrawerPagedInventory pages;

    DrawerChestInventory(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers,
            final DrawerWithdrawalProtection protection) {
        this(context, hoppers, protection, (player, barrel) -> { });
    }

    DrawerChestInventory(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers,
            final DrawerWithdrawalProtection protection, final DrawerUpgradeOpener upgradeOpener) {
        this.context = context;
        this.hoppers = hoppers;
        this.protection = protection;
        this.pages = new DrawerPagedInventory(context, hoppers, protection, upgradeOpener);
    }

    void open(final Player player, final Barrel original) {
        final Location location = original.getLocation();
        if (!player.isOnline() || !player.getWorld().equals(location.getWorld())
                || player.getLocation().distanceSquared(location) > 64D
                || !Bukkit.isOwnedByCurrentRegion(location)) {
            player.sendMessage(context.messages().component("drawer.menu-too-far"));
            return;
        }
        final Barrel live = context.blocks().barrel(location.getBlock()).orElse(null);
        if (live == null || !context.repository().isDrawer(live)) return;
        hoppers.recoverAndRebalance(live);
        final Ready ready = ready(player, live);
        if (ready != null) pages.open(player, ready.barrel());
    }

    void opening(final InventoryOpenEvent event) {
        final var page = DrawerPagedInventory.holder(event.getInventory());
        if (page != null) { pages.opening(event, page); return; }
        final Barrel barrel = drawer(event.getInventory());
        if (barrel == null || event.isCancelled() || !(event.getPlayer() instanceof Player player)) return;
        final Ready ready = ready(player, barrel);
        if (ready == null || !event.getInventory().equals(ready.barrel().getInventory())) event.setCancelled(true);
    }

    void click(final InventoryClickEvent event) {
        final Inventory top = event.getView().getTopInventory();
        final var page = DrawerPagedInventory.holder(top);
        if (page != null) { pages.click(event, page); return; }
        final Barrel barrel = drawer(top);
        if (barrel == null || event.isCancelled() || !(event.getWhoClicked() instanceof Player player)) return;
        final int rawSlot = event.getRawSlot();
        final boolean topSlot = rawSlot >= 0 && rawSlot < top.getSize();
        final InventoryAction action = event.getAction();
        if (!topSlot && action != InventoryAction.MOVE_TO_OTHER_INVENTORY
                && action != InventoryAction.COLLECT_TO_CURSOR && action != InventoryAction.UNKNOWN) return;
        // Creative cloning is not a transfer of real storage stock.
        if (action == InventoryAction.CLONE_STACK || action == InventoryAction.UNKNOWN
                || action == InventoryAction.PICKUP_FROM_BUNDLE
                || action == InventoryAction.PLACE_ALL_INTO_BUNDLE
                || action == InventoryAction.PLACE_SOME_INTO_BUNDLE) {
            // Unpacking or filling a stored bundle changes its item identity while hidden
            // copies still have the original contents. Move the whole bundle out first.
            event.setCancelled(true);
            return;
        }
        final Ready ready = ready(player, barrel);
        if (ready == null || !top.equals(ready.barrel().getInventory())) {
            event.setCancelled(true);
            return;
        }
        final ItemStack incoming;
        final int increase;
        switch (action) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE -> {
                incoming = event.getCursor();
                final int fit = room(top.getItem(rawSlot), incoming, top.getMaxStackSize());
                increase = valid(incoming) ? Math.min(incoming.getAmount(),
                        action == InventoryAction.PLACE_ONE ? Math.min(1, fit) : fit) : 0;
            }
            case SWAP_WITH_CURSOR -> {
                incoming = event.getCursor();
                increase = count(incoming) - count(event.getCurrentItem());
            }
            case HOTBAR_SWAP -> {
                incoming = event.getClick() == ClickType.SWAP_OFFHAND ? player.getInventory().getItemInOffHand()
                        : event.getHotbarButton() >= 0 ? player.getInventory().getItem(event.getHotbarButton()) : null;
                increase = count(incoming) - count(event.getCurrentItem());
            }
            case PLACE_FROM_BUNDLE -> {
                // Paper exposes bundle contents, but not its selected stack. Prove that every
                // possible selected stack matches and fits instead of guessing its selection.
                final var items = event.getCursor() != null && event.getCursor().getItemMeta() instanceof BundleMeta bundle
                        ? bundle.getItems() : java.util.List.<ItemStack>of();
                incoming = items.isEmpty() ? null : items.getFirst();
                if (items.stream().anyMatch(item -> !valid(item) || !item.isSimilar(incoming))) {
                    event.setCancelled(true);
                    player.sendMessage(context.messages().component("drawer.wrong-item"));
                    return;
                }
                increase = Math.min(items.stream().mapToInt(ItemStack::getAmount).max().orElse(0),
                        room(top.getItem(rawSlot), incoming, top.getMaxStackSize()));
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                incoming = topSlot ? null : event.getCurrentItem();
                increase = incoming == null ? 0 : fitting(top, incoming);
            }
            default -> { incoming = null; increase = 0; }
        }
        final DrawerState state = acceptItem(player, ready, incoming);
        if (state == null) {
            event.setCancelled(true);
            return;
        }
        final long space = state.availableCapacityForPhysical(ready.stock().count());
        if (increase > space) {
            event.setCancelled(true);
            // Vanilla has no knowledge of hidden stock. Place/shift only the part that fits
            // logically, keeping the rest on the cursor or in the player's source slot.
            if (space > 0L && (action == InventoryAction.PLACE_ALL || action == InventoryAction.PLACE_SOME
                    || (!topSlot && action == InventoryAction.MOVE_TO_OTHER_INVENTORY))) {
                partialDeposit(event, ready, state, incoming, Math.toIntExact(space), topSlot);
            } else {
                player.sendMessage(context.messages().component("drawer.full"));
            }
            return;
        }
        if (!initialize(ready, state)) {
            event.setCancelled(true);
            player.sendMessage(context.messages().component("general.configuration-error"));
            return;
        }
        // Let vanilla handle cursor, shift, hotbar/offhand, drop, collect and slot moves. The
        // delayed mirror reconciliation observes only moves that actually completed.
        hoppers.queueRebalance(ready.barrel());
    }

    void drag(final InventoryDragEvent event) {
        final Inventory top = event.getView().getTopInventory();
        final var page = DrawerPagedInventory.holder(top);
        if (page != null) { pages.drag(event, page); return; }
        final Barrel barrel = drawer(top);
        if (barrel == null || event.isCancelled() || !(event.getWhoClicked() instanceof Player player)
                || event.getRawSlots().stream().noneMatch(slot -> slot >= 0 && slot < top.getSize())) return;
        final Ready ready = ready(player, barrel);
        if (ready == null || !top.equals(ready.barrel().getInventory())) { event.setCancelled(true); return; }
        final DrawerState state = acceptItem(player, ready, event.getOldCursor());
        if (state == null) { event.setCancelled(true); return; }
        long increase = 0L;
        for (final var entry : event.getNewItems().entrySet()) {
            if (entry.getKey() < 0 || entry.getKey() >= top.getSize()) continue;
            if (!state.matchesTemplate(entry.getValue())) { event.setCancelled(true); return; }
            increase += (long) count(entry.getValue()) - count(top.getItem(entry.getKey()));
        }
        if (increase > state.availableCapacityForPhysical(ready.stock().count())) {
            event.setCancelled(true);
            player.sendMessage(context.messages().component("drawer.full"));
        } else if (!initialize(ready, state)) {
            event.setCancelled(true);
            player.sendMessage(context.messages().component("general.configuration-error"));
        } else {
            hoppers.queueRebalance(ready.barrel());
        }
    }

    void close(final InventoryCloseEvent event) {
        final var page = DrawerPagedInventory.holder(event.getInventory());
        if (page != null) { pages.close(page); return; }
        final Barrel barrel = drawer(event.getInventory());
        if (barrel != null && Bukkit.isOwnedByCurrentRegion(barrel.getLocation())) hoppers.queueRebalance(barrel);
    }

    void quit(final java.util.UUID player) { pages.quit(player); }
    void disable() { pages.disable(); }

    void observed(final InventoryClickEvent event) {
        final var page = DrawerPagedInventory.holder(event.getView().getTopInventory());
        if (page != null) pages.observed(event, page);
    }

    void observed(final InventoryDragEvent event) {
        final var page = DrawerPagedInventory.holder(event.getView().getTopInventory());
        if (page != null) pages.observed(event, page);
    }

    private DrawerState acceptItem(final Player player, final Ready ready, final ItemStack incoming) {
        DrawerState state = ready.state();
        if (!valid(incoming)) return state;
        if (state.hasTemplate() && !state.matchesTemplate(incoming)) {
            player.sendMessage(context.messages().component("drawer.wrong-item"));
            return null;
        }
        if (!state.hasTemplate()) {
            final var tier = context.tier(state.tierId()).orElse(null);
            if (tier == null) return null;
            state = state.initializeCapacityForFirstItem(tier.tier(), incoming).withTemplate(incoming);
        }
        return state;
    }

    private boolean initialize(final Ready ready, final DrawerState state) {
        return ready.state() == state || context.repository().save(ready.barrel(), state);
    }

    private Ready ready(final Player player, final Barrel original) {
        if (!Bukkit.isOwnedByCurrentRegion(original.getLocation())) return null;
        if (!player.getWorld().equals(original.getLocation().getWorld())
                || player.getLocation().distanceSquared(original.getLocation()) > 64D) {
            player.sendMessage(context.messages().component("drawer.menu-too-far"));
            return null;
        }
        final Barrel barrel = context.blocks().barrel(original.getBlock()).orElse(null);
        if (barrel == null || !context.repository().isDrawer(barrel)) return null;
        if (!player.hasPermission("devstoragedrawer.use") || !protection.canWithdraw(player, barrel)) {
            player.sendMessage(context.messages().component("general.no-permission"));
            return null;
        }
        if (!(context.repository().read(barrel) instanceof DrawerStateReadResult.Valid read)
                || read.state().hasPendingProxyJournal()) {
            player.sendMessage(context.messages().component("drawer.inventory-busy"));
            return null;
        }
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, read.state());
        if (!stock.matchesTemplate()) return null;
        try {
            read.state().totalForPhysical(stock.count());
            return new Ready(barrel, read.state(), stock);
        } catch (final IllegalArgumentException | com.teenkung.devstoragedrawer.domain.DrawerInvariantViolationException exception) {
            return null;
        }
    }

    private Barrel drawer(final Inventory inventory) {
        if (!(inventory.getHolder(false) instanceof Barrel barrel)) return null;
        // A stale view may survive a region transition. Reject it before reading foreign state.
        if (!Bukkit.isOwnedByCurrentRegion(barrel.getLocation())) return barrel;
        return context.repository().isDrawer(barrel) ? barrel : null;
    }

    private void partialDeposit(final InventoryClickEvent event, final Ready ready, final DrawerState state,
            final ItemStack incoming, final int amount, final boolean cursorSource) {
        final Player player = (Player) event.getWhoClicked();
        final Inventory top = event.getView().getTopInventory();
        final ItemStack[] before = cloneContents(top.getContents());
        final ItemStack original = incoming.clone();
        final long total = state.totalForPhysical(ready.stock().count());
        final DrawerState baseline = state.migrateToReservedMirror(ready.stock().count())
                .withStoredTotalAndExpectedMirrorCount(total, ready.stock().count());
        try {
            int left = amount;
            for (int slot = 0; slot < top.getSize() && left > 0; slot++) {
                if (cursorSource && slot != event.getRawSlot()) continue;
                final ItemStack old = top.getItem(slot);
                final int moved = Math.min(left, room(old, incoming, top.getMaxStackSize()));
                if (moved == 0) continue;
                final ItemStack replacement = incoming.clone();
                replacement.setAmount(count(old) + moved);
                top.setItem(slot, replacement);
                left -= moved;
            }
            if (left != 0) throw new IllegalStateException("The drawer slots changed during a deposit");
            final ItemStack remainder = incoming.clone();
            remainder.setAmount(incoming.getAmount() - amount);
            if (cursorSource) player.setItemOnCursor(remainder.getAmount() == 0 ? null : remainder);
            else event.getClickedInventory().setItem(event.getSlot(), remainder.getAmount() == 0 ? null : remainder);
            final Barrel fresh = context.blocks().barrel(ready.barrel().getBlock()).orElseThrow();
            final DrawerState after = baseline.withStoredTotalAndExpectedMirrorCount(total + amount,
                    ready.stock().count() + amount);
            if (!context.repository().save(fresh, after)) throw new IllegalStateException("Could not save the deposit");
            try {
                context.notifyCommitted(fresh, after, ready.stock().count() + amount, DrawerChangeCause.PLAYER_DEPOSIT);
                hoppers.queueRebalance(fresh);
            } catch (final RuntimeException exception) {
                context.logger().warning("Drawer deposit saved but refresh failed: " + exception.getMessage());
            }
        } catch (final RuntimeException exception) {
            top.setContents(before);
            if (cursorSource) player.setItemOnCursor(original);
            else event.getClickedInventory().setItem(event.getSlot(), original);
            context.blocks().barrel(ready.barrel().getBlock()).ifPresent(live -> context.repository().save(live, baseline));
            player.sendMessage(context.messages().component("general.configuration-error"));
            context.logger().warning("Rejected partial drawer deposit: " + exception.getMessage());
        }
    }

    static int fitting(final Inventory inventory, final ItemStack incoming) {
        if (!valid(incoming)) return 0;
        int space = 0;
        for (final ItemStack current : inventory.getContents()) {
            space += room(current, incoming, inventory.getMaxStackSize());
            if (space >= incoming.getAmount()) return incoming.getAmount();
        }
        return Math.min(space, incoming.getAmount());
    }

    private static int room(final ItemStack current, final ItemStack incoming, final int inventoryLimit) {
        if (!valid(incoming) || (valid(current) && !current.isSimilar(incoming))) return 0;
        return Math.max(0, Math.min(inventoryLimit, incoming.getMaxStackSize()) - count(current));
    }

    private static boolean valid(final ItemStack item) { return DrawerItemIdentity.isStorageCandidate(item); }
    private static int count(final ItemStack item) { return valid(item) ? item.getAmount() : 0; }

    private static ItemStack[] cloneContents(final ItemStack[] items) {
        final ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? null : items[i].clone();
        return copy;
    }

    private record Ready(Barrel barrel, DrawerState state, DrawerBlockAccess.PhysicalStock stock) { }
}
