package com.teenkung.devstoragedrawer.block;

import com.teenkung.devstoragedrawer.api.DrawerSaleApi;
import com.teenkung.devstoragedrawer.interaction.DrawerWithdrawalProtection;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/** Public contract implementation; all methods are confined to the clicked block's region. */
final class DrawerSaleService implements DrawerSaleApi {
    private final DrawerRuntimeContext context;
    private final DrawerSellWandBridge bridge;
    private final DrawerWithdrawalProtection protection;
    private volatile boolean available = true;

    DrawerSaleService(DrawerRuntimeContext context, DrawerSellWandBridge bridge, DrawerWithdrawalProtection protection) {
        this.context = Objects.requireNonNull(context, "context");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @Override
    public Probe probeSaleTarget(Player player, Block block, BlockFace face) {
        if (!available) return new Probe(Status.UNAVAILABLE, null, 0L);
        if (block == null) {
            return new Probe(Status.NOT_DRAWER, null, 0L);
        }
        if (!Bukkit.isOwnedByCurrentRegion(block.getLocation())) {
            return new Probe(Status.UNAVAILABLE, null, 0L);
        }
        Barrel barrel = context.blocks().barrel(block).orElse(null);
        if (barrel == null || !context.repository().isDrawer(barrel)) {
            return new Probe(Status.NOT_DRAWER, null, 0L);
        }
        // Reject unauthorized clicks before decoding item metadata or scanning the mirror.
        if (face == null || !bridge.canSellFrom(player, barrel, face)
                || !protection.canWithdraw(player, barrel)) {
            return new Probe(Status.DENIED, null, 0L);
        }
        DrawerSellWandBridge.ReadyDrawer ready = bridge.readyDrawer(barrel);
        if (ready == null) return new Probe(Status.UNAVAILABLE, null, 0L);
        long total = ready.state().totalForPhysical(ready.stock().count());
        if (total > 0L && ready.state().hasTemplate()) {
            return new Probe(Status.READY, ready.state().requireTemplate(), total, player, face);
        }
        return new Probe(Status.EMPTY, null, 0L);
    }

    @Override
    public SaleResult withExternalSale(Location location, Probe expected, long maxAmount,
                                       UUID operationId, Payment callback) {
        if (!available || location == null || location.getWorld() == null || expected == null
                || expected.status() != Status.READY || maxAmount <= 0 || operationId == null || callback == null) {
            return SaleResult.UNAVAILABLE;
        }
        if (!Bukkit.isOwnedByCurrentRegion(location)) {
            return SaleResult.UNAVAILABLE;
        }
        Block block = location.getBlock();
        Barrel barrel = context.blocks().barrel(block).orElse(null);
        if (barrel == null) return SaleResult.STOCK_CHANGED;
        // A quote is only a snapshot. Permissions, facing and region protection may all
        // change while the caller prepares its payment audit; fail before any stock read or
        // journal write if the original click would no longer be permitted.
        final Player actor = expected.actor();
        final BlockFace face = expected.clickedFace();
        if (actor == null || face == null || !Bukkit.isOwnedByCurrentRegion(actor)
                || !actor.isOnline() || !context.repository().isDrawer(barrel)
                || !bridge.canSellFrom(actor, barrel, face)
                || !protection.canWithdraw(actor, barrel)) {
            return SaleResult.UNAVAILABLE;
        }
        // Hopper input may grow the stock while the seller prepares its audit. Recheck the
        // item and require at least the quoted stock; sell only the quoted amount so new
        // arrivals remain in the drawer and the payment still matches the prepared audit.
        DrawerSellWandBridge.ReadyDrawer ready = bridge.readyDrawer(barrel);
        if (ready == null || !ready.state().hasTemplate()
                || ready.state().totalForPhysical(ready.stock().count()) < expected.amount()
                || !ready.state().matchesTemplate(expected.template())) {
            return SaleResult.STOCK_CHANGED;
        }
        return bridge.withExternalSale(ready, Math.min(maxAmount, expected.amount()), operationId, callback);
    }

    void disable() { available = false; }
}
