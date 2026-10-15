package com.teenkung.devstoragedrawer;

import com.teenkung.devstoragedrawer.api.DevStorageDrawerApi;
import com.teenkung.devstoragedrawer.api.DrawerSaleApi;
import com.teenkung.devstoragedrawer.bedrock.BedrockPlayerDetector;
import com.teenkung.devstoragedrawer.block.DrawerRuntime;
import com.teenkung.devstoragedrawer.block.DrawerSellWandBridge;
import com.teenkung.devstoragedrawer.block.DevStorageDrawerAcceptance;
import com.teenkung.devstoragedrawer.block.DevStorageDrawerRestartAcceptance;
import com.teenkung.devstoragedrawer.command.DrawerAdminFacade;
import com.teenkung.devstoragedrawer.command.DrawersCommand;
import com.teenkung.devstoragedrawer.config.DrawerConfigurationService;
import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import com.teenkung.devstoragedrawer.display.DrawerVisualRenderer;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.domain.SingleItemDrawerStorage;
import com.teenkung.devstoragedrawer.interaction.DrawerWithdrawalProtection;
import com.teenkung.devstoragedrawer.interaction.WorldGuardDrawerWithdrawalProtection;
import com.teenkung.devstoragedrawer.parcel.DrawerParcelListener;
import com.teenkung.devstoragedrawer.parcel.DrawerParcelService;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import com.teenkung.devstoragedrawer.receipt.WithdrawalReceiptStore;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import com.teenkung.devstoragedrawer.upgrade.DrawerUpgradeService;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Main plugin lifecycle and explicit integration boundary for all drawer subsystems. */
public final class DevStorageDrawer extends JavaPlugin {
    private DrawerConfigurationService configuration;
    private DrawerRuntime runtime;
    private DrawerVisualRenderer visuals;
    private DrawerParcelService parcels;
    private BedrockPlayerDetector bedrockPlayers;
    private WithdrawalReceiptStore withdrawalReceipts;
    private SingleItemDrawerStorage storage;
    private DevStorageDrawerApi api;
    private DrawerSaleApi saleApi;

