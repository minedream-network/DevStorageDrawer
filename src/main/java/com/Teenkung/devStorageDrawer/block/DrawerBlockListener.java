package com.teenkung.devstoragedrawer.block;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.config.DrawerDialogTypeFactory;
import com.teenkung.devstoragedrawer.config.DrawerDestroyMenu;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.domain.DrawerInvariantViolationException;
import com.teenkung.devstoragedrawer.domain.DrawerJournalKind;
import com.teenkung.devstoragedrawer.domain.DrawerJournalReconciliation;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Placement, safe breaking, explosion/piston protection, and loaded-barrel repair. */
public final class DrawerBlockListener implements Listener {

    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;
    private final DrawerBreakDrops breakDrops;
    private final Map<UUID, DrawerBreakTicket> pendingBreaks;

    public DrawerBlockListener(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers) {
        this(context, hoppers, new DrawerBreakDrops(context, hoppers), new ConcurrentHashMap<>());
    }

    DrawerBlockListener(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers,
            final DrawerBreakDrops breakDrops, final Map<UUID, DrawerBreakTicket> pendingBreaks) {
        this.context = Objects.requireNonNull(context, "context");
        this.hoppers = Objects.requireNonNull(hoppers, "hoppers");
        this.breakDrops = Objects.requireNonNull(breakDrops, "breakDrops");
        this.pendingBreaks = Objects.requireNonNull(pendingBreaks, "pendingBreaks");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final Barrel barrel = this.context.blocks().barrel(event.getBlockPlaced()).orElse(null);
        if (barrel == null) {
            return;
        }
        final DrawerTierDefinition tier = this.context.blocks().tierForPlacedItem(event.getItemInHand()).orElse(null);
        if (tier == null) {
            return;
        }
        if (tier.tier().optionalPlacementPermission().filter(permission -> !event.getPlayer().hasPermission(permission)).isPresent()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(this.context.messages().component("general.no-permission"));
            return;
        }
        final DrawerState state = DrawerState.empty(tier.tier().id(), tier.tier().stackCapacity());
        if (!this.context.repository().save(barrel, state)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(this.context.messages().component("general.configuration-error"));
            return;
        }
        this.context.notifyCommitted(barrel, state, 0L, DrawerChangeCause.PLACED);
        this.hoppers.watch(barrel);
    }

    // A confirmed break performs its own durable drop transaction. Inspect the final
    // protection decision before cancelling vanilla destruction and starting that work.
    // Cancelling at HIGHEST would hide this event from later HIGHEST protection listeners
    // registered with ignoreCancelled=true.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final Barrel barrel = this.context.blocks().barrel(event.getBlock()).orElse(null);
        if (barrel == null || !this.context.repository().isDrawer(barrel)) {
            return;
        }
        if (this.breakDrops.isInProgress(barrel)) {
            event.setCancelled(true);
            return;
        }
        final DrawerStateReadResult read = this.context.repository().read(barrel);
        if (!(read instanceof DrawerStateReadResult.Valid valid)) {
            denyUnsafeBreak(event, barrel, "its persisted drawer state is invalid");
            return;
        }
        DrawerState state = this.breakDrops.recoverInterrupted(barrel, valid.state());
        final DrawerBlockAccess.PhysicalStock stock = this.context.blocks().inspect(barrel, state);
        if (!stock.matchesTemplate()) {
            denyUnsafeBreak(event, barrel, "its proxy inventory does not match the drawer item");
            return;
        }
        if (state.hasPendingProxyJournal()) {
            if (state.proxyJournal().filter(journal -> journal.kind() == DrawerJournalKind.PLAYER_WITHDRAWAL).isPresent()) {
                denyUnsafeBreak(event, barrel, "a player withdrawal is still being recovered");
                return;
            }
            try {
                final DrawerJournalReconciliation recovery = this.context.storage().reconcile(state, stock.count());
                if (recovery.requiresManualRecovery()) {
                    denyUnsafeBreak(event, barrel, "its pending recovery journal requires reconciliation ("
                            + recovery.resolution() + ")");
                    return;
                }
                state = recovery.state();
            } catch (final DrawerInvariantViolationException exception) {
                denyUnsafeBreak(event, barrel, "its hopper recovery journal cannot be resolved: " + exception.getMessage());
                return;
            }
        }
        final DrawerTierDefinition tier = this.context.tier(state.tierId()).orElse(null);
        if (tier == null) {
            denyUnsafeBreak(event, barrel, "its configured tier no longer exists");
            return;
        }
        final long total;
        try {
            total = state.totalForPhysical(stock.count());
        } catch (final DrawerInvariantViolationException exception) {
            denyUnsafeBreak(event, barrel, "its stored item total is invalid");
            return;
        }

