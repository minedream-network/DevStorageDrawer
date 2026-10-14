package com.teenkung.devstoragedrawer.config;

import java.util.Map;
import java.util.Objects;
import org.bukkit.entity.ItemDisplay;

/** Immutable, fully validated settings snapshot used by all runtime services. */
public record DrawerSettings(
        Map<String, DrawerTierDefinition> tiers,
        Interaction interaction,
        Visuals visuals,
        Bedrock bedrock,
        Automation automation,
        Redstone redstone,
        Parcels parcels,
        Breaking breaking,
        StorageMenu storageMenu,
        Upgrade upgrade,
        DrawerUpgradeMenu upgradeMenu
) {
    public DrawerSettings {
        tiers = Map.copyOf(Objects.requireNonNull(tiers, "tiers"));
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("At least one drawer tier is required");
        }
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(visuals, "visuals");
        Objects.requireNonNull(bedrock, "bedrock");
        Objects.requireNonNull(automation, "automation");
        Objects.requireNonNull(redstone, "redstone");
        Objects.requireNonNull(parcels, "parcels");
        Objects.requireNonNull(breaking, "breaking");
        Objects.requireNonNull(storageMenu, "storageMenu");
        Objects.requireNonNull(upgrade, "upgrade");
        Objects.requireNonNull(upgradeMenu, "upgradeMenu");
        if (redstone.logicalComparatorOutput()) {
            if (!automation.enabled() || automation.outputProxySlots() != 26) {
                throw new IllegalArgumentException(
                        "Logical comparator output requires automation.enabled and exactly 26 output proxy slots"
                );
            }
            for (final DrawerTierDefinition definition : tiers.values()) {
                if (definition.tier().stackCapacity() < 27L) {
                    throw new IllegalArgumentException(
                            "Logical comparator output requires every tier to hold at least 27 stacks: "
                                    + definition.tier().id()
                    );
                }
            }
        }
    }

    public DrawerSettings(final Map<String, DrawerTierDefinition> tiers, final Interaction interaction,
            final Visuals visuals, final Bedrock bedrock, final Automation automation,
            final Redstone redstone, final Parcels parcels) {
        this(tiers, interaction, visuals, bedrock, automation, redstone, parcels, new Breaking(16));
    }

    public DrawerSettings(final Map<String, DrawerTierDefinition> tiers, final Interaction interaction,
            final Visuals visuals, final Bedrock bedrock, final Automation automation,
            final Redstone redstone, final Parcels parcels, final Breaking breaking) {
        this(tiers, interaction, visuals, bedrock, automation, redstone, parcels, breaking, new StorageMenu(4));
    }

    public DrawerSettings(final Map<String, DrawerTierDefinition> tiers, final Interaction interaction,
            final Visuals visuals, final Bedrock bedrock, final Automation automation,
            final Redstone redstone, final Parcels parcels, final Breaking breaking,
            final StorageMenu storageMenu) {
        this(tiers, interaction, visuals, bedrock, automation, redstone, parcels, breaking, storageMenu,
                new Upgrade(false, 45, 8D), DrawerUpgradeMenu.defaults());
    }

    public record Upgrade(boolean enabled, int sessionSeconds, double maximumDistance) {
        public Upgrade {
            if (sessionSeconds < 5 || sessionSeconds > 300) {
                throw new IllegalArgumentException("upgrade.session-seconds must be between 5 and 300");
            }
            if (!Double.isFinite(maximumDistance) || maximumDistance < 1D || maximumDistance > 16D) {
                throw new IllegalArgumentException("upgrade.maximum-distance must be between 1 and 16");
            }
        }
    }

    public record StorageMenu(long refreshTicks, Map<DrawerMenuIcon, String> materials) {
        public StorageMenu {
            if (refreshTicks < 1 || refreshTicks > 100) {
                throw new IllegalArgumentException("storage-menu.refresh-ticks must be between 1 and 100");
            }
            final Map<DrawerMenuIcon, String> normalized = new java.util.EnumMap<>(DrawerMenuIcon.class);
            Objects.requireNonNull(materials, "materials").forEach((icon, material) -> {
                Objects.requireNonNull(icon, "icon");
                if (material == null || material.isBlank()) {
                    throw new IllegalArgumentException(icon.path() + " must be a material or provider:id");
                }
                normalized.put(icon, material.trim());
            });
            materials = Map.copyOf(normalized);
        }

        public StorageMenu(final long refreshTicks) { this(refreshTicks, Map.of()); }

        public String material(final DrawerMenuIcon icon) {
            return materials.getOrDefault(icon, icon.fallback().name());
        }
    }

    public record Breaking(int stacksPerTick) {
        public Breaking {
            if (stacksPerTick < 1 || stacksPerTick > 128) {
                throw new IllegalArgumentException("breaking.stacks-per-tick must be between 1 and 128");
            }
        }
    }

    public record Interaction(int bulkDepositWindowTicks, boolean requireFacingFace) {
        public Interaction {
            if (bulkDepositWindowTicks < 1) {
                throw new IllegalArgumentException("bulkDepositWindowTicks must be positive");
            }
        }
    }

    public record Visuals(
            boolean enabled,
            boolean repairOnChunkLoad,
            long updateDelayTicks,
            double frontOffset,
            boolean itemEnabled,
            float itemScale,
            float itemDepthScale,
            double itemOffsetY,
            ItemDisplay.ItemDisplayTransform itemTransform,
            boolean textEnabled,
            float textScale,
            int textLineWidth,
            double nameOffsetY,
            double amountOffsetY
    ) {
        public Visuals {
            if (updateDelayTicks < 1) {
                throw new IllegalArgumentException("updateDelayTicks must be positive");
            }
            Objects.requireNonNull(itemTransform, "itemTransform");
            if (frontOffset < 0D || itemScale <= 0F || itemDepthScale <= 0F
                    || textScale <= 0F || textLineWidth < 1) {
                throw new IllegalArgumentException("visual front offset, scales, and line width must be positive");
            }
        }
    }

    public record Bedrock(
            boolean enabled,
            boolean required,
            boolean armorStandShowItem,
            boolean armorStandShowName,
            boolean armorStandMarker,
            boolean armorStandInvisible,
            double hologramHeight,
            double hologramLineSpacing
    ) {
        public Bedrock {
            if (!Double.isFinite(hologramHeight) || hologramHeight < 0.5D || hologramHeight > 4D) {
                throw new IllegalArgumentException("bedrock.hologram.height must be between 0.5 and 4");
            }
            if (!Double.isFinite(hologramLineSpacing) || hologramLineSpacing < 0.1D || hologramLineSpacing > 1D) {
                throw new IllegalArgumentException("bedrock.hologram.line-spacing must be between 0.1 and 1");
            }
        }

        public Bedrock(final boolean enabled, final boolean required, final boolean armorStandShowItem,
                final boolean armorStandShowName, final boolean armorStandMarker, final boolean armorStandInvisible) {
            this(enabled, required, armorStandShowItem, armorStandShowName, armorStandMarker, armorStandInvisible,
                    1.35D, 0.25D);
        }
    }

    public record Automation(
            boolean enabled,
            int outputProxySlots,
            long rebalanceDelayTicks,
            boolean recoverJournalOnLoad,
            boolean debugLogging,
            long fallbackPollIntervalTicks,
            HopperTransferPolicy hopperTransfers
    ) {
        public Automation {
            if (outputProxySlots < 1 || outputProxySlots > 26) {
                throw new IllegalArgumentException("outputProxySlots must be between 1 and 26");
            }
            if (rebalanceDelayTicks < 1) {
                throw new IllegalArgumentException("automation delay must be positive");
            }
            if (fallbackPollIntervalTicks < 1) {
                throw new IllegalArgumentException("automation fallback poll interval must be positive");
            }
            Objects.requireNonNull(hopperTransfers, "hopperTransfers");
        }
    }

    public record Redstone(
            boolean logicalComparatorOutput,
            long comparatorRecalculateDelayTicks,
            boolean protectFromExplosions,
            boolean preventPistonMovement
    ) {
        public Redstone {
            if (comparatorRecalculateDelayTicks < 1) {
                throw new IllegalArgumentException("comparatorRecalculateDelayTicks must be positive");
            }
        }
    }

    public record Parcels(
            boolean enabled,
            boolean dropOnBreak
    ) {
    }
}
