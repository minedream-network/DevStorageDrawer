package com.teenkung.devstoragedrawer.interaction;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.api.DrawerSaleApi;
import com.teenkung.devstoragedrawer.block.DrawerBlockAccess;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.block.DrawerSellWandBridge;
import com.teenkung.devstoragedrawer.config.DrawerMenuIcon;
import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import com.teenkung.devstoragedrawer.domain.DrawerItemIdentity;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Native chest movement with guarded logical accounting and explicit navigation controls. */
final class DrawerPagedInventory {
    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;
    private final DrawerWithdrawalProtection protection;
    private final DrawerSellWandBridge transfers;
    private final DrawerNativeInventoryMove nativeMoves;
    private final DrawerMenuItems menuItems;
    private final DrawerUpgradeOpener upgradeOpener;
    private final Map<UUID, DrawerPageHolder> sessions = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    DrawerPagedInventory(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers,
            final DrawerWithdrawalProtection protection) {
        this(context, hoppers, protection, (player, barrel) -> { });
    }

    DrawerPagedInventory(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers,
            final DrawerWithdrawalProtection protection, final DrawerUpgradeOpener upgradeOpener) {
        this.context = context;
        this.hoppers = hoppers;
        this.protection = protection;
        this.transfers = new DrawerSellWandBridge(context, hoppers);
        this.nativeMoves = new DrawerNativeInventoryMove(context, hoppers);
        this.menuItems = new DrawerMenuItems(context::logger);
        this.upgradeOpener = upgradeOpener;
    }

    void open(final Player player, final Barrel original) {
        if (stopped) return;
        final DrawerPageHolder holder = new DrawerPageHolder(player.getUniqueId(), original.getLocation());
        final Ready ready = ready(player, holder, true);
        if (ready == null) return;
        holder.inventory = Bukkit.createInventory(holder, DrawerPageHolder.SIZE,
                context.messages().dialogComponent("storage.title"));
        render(holder, ready, true);
        // Opening fires close/open synchronously. Register only after the old view has closed.
        player.openInventory(holder.inventory);
        if (player.getOpenInventory().getTopInventory().equals(holder.inventory)) {
            sessions.put(player.getUniqueId(), holder);
            if (stopped) { close(holder); return; }
            schedule(player, holder);
        } else holder.inventory.clear();
    }

    static DrawerPageHolder holder(final Inventory inventory) {
        return inventory.getHolder(false) instanceof DrawerPageHolder page ? page : null;
    }