    @Override
    public void onEnable() {
        try {
            configuration = new DrawerConfigurationService(this);
            configuration.load();
        } catch (final RuntimeException exception) {
            getLogger().severe("Unable to load DevStorageDrawer configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final PluginManager pluginManager = getServer().getPluginManager();
        bedrockPlayers = BedrockPlayerDetector.create(pluginManager);
        if (!validateBedrockRequirement(configuration.settings(), pluginManager)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final FoliaExecution execution = new FoliaExecution(this);
        final DrawerStateRepository repository = new DrawerStateRepository();
        withdrawalReceipts = new WithdrawalReceiptStore(getDataFolder().toPath().resolve("withdrawal-receipts.dat"));
        try {
            withdrawalReceipts.load();
        } catch (final IOException exception) {
            getLogger().severe("Unable to load withdrawal receipts safely: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        parcels = new DrawerParcelService();
        storage = new SingleItemDrawerStorage(configuration.settings().redstone().logicalComparatorOutput());
        visuals = new DrawerVisualRenderer(this, execution, repository, bedrockPlayers, configuration.settings());
        runtime = new DrawerRuntime(
                this,
                execution,
                repository,
                storage,
                configuration.settings(),
                configuration.messages(),
                configuration::tier,
                new DrawerRuntime.DrawerRenderer() {
                    @Override
                    public void refresh(final Barrel barrel, final DrawerState state, final long physicalProxyCount) {
                        visuals.refresh(barrel, state, physicalProxyCount);
                    }

                    @Override
                    public void remove(final Barrel barrel, final DrawerState state) {
                        visuals.remove(barrel, state);
                    }

                    @Override
                    public void removeAt(final Barrel barrel) {
                        visuals.removeAt(barrel);
                    }
                },
                (owner, template, total) -> parcels.createParcel(owner.getUniqueId(), template, total),
                withdrawalReceipts,
                entity -> resolveDisplayTarget(entity, repository),
                pluginManager.isPluginEnabled("WorldGuard")
                        ? new WorldGuardDrawerWithdrawalProtection()
                        : DrawerWithdrawalProtection.ALLOW_ALL
        );
        api = runtime.api();
        getServer().getServicesManager().register(DevStorageDrawerApi.class, api, this, ServicePriority.Normal);
        saleApi = runtime.saleApi();
        getServer().getServicesManager().register(DrawerSaleApi.class, saleApi, this, ServicePriority.Normal);

        runtime.listeners().forEach(listener -> pluginManager.registerEvents(listener, this));
        pluginManager.registerEvents(visuals, this);
        pluginManager.registerEvents(new DrawerParcelListener(parcels, () -> configuration.messages()), this);
        registerCommand(execution);
        runtime.repairLoadedDrawers();
        runtime.verifyLoadedUpgradeOperations();
        getLogger().info("DevStorageDrawer enabled with " + configuration.settings().tiers().size() + " configured tier(s).");
        final String restartAcceptancePhase = System.getProperty(DevStorageDrawerRestartAcceptance.PHASE_PROPERTY, "");
        if (!restartAcceptancePhase.isBlank()) {
            getLogger().warning("Development restart acceptance phase " + restartAcceptancePhase + " is enabled.");
            new DevStorageDrawerRestartAcceptance(this, runtime, repository, visuals).start(restartAcceptancePhase);
        } else if (Boolean.getBoolean(DevStorageDrawerAcceptance.ENABLE_PROPERTY)) {
            getLogger().warning("Development acceptance mode is enabled; an isolated live rig will run and stop the server.");
            new DevStorageDrawerAcceptance(this, runtime, repository, visuals, parcels).start();
        }
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.disable();
        }
        if (api != null) {
            getServer().getServicesManager().unregister(DevStorageDrawerApi.class, api);
            api = null;
        }
        if (saleApi != null) {
            getServer().getServicesManager().unregister(DrawerSaleApi.class, saleApi);
            saleApi = null;
        }
        // Drawer state is written synchronously on every mutation. Display entities remain persistent
        // and are reconciled through their PDC link on the next enable/chunk load.
        getLogger().info("DevStorageDrawer disabled.");
    }

    /** Stable public integration service. It is available after this plugin has enabled. */
    public DevStorageDrawerApi getApi() {
        if (api == null) {
            throw new IllegalStateException("DevStorageDrawer API is unavailable while the plugin is disabled");
        }
        return api;
    }

    /**
     * @deprecated Use {@link #getApi()} or Bukkit's ServicesManager. The bridge requires callers
     *             to manage Folia region ownership themselves and is kept only for compatibility.
     */
    @Deprecated(since = "1.0.0")
    public DrawerSellWandBridge getSellWandBridge() {
        return runtime == null ? null : runtime.sellWandBridge();
    }

    private void registerCommand(final FoliaExecution execution) {
        final PluginCommand command = Objects.requireNonNull(getCommand("drawers"), "drawers command missing from plugin.yml");
        final DrawersCommand executor = new DrawersCommand(configuration, new DrawerAdminFacade() {
            @Override
            public void validateReload(final DrawerSettings settings) {
                if (!validateBedrockRequirement(settings, getServer().getPluginManager())) {
                    throw new IllegalArgumentException("Bedrock renderer requires Floodgate on this Paper server");
                }
            }

            @Override
            public void reload(final DrawerSettings settings, final DrawerMessages messages) {
                storage.setLogicalComparatorProxy(settings.redstone().logicalComparatorOutput());
                visuals.reloadSettings(settings);
                runtime.reloadSettings(settings);
                runtime.reloadMessages(messages);
                runtime.repairLoadedDrawers();
            }

            @Override
            public void repairLoadedDrawers() {
                runtime.repairLoadedDrawers();
            }

            @Override
            public void migrateTargetedDrawer(final Player player) {
                runtime.migrateTargetedDrawer(player);
            }

            @Override
            public boolean upgradeLedgerAvailable() {
                return runtime.upgradeLedgerAvailable();
            }

            @Override
            public List<DrawerUpgradeService.PendingUpgrade> pendingUpgradeOperations() {
                return runtime.pendingUpgradeOperations();
            }

            @Override
            public boolean resolveUpgradeOperation(final UUID id, final DrawerUpgradeService.Resolution resolution) {
                return runtime.resolveUpgradeOperation(id, resolution);
            }
        }, execution);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private Optional<Barrel> resolveDisplayTarget(final Entity entity, final DrawerStateRepository repository) {
        return visuals.drawerLocation(entity)
                .filter(location -> location.getBlock().getType() == Material.BARREL)
                .flatMap(location -> location.getBlock().getState() instanceof Barrel barrel && repository.isDrawer(barrel)
                        ? Optional.of(barrel)
                        : Optional.empty());
    }

    private boolean validateBedrockRequirement(final DrawerSettings settings, final PluginManager pluginManager) {
        if (!settings.bedrock().enabled() || !settings.bedrock().required()) {
            if (settings.bedrock().enabled() && !bedrockPlayers.isAvailable()) {
                getLogger().warning("Floodgate holograms are enabled but backend Floodgate is unavailable; "
                        + "the Java renderer remains available. Install Floodgate on this backend for Bedrock holograms.");
            }
            return true;
        }
        final boolean floodgate = pluginManager.isPluginEnabled("floodgate");
        if (floodgate && bedrockPlayers.isAvailable()) {
            return true;
        }
        getLogger().severe("Bedrock renderer is required but the backend Floodgate API is unavailable. "
                + "Install or enable Floodgate on this Paper server, or set bedrock.required: false. "
                + "Geyser may run on the proxy and does not need to be installed on this backend.");
        return false;
    }
}
