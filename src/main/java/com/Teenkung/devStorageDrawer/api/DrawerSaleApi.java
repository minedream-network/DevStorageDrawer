package com.teenkung.devstoragedrawer.api;

import java.util.UUID;
import java.util.Objects;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;

/**
 * Compact, synchronous sale access for integrations already running on the drawer's owning
 * region. Drawer owns debit and known-failure compensation around the payment callback.
 * Unknown payment outcomes retain durable evidence; never retry a debit automatically.
 */
public interface DrawerSaleApi {
    String VERSION = "1.0.0";

    default String apiVersion() {
        return VERSION;
    }

    /** Authorizes the click and captures the logical stock on the block's owning region. */
    Probe probeSaleTarget(Player player, Block block, BlockFace face);

    /**
     * Rechecks the item and available quoted stock, journals the debit, and invokes payment synchronously.
     * Additional matching items may arrive after the probe; they remain in the drawer. The
     * callback receives at most min(maxAmount, expectedStock.amount()), never the new arrivals.
     * A lower total or changed item rejects the quote before invoking payment.
     * The caller must already own this region and have a durable audit for operationId.
     * REJECTED restores stock before returning; UNKNOWN retains the journal for manual recovery.
     * Never retry an uncertain operation or schedule payment outside this callback.
     */
    SaleResult withExternalSale(Location location, Probe expectedStock, long maxAmount,
                                UUID operationId, Payment callback);

    @FunctionalInterface
    interface Payment { PaymentOutcome pay(ItemStack template, long amount); }

    enum PaymentOutcome { PAID, REJECTED, UNKNOWN }
    enum SaleResult { SOLD, STOCK_CHANGED, REJECTED, UNAVAILABLE, RECONCILIATION_REQUIRED }

    enum Status { NOT_DRAWER, DENIED, EMPTY, READY, UNAVAILABLE }

    record Probe(Status status, ItemStack template, long amount) {
        public Probe {
            status = Objects.requireNonNull(status, "status");
            template = template == null ? null : template.clone();
            if (status == Status.READY ? template == null || amount <= 0
                : template != null || amount != 0) {
                throw new IllegalArgumentException("Invalid drawer sale probe");
            }
        }

        @Override public ItemStack template() { return template == null ? null : template.clone(); }
    }

}
