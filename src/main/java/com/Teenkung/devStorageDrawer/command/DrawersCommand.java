package com.teenkung.devstoragedrawer.command;

import com.teenkung.devstoragedrawer.config.DrawerConfigurationService;
import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import com.teenkung.devstoragedrawer.upgrade.DrawerUpgradeService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Permission-guarded administration command for configured drawer tiers. */
public final class DrawersCommand implements CommandExecutor, TabCompleter {
    private static final int MAX_GIVE_AMOUNT = 4096;
    private static final int GIVE_STACKS_PER_TICK = 16;
    private final DrawerConfigurationService configuration;
    private final DrawerAdminFacade runtime;
    private final FoliaExecution execution;

    public DrawersCommand(final DrawerConfigurationService configuration, final DrawerAdminFacade runtime,
            final FoliaExecution execution) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args
    ) {
        if (args.length == 0) {
            send(sender, "command.usage");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "reload" -> reload(sender);
            case "repair" -> repair(sender);
            case "migrate" -> migrate(sender);
            case "upgrade" -> upgrade(sender, args);
            default -> {
                send(sender, "command.usage");
                yield true;
            }
        };
    }

    private boolean give(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission("devstoragedrawer.admin.give")) {
            send(sender, "general.no-permission");
            return true;
        }
        if (args.length < 3 || args.length > 4) {
            send(sender, "command.give-usage");
            return true;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "command.player-not-found", Map.of("player", args[1]));
            return true;
        }
        final DrawerTierDefinition tier = configuration.tier(args[2]).orElse(null);
        if (tier == null) {
            send(sender, "command.unknown-tier", Map.of("tier", args[2]));
            return true;
        }
        final int amount = args.length == 4 ? positiveAmount(sender, args[3]) : 1;
        if (amount < 1) {
            return true;
        }
        new GiveOperation(sender, target, args[1], tier, amount).start();
        return true;
    }

    private int positiveAmount(final CommandSender sender, final String source) {
        try {
            final int parsed = Integer.parseInt(source);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            if (parsed > MAX_GIVE_AMOUNT) {
                send(sender, "command.give-amount-limit", Map.of("limit", MAX_GIVE_AMOUNT));
                return -1;
            }
            return parsed;
        } catch (final NumberFormatException exception) {
            send(sender, "command.invalid-amount");
            return -1;
        }
    }

    private final class GiveOperation {
        private final CommandSender sender;
        private final Player target;
        private final String playerName;
        private final DrawerTierDefinition tier;
        private final int amount;
        private final AtomicInteger delivered = new AtomicInteger();
        private final AtomicBoolean finished = new AtomicBoolean();

        private GiveOperation(final CommandSender sender, final Player target, final String playerName,
                final DrawerTierDefinition tier, final int amount) {
            this.sender = sender;
            this.target = target;
            this.playerName = playerName;
            this.tier = tier;
            this.amount = amount;
        }

        private void start() {
            execution.runOnEntity(target, ignored -> deliverBatch(), this::interrupted);
        }

        private void deliverBatch() {
            if (!target.isOnline()) {
                interrupted();
                return;
            }
            int stacks = 0;
            while (delivered.get() < amount && stacks++ < GIVE_STACKS_PER_TICK) {
                final ItemStack stack = tier.createItem();
                final int batch = Math.min(amount - delivered.get(), stack.getMaxStackSize());
                stack.setAmount(batch);
                final Map<Integer, ItemStack> leftovers = target.getInventory().addItem(stack);
                for (final ItemStack leftover : leftovers.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                }
                delivered.addAndGet(batch);
            }
            if (delivered.get() == amount) {
                if (finished.compareAndSet(false, true)) {
                    sendOnSender(sender, "command.given", Map.of(
                            "amount", amount, "tier", tier.tier().id(), "player", playerName));
                }
            } else {
                execution.runLaterOnEntity(target, 1L, ignored -> deliverBatch(), this::interrupted);
            }
        }

        private void interrupted() {
            if (finished.compareAndSet(false, true)) {
                sendOnSender(sender, "command.give-interrupted", Map.of(
                        "delivered", delivered.get(), "amount", amount, "tier", tier.tier().id(),
                        "player", playerName));
            }
        }
    }

    private void sendOnSender(final CommandSender sender, final String key, final Map<String, ?> placeholders) {
        if (sender instanceof Player player) {
            execution.runOnEntity(player, ignored -> send(player, key, placeholders), () -> { });
        } else {
            execution.runGlobal(ignored -> send(sender, key, placeholders));
        }
    }

    private boolean reload(final CommandSender sender) {
        if (!sender.hasPermission("devstoragedrawer.admin.reload")) {
            send(sender, "general.no-permission");
            return true;
        }
        try {
            configuration.load(runtime::validateReload);
            runtime.reload(configuration.settings(), configuration.messages());
            send(sender, "command.reloaded");
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage(configuration.messages().component("general.configuration-error"));
        }
        return true;
    }

    private boolean repair(final CommandSender sender) {
        if (!sender.hasPermission("devstoragedrawer.admin.repair")) {
            send(sender, "general.no-permission");
            return true;
        }
        runtime.repairLoadedDrawers();
        send(sender, "command.repair-queued");
        return true;
    }

    private boolean migrate(final CommandSender sender) {
        if (!sender.hasPermission("devstoragedrawer.admin.migrate")) {
            send(sender, "general.no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "command.player-only");
            return true;
        }
        runtime.migrateTargetedDrawer(player);
        return true;
    }

    private boolean upgrade(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission("devstoragedrawer.admin.upgrade-reconcile")) {
            send(sender, "general.no-permission");
            return true;
        }
        if (!runtime.upgradeLedgerAvailable()) {
            send(sender, "command.upgrade-ledger-unavailable");
            return true;
        }
        if (args.length >= 2 && "pending".equalsIgnoreCase(args[1]) && args.length <= 3) {
            final int page;
            try {
                page = args.length == 3 ? Integer.parseInt(args[2]) : 1;
                if (page < 1) throw new NumberFormatException();
            } catch (final NumberFormatException exception) {
                send(sender, "command.upgrade-usage");
                return true;
            }
            final List<DrawerUpgradeService.PendingUpgrade> pending = runtime.pendingUpgradeOperations().stream()
                    .sorted(Comparator.comparingLong(DrawerUpgradeService.PendingUpgrade::createdAt))
                    .toList();
            final long offset = ((long) page - 1L) * 10L;
            if (offset >= pending.size() && !(pending.isEmpty() && page == 1)) {
                send(sender, "command.upgrade-page-missing");
                return true;
            }
            send(sender, "command.upgrade-pending-header", Map.of("count", pending.size(), "page", page));
            for (int i = (int) offset; i < pending.size() && i < offset + 10L; i++) {
                final DrawerUpgradeService.PendingUpgrade operation = pending.get(i);
                send(sender, "command.upgrade-pending-row", Map.ofEntries(
                        Map.entry("operation", operation.id()), Map.entry("player", operation.player()),
                        Map.entry("world", operation.world()), Map.entry("x", operation.x()),
                        Map.entry("y", operation.y()), Map.entry("z", operation.z()),
                        Map.entry("from", operation.fromTier()), Map.entry("to", operation.toTier()),
                        Map.entry("cost", operation.cost()), Map.entry("status", operation.status())));
            }
            return true;
        }
        if (args.length == 4 && "resolve".equalsIgnoreCase(args[1])) {
            final UUID id;
            final DrawerUpgradeService.Resolution resolution;
            try {
                id = UUID.fromString(args[2]);
                resolution = switch (args[3].toLowerCase(Locale.ROOT)) {
                    case "committed" -> DrawerUpgradeService.Resolution.COMMITTED;
                    case "refunded" -> DrawerUpgradeService.Resolution.REFUNDED;
                    case "no-charge" -> DrawerUpgradeService.Resolution.NO_CHARGE;
                    default -> throw new IllegalArgumentException("Invalid resolution");
                };
            } catch (final IllegalArgumentException exception) {
                send(sender, "command.upgrade-usage");
                return true;
            }
            if (!runtime.resolveUpgradeOperation(id, resolution)) {
                send(sender, "command.upgrade-resolution-failed", Map.of("operation", id));
                return true;
            }
            Bukkit.getLogger().warning("Drawer upgrade " + id + " manually resolved as "
                    + resolution + " by " + sender.getName());
            send(sender, "command.upgrade-resolved", Map.of("operation", id, "resolution", resolution));
            return true;
        }
        send(sender, "command.upgrade-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String alias,
            final String[] args
    ) {
        if (args.length == 1) {
            return matching(args[0], List.of("give", "reload", "repair", "migrate", "upgrade"));
        }
        if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
            return matching(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 3 && "give".equalsIgnoreCase(args[0])) {
            return matching(args[2], new ArrayList<>(configuration.settings().tiers().keySet()));
        }
        if (args.length == 2 && "upgrade".equalsIgnoreCase(args[0])
                && sender.hasPermission("devstoragedrawer.admin.upgrade-reconcile")) {
            return matching(args[1], List.of("pending", "resolve"));
        }
        if (args.length == 4 && "upgrade".equalsIgnoreCase(args[0])
                && "resolve".equalsIgnoreCase(args[1])
                && sender.hasPermission("devstoragedrawer.admin.upgrade-reconcile")) {
            return matching(args[3], List.of("committed", "refunded", "no-charge"));
        }
        return List.of();
    }

    private static List<String> matching(final String prefix, final List<String> candidates) {
        final String normalized = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted()
                .collect(Collectors.toList());
    }

    private void send(final CommandSender sender, final String key) {
        sender.sendMessage(configuration.messages().component(key));
    }

    private void send(final CommandSender sender, final String key, final Map<String, ?> placeholders) {
        sender.sendMessage(configuration.messages().component(key, placeholders));
    }
}