        final String fingerprint;
        try {
            // Approval identifies the drawer and item, while quantity is read again when the
            // confirmed break starts. Hopper transfers must not invalidate an open dialog.
            // The drop journal debits that fresh total, including transfers already completed.
            final byte[] template = state.serializedTemplate();
            fingerprint = state.tierId() + ":" + state.capacitySnapshot() + ":"
                    + (template == null ? "" : Base64.getEncoder().encodeToString(template));
        } catch (final RuntimeException exception) {
            denyUnsafeBreak(event, barrel, "its state could not be checked before confirmation");
            return;
        }
        final DrawerBreakTicket.BlockKey key = new DrawerBreakTicket.BlockKey(
                barrel.getWorld().getUID(), barrel.getX(), barrel.getY(), barrel.getZ()
        );
        final UUID playerId = event.getPlayer().getUniqueId();
        final DrawerBreakTicket pending = this.pendingBreaks.get(playerId);
        final long now = System.currentTimeMillis();
        if (pending == null || !pending.hasApproval()) {
            event.setCancelled(true);
            if (pending != null && pending.isDialogQueued(playerId, key, now)) return;
            showBreakConfirmation(event.getPlayer(), key, fingerprint, total);
            return;
        }
        if (!pending.consume(playerId, key, fingerprint, now)) {
            event.setCancelled(true);
            this.pendingBreaks.remove(playerId, pending);
            event.getPlayer().sendMessage(this.context.messages().component("drawer.break-changed"));
            return;
        }
        this.pendingBreaks.remove(playerId, pending);

        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);
        try {
            this.breakDrops.begin(event.getPlayer(), barrel, state, tier.createItem());
        } catch (final RuntimeException exception) {
            denyUnsafeBreak(event, barrel, "its drops could not be prepared: " + exception.getMessage());
        }
    }

    private void showBreakConfirmation(
            final Player player,
            final DrawerBreakTicket.BlockKey key,
            final String fingerprint,
            final long total
    ) {
        final UUID playerId = player.getUniqueId();
        final DrawerBreakTicket ticket = new DrawerBreakTicket(playerId, key, fingerprint, System.currentTimeMillis());
        this.pendingBreaks.put(playerId, ticket);
        this.context.execution().runOnEntity(player, ignored -> {
            if (!player.isOnline() || this.pendingBreaks.get(playerId) != ticket) {
                return;
            }
            try {
                final Map<String, Object> values = Map.of(
                        "amount", total,
                        "world", player.getWorld().getName(),
                        "x", key.x(),
                        "y", key.y(),
                        "z", key.z()
                );
                final DrawerDestroyMenu menu = this.context.settings().destroyMenu();
                final boolean bedrock = menu.bedrock().enabled()
                        && this.context.plugin().getServer().getPluginManager().isPluginEnabled("floodgate")
                        && DrawerDestroyBedrock.isPlayer(player);
                final boolean opened;
                if (bedrock) {
                    opened = DrawerDestroyBedrock.show(player, this.context.messages(), menu, values,
                            action -> this.context.execution().runOnEntity(player,
                                    task -> respondToDestroy(player, ticket, action),
                                    () -> this.pendingBreaks.remove(playerId, ticket)));
                } else {
                    showJavaDestroyDialog(player, ticket, menu, values, total == 0L);
                    opened = true;
                }
                if (!opened) throw new IllegalStateException("the configured form could not be sent");
                ticket.markDialogShown();
            } catch (final RuntimeException | LinkageError exception) {
                this.pendingBreaks.remove(playerId, ticket);
                player.sendMessage(this.context.messages().component("drawer.break-unavailable"));
                this.context.logger().warning("Could not show drawer break dialog to " + player.getName()
                        + ": " + exception.getMessage());
            }
        }, () -> this.pendingBreaks.remove(playerId, ticket));
    }

    private void showJavaDestroyDialog(final Player player, final DrawerBreakTicket ticket,
            final DrawerDestroyMenu menu, final Map<String, Object> values, final boolean empty) {
        final DrawerDestroyMenu.Dialog configured = menu.confirmation();
        final List<ActionButton> actions = configured.visibleButtons().stream()
                .map(button -> destroyActionButton(player, ticket, button, menu, values, empty))
                .toList();
        final ActionButton exit = configured.exitButton().enabled()
                ? destroyActionButton(player, ticket, configured.exitButton(), menu, values, empty) : null;
        final List<? extends DialogBody> bodies = configured.body().stream()
                .map(body -> DialogBody.plainMessage(
                        renderDestroy(menu, body.text(), values, empty), body.width()))
                .toList();
        final DialogBase.Builder base = DialogBase.builder(renderDestroy(menu, configured.title(), values, empty))
                .body(bodies)
                .canCloseWithEscape(configured.canCloseWithEscape())
                .pause(configured.pause())
                .afterAction(afterAction(configured.afterAction()));
        if (!configured.externalTitle().isBlank()) {
            base.externalTitle(renderDestroy(menu, configured.externalTitle(), values, empty));
        }
        final Dialog dialog = Dialog.create(factory -> factory.empty().base(base.build())
                .type(DrawerDialogTypeFactory.actionsOrNotice(actions, exit, configured.columns())));
        player.showDialog(dialog);
    }

    private ActionButton destroyActionButton(final Player player, final DrawerBreakTicket ticket,
            final DrawerDestroyMenu.Button configured, final DrawerDestroyMenu menu,
            final Map<String, Object> values, final boolean empty) {
        final ClickCallback.Options clickOptions = ClickCallback.Options.builder()
                .uses(1).lifetime(Duration.ofSeconds(60)).build();
        final ActionButton.Builder builder = ActionButton.builder(
                        renderDestroy(menu, configured.label(), values, empty))
                .width(configured.width())
                .action(DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player clicked)
                            || !clicked.getUniqueId().equals(player.getUniqueId())) return;
                    this.context.execution().runOnEntity(clicked,
                            task -> respondToDestroy(clicked, ticket, configured.action()),
                            () -> this.pendingBreaks.remove(clicked.getUniqueId(), ticket));
                }, clickOptions));
        if (!configured.tooltip().isBlank()) {
            builder.tooltip(renderDestroy(menu, configured.tooltip(), values, empty));
        }
        return builder.build();
    }

    private net.kyori.adventure.text.Component renderDestroy(final DrawerDestroyMenu menu,
            final String raw, final Map<String, Object> values, final boolean empty) {
        return this.context.messages().rawComponent(menu.expand(raw, empty), values);
    }

    private void respondToDestroy(final Player player, final DrawerBreakTicket ticket, final String action) {
        if (action.equals("confirm")) {
            confirmBreak(player, ticket);
        } else {
            this.pendingBreaks.remove(player.getUniqueId(), ticket);
        }
    }

    private static DialogBase.DialogAfterAction afterAction(final String configured) {
        return switch (configured.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "none" -> DialogBase.DialogAfterAction.NONE;
            case "wait", "wait-for-response" -> DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE;
            default -> DialogBase.DialogAfterAction.CLOSE;
        };
    }

    /** Called after the dialog closes, on the player's entity thread. */
    void confirmBreak(final Player player, final DrawerBreakTicket ticket) {
        final UUID playerId = player.getUniqueId();
        if (this.pendingBreaks.get(playerId) != ticket) return;
        try {
            final DrawerBreakTicket.BlockKey key = ticket.block();
            if (!player.isOnline() || !player.getWorld().getUID().equals(key.worldId())
                    || !ticket.approve(System.currentTimeMillis())) {
                player.sendMessage(this.context.messages().component("drawer.break-changed"));
                return;
            }
            final Location location = new Location(player.getWorld(), key.x(), key.y(), key.z());
            if (player.getLocation().distanceSquared(location) > 64D || !Bukkit.isOwnedByCurrentRegion(location)) {
                player.sendMessage(this.context.messages().component("drawer.break-changed"));
                return;
            }
            final Barrel current = this.context.blocks().barrel(location.getBlock()).orElse(null);
            if (current == null || !this.context.repository().isDrawer(current)) {
                player.sendMessage(this.context.messages().component("drawer.break-changed"));
                return;
            }
            // Re-enter the normal server break event so protection plugins can veto it again.
            // Our listener consumes this one-use approval and starts guarded drops immediately.
            player.breakBlock(location.getBlock());
            if (!ticket.wasConsumed() && this.pendingBreaks.get(playerId) == ticket) {
                player.sendMessage(this.context.messages().component("drawer.break-unavailable"));
            }
        } catch (final RuntimeException exception) {
            player.sendMessage(this.context.messages().component("drawer.break-unavailable"));
            this.context.logger().warning("Could not complete confirmed drawer break: " + exception.getMessage());
        } finally {
            this.pendingBreaks.remove(playerId, ticket);
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        this.pendingBreaks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(final BlockExplodeEvent event) {
        if (!this.context.settings().redstone().protectFromExplosions()) {
            return;
        }
        event.blockList().removeIf(this.context.blocks()::isDrawer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(final EntityExplodeEvent event) {
        if (!this.context.settings().redstone().protectFromExplosions()) {
            return;
        }
        event.blockList().removeIf(this.context.blocks()::isDrawer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        if (this.context.settings().redstone().preventPistonMovement()
                && event.getBlocks().stream().anyMatch(this.context.blocks()::isDrawer)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        if (this.context.settings().redstone().preventPistonMovement()
                && event.getBlocks().stream().anyMatch(this.context.blocks()::isDrawer)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        if (!this.context.settings().visuals().repairOnChunkLoad()
                && !this.context.settings().automation().recoverJournalOnLoad()) {
            return;
        }
        queueChunkRepair(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(final ChunkUnloadEvent event) {
        this.breakDrops.pause(event.getChunk());
        this.hoppers.unwatch(event.getChunk());
    }

    /** Queues every currently loaded chunk from the global scheduler, then inspects each on its owning region. */
    public void repairLoadedDrawers() {
        this.context.execution().runGlobal(ignored -> {
            final List<ChunkKey> loadedChunks = new ArrayList<>();
            for (final World world : Bukkit.getWorlds()) {
                for (final Chunk chunk : world.getLoadedChunks()) {
                    loadedChunks.add(new ChunkKey(world, chunk.getX(), chunk.getZ()));
                }
            }
            for (final ChunkKey chunk : loadedChunks) {
                final Location location = new Location(chunk.world(), chunk.x() << 4, chunk.world().getMinHeight(), chunk.z() << 4);
                this.context.execution().executeAt(location, () -> {
                    if (chunk.world().isChunkLoaded(chunk.x(), chunk.z())) {
                        queueChunkRepair(chunk.world().getChunkAt(chunk.x(), chunk.z()));
                    }
                });
            }
        });
    }

    private void queueChunkRepair(final Chunk chunk) {
        for (final BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Barrel barrel) || !this.context.repository().isDrawer(barrel)) {
                continue;
            }
            final Location location = barrel.getLocation();
            this.context.execution().executeAt(location, () -> {
                final Barrel live = this.context.blocks().barrel(location.getBlock()).orElse(null);
                if (live != null) {
                    if (this.context.repository().read(live) instanceof DrawerStateReadResult.Valid valid) {
                        this.breakDrops.recoverInterrupted(live, valid.state());
                    }
                    this.hoppers.watch(live);
                    this.hoppers.recoverAndRebalance(live);
                }
            });
        }
    }

    /** Never destroy a drawer when its logical contents cannot be returned safely. */
    private void denyUnsafeBreak(final BlockBreakEvent event, final Barrel barrel, final String reason) {
        event.setCancelled(true);
        this.hoppers.watch(barrel);
        this.context.logger().warning("Cancelled break for drawer at " + barrel.getLocation() + " because " + reason
                + "; the stored contents remain in place");
        event.getPlayer().sendMessage(this.context.messages().component("drawer.break-unavailable"));
    }

    void disable() {
        this.pendingBreaks.clear();
        this.breakDrops.disable();
    }

    private record ChunkKey(World world, int x, int z) {
    }
}