    void opening(final InventoryOpenEvent event, final DrawerPageHolder holder) {
        if (stopped || holder.closed || !event.getInventory().equals(holder.inventory)
                || !(event.getPlayer() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || ready(player, holder, true) == null) event.setCancelled(true);
    }

    void click(final InventoryClickEvent event, final DrawerPageHolder holder) {
        if (event.isCancelled() || !(event.getWhoClicked() instanceof Player player)) return;
        flushNative(holder);
        final int raw = event.getRawSlot();
        final boolean top = raw >= 0 && raw < DrawerPageHolder.SIZE;
        final InventoryAction action = event.getAction();
        // Ordinary bottom-inventory organization and dropping the real cursor stay vanilla.
        if (!top && action != InventoryAction.MOVE_TO_OTHER_INVENTORY
                && action != InventoryAction.COLLECT_TO_CURSOR && action != InventoryAction.UNKNOWN) return;
        final Ready ready;
        try {
            ready = active(player, holder, true);
            if (ready != null && nativeClick(event, holder, ready)) return;
        } catch (final RuntimeException exception) {
            event.setCancelled(true);
            context.logger().warning("Rejected native drawer click: " + exception.getMessage());
            message(player, "general.configuration-error");
            return;
        }
        if (ready == null) { event.setCancelled(true); return; }
        event.setCancelled(true);
        if (top && raw >= DrawerPageHolder.ITEM_SLOTS) {
            if (raw == 48 && upgradeEnabled()) {
                final Barrel upgradeTarget = ready.barrel();
                close(holder);
                player.closeInventory();
                context.execution().runLaterOnEntity(player, 1L,
                        ignored -> upgradeOpener.open(player, upgradeTarget), () -> { });
                return;
            }
            if (raw == 45 || raw == 53) {
                final int size = ready.state().hasTemplate() ? ready.state().requireTemplate().getMaxStackSize() : 64;
                final long pages = DrawerPageHolder.pages(ready.total(), ready.state().capacitySnapshot(), size);
                final long nextPage = event.isShiftClick() ? (raw == 45 ? 0 : pages - 1)
                        : Math.max(0L, Math.min(pages - 1, holder.page + (raw == 45 ? -1 : 1)));
                final boolean changed = nextPage != holder.page;
                holder.page = nextPage;
                render(holder, ready, changed);
            }
            return;
        }
        final ItemStack cursor = player.getItemOnCursor();
        final ItemStack shown = top ? holder.inventory.getItem(raw) : null;
        switch (action) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE -> {
                if (!top || !valid(shown) || valid(cursor) && !cursor.isSimilar(shown)) break;
                final int wanted = action == InventoryAction.PICKUP_HALF ? (count(shown) + 1) / 2
                        : action == InventoryAction.PICKUP_ONE ? 1 : count(shown);
                final int amount = bounded(ready, shown, Math.min(wanted, room(cursor, shown, 64)));
                take(player, holder, ready, raw, shown, amount, () -> {
                    if (!same(player.getItemOnCursor(), cursor)) return false;
                    player.setItemOnCursor(stack(shown, count(cursor) + amount));
                    return true;
                });
            }
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> {
                if (!top || !valid(cursor)) break;
                final int wanted = action == InventoryAction.PLACE_ONE ? 1 : count(cursor);
                final int amount = Math.min(wanted, room(shown, cursor, 64));
                final int fitting = Math.min(amount, free(ready, cursor));
                deposit(player, holder, ready, cursor, fitting, raw,
                        () -> player.setItemOnCursor(stack(cursor, count(cursor) - fitting)),
                        () -> player.setItemOnCursor(copy(cursor)));
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                if (top && valid(shown)) {
                    final ItemStack[] before = copies(player.getInventory().getStorageContents());
                    final ItemStack[] after = copies(before);
                    final int amount = add(after, shown, bounded(ready, shown, count(shown)),
                            player.getInventory().getMaxStackSize());
                    take(player, holder, ready, raw, shown, amount, () -> {
                        if (!sameContents(before, player.getInventory().getStorageContents())) return false;
                        player.getInventory().setStorageContents(after);
                        return true;
                    });
                } else if (!top && valid(event.getCurrentItem())) {
                    final ItemStack source = event.getCurrentItem().clone();
                    final int amount = Math.min(count(source), free(ready, source));
                    deposit(player, holder, ready, source, amount, -1,
                            () -> event.getClickedInventory().setItem(event.getSlot(), stack(source, count(source) - amount)),
                            () -> event.getClickedInventory().setItem(event.getSlot(), source));
                }
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (!top) break;
                final int hotbar = event.getClick() == ClickType.SWAP_OFFHAND ? 40 : event.getHotbarButton();
                if (hotbar < 0 || hotbar > 8 && hotbar != 40) break;
                final ItemStack source = copy(player.getInventory().getItem(hotbar));
                // Matching items exchange their net count; another item cannot replace the drawer identity.
                if (valid(source) && valid(shown) && !source.isSimilar(shown)) {
                    message(player, "drawer.wrong-item"); break;
                }
                final int delta = count(shown) - count(source);
                if (delta > 0) {
                    final int amount = bounded(ready, shown, delta);
                    take(player, holder, ready, raw, shown, amount, () -> {
                        if (!same(player.getInventory().getItem(hotbar), source)) return false;
                        player.getInventory().setItem(hotbar, stack(shown, count(source) + amount)); return true;
                    });
                } else if (delta < 0) {
                    final int amount = Math.min(-delta, free(ready, source));
                    deposit(player, holder, ready, source, amount, raw,
                            () -> player.getInventory().setItem(hotbar, stack(source, count(source) - amount)),
                            () -> player.getInventory().setItem(hotbar, source));
                }
            }
            case COLLECT_TO_CURSOR -> collect(player, holder, ready, cursor);
            case DROP_ONE_SLOT, DROP_ALL_SLOT -> {
                if (!top || !valid(shown)) break;
                final int amount = bounded(ready, shown, action == InventoryAction.DROP_ONE_SLOT ? 1 : count(shown));
                take(player, holder, ready, raw, shown, amount, () -> {
                    final var item = holder.location.getWorld().dropItem(holder.location.clone().add(0.5, 0.5, 0.5),
                            stack(shown, amount));
                    if (item.isValid() && !item.isDead() && same(item.getItemStack(), stack(shown, amount))) return true;
                    item.remove();
                    if (item.isValid()) throw new IllegalStateException("Rejected GUI item drop could not be removed");
                    return false;
                });
            }
            // Bundle editing, creative cloning and unknown actions cannot mutate projected stock.
            default -> { }
        }
        final Ready fresh = active(player, holder, false);
        if (fresh != null) render(holder, fresh, false);
    }

