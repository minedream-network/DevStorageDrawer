package com.teenkung.devstoragedrawer.config;

import com.teenkung.devstoragedrawer.domain.DrawerTier;
import com.teenkung.devstoragedrawer.domain.DrawerValidationException;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads the plugin's user-facing YAML files into one validated runtime snapshot. */
public final class DrawerConfigurationService {
    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private DrawerSettings settings;
    private DrawerMessages messages;

    public DrawerConfigurationService(final JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void load() {
        plugin.saveDefaultConfig();
        saveBundledDefault("tiers.yml");
        saveBundledDefault("messages.yml");
        saveBundledDefault("upgrade-menu.yml");
        plugin.reloadConfig();

        final FileConfiguration configuration = plugin.getConfig();
        final int configVersion = configuration.getInt("config-version", -1);
        if (configVersion != 1) {
            throw new DrawerValidationException("Unsupported config-version: " + configVersion);
        }
        final FileConfiguration tiers = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "tiers.yml"));
        final FileConfiguration messageConfiguration = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "messages.yml")
        );
        final FileConfiguration upgradeMenuConfiguration = loadBundledConfiguration("upgrade-menu.yml");
        migrateBreakMessages(messageConfiguration);
        final HopperTransferPolicy hopperTransfers = parseHopperTransfers(configuration);

        this.settings = new DrawerSettings(
                parseTiers(tiers, configuration),
                new DrawerSettings.Interaction(
                        positiveInt(configuration, "interaction.bulk-deposit-window-ticks"),
                        configuration.getBoolean("interaction.require-facing-face", false)
                ),
                new DrawerSettings.Visuals(
                        configuration.getBoolean("visuals.enabled", true),
                        configuration.getBoolean("visuals.repair-on-chunk-load", true),
                        positiveLong(configuration, "visuals.update-delay-ticks"),
                        configuration.getDouble("visuals.front-offset", 0.54D),
                        configuration.getBoolean("visuals.item.enabled", true),
                        (float) configuration.getDouble("visuals.item.scale", 0.36D),
                        (float) configuration.getDouble("visuals.item.depth-scale", 0.08D),
                        configuration.getDouble("visuals.item.offset-y", 0.02D),
                        itemDisplayTransform(configuration, "visuals.item.model-context"),
                        configuration.getBoolean("visuals.text.enabled", true),
                        (float) configuration.getDouble("visuals.text.scale", 0.40D),
                        configuration.getInt("visuals.text.line-width", 92),
                        configuration.getDouble("visuals.text.name-offset-y", 0.30D),
                        configuration.getDouble("visuals.text.amount-offset-y", -0.42D)
                ),
                new DrawerSettings.Bedrock(
                        configuration.getBoolean("bedrock.enabled", true),
                        configuration.getBoolean("bedrock.required", false),
                        configuration.getBoolean("bedrock.armor-stand.show-item", false),
                        configuration.getBoolean("bedrock.armor-stand.show-name", true),
                        configuration.getBoolean("bedrock.armor-stand.marker", true),
                        configuration.getBoolean("bedrock.armor-stand.invisible", true),
                        configuration.getDouble("bedrock.hologram.height", 1.35D),
                        configuration.getDouble("bedrock.hologram.line-spacing", 0.25D)
                ),
                new DrawerSettings.Automation(
                        configuration.getBoolean("automation.enabled", true),
                        configuration.getInt("automation.output-proxy-slots", 26),
                        positiveLong(configuration, "automation.rebalance-delay-ticks"),
                        configuration.getBoolean("automation.journal.recover-on-load", true),
                        configuration.getBoolean("automation.debug-logging", false),
                        positiveLong(configuration, "automation.fallback-poll-interval-ticks", 10L),
                        hopperTransfers
                ),
                new DrawerSettings.Redstone(
                        configuration.getBoolean("redstone.logical-comparator-output", true),
                        positiveLong(configuration, "redstone.comparator-recalculate-delay-ticks"),
                        configuration.getBoolean("redstone.protect-from-explosions", true),
                        configuration.getBoolean("redstone.prevent-piston-movement", true)
                ),
                new DrawerSettings.Parcels(
                        configuration.getBoolean("parcels.enabled", true),
                        configuration.getBoolean("parcels.drop-on-break", true)
                ),
                new DrawerSettings.Breaking(configuration.getInt("breaking.stacks-per-tick", 16)),
                parseStorageMenu(configuration),
                new DrawerSettings.Upgrade(
                        configuration.getBoolean("upgrade.enabled", true),
                        configuration.getInt("upgrade.session-seconds", 45),
                        configuration.getDouble("upgrade.maximum-distance", 8D)
                ),
                DrawerUpgradeMenu.parse(upgradeMenuConfiguration)
        );
        this.messages = new DrawerMessages(flattenMessages(messageConfiguration));
    }

    public DrawerSettings settings() {
        return requireLoaded(settings, "settings");
    }

    static DrawerSettings.StorageMenu parseStorageMenu(final ConfigurationSection configuration) {
        final Map<DrawerMenuIcon, String> materials = new java.util.EnumMap<>(DrawerMenuIcon.class);
        for (final DrawerMenuIcon icon : DrawerMenuIcon.values()) {
            final Object configured = configuration.get(icon.path());
            if (configured == null) continue;
            if (!(configured instanceof String material) || material.isBlank()) {
                throw new DrawerValidationException(icon.path() + " must be a material or provider:id");
            }
            materials.put(icon, material);
        }
        return new DrawerSettings.StorageMenu(configuration.getLong("storage-menu.refresh-ticks", 4), materials);
    }

    private void migrateBreakMessages(final FileConfiguration messages) {
        // Upgrade only the old bundled wording. Preserve server-specific translations and edits.
        final Map<String, List<String>> oldDefaults = Map.of(
                "drawer.break-warning", List.of(
                        "<gray>This drawer holds <white><amount> <gray>item(s). The drawer block and one owner-bound contents parcel will drop. Confirm, then break it once more within 15 seconds.",
                        "<gray>This drawer holds <white><amount> <gray>item(s). Its contents will drop as normal item stacks, followed by the drawer block. Large amounts drop in batches. Confirm, then break it once more within 15 seconds."),
                "drawer.break-warning-empty", List.of("<gray>This drawer is empty. Confirm, then break it once more within 15 seconds."),
                "drawer.break-approved", List.of("<yellow>◆ <gray>Confirmed. Break this drawer again within 15 seconds."),
                "drawer.break-unavailable", List.of("<red>✘ <gray>Break cancelled to protect stored items. Ask an administrator to repair the drawer or check the server log.")
        );
        try (final var resource = plugin.getResource("messages.yml")) {
            if (resource == null) return;
            final var defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
            messages.setDefaults(defaults);
            messages.options().copyDefaults(true);
            for (final var entry : oldDefaults.entrySet()) {
                final String current = messages.getString(entry.getKey());
                if (current != null && entry.getValue().contains(current)) {
                    messages.set(entry.getKey(), defaults.getString(entry.getKey()));
                }
            }
        } catch (final java.io.IOException exception) {
            throw new DrawerValidationException("Could not load default break messages", exception);
        }
    }

    public DrawerMessages messages() {
        return requireLoaded(messages, "messages");
    }

    public Optional<DrawerTierDefinition> tier(final String id) {
        return Optional.ofNullable(settings().tiers().get(id));
    }

    public DrawerTierDefinition requireTier(final String id) {
        return tier(id).orElseThrow(() -> new DrawerValidationException("Unknown drawer tier: " + id));
    }

    private void saveBundledDefault(final String resource) {
        final File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
    }

    private FileConfiguration loadBundledConfiguration(final String resource) {
        final File file = new File(plugin.getDataFolder(), resource);
        if (file.isFile()) return YamlConfiguration.loadConfiguration(file);
        java.io.InputStream stream = plugin.getResource(resource);
        if (stream == null) stream = DrawerConfigurationService.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) throw new DrawerValidationException("Missing bundled configuration: " + resource);
        final java.io.InputStream loaded = stream;
        try (loaded) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(loaded, StandardCharsets.UTF_8));
        } catch (final java.io.IOException exception) {
            throw new DrawerValidationException("Could not load bundled configuration: " + resource, exception);
        }
    }

    private HopperTransferPolicy parseHopperTransfers(final ConfigurationSection configuration) {
        final String amountPath = "automation.hopper-transfer-amount";
        final int configuredAmount = configuration.getInt(amountPath, 0);
        if (configuredAmount < 0) {
            throw new DrawerValidationException(amountPath + " must be zero (automatic) or a positive whole number");
        }

        int defaultAmount = configuredAmount;
        final Map<String, Integer> worldAmounts = new LinkedHashMap<>();
        if (configuredAmount == 0) {
            final File spigotFile = new File("spigot.yml").getAbsoluteFile();
            if (!spigotFile.isFile()) {
                defaultAmount = 1;
                plugin.getLogger().warning("Could not auto-detect hopper transfer amounts because "
                        + spigotFile + " does not exist; using 1. Set " + amountPath
                        + " when the server uses a custom --spigot-settings path.");
            } else {
                final FileConfiguration spigot = YamlConfiguration.loadConfiguration(spigotFile);
                defaultAmount = validExternalHopperAmount(
                        spigot.getInt("world-settings.default.hopper-amount", 1),
                        "world-settings.default.hopper-amount",
                        1
                );
                final ConfigurationSection worlds = spigot.getConfigurationSection("world-settings");
                if (worlds != null) {
                    for (final String worldName : worlds.getKeys(false)) {
                        if (worldName.equals("default")) {
                            continue;
                        }
                        final ConfigurationSection world = worlds.getConfigurationSection(worldName);
                        if (world == null || !world.contains("hopper-amount")) {
                            continue;
                        }
                        final int amount = validExternalHopperAmount(
                                world.getInt("hopper-amount", defaultAmount),
                                "world-settings." + worldName + ".hopper-amount",
                                defaultAmount
                        );
                        worldAmounts.put(worldName, amount);
                    }
                }
            }
        }

        final ConfigurationSection overrides = configuration.getConfigurationSection(
                "automation.hopper-transfer-amount-overrides"
        );
        if (overrides != null) {
            for (final String worldName : overrides.getKeys(false)) {
                final int amount = overrides.getInt(worldName);
                if (amount < 1) {
                    throw new DrawerValidationException("automation.hopper-transfer-amount-overrides."
                            + worldName + " must be a positive whole number");
                }
                worldAmounts.put(worldName, amount);
            }
        }
        return new HopperTransferPolicy(defaultAmount, worldAmounts);
    }

    private int validExternalHopperAmount(final int amount, final String path, final int fallback) {
        if (amount > 0) {
            return amount;
        }
        plugin.getLogger().warning("Ignoring invalid " + path + " in spigot.yml; using " + fallback);
        return fallback;
    }

    private Map<String, DrawerTierDefinition> parseTiers(final FileConfiguration configuration,
            final ConfigurationSection globalConfiguration) {
        final int configVersion = configuration.getInt("config-version", -1);
        if (configVersion != 1) {
            throw new DrawerValidationException("Unsupported tiers.yml config-version: " + configVersion);
        }
        final ConfigurationSection tiers = configuration.getConfigurationSection("tiers");
        if (tiers == null) {
            throw new DrawerValidationException("tiers.yml requires a tiers section");
        }
        final Map<String, DrawerTierDefinition> definitions = new LinkedHashMap<>();
        for (final String id : tiers.getKeys(false)) {
            final ConfigurationSection tier = requireSection(tiers, id);
            final ConfigurationSection item = requireSection(tier, "item");
            final String materialName = item.getString("material", "BARREL");
            if (!Material.BARREL.name().equalsIgnoreCase(materialName)) {
                throw new DrawerValidationException("Tier " + id + " must use material BARREL");
            }
            final String name = requiredString(item, "name", "Tier " + id + " needs item.name");
            final List<Component> lore = new ArrayList<>();
            for (final String line : item.getStringList("lore")) {
                lore.add(miniMessage.deserialize(line));
            }
            final Integer customModelData = item.contains("custom-model-data") && item.get("custom-model-data") != null
                    ? item.getInt("custom-model-data")
                    : null;
            final DrawerTier drawerTier = new DrawerTier(
                    id,
                    name,
                    positiveLong(tier, "capacity-stacks"),
                    tier.getString("placement-permission", "")
            );
            ConfigurationSection upgrade = tier.getConfigurationSection("upgrade");
            if (upgrade == null) {
                upgrade = globalConfiguration.getConfigurationSection("upgrade.routes." + id);
            }
            final DrawerUpgradePath upgradePath = upgrade == null ? null : new DrawerUpgradePath(
                    requiredString(upgrade, "next-tier", "Tier " + id + " upgrade needs next-tier"),
                    nonNegativeDouble(upgrade, "cost"),
                    upgrade.getString("permission", "")
            );
            definitions.put(id, new DrawerTierDefinition(
                    drawerTier, miniMessage.deserialize(name), lore, customModelData, upgradePath
            ));
        }
        for (final DrawerTierDefinition definition : definitions.values()) {
            final DrawerUpgradePath path = definition.upgradePath();
            if (path == null) continue;
            final DrawerTierDefinition target = definitions.get(path.nextTierId());
            if (target == null) {
                throw new DrawerValidationException("Tier " + definition.tier().id()
                        + " upgrades to unknown tier " + path.nextTierId());
            }
            if (target.tier().stackCapacity() <= definition.tier().stackCapacity()) {
                throw new DrawerValidationException("Tier " + definition.tier().id()
                        + " must upgrade to a tier with greater capacity");
            }
        }
        return definitions;
    }

    private static Map<String, String> flattenMessages(final ConfigurationSection section) {
        final Map<String, String> values = new LinkedHashMap<>();
        flattenMessages(section, "", values);
        return values;
    }

    private static void flattenMessages(
            final ConfigurationSection section,
            final String prefix,
            final Map<String, String> values
    ) {
        for (final String key : section.getKeys(false)) {
            final String path = prefix.isEmpty() ? key : prefix + "." + key;
            final Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                flattenMessages(nested, path, values);
            } else if (value != null) {
                values.put(path, String.valueOf(value));
            }
        }
    }

    private static ConfigurationSection requireSection(final ConfigurationSection parent, final String path) {
        final ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new DrawerValidationException("Missing configuration section: " + path);
        }
        return section;
    }

    private static String requiredString(final ConfigurationSection configuration, final String path, final String error) {
        final String value = configuration.getString(path);
        if (value == null || value.isBlank()) {
            throw new DrawerValidationException(error);
        }
        return value;
    }

    private static int positiveInt(final ConfigurationSection configuration, final String path) {
        final int value = configuration.getInt(path);
        if (value < 1) {
            throw new DrawerValidationException(path + " must be a positive whole number");
        }
        return value;
    }

    private static long positiveLong(final ConfigurationSection configuration, final String path) {
        return positiveLong(configuration, path, 0L);
    }

    private static long positiveLong(final ConfigurationSection configuration, final String path, final long defaultValue) {
        final long value = configuration.getLong(path, defaultValue);
        if (value < 1L) {
            throw new DrawerValidationException(path + " must be a positive whole number");
        }
        return value;
    }

    private static double nonNegativeDouble(final ConfigurationSection configuration, final String path) {
        final double value = configuration.getDouble(path, Double.NaN);
        if (!Double.isFinite(value) || value < 0D) {
            throw new DrawerValidationException(path + " must be a finite non-negative number");
        }
        return value;
    }

    private static ItemDisplay.ItemDisplayTransform itemDisplayTransform(
            final ConfigurationSection configuration,
            final String path
    ) {
        final String configured = configuration.getString(path, ItemDisplay.ItemDisplayTransform.NONE.name());
        try {
            return ItemDisplay.ItemDisplayTransform.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            throw new DrawerValidationException(path + " is not a supported item display transform: " + configured);
        }
    }

    private static <T> T requireLoaded(final T value, final String name) {
        if (value == null) {
            throw new IllegalStateException("Drawer configuration " + name + " has not been loaded");
        }
        return value;
    }
}
