package com.teenkung.devstoragedrawer.upgrade;

import com.teenkung.devstoragedrawer.api.DrawerChangeCause;
import com.teenkung.devstoragedrawer.block.DrawerBlockAccess;
import com.teenkung.devstoragedrawer.block.DrawerRuntimeContext;
import com.teenkung.devstoragedrawer.config.DrawerDialogTypeFactory;
import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.config.DrawerUpgradeMenu;
import com.teenkung.devstoragedrawer.config.DrawerUpgradePath;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.interaction.DrawerWithdrawalProtection;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/** Player-bound, stale-safe capacity upgrades for placed drawers. */
public final class DrawerUpgradeService implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final DrawerRuntimeContext context;
    private final DrawerHopperBridge hoppers;
    private final DrawerWithdrawalProtection protection;
    private final DrawerEconomy economy;
    private final UpgradeIntentStore intents;
    private final boolean ledgerReady;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<BlockKey, UUID> activePurchases = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    public DrawerUpgradeService(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers,
            final DrawerWithdrawalProtection protection) {
        this(context, hoppers, protection, economy(context));
    }

    private static DrawerEconomy economy(final DrawerRuntimeContext context) {
        if (!context.plugin().getServer().getPluginManager().isPluginEnabled("Vault")) {
            return UnavailableEconomy.INSTANCE;
        }
        try {
            return new VaultDrawerEconomy(context.plugin());
        } catch (final RuntimeException | LinkageError exception) {
            context.logger().warning("Vault is enabled but its economy API is unavailable: " + exception.getMessage());
            return UnavailableEconomy.INSTANCE;
        }
    }

    DrawerUpgradeService(final DrawerRuntimeContext context, final DrawerHopperBridge hoppers,
            final DrawerWithdrawalProtection protection, final DrawerEconomy economy) {
        this.context = Objects.requireNonNull(context, "context");
        this.hoppers = Objects.requireNonNull(hoppers, "hoppers");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.intents = new UpgradeIntentStore(context.plugin().getDataFolder().toPath().resolve("upgrade-intents"));
        boolean loaded = false;
        try {
            intents.load();
            loaded = true;
            for (final UpgradeIntentStore.Intent pending : intents.pending()) {
                context.logger().severe("Unresolved drawer upgrade " + pending.id() + " at " + pending.block()
                        + " for " + pending.player() + ": " + pending.status() + ", cost " + pending.cost());
            }
        } catch (final IOException exception) {
            context.logger().severe("Paid drawer upgrades disabled: upgrade intent ledger cannot load: "
                    + exception.getMessage());
        }
        this.ledgerReady = loaded;
    }

    /** Called from the storage-menu control after its projected inventory has closed. */
    public void open(final Player player, final Barrel original) {
        if (stopped) return;
        final View view = inspect(player, original, true);
        if (view != null) show(player, view, false);
    }

    private void show(final Player player, final View view, final boolean confirmation) {
        if (stopped || !player.isOnline()) return;
        final Session session = new Session(UUID.randomUUID(), view,
                System.nanoTime() + context.settings().upgrade().sessionSeconds() * 1_000_000_000L);
        sessions.put(player.getUniqueId(), session);
        final boolean maximum = view.path() == null;
        final boolean opened = context.plugin().getServer().getPluginManager().isPluginEnabled("floodgate")
                && DrawerUpgradeBedrock.isPlayer(player)
                && context.settings().upgradeMenu().bedrock().enabled()
                ? DrawerUpgradeBedrock.show(player, context.messages(), context.settings().upgradeMenu(), values(view),
                        confirmation, maximum, action -> respond(player, session, action))
                : showJava(player, session, confirmation, maximum);
        if (!opened) {
            sessions.remove(player.getUniqueId(), session);
            message(player, "upgrade.ui-unavailable", Map.of());
        }
    }

    private boolean showJava(final Player player, final Session session, final boolean confirmation,
            final boolean maximum) {
        try {
            final DrawerUpgradeMenu menu = context.settings().upgradeMenu();
            final DrawerUpgradeMenu.Dialog configured = confirmation ? menu.confirmation() : menu.main();
            final Map<String, Object> values = values(session.view);
            final List<ActionButton> actions = new ArrayList<>();
            for (final DrawerUpgradeMenu.Button button : configured.visibleButtons()) {
                if (maximum && button.action().equals("upgrade")) continue;
                actions.add(actionButton(player, session, button, values, menu, maximum));
            }
            final ActionButton exit = configured.exitButton().enabled()
                    ? actionButton(player, session, configured.exitButton(), values, menu, maximum) : null;
            final List<? extends DialogBody> bodies = configured.body().stream()
                    .map(body -> DialogBody.plainMessage(render(menu, body.text(), values, maximum), body.width()))
                    .toList();
            final DialogBase.Builder base = DialogBase.builder(render(menu, configured.title(), values, maximum))
                    .body(bodies)
                    .canCloseWithEscape(configured.canCloseWithEscape())
                    .pause(configured.pause())
                    .afterAction(afterAction(configured.afterAction()));
            if (!configured.externalTitle().isBlank()) {
                base.externalTitle(render(menu, configured.externalTitle(), values, maximum));
            }
            final Dialog dialog = Dialog.create(factory -> factory.empty().base(base.build())
                    .type(DrawerDialogTypeFactory.actionsOrNotice(actions, exit, configured.columns())));
            player.showDialog(dialog);
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            context.logger().warning("Could not show drawer upgrade dialog to " + player.getName() + ": "
                    + exception.getMessage());
            return false;
        }
    }

    private ActionButton actionButton(final Player player, final Session session,
            final DrawerUpgradeMenu.Button configured, final Map<String, Object> values,
            final DrawerUpgradeMenu menu, final boolean maximum) {
        final ClickCallback.Options options = ClickCallback.Options.builder().uses(1)
                .lifetime(Duration.ofSeconds(context.settings().upgrade().sessionSeconds())).build();
        final ActionButton.Builder builder = ActionButton.builder(render(menu, configured.label(), values, maximum))
                .width(configured.width())
                .action(DialogAction.customClick((response, audience) -> {
                    if (!(audience instanceof Player clicked)
                            || !clicked.getUniqueId().equals(player.getUniqueId())) return;
                    context.execution().runOnEntity(clicked, ignored -> respond(clicked, session, configured.action()),
                            () -> sessions.remove(clicked.getUniqueId(), session));
                }, options));
        if (!configured.tooltip().isBlank()) {
            builder.tooltip(render(menu, configured.tooltip(), values, maximum));
        }
        return builder.build();
    }

    private void respond(final Player player, final Session expected, final String action) {
        if (!expected.claimed.compareAndSet(false, true)
                || !sessions.remove(player.getUniqueId(), expected)) return;
        if (stopped || System.nanoTime() > expected.expires) {
            message(player, "upgrade.stale", Map.of());
            return;
        }
        if (action.equals("close")) return;
        final Location expectedLocation = expected.view.location();
        if (!player.getWorld().equals(expectedLocation.getWorld())
                || !Bukkit.isOwnedByCurrentRegion(expectedLocation)) {
            message(player, "upgrade.stale", Map.of());
            return;
        }
        final Barrel expectedBarrel = context.blocks().barrel(expectedLocation.getBlock()).orElse(null);
        final View current = inspect(player, expectedBarrel, false);
        if (current == null || !expected.view.sameRoute(current)) {
            message(player, "upgrade.stale", Map.of());
            return;
        }
        switch (action) {
            case "upgrade" -> show(player, current, true);
            case "back" -> show(player, current, false);
            case "confirm" -> purchase(player, current);
            default -> message(player, "upgrade.stale", Map.of());
        }
    }

    private void purchase(final Player player, final View view) {
        if (view.path() == null || view.next() == null) {
            message(player, "upgrade.maximum", Map.of());
            return;
        }
        final BlockKey key = BlockKey.of(view.location());
        final UUID token = UUID.randomUUID();
        if (!ledgerReady) {
            message(player, "upgrade.ledger-unavailable", Map.of());
            return;
        }
        final var previous = intents.pendingAt(intentKey(view.location()));
        if (previous.isPresent()) {
            message(player, "upgrade.pending", Map.of("operation", previous.orElseThrow().id()));
            return;
        }
        if (activePurchases.putIfAbsent(key, token) != null) {
            message(player, "upgrade.busy", Map.of());
            return;
        }
        try {
            final DrawerState upgraded = view.state().withTierAndCapacitySnapshot(
                    view.next().tier().id(), view.nextCapacity());
            final UpgradeIntentStore.Intent intent = new UpgradeIntentStore.Intent(token,
                    player.getUniqueId(), intentKey(view.location()), view.current().tier().id(),
                    view.next().tier().id(), view.path().cost(), System.currentTimeMillis(),
                    UpgradeIntentStore.Status.PAYMENT_UNKNOWN);
            final DrawerUpgradePayment.Result result = DrawerUpgradePayment.execute(
                    economy, intents, intent,
                    () -> context.repository().save(view.barrel(), upgraded)
            );
            final Map<String, Object> messageValues = Map.of(
                    "tier", tierName(view.current()), "next", tierName(view.next()),
                    "cost", view.path().cost()
            );
            switch (result) {
                case SUCCESS -> {
                    try {
                        context.notifyCommitted(view.barrel(), upgraded, view.stock().count(), DrawerChangeCause.UPGRADE);
                        hoppers.queueRebalance(view.barrel());
                    } catch (final RuntimeException exception) {
                        context.logger().warning("Drawer upgrade saved at " + view.location()
                                + " but a post-commit refresh failed: " + exception.getMessage());
                    }
                    message(player, "upgrade.success", messageValues);
                }
                case NOT_READY -> message(player, "upgrade.economy-unavailable", messageValues);
                case INSUFFICIENT -> message(player, "upgrade.insufficient-funds", messageValues);
                case LEDGER_UNAVAILABLE -> message(player, "upgrade.ledger-unavailable", messageValues);
                case FAILED -> message(player, "upgrade.failed", messageValues);
                case UNKNOWN -> {
                    context.logger().severe("Drawer upgrade " + token + " needs manual reconciliation for "
                            + player.getUniqueId() + " at " + view.location() + " amount " + view.path().cost());
                    message(player, "upgrade.pending", Map.of("operation", token));
                }
            }
        } finally {
            activePurchases.remove(key, token);
        }
    }

    private View inspect(final Player player, final Barrel original, final boolean report) {
        if (original == null || stopped || !context.settings().upgrade().enabled()) {
            if (report) message(player, "upgrade.disabled", Map.of());
            return null;
        }
        if (!ledgerReady) {
            if (report) message(player, "upgrade.ledger-unavailable", Map.of());
            return null;
        }
        final Location location = original.getLocation();
        final var pending = intents.pendingAt(intentKey(location));
        if (pending.isPresent()) {
            if (report) message(player, "upgrade.pending", Map.of("operation", pending.orElseThrow().id()));
            return null;
        }
        final double maximum = context.settings().upgrade().maximumDistance();
        if (!player.isOnline() || !player.getWorld().equals(location.getWorld())
                || player.getLocation().distanceSquared(location) > maximum * maximum
                || !Bukkit.isOwnedByCurrentRegion(location)) {
            if (report) message(player, "upgrade.unavailable", Map.of());
            return null;
        }
        final Barrel barrel = context.blocks().barrel(location.getBlock()).orElse(null);
        if (barrel == null || !context.repository().isDrawer(barrel)) {
            if (report) message(player, "upgrade.unavailable", Map.of());
            return null;
        }
        if (!player.hasPermission("devstoragedrawer.use") || !player.hasPermission("devstoragedrawer.upgrade")) {
            if (report) message(player, "upgrade.no-permission", Map.of());
            return null;
        }
        if (!protection.canWithdraw(player, barrel)) {
            if (report) message(player, "upgrade.protected", Map.of());
            return null;
        }
        if (!(context.repository().read(barrel) instanceof DrawerStateReadResult.Valid valid)) {
            if (report) message(player, "upgrade.unavailable", Map.of());
            return null;
        }
        final DrawerState state = valid.state();
        if (state.hasPendingProxyJournal()) {
            if (report) message(player, "upgrade.busy", Map.of());
            return null;
        }
        final DrawerBlockAccess.PhysicalStock stock = context.blocks().inspect(barrel, state);
        if (!stock.matchesTemplate()) {
            if (report) message(player, "upgrade.busy", Map.of());
            return null;
        }
        final DrawerTierDefinition current = context.tier(state.tierId()).orElse(null);
        if (current == null) return null;
        final DrawerUpgradePath path = current.upgradePath();
        if (path == null) {
            return new View(barrel, location, state, stock, state.totalForPhysical(stock.count()), current,
                    null, null, state.capacitySnapshot());
        }
        if (path.optionalPermission().filter(permission -> !player.hasPermission(permission)).isPresent()) {
            if (report) message(player, "upgrade.no-permission", Map.of());
            return null;
        }
        final DrawerTierDefinition next = context.tier(path.nextTierId()).orElse(null);
        if (next == null) return null;
        final long nextCapacity = state.hasTemplate()
                ? next.tier().capacityFor(state.requireTemplate()) : next.tier().stackCapacity();
        if (nextCapacity <= state.capacitySnapshot()) return null;
        return new View(barrel, location, state, stock, state.totalForPhysical(stock.count()), current,
                path, next, nextCapacity);
    }

    private Map<String, Object> values(final View view) {
        final boolean maximum = view.path() == null;
        return Map.ofEntries(
                Map.entry("tier", tierName(view.current())),
                Map.entry("next", maximum ? "Maximum" : tierName(view.next())),
                Map.entry("cost", maximum ? "-" : view.path().cost()),
                Map.entry("stored", view.total()),
                Map.entry("capacity", view.state().capacitySnapshot()),
                Map.entry("next_capacity", view.nextCapacity()),
                Map.entry("increase", Math.max(0L, view.nextCapacity() - view.state().capacitySnapshot())),
                Map.entry("world", view.location().getWorld().getName()),
                Map.entry("x", view.location().getBlockX()),
                Map.entry("y", view.location().getBlockY()),
                Map.entry("z", view.location().getBlockZ())
        );
    }

    private Component render(final DrawerUpgradeMenu menu, final String raw, final Map<String, Object> values,
            final boolean maximum) {
        return context.messages().rawComponent(DrawerUpgradeBedrock.expand(menu, raw, values, maximum), values);
    }

    private static DialogBase.DialogAfterAction afterAction(final String configured) {
        return switch (configured.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "none" -> DialogBase.DialogAfterAction.NONE;
            case "wait", "wait-for-response" -> DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE;
            default -> DialogBase.DialogAfterAction.CLOSE;
        };
    }

    private static String tierName(final DrawerTierDefinition tier) {
        return PLAIN.serialize(tier.itemName());
    }

    private static UpgradeIntentStore.BlockKey intentKey(final Location location) {
        return new UpgradeIntentStore.BlockKey(Objects.requireNonNull(location.getWorld()).getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Ledger-only view; callers must independently verify the bank and barrel before resolution. */
    public boolean ledgerAvailable() {
        return ledgerReady;
    }

    public List<PendingUpgrade> pendingOperations() {
        return intents.pending().stream().map(intent -> new PendingUpgrade(intent.id(), intent.player(),
                intent.block().world(), intent.block().x(), intent.block().y(), intent.block().z(),
                intent.fromTier(), intent.toTier(), intent.cost(), intent.status().name(), intent.createdAt()))
                .toList();
    }

    public enum Resolution { COMMITTED, REFUNDED, NO_CHARGE }

    public record PendingUpgrade(UUID id, UUID player, UUID world, int x, int y, int z,
                                 String fromTier, String toTier, double cost, String status, long createdAt) { }

    /** An administrator explicitly records a verified outcome; this never moves money or changes a barrel. */
    public boolean resolveOperation(final UUID id, final Resolution resolution) {
        if (!ledgerReady || activePurchases.containsValue(id)) return false;
        final UpgradeIntentStore.Intent existing = intents.find(id).orElse(null);
        if (existing == null || !existing.status().pending()) return false;
        if (existing.status() == UpgradeIntentStore.Status.COMMITTED
                && resolution != Resolution.COMMITTED) return false;
        if (resolution == Resolution.NO_CHARGE
                && existing.status() != UpgradeIntentStore.Status.PAYMENT_UNKNOWN) {
            return false;
        }
        try {
            if (resolution == Resolution.COMMITTED) {
                // A provider throw can leave PAYMENT_UNKNOWN after charging, and a barrel save can
                // leave CHARGED after committing. This command is explicitly for an administrator
                // who verified both external facts, so advance through the durable audit states.
                UpgradeIntentStore.Status status = existing.status();
                if (status == UpgradeIntentStore.Status.PAYMENT_UNKNOWN) {
                    status = intents.transition(id, UpgradeIntentStore.Status.CHARGED).status();
                }
                if (status == UpgradeIntentStore.Status.CHARGED) {
                    status = intents.transition(id, UpgradeIntentStore.Status.COMMITTED).status();
                }
                if (status != UpgradeIntentStore.Status.COMMITTED) return false;
                intents.transition(id, UpgradeIntentStore.Status.VERIFIED);
            } else {
                intents.transition(id, resolution == Resolution.REFUNDED
                        ? UpgradeIntentStore.Status.REFUNDED
                        : UpgradeIntentStore.Status.REJECTED);
            }
            return true;
        } catch (final IOException exception) {
            context.logger().severe("Could not record resolution of drawer upgrade " + id + ": "
                    + exception.getMessage());
            return false;
        }
    }

    /** Finalize a known charge when the loaded barrel proves that its target tier was applied. */
    public void verifyLoadedBarrel(final Barrel barrel) {
        if (!ledgerReady || barrel == null) return;
        final UpgradeIntentStore.BlockKey key = intentKey(barrel.getLocation());
        for (final UpgradeIntentStore.Intent intent : intents.pending()) {
            if (!intent.block().equals(key) || (intent.status() != UpgradeIntentStore.Status.CHARGED
                    && intent.status() != UpgradeIntentStore.Status.COMMITTED)) continue;
            final DrawerStateReadResult read = context.repository().read(barrel);
            if (!(read instanceof DrawerStateReadResult.Valid valid)
                    || !valid.state().tierId().equals(intent.toTier())) {
                context.logger().warning("Paid drawer upgrade " + intent.id() + " is " + intent.status()
                        + " but its loaded barrel does not have target tier " + intent.toTier() + " at "
                        + barrel.getLocation() + "; inspect it before resolving");
                continue;
            }
            try {
                if (intents.verifyApplied(intent.id())) {
                    context.logger().info("Automatically verified paid drawer upgrade " + intent.id()
                            + " from the loaded barrel at " + barrel.getLocation());
                }
            } catch (final IOException exception) {
                context.logger().severe("Could not finalize paid drawer upgrade " + intent.id()
                        + " at " + barrel.getLocation() + ": " + exception.getMessage());
            }
        }
    }

    /** Schedule a comparison for intents whose chunks were already loaded at plugin enable. */
    public void verifyLoadedOperations() {
        if (!ledgerReady) return;
        for (final UpgradeIntentStore.Intent intent : intents.pending()) {
            if (intent.status() != UpgradeIntentStore.Status.CHARGED
                    && intent.status() != UpgradeIntentStore.Status.COMMITTED) continue;
            context.execution().runGlobal(ignored -> {
                final org.bukkit.World world = Bukkit.getWorld(intent.block().world());
                if (world == null) return;
                final int chunkX = intent.block().x() >> 4;
                final int chunkZ = intent.block().z() >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) return;
                final Location location = new Location(world, intent.block().x(), intent.block().y(), intent.block().z());
                context.execution().executeAt(location, () -> {
                    final Barrel barrel = context.blocks().barrel(location.getBlock()).orElse(null);
                    if (barrel == null || !context.repository().isDrawer(barrel)) {
                        context.logger().severe("Paid drawer upgrade " + intent.id()
                                + " has no tagged barrel at " + intent.block());
                    } else {
                        verifyLoadedBarrel(barrel);
                    }
                });
            });
        }
    }

    private void message(final Player player, final String key, final Map<String, ?> values) {
        player.sendMessage(context.messages().component(key, values));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        if (!ledgerReady) return;
        final Set<UpgradeIntentStore.BlockKey> expected = new HashSet<>();
        for (final UpgradeIntentStore.Intent intent : intents.pending()) {
            if ((intent.status() == UpgradeIntentStore.Status.CHARGED
                    || intent.status() == UpgradeIntentStore.Status.COMMITTED)
                    && intent.block().world().equals(event.getWorld().getUID())
                    && intent.block().x() >> 4 == event.getChunk().getX()
                    && intent.block().z() >> 4 == event.getChunk().getZ()) {
                expected.add(intent.block());
            }
        }
        if (expected.isEmpty()) return;
        for (final org.bukkit.block.BlockState state : event.getChunk().getTileEntities()) {
            if (state instanceof Barrel barrel && context.repository().isDrawer(barrel)) {
                verifyLoadedBarrel(barrel);
                expected.remove(intentKey(barrel.getLocation()));
            }
        }
        for (final UpgradeIntentStore.BlockKey missing : expected) {
            context.logger().severe("Paid drawer upgrade at " + missing
                    + " has no tagged barrel in its loaded chunk; inspect the economy and world before resolving");
        }
    }

    public void stop() {
        stopped = true;
        sessions.clear();
        activePurchases.clear();
    }

    private record View(Barrel barrel, Location location, DrawerState state,
            DrawerBlockAccess.PhysicalStock stock, long total, DrawerTierDefinition current,
            DrawerUpgradePath path, DrawerTierDefinition next, long nextCapacity) {
        private boolean sameRoute(final View other) {
            return BlockKey.of(location).equals(BlockKey.of(other.location))
                    && current.tier().id().equals(other.current.tier().id())
                    && Objects.equals(path, other.path)
                    && (next == null ? other.next == null : other.next != null
                            && next.tier().id().equals(other.next.tier().id()));
        }
    }

    private static final class Session {
        private final UUID id;
        private final View view;
        private final long expires;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private Session(final UUID id, final View view, final long expires) {
            this.id = id;
            this.view = view;
            this.expires = expires;
        }
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        private static BlockKey of(final Location location) {
            return new BlockKey(Objects.requireNonNull(location.getWorld()).getUID(), location.getBlockX(),
                    location.getBlockY(), location.getBlockZ());
        }
    }

    private enum UnavailableEconomy implements DrawerEconomy {
        INSTANCE;
        @Override public boolean ready() { return false; }
        @Override public boolean withdraw(final UUID playerId, final double amount) { return false; }
        @Override public boolean deposit(final UUID playerId, final double amount) { return false; }
    }
}