    void drag(final InventoryDragEvent event, final DrawerPageHolder holder) {
        if (event.isCancelled() || !(event.getWhoClicked() instanceof Player player)
                || event.getRawSlots().stream().noneMatch(slot -> slot >= 0 && slot < DrawerPageHolder.SIZE)) return;
        flushNative(holder);
        final Ready ready;
        try {
            ready = active(player, holder, true);
            if (ready != null && nativeDrag(event, holder, ready)) return;
        } catch (final RuntimeException exception) {
            event.setCancelled(true);
            context.logger().warning("Rejected native drawer drag: " + exception.getMessage());
            message(player, "general.configuration-error");
            return;
        }
        if (ready == null) { event.setCancelled(true); return; }
        // Paper restores the old cursor on DENY. Never consume it manually in a cancelled drag.
        event.setCancelled(true);
    }

    /** A normal move is left uncancelled, so server and client execute the same container rules. */
    private boolean nativeClick(final InventoryClickEvent event, final DrawerPageHolder holder, final Ready ready) {
        final int raw = event.getRawSlot();
        final boolean top = raw >= 0 && raw < DrawerPageHolder.ITEM_SLOTS;
        if (raw >= DrawerPageHolder.ITEM_SLOTS && raw < DrawerPageHolder.SIZE) return false;
        final Player player = (Player) event.getWhoClicked();
        final ItemStack shown = top ? holder.inventory.getItem(raw) : null;
        final ItemStack cursor = player.getItemOnCursor();
        final InventoryAction action = event.getAction();
        final ItemStack identity;
        final int delta;
        switch (action) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE -> {
                if (!top || !valid(shown) || valid(cursor) && !cursor.isSimilar(shown)) return false;
                identity = shown;
                final int wanted = action == InventoryAction.PICKUP_HALF ? (count(shown) + 1) / 2
                        : action == InventoryAction.PICKUP_ONE ? 1 : count(shown);
                delta = -Math.min(wanted, room(cursor, shown, 64));
            }
            case PLACE_ALL, PLACE_SOME, PLACE_ONE -> {
                if (!top || !valid(cursor)) return false;
                identity = cursor;
                delta = Math.min(action == InventoryAction.PLACE_ONE ? 1 : count(cursor), room(shown, cursor, 64));
            }
            case HOTBAR_SWAP -> {
                if (!top) return false;
                final int slot = event.getClick() == ClickType.SWAP_OFFHAND ? 40 : event.getHotbarButton();
                if (slot < 0 || slot > 8 && slot != 40) return false;
                final ItemStack source = player.getInventory().getItem(slot);
                if (valid(source) && valid(shown) && !source.isSimilar(shown)) return false;
                identity = valid(source) ? source : shown;
                delta = count(source) - count(shown);
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                identity = top ? shown : event.getCurrentItem();
                if (!valid(identity) || matchesControl(holder, identity)) return false;
                if (top) delta = -count(shown); // Native may move fewer items when the player has little space.
                else {
                    int room = 0;
                    for (int slot = 0; slot < DrawerPageHolder.ITEM_SLOTS; slot++) {
                        room += room(holder.inventory.getItem(slot), identity, 64);
                    }
                    delta = Math.min(count(identity), room);
                }
            }
            case COLLECT_TO_CURSOR -> {
                if (!valid(cursor) || matchesControl(holder, cursor)) return false;
                identity = cursor;
                // Vanilla searches the displayed inventory, even after another viewer emptied
                // the drawer or changed its identity. A zero logical delta cannot authorize
                // collecting those stale projected stacks without a transfer journal.
                final int available = visible(holder, cursor, null);
                if (available < 0 || available > 0 && !ready.state().matchesTemplate(cursor)) return false;
                delta = -Math.min(cursor.getMaxStackSize() - count(cursor), Math.max(0, available));
            }
            case NOTHING -> { return true; }
            default -> { return false; }
        }
        if (!valid(identity) || ready.state().hasTemplate() && !ready.state().matchesTemplate(identity)) {
            // Only an empty matching projection proves collection stays in the player's inventory.
            return action == InventoryAction.COLLECT_TO_CURSOR && delta == 0;
        }
        if (delta < 0 && -delta > ready.total() || delta > 0 && delta > free(ready, identity)) return false;
        return beginNative(player, holder, ready, identity, delta, event);
    }

    private boolean nativeDrag(final InventoryDragEvent event, final DrawerPageHolder holder, final Ready ready) {
        final Player player = (Player) event.getWhoClicked();
        final ItemStack identity = event.getOldCursor();
        // Paper sets the carried item to the NEW cursor before firing InventoryDragEvent.
        // The event's old cursor remains the authoritative source for its proposed distribution.
        if (!valid(identity)
                || ready.state().hasTemplate() && !ready.state().matchesTemplate(identity)) return false;
        int delta = 0;
        int distributed = 0;
        for (final var entry : event.getNewItems().entrySet()) {
            final int raw = entry.getKey();
            if (raw < 0 || raw >= event.getView().countSlots()
                    || raw >= DrawerPageHolder.ITEM_SLOTS && raw < DrawerPageHolder.SIZE
                    || !identity.isSimilar(entry.getValue())) return false;
            final ItemStack before = event.getView().getItem(raw);
            final int added = count(entry.getValue()) - count(before);
            if (added < 0 || count(entry.getValue()) > identity.getMaxStackSize()
                    || valid(before) && !before.isSimilar(identity)) return false;
            distributed += added;
            if (raw < DrawerPageHolder.ITEM_SLOTS) delta += added;
        }
        if (distributed != count(identity) - count(event.getCursor()) || delta > free(ready, identity)) return false;
        return beginNative(player, holder, ready, identity, delta, event);
    }

    private boolean beginNative(final Player player, final DrawerPageHolder holder, final Ready ready,
            final ItemStack identity, final int delta, final org.bukkit.event.Cancellable event) {
        if (delta == 0) return true;
        final int before = visible(holder, identity, null);
        if (before < 0) return false;
        final var ticket = nativeMoves.prepare(ready, player.getUniqueId(), identity, delta, before);
        if (ticket == null) return false;
        holder.nativeMove.set(ticket);
        holder.nativeEvent = event;
        holder.nativeDispatchComplete = false;
        if (!holder.nativeCompletionQueued) {
            holder.nativeCompletionQueued = true;
            final Runnable complete = () -> {
                holder.nativeCompletionQueued = false;
                holder.nativeDispatchComplete = true;
                flushNative(holder);
            };
            try { context.execution().runOnEntity(player, task -> complete.run(), complete); }
            catch (final RuntimeException exception) { holder.nativeCompletionQueued = false; throw exception; }
        }
        return true;
    }

    private boolean matchesControl(final DrawerPageHolder holder, final ItemStack item) {
        for (int slot = DrawerPageHolder.ITEM_SLOTS; slot < DrawerPageHolder.SIZE; slot++) {
            final ItemStack control = holder.inventory.getItem(slot);
            if (valid(control) && control.isSimilar(item)) return true;
        }
        return false;
    }

    private static int visible(final DrawerPageHolder holder, final ItemStack template, final int[] amounts) {
        int total = 0;
        for (int slot = 0; slot < DrawerPageHolder.ITEM_SLOTS; slot++) {
            final ItemStack item = holder.inventory.getItem(slot);
            if (valid(item) && !item.isSimilar(template)) return -1;
            final int count = count(item);
            if (amounts != null) amounts[slot] = count;
            total += count;
        }
        return total;
    }

    private void flushNative(final DrawerPageHolder holder) {
        if (!holder.nativeDispatchComplete) return;
        final var ticket = holder.nativeMove.get();
        if (ticket == null) return;
        final int[] amounts = new int[DrawerPageHolder.ITEM_SLOTS];
        final int after = visible(holder, ticket.template(), amounts);
        if (!holder.nativeMove.compareAndSet(ticket, null)) return;
        holder.nativeEvent = null;
        System.arraycopy(amounts, 0, holder.amounts, 0, amounts.length);
        holder.itemPaintNeeded = false;
        final Runnable commit = () -> {
            if (nativeMoves.complete(ticket, after)) {
                holder.stockAnchor = ticket.journal().totalBefore() + after - ticket.visibleBefore();
                if (after > 0) holder.template = ticket.template();
            }
        };
        if (Bukkit.isOwnedByCurrentRegion(ticket.location())) commit.run();
        else {
            try { context.execution().executeAt(ticket.location(), commit); }
            catch (final RuntimeException exception) {
                context.logger().warning("Native drawer transfer remains guarded during shutdown: " + exception.getMessage());
            }
        }
    }

    void observed(final InventoryClickEvent event, final DrawerPageHolder holder) {
        if (holder.nativeEvent == event) holder.nativeDispatchComplete = true;
    }

    void observed(final InventoryDragEvent event, final DrawerPageHolder holder) {
        if (holder.nativeEvent == event) holder.nativeDispatchComplete = true;
    }

    private void collect(final Player player, final DrawerPageHolder holder, final Ready ready, final ItemStack cursor) {
        if (!valid(cursor)) return;
        final ItemStack[] before = copies(player.getInventory().getStorageContents());
        final ItemStack[] after = copies(before);
        int wanted = cursor.getMaxStackSize() - count(cursor);
        int collected = 0;
        for (int i = 0; i < after.length && wanted > 0; i++) {
            if (!valid(after[i]) || !after[i].isSimilar(cursor)) continue;
            final int amount = Math.min(wanted, count(after[i]));
            after[i] = stack(after[i], count(after[i]) - amount);
            wanted -= amount; collected += amount;
        }
        final int fromDrawer = bounded(ready, cursor, wanted);
        final int result = count(cursor) + collected + fromDrawer;
        if (fromDrawer == 0) {
            player.getInventory().setStorageContents(after);
            player.setItemOnCursor(stack(cursor, result));
        } else take(player, holder, ready, -1, cursor, fromDrawer, () -> {
            if (!same(player.getItemOnCursor(), cursor)
                    || !sameContents(before, player.getInventory().getStorageContents())) return false;
            player.getInventory().setStorageContents(after);
            player.setItemOnCursor(stack(cursor, result)); return true;
        });
    }

    private void take(final Player player, final DrawerPageHolder holder, final Ready ready, final int slot,
            final ItemStack item, final int amount, final BooleanSupplier delivery) {
        if (amount <= 0) return;
        final var result = transfers.transferToPlayer(ready.barrel(), item, amount, (template, delivered) -> {
            if (delivered != amount || !template.isSimilar(item)) return DrawerSaleApi.PaymentOutcome.REJECTED;
            return delivery.getAsBoolean() ? DrawerSaleApi.PaymentOutcome.PAID : DrawerSaleApi.PaymentOutcome.REJECTED;
        });
        if (result == DrawerSaleApi.SaleResult.SOLD) {
            holder.stockAnchor = ready.total() - amount;
            if (slot >= 0) holder.amounts[slot] = Math.max(0, holder.amounts[slot] - amount);
            else holder.distribute(Math.max(0, java.util.Arrays.stream(holder.amounts).sum() - amount),
                    item.getMaxStackSize(), false);
            holder.itemPaintNeeded = true;
        } else if (result != DrawerSaleApi.SaleResult.REJECTED) message(player, "drawer.inventory-busy");
    }

    private boolean deposit(final Player player, final DrawerPageHolder holder, final Ready ready,
            final ItemStack item, final int requested, final int targetSlot, final Runnable consume, final Runnable restore) {
        if (!valid(item)) return false;
        DrawerState state = ready.state();
        if (state.hasTemplate() && !state.matchesTemplate(item)) { message(player, "drawer.wrong-item"); return false; }
        final int amount = Math.min(requested, free(ready, item));
        if (amount <= 0) {
            if (free(ready, item) == 0) message(player, "drawer.full");
            return false;
        }
        if (!state.hasTemplate()) {
            final var tier = context.tier(state.tierId()).orElse(null);
            if (tier == null) return false;
            state = state.initializeCapacityForFirstItem(tier.tier(), item).withTemplate(item);
        }
        final DrawerState after = state.migrateToReservedMirror(ready.stock().count())
                .withStoredTotalAndExpectedMirrorCount(ready.total() + amount, ready.stock().count());
        boolean saved = false;
        try {
            consume.run();
            final Barrel fresh = context.blocks().barrel(ready.barrel().getBlock()).orElseThrow();
            if (!context.repository().save(fresh, after)) throw new IllegalStateException("Could not persist deposit");
            saved = true;
            holder.stockAnchor = ready.total() + amount;
            if (targetSlot >= 0) holder.amounts[targetSlot] += amount;
            else holder.distribute(Math.min(DrawerPageHolder.ITEM_SLOTS * item.getMaxStackSize(),
                    java.util.Arrays.stream(holder.amounts).sum() + amount), item.getMaxStackSize(), false);
            holder.itemPaintNeeded = true;
            context.notifyCommitted(fresh, after, ready.stock().count(), DrawerChangeCause.PLAYER_DEPOSIT);
            hoppers.queueRebalance(fresh);
            return true;
        } catch (final RuntimeException exception) {
            if (!saved) restore.run();
            context.logger().warning("Drawer chest deposit " + (saved ? "refresh failed" : "rejected") + ": " + exception.getMessage());
            if (!saved) message(player, "general.configuration-error");
            return saved;
        }
    }

    private int free(final Ready ready, final ItemStack item) {
        if (!valid(item)) return 0;
        long capacity = ready.state().capacitySnapshot();
        if (!ready.state().hasTemplate()) {
            final var tier = context.tier(ready.state().tierId()).orElse(null);
            if (tier == null) return 0;
            capacity = tier.tier().capacityFor(item);
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, capacity - ready.total()));
    }

    private static int bounded(final Ready ready, final ItemStack item, final int wanted) {
        return ready.state().matchesTemplate(item) ? (int) Math.min(ready.total(), Math.max(0, wanted)) : 0;
    }

    private Ready active(final Player player, final DrawerPageHolder holder, final boolean notify) {
        if (stopped || holder.closed || sessions.get(player.getUniqueId()) != holder
                || !player.getOpenInventory().getTopInventory().equals(holder.inventory)) return null;
        return ready(player, holder, notify);
    }

    private Ready ready(final Player player, final DrawerPageHolder holder, final boolean notify) {
        if (!player.isOnline() || !holder.owner.equals(player.getUniqueId())
                || !player.getWorld().equals(holder.location.getWorld())
                || player.getLocation().distanceSquared(holder.location) > 64D
                || !Bukkit.isOwnedByCurrentRegion(holder.location)) {
            if (notify) message(player, "drawer.menu-too-far");
            return null;
        }
        final Barrel barrel = context.blocks().barrel(holder.location.getBlock()).orElse(null);
        if (barrel == null || !context.repository().isDrawer(barrel)) return null;
        if (!player.hasPermission("devstoragedrawer.use") || !protection.canWithdraw(player, barrel)) {
            if (notify) message(player, "general.no-permission"); return null;
        }
        if (!(context.repository().read(barrel) instanceof DrawerStateReadResult.Valid read)
                || read.state().hasPendingProxyJournal()) {
            if (notify) message(player, "drawer.inventory-busy"); return null;
        }
        final var stock = context.blocks().inspect(barrel, read.state());
        if (!stock.matchesTemplate()) return null;
        try { return new Ready(barrel, read.state(), stock, read.state().totalForPhysical(stock.count())); }
        catch (final IllegalArgumentException | com.teenkung.devstoragedrawer.domain.DrawerInvariantViolationException exception) {
            return null;
        }
    }

    void render(final DrawerPageHolder holder, final Ready ready, boolean reset) {
        if (holder.nativeMove.get() != null) return;
        final var menuSettings = context.settings().storageMenu();
        final var messages = context.messages();
        final boolean footerChanged = holder.displayedMenu != menuSettings || holder.displayedMessages != messages;
        final boolean sameTemplate = ready.state().hasTemplate() ? ready.state().matchesTemplate(holder.template)
                : !valid(holder.template);
        if (!sameTemplate) reset = true;
        // Fresh counts were already read above; unchanged frames need no template or item clones.
        if (!reset && !footerChanged && !holder.itemPaintNeeded && holder.displayedTotal == ready.total()
                && holder.displayedCapacity == ready.state().capacitySnapshot()) return;
        final ItemStack template = sameTemplate ? holder.template : ready.state().template().orElse(null);
        final int stackSize = valid(template) ? template.getMaxStackSize() : 64;
        final long pages = DrawerPageHolder.pages(ready.total(), ready.state().capacitySnapshot(), stackSize);
        if (holder.page >= pages) { holder.page = pages - 1; reset = true; }
        holder.template = template;
        final int budget = DrawerPageHolder.pageAmount(ready.total(), holder.page, stackSize);
        final int current = java.util.Arrays.stream(holder.amounts).sum();
        final long arrivals = Math.max(0, ready.total() - holder.stockAnchor);
        final int desired = reset ? budget : (int) Math.min(budget, Math.min((long) current + arrivals,
                (long) DrawerPageHolder.ITEM_SLOTS * stackSize));
        final boolean paint = reset || current != desired || holder.itemPaintNeeded;
        if (reset || current != desired) holder.distribute(desired, stackSize, reset);
        holder.stockAnchor = ready.total();
        if (paint) for (int slot = 0; slot < DrawerPageHolder.ITEM_SLOTS; slot++) {
            final ItemStack existing = holder.inventory.getItem(slot);
            final int amount = holder.amounts[slot];
            if (amount == count(existing) && (amount == 0 || similar(existing, template))) continue;
            holder.inventory.setItem(slot, stack(template, amount));
        }
        holder.itemPaintNeeded = false;
        if (reset || footerChanged || holder.pages != pages || holder.displayedTotal != ready.total()
                || holder.displayedCapacity != ready.state().capacitySnapshot()) {
            holder.pages = pages;
            final Map<String, Object> values = Map.of("page", holder.page + 1, "pages", pages,
                    "amount", ready.total(), "capacity", ready.state().capacitySnapshot());
            if (reset || footerChanged) {
                final ItemStack separator = menuItems.create(menuSettings, DrawerMenuIcon.SEPARATOR);
                final var meta = separator.getItemMeta();
                if (meta != null) {
                    meta.displayName(messages.dialogComponent("storage.separator"));
                    separator.setItemMeta(meta);
                }
                for (final int slot : new int[] {46, 47, 50, 51, 52}) setChanged(holder.inventory, slot, separator.clone());
            }
            setChanged(holder.inventory, 45, button(menuSettings, messages, holder.page > 0 ? DrawerMenuIcon.PREVIOUS : DrawerMenuIcon.FIRST,
                    holder.page > 0 ? "storage.previous" : "storage.first", values));
            setChanged(holder.inventory, 49, button(menuSettings, messages, DrawerMenuIcon.PAGE, "storage.page", values));
            setChanged(holder.inventory, 48, upgradeButton(menuSettings, messages, ready));
            setChanged(holder.inventory, 53, button(menuSettings, messages, holder.page + 1 < pages ? DrawerMenuIcon.NEXT : DrawerMenuIcon.LAST,
                    holder.page + 1 < pages ? "storage.next" : "storage.last", values));
            holder.displayedTotal = ready.total(); holder.displayedCapacity = ready.state().capacitySnapshot();
            holder.displayedMenu = menuSettings; holder.displayedMessages = messages;
        }
    }

    private ItemStack upgradeButton(final DrawerSettings.StorageMenu menuSettings, final DrawerMessages messages,
            final Ready ready) {
        if (!upgradeEnabled()) {
            return menuItems.create(menuSettings, DrawerMenuIcon.SEPARATOR);
        }
        final ItemStack item = menuItems.create(menuSettings, DrawerMenuIcon.UPGRADE);
        final var meta = item.getItemMeta();
        if (meta != null) {
            final var current = context.tier(ready.state().tierId()).orElse(null);
            final var path = current == null ? null : current.upgradePath();
            final var next = path == null ? null : context.tier(path.nextTierId()).orElse(null);
            meta.displayName(messages.dialogComponent("storage.upgrade"));
            meta.lore(java.util.List.of(messages.dialogComponent(next == null
                    ? "storage.upgrade-maximum" : "storage.upgrade-available", next == null ? Map.of() : Map.of(
                            "tier", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                    .serialize(current.itemName()),
                            "next", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                    .serialize(next.itemName())))));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack button(final DrawerSettings.StorageMenu menuSettings, final DrawerMessages messages,
            final DrawerMenuIcon icon, final String key, final Map<String, Object> values) {
        final ItemStack item = menuItems.create(menuSettings, icon);
        final var meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.dialogComponent(key, values));
            meta.lore(java.util.List.of(messages.dialogComponent("storage.stock", values),
                    messages.dialogComponent(key.equals("storage.page") ? "storage.controls" : "storage.navigation", values)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void schedule(final Player player, final DrawerPageHolder holder) {
        if (stopped || holder.closed) return;
        context.execution().runLaterOnEntity(player, context.settings().storageMenu().refreshTicks(), task -> {
                    if (stopped || holder.closed || sessions.get(player.getUniqueId()) != holder) return;
                    if (!player.getOpenInventory().getTopInventory().equals(holder.inventory)) { close(holder); return; }
                    flushNative(holder);
                    final Ready ready = active(player, holder, false);
                    if (ready == null) {
                        // Busy transfers retain the view. Distance/region changes and removed blocks close safely.
                        if (!player.getWorld().equals(holder.location.getWorld())
                                || player.getLocation().distanceSquared(holder.location) > 64D
                                || !Bukkit.isOwnedByCurrentRegion(holder.location)
                                || context.blocks().barrel(holder.location.getBlock())
                                        .filter(context.repository()::isDrawer).isEmpty()
                                || !player.hasPermission("devstoragedrawer.use")) {
                            close(holder); player.closeInventory(); return;
                        }
                    } else render(holder, ready, false);
                    schedule(player, holder);
                }, () -> close(holder));
    }
    private boolean upgradeEnabled() {
        return context.settings().upgrade() != null && context.settings().upgrade().enabled();
    }

    void close(final DrawerPageHolder holder) {
        if (!holder.nativeDispatchComplete && holder.nativeEvent != null) {
            holder.nativeEvent.setCancelled(true);
            holder.nativeDispatchComplete = true;
        }
        flushNative(holder);
        holder.closed = true;
        sessions.remove(holder.owner, holder);
        holder.inventory.clear();
    }
    void quit(final UUID player) { final var holder = sessions.remove(player); if (holder != null) close(holder); }
    void disable() {
        stopped = true;
        // These are plugin-created projections, not live block inventories. Clear them immediately:
        // entity tasks may no longer run once the plugin's listeners/schedulers are removed.
        sessions.values().forEach(this::close);
        sessions.clear();
    }
    private void message(final Player player, final String key) { player.sendMessage(context.messages().component(key)); }
    private static boolean valid(final ItemStack item) { return DrawerItemIdentity.isStorageCandidate(item); }
    private static int count(final ItemStack item) { return valid(item) ? item.getAmount() : 0; }
    private static ItemStack copy(final ItemStack item) { return valid(item) ? item.clone() : null; }
    private static ItemStack stack(final ItemStack template, final int amount) {
        if (!valid(template) || amount <= 0) return null;
        final ItemStack result = template.clone(); result.setAmount(amount); return result;
    }
    private static boolean similar(final ItemStack a, final ItemStack b) {
        return !valid(a) && !valid(b) || valid(a) && valid(b) && a.isSimilar(b);
    }
    private static boolean same(final ItemStack a, final ItemStack b) { return similar(a, b) && count(a) == count(b); }
    private static void setChanged(final Inventory inventory, final int slot, final ItemStack next) {
        if (!same(inventory.getItem(slot), next)) inventory.setItem(slot, next);
    }
    private static int room(final ItemStack current, final ItemStack incoming, final int limit) {
        return valid(incoming) && (!valid(current) || current.isSimilar(incoming))
                ? Math.max(0, Math.min(limit, incoming.getMaxStackSize()) - count(current)) : 0;
    }
    private static ItemStack[] copies(final ItemStack[] items) {
        final ItemStack[] result = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) result[i] = copy(items[i]);
        return result;
    }

    private static boolean sameContents(final ItemStack[] a, final ItemStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!same(a[i], b[i])) return false;
        return true;
    }
    private static int add(final ItemStack[] slots, final ItemStack item, final int requested, final int limit) {
        int left = requested;
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < slots.length && left > 0; i++) {
                if ((pass == 0) != valid(slots[i])) continue;
                final int amount = Math.min(left, room(slots[i], item, limit));
                if (amount == 0) continue;
                slots[i] = stack(item, count(slots[i]) + amount); left -= amount;
            }
        }
        return requested - left;
    }
    record Ready(Barrel barrel, DrawerState state, DrawerBlockAccess.PhysicalStock stock, long total) { }
}
