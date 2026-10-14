package com.teenkung.devstoragedrawer.interaction;

import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** One bounded projection of logical stock; no displayed stack is itself storage. */
final class DrawerPageHolder implements InventoryHolder {
    static final int ITEM_SLOTS = 45;
    static final int SIZE = 54;
    final UUID owner;
    final Location location;
    final int[] amounts = new int[ITEM_SLOTS];
    Inventory inventory;
    ItemStack template;
    long page;
    long pages = 1;
    long displayedTotal = -1;
    long displayedCapacity = -1;
    DrawerSettings.StorageMenu displayedMenu;
    DrawerMessages displayedMessages;
    volatile long stockAnchor = -1;
    final java.util.concurrent.atomic.AtomicReference<DrawerNativeInventoryMove.Ticket> nativeMove =
            new java.util.concurrent.atomic.AtomicReference<>();
    org.bukkit.event.Cancellable nativeEvent;
    boolean nativeDispatchComplete;
    boolean nativeCompletionQueued;
    boolean itemPaintNeeded;
    volatile boolean closed;

    DrawerPageHolder(final UUID owner, final Location location) {
        this.owner = owner;
        this.location = location.clone();
    }

    static long pages(final long total, final int stackSize) {
        return total == 0 ? 1 : 1 + (total - 1) / ((long) ITEM_SLOTS * stackSize);
    }

    static long pages(final long total, final long capacity, final int stackSize) {
        final long used = pages(total, stackSize);
        // A full last page must not prevent cursor deposits into remaining logical capacity.
        return total > 0 && total < capacity && total % ((long) ITEM_SLOTS * stackSize) == 0 ? used + 1 : used;
    }

    static int pageAmount(final long total, final long page, final int stackSize) {
        final long span = (long) ITEM_SLOTS * stackSize;
        return (int) Math.min(span, Math.max(0L, total - Math.multiplyExact(page, span)));
    }

    /** Keep holes/split stacks where the player left them; external changes affect the tail. */
    void distribute(final int desired, final int stackSize, final boolean reset) {
        if (reset) java.util.Arrays.fill(amounts, 0);
        int delta = desired - java.util.Arrays.stream(amounts).sum();
        for (int slot = reset ? 0 : ITEM_SLOTS - 1;
                slot >= 0 && slot < ITEM_SLOTS && delta != 0; slot += reset ? 1 : -1) {
            final int moved = delta > 0 ? Math.min(delta, stackSize - amounts[slot])
                    : -Math.min(-delta, amounts[slot]);
            amounts[slot] += moved;
            delta -= moved;
        }
    }

    @Override public Inventory getInventory() { return inventory; }
}
