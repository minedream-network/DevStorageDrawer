package com.teenkung.devstoragedrawer.display;

import com.teenkung.devstoragedrawer.text.DrawerTextFormat;
import com.teenkung.devstoragedrawer.bedrock.BedrockPlayerDetector;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import com.teenkung.devstoragedrawer.domain.DrawerDisplayLink;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.AxisAngle4f;
import org.joml.Matrix4f;

/**
 * Maintains Java display entities and a two-line armor-stand hologram for Floodgate players.
 * Runtime callers invoke {@link #refresh(Barrel, DrawerState, long)} only from the barrel's
 * region thread and only after the current storage state has been persisted.
 */
public final class DrawerVisualRenderer implements Listener {

    /** Keeps Java displays readable even where the client samples the drawer's block lighting. */
    private static final Display.Brightness FULL_BRIGHTNESS = new Display.Brightness(15, 15);

    private final JavaPlugin plugin;
    private final FoliaExecution execution;
    private final DrawerStateRepository repository;
    private final BedrockPlayerDetector bedrockPlayers;
    private final ConcurrentMap<RenderKey, Location> pendingRenders = new ConcurrentHashMap<>();
    private volatile DrawerSettings settings;

    public DrawerVisualRenderer(
            final JavaPlugin plugin,
            final FoliaExecution execution,
            final DrawerStateRepository repository,
            final BedrockPlayerDetector bedrockPlayers,
            final DrawerSettings settings
    ) {
        this.plugin = plugin;
        this.execution = execution;
        this.repository = repository;
        this.bedrockPlayers = bedrockPlayers;
        this.settings = settings;
    }

    public void reloadSettings(final DrawerSettings settings) {
        this.settings = settings;
    }

    public void refresh(final Barrel barrel, final DrawerState state, final long physicalCount) {
        final Location location = barrel.getLocation();
        final RenderKey key = RenderKey.of(location);
        if (pendingRenders.put(key, location) != null) {
            return;
        }
        execution.runLaterAt(location, settings.visuals().updateDelayTicks(), ignored -> {
            final Location target = pendingRenders.remove(key);
            if (target != null) {
                renderAt(target);
            }
        });
    }

    private void renderAt(final Location location) {
        final Block block = location.getBlock();
        if (!(block.getState() instanceof Barrel barrel) || !repository.isDrawer(barrel)) {
            return;
        }
        final DrawerStateReadResult read = repository.read(barrel);
        if (read instanceof DrawerStateReadResult.Valid valid) {
            renderNow(barrel, valid.state(), countPhysical(barrel, valid.state()));
        }
    }

    private void renderNow(final Barrel barrel, final DrawerState state, final long physicalCount) {
        if (!settings.visuals().enabled() || !state.hasTemplate()) {
            remove(barrel, state);
            return;
        }

        final ItemStack template = state.requireTemplate();
        final long total = displayedTotal(state, physicalCount);
        final FaceLayout layout = layout(barrel);
        final BedrockLayout hologram = bedrockLayout(barrel);
        final DrawerDisplayLink current = state.displayLink();
        final World world = barrel.getWorld();

        removeDisabledDisplays(world, current);

        final ItemDisplay javaItem = settings.visuals().itemEnabled()
                ? resolveOrSpawn(world, current.optionalJavaItemDisplayId(), ItemDisplay.class,
                layout.origin(), entity -> configureJavaItem(entity, barrel.getBlock(), template, layout))
                : null;
        final TextDisplay javaName = settings.visuals().textEnabled()
                ? resolveOrSpawn(world, current.optionalJavaNameTextDisplayId(), TextDisplay.class,
                layout.origin(), entity -> configureJavaName(entity, barrel.getBlock(), template, layout))
                : null;
        final TextDisplay javaAmount = settings.visuals().textEnabled()
                ? resolveOrSpawn(world, current.optionalJavaAmountTextDisplayId(), TextDisplay.class,
                layout.origin(), entity -> configureJavaAmount(entity, barrel.getBlock(), total, state.capacitySnapshot(), layout))
                : null;

        // Reuse the two persisted Bedrock entity IDs so existing drawers need no data migration.
        final ArmorStand bedrockItem = bedrockEnabled()
                ? resolveOrSpawn(world, current.optionalBedrockItemDisplayId(), ArmorStand.class,
                hologram.name(), entity -> configureBedrockItem(entity, barrel.getBlock(), template))
                : null;
        final ArmorStand bedrockText = bedrockEnabled()
                ? resolveOrSpawn(world, current.optionalBedrockTextDisplayId(), ArmorStand.class,
                hologram.amount(), entity -> configureBedrockText(entity, barrel.getBlock(), total, state.capacitySnapshot()))
                : null;

        updateJavaItem(javaItem, template, layout);
        updateJavaName(javaName, template, layout);
        updateJavaAmount(javaAmount, total, state.capacitySnapshot(), layout);
        updateBedrockItem(bedrockItem, template, hologram);
        updateBedrockText(bedrockText, total, state.capacitySnapshot(), hologram);

        final DrawerDisplayLink next = new DrawerDisplayLink(
                id(javaItem), id(javaName), id(javaAmount), id(bedrockItem), id(bedrockText)
        );
        if (!next.equals(current)) {
            saveDisplayLink(barrel, next);
        }
    }

    public void remove(final Barrel barrel, final DrawerState state) {
        pendingRenders.remove(RenderKey.of(barrel.getLocation()));
        final DrawerDisplayLink link = state.displayLink();
        remove(worldEntity(barrel.getWorld(), link.optionalJavaItemDisplayId()));
        remove(worldEntity(barrel.getWorld(), link.optionalJavaNameTextDisplayId()));
        remove(worldEntity(barrel.getWorld(), link.optionalJavaAmountTextDisplayId()));
        remove(worldEntity(barrel.getWorld(), link.optionalBedrockItemDisplayId()));
        remove(worldEntity(barrel.getWorld(), link.optionalBedrockTextDisplayId()));
        if (!link.isEmpty()) {
            saveDisplayLink(barrel, DrawerDisplayLink.none());
        }
    }

    /** Removes display entities by their owner coordinates when no readable drawer state remains. */
    public void removeAt(final Barrel barrel) {
        final Location location = barrel.getLocation();
        pendingRenders.remove(RenderKey.of(location));
        for (final Entity entity : barrel.getWorld().getNearbyEntities(location.clone().add(0.5D, 0.5D, 0.5D), 2D, 2D, 2D)) {
            if (isDisplay(entity) && drawerLocation(entity).filter(owner -> sameBlock(owner, location)).isPresent()) {
                remove(entity);
            }
        }
    }

    private void saveDisplayLink(final Barrel barrel, final DrawerDisplayLink replacement) {
        final BlockState currentBlockState = barrel.getBlock().getState();
        if (!(currentBlockState instanceof Barrel currentBarrel) || !repository.isDrawer(currentBarrel)) {
            return;
        }
        final DrawerStateReadResult current = repository.read(currentBarrel);
        if (current instanceof DrawerStateReadResult.Valid valid) {
            repository.save(currentBarrel, valid.state().withDisplayLink(replacement));
        }
    }

    /** Repairs all tagged drawers among the chunk's existing tile entities. */
    public int repairChunk(final Chunk chunk) {
        int repaired = 0;
        for (final BlockState blockState : chunk.getTileEntities()) {
            if (!(blockState instanceof Barrel barrel) || !repository.isDrawer(barrel)) {
                continue;
            }
            final DrawerStateReadResult read = repository.read(barrel);
            if (read instanceof DrawerStateReadResult.Valid valid) {
                refresh(barrel, valid.state(), countPhysical(barrel, valid.state()));
                repaired++;
            }
        }
        for (final Entity entity : chunk.getEntities()) {
            if (!isDisplay(entity)) {
                continue;
            }
            final Location ownerLocation = drawerLocation(entity).orElse(null);
            if (ownerLocation == null || !Bukkit.isOwnedByCurrentRegion(ownerLocation)) {
                if (ownerLocation == null) {
                    remove(entity);
                }
                continue;
            }
            final Block ownerBlock = ownerLocation.getBlock();
            if (!(ownerBlock.getState() instanceof Barrel owner) || !repository.isDrawer(owner)) {
                remove(entity);
                continue;
            }
            final DrawerStateReadResult read = repository.read(owner);
            if (!(read instanceof DrawerStateReadResult.Valid valid)
                    || !isLinked(valid.state().displayLink(), entity.getUniqueId())) {
                remove(entity);
            }
        }
        return repaired;
    }

    public Optional<Location> drawerLocation(final Entity entity) {
        if (!isDisplay(entity)) {
            return Optional.empty();
        }
        final PersistentDataContainer pdc = entity.getPersistentDataContainer();
        final String worldId = pdc.get(DrawerDisplayKeys.WORLD, PersistentDataType.STRING);
        final Integer x = pdc.get(DrawerDisplayKeys.X, PersistentDataType.INTEGER);
        final Integer y = pdc.get(DrawerDisplayKeys.Y, PersistentDataType.INTEGER);
        final Integer z = pdc.get(DrawerDisplayKeys.Z, PersistentDataType.INTEGER);
        if (worldId == null || x == null || y == null || z == null) {
            return Optional.empty();
        }
        try {
            final World world = Bukkit.getWorld(UUID.fromString(worldId));
            return world == null ? Optional.empty() : Optional.of(new Location(world, x, y, z));
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public boolean isDisplay(final Entity entity) {
        final Byte marker = entity.getPersistentDataContainer().get(DrawerDisplayKeys.MARKER, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDisplayDamage(final EntityDamageEvent event) {
        if (isDisplay(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStandManipulate(final PlayerArmorStandManipulateEvent event) {
        if (isDisplay(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        if (settings.visuals().repairOnChunkLoad()) {
            repairChunk(event.getChunk());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTrackDisplay(final PlayerTrackEntityEvent event) {
        final Entity entity = event.getEntity();
        if (!isDisplay(entity)) {
            return;
        }
        final String role = entity.getPersistentDataContainer().get(DrawerDisplayKeys.ROLE, PersistentDataType.STRING);
        final boolean floodgate = bedrockPlayers.isBedrock(event.getPlayer());
        final boolean visible = role != null && settings.visuals().enabled() && switch (role) {
            case "bedrock_item", "bedrock_text" -> floodgate && bedrockEnabled();
            case "java_item" -> !floodgate && settings.visuals().itemEnabled();
            case "java_name", "java_amount" -> !floodgate && settings.visuals().textEnabled();
            default -> false;
        };
        if (!visible) {
            event.setCancelled(true);
        }
    }

    private <T extends Entity> T resolveOrSpawn(
            final World world,
            final Optional<UUID> existingId,
            final Class<T> type,
            final Location location,
            final Consumer<T> initializer
    ) {
        final Entity existing = existingId.map(world::getEntity).orElse(null);
        if (type.isInstance(existing) && isDisplay(existing)) {
            return type.cast(existing);
        }
        if (existing != null && isDisplay(existing)) {
            remove(existing);
        }
        return world.spawn(location, type, initializer);
    }

    private void configureJavaItem(
            final ItemDisplay display,
            final Block drawer,
            final ItemStack template,
            final FaceLayout layout
    ) {
        tag(display, drawer, "java_item");
        display.setVisibleByDefault(true);
        display.setItemDisplayTransform(settings.visuals().itemTransform());
        display.setItemStack(template.clone());
        applyItemPresentation(display, layout);
    }

    private void configureJavaName(
            final TextDisplay display,
            final Block drawer,
            final ItemStack template,
            final FaceLayout layout
    ) {
        tag(display, drawer, "java_name");
        display.setVisibleByDefault(true);
        display.setBillboard(Display.Billboard.FIXED);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.text(displayName(template));
        applyTextPresentation(display, layout, settings.visuals().nameOffsetY());
    }

    private void configureJavaAmount(
            final TextDisplay display,
            final Block drawer,
            final long total,
            final long capacity,
            final FaceLayout layout
    ) {
        tag(display, drawer, "java_amount");
        display.setVisibleByDefault(true);
        display.setBillboard(Display.Billboard.FIXED);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.text(amountText(total, capacity));
        applyTextPresentation(display, layout, settings.visuals().amountOffsetY());
    }

    private void configureBedrockItem(
            final ArmorStand stand,
            final Block drawer,
            final ItemStack template
    ) {
        tag(stand, drawer, "bedrock_item");
        stand.setVisibleByDefault(true);
        applyBedrockItem(stand, template);
    }

    private void configureBedrockText(
            final ArmorStand stand,
            final Block drawer,
            final long total,
            final long capacity
    ) {
        tag(stand, drawer, "bedrock_text");
        stand.setVisibleByDefault(true);
        applyBedrockText(stand, total, capacity);
    }

    private void updateJavaItem(final ItemDisplay display, final ItemStack template, final FaceLayout layout) {
        update(display, entity -> {
            entity.setVisibleByDefault(true);
            entity.setItemDisplayTransform(settings.visuals().itemTransform());
            entity.setItemStack(template.clone());
            applyItemPresentation(entity, layout);
            teleport(entity, layout.origin());
        });
    }

    private void updateJavaName(final TextDisplay display, final ItemStack template, final FaceLayout layout) {
        update(display, entity -> {
            entity.setVisibleByDefault(true);
            entity.text(displayName(template));
            applyTextPresentation(entity, layout, settings.visuals().nameOffsetY());
            teleport(entity, layout.origin());
        });
    }

    private void updateJavaAmount(final TextDisplay display, final long total, final long capacity, final FaceLayout layout) {
        update(display, entity -> {
            entity.setVisibleByDefault(true);
            entity.text(amountText(total, capacity));
            applyTextPresentation(entity, layout, settings.visuals().amountOffsetY());
            teleport(entity, layout.origin());
        });
    }

    private void updateBedrockItem(final ArmorStand stand, final ItemStack template, final BedrockLayout layout) {
        update(stand, entity -> {
            entity.setVisibleByDefault(true);
            applyBedrockItem(entity, template);
            teleport(entity, layout.name());
        });
    }

    private void updateBedrockText(
            final ArmorStand stand,
            final long total,
            final long capacity,
            final BedrockLayout layout
    ) {
        update(stand, entity -> {
            entity.setVisibleByDefault(true);
            applyBedrockText(entity, total, capacity);
            teleport(entity, layout.amount());
        });
    }

    private boolean bedrockEnabled() {
        return settings.bedrock().enabled() && bedrockPlayers.isAvailable();
    }

    private void applyBedrockBase(final ArmorStand stand, final boolean invisible) {
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setMarker(settings.bedrock().armorStandMarker());
        stand.setInvisible(invisible);
        stand.setSmall(true);
        stand.setSilent(true);
        stand.setCollidable(false);
        stand.setCanPickupItems(false);
        stand.setCustomNameVisible(settings.bedrock().armorStandShowName());
    }

    private void applyBedrockItem(final ArmorStand stand, final ItemStack template) {
        applyBedrockBase(stand, settings.bedrock().armorStandInvisible());
        stand.customName(displayName(template));
        if (stand.getEquipment() != null) {
            stand.getEquipment().setHelmet(settings.bedrock().armorStandShowItem() ? template.clone() : null);
        }
    }

    private void applyBedrockText(final ArmorStand stand, final long total, final long capacity) {
        applyBedrockBase(stand, true);
        stand.customName(amountText(total, capacity));
    }

    private void tag(final Entity entity, final Block drawer, final String role) {
        final PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(DrawerDisplayKeys.MARKER, PersistentDataType.BYTE, (byte) 1);
        pdc.set(DrawerDisplayKeys.ROLE, PersistentDataType.STRING, role);
        pdc.set(DrawerDisplayKeys.WORLD, PersistentDataType.STRING, drawer.getWorld().getUID().toString());
        pdc.set(DrawerDisplayKeys.X, PersistentDataType.INTEGER, drawer.getX());
        pdc.set(DrawerDisplayKeys.Y, PersistentDataType.INTEGER, drawer.getY());
        pdc.set(DrawerDisplayKeys.Z, PersistentDataType.INTEGER, drawer.getZ());
    }

    private <T extends Entity> void update(final T entity, final Consumer<T> action) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        execution.runOnEntity(entity, ignored -> action.accept(entity), () -> { });
    }

    private void remove(final Entity entity) {
        if (entity != null) {
            if (entity.isValid()) {
                execution.runOnEntity(entity, ignored -> entity.remove(), () -> { });
            }
        }
    }

    private void removeDisabledDisplays(final World world, final DrawerDisplayLink link) {
        if (!settings.visuals().itemEnabled()) {
            remove(worldEntity(world, link.optionalJavaItemDisplayId()));
        }
        if (!settings.visuals().textEnabled()) {
            remove(worldEntity(world, link.optionalJavaNameTextDisplayId()));
            remove(worldEntity(world, link.optionalJavaAmountTextDisplayId()));
        }
        if (!bedrockEnabled()) {
            remove(worldEntity(world, link.optionalBedrockItemDisplayId()));
            remove(worldEntity(world, link.optionalBedrockTextDisplayId()));
        }
    }

    private void teleport(final Entity entity, final Location location) {
        entity.teleportAsync(location).whenComplete((success, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Could not reposition drawer display " + entity.getUniqueId(), failure);
            } else if (!success) {
                plugin.getLogger().warning("Could not reposition drawer display " + entity.getUniqueId());
            }
        });
    }

    private static Entity worldEntity(final World world, final Optional<UUID> id) {
        return id.map(world::getEntity).orElse(null);
    }

    private static UUID id(final Entity entity) {
        return entity == null ? null : entity.getUniqueId();
    }

    private static boolean isLinked(final DrawerDisplayLink link, final UUID id) {
        return id.equals(link.javaItemDisplayId())
                || id.equals(link.javaNameTextDisplayId())
                || id.equals(link.javaAmountTextDisplayId())
                || id.equals(link.bedrockItemDisplayId())
                || id.equals(link.bedrockTextDisplayId());
    }

    private static Component amountText(final long total, final long capacity) {
        return Component.text(DrawerTextFormat.number(total) + " / " + DrawerTextFormat.number(capacity));
    }

    /** Uses the item translation directly so TextDisplay does not render Paper's bracketed fallback name. */
    private static Component displayName(final ItemStack template) {
        final ItemMeta meta = template.getItemMeta();
        return meta.hasDisplayName() ? template.displayName() : Component.translatable(template);
    }

    private static boolean sameBlock(final Location first, final Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private void applyItemPresentation(final ItemDisplay display, final FaceLayout layout) {
        display.setBrightness(FULL_BRIGHTNESS);
        display.setRotation(0F, 0F);
        display.setTransformationMatrix(new Matrix4f()
                .rotate(itemRotation(layout.face()))
                .translate(0F, (float) settings.visuals().itemOffsetY(), (float) -settings.visuals().frontOffset())
                .scale(
                        settings.visuals().itemScale(),
                        settings.visuals().itemScale(),
                        settings.visuals().itemDepthScale()
                ));
    }

    private void applyTextPresentation(
            final TextDisplay display,
            final FaceLayout layout,
            final double verticalOffset
    ) {
        display.setBrightness(FULL_BRIGHTNESS);
        display.setLineWidth(settings.visuals().textLineWidth());
        display.setRotation(0F, 0F);
        Matrix4f matrix = new Matrix4f().rotate(textRotation(layout.face()));
        if (layout.face().getModY() != 0) {
            matrix = matrix.rotate((float) Math.PI, 0F, 1F, 0F);
        }
        display.setTransformationMatrix(matrix
                .translate(0F, (float) verticalOffset, (float) settings.visuals().frontOffset())
                .scale(settings.visuals().textScale()));
    }

    private static long countPhysical(final Barrel barrel, final DrawerState state) {
        if (!state.hasTemplate()) {
            return 0L;
        }
        long total = 0L;
        for (final ItemStack stack : barrel.getInventory().getContents()) {
            if (state.matchesTemplate(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Schema 2 persists the authoritative total separately from its real inventory mirror. The
     * mirror can legitimately lead that total until hopper or chunk-load reconciliation runs, so a
     * visual refresh must not apply the stricter mutation invariant to that transient observation.
     */
    static long displayedTotal(final DrawerState state, final long physicalCount) {
        return state.usesReservedMirror()
                ? state.storedTotal()
                : state.totalForPhysical(physicalCount);
    }

    private FaceLayout layout(final Barrel barrel) {
        final BlockFace face = barrel.getBlockData() instanceof Directional directional
                ? directional.getFacing()
                : BlockFace.NORTH;
        final Location center = barrel.getLocation().add(0.5D, 0.5D, 0.5D);
        final Location front = center.clone().add(
                face.getModX() * settings.visuals().frontOffset(),
                face.getModY() * settings.visuals().frontOffset(),
                face.getModZ() * settings.visuals().frontOffset()
        );
        final BlockFace up = switch (face) {
            case UP -> BlockFace.NORTH;
            case DOWN -> BlockFace.SOUTH;
            default -> BlockFace.UP;
        };
        return new FaceLayout(
                center,
                offset(front, up, settings.visuals().itemOffsetY()),
                offset(front, up, settings.visuals().nameOffsetY()),
                offset(front, up, settings.visuals().amountOffsetY()),
                face,
                rotationYaw(face),
                rotationPitch(face)
        );
    }

    private BedrockLayout bedrockLayout(final Barrel barrel) {
        final Location name = barrel.getLocation().add(0.5D, settings.bedrock().hologramHeight(), 0.5D);
        return new BedrockLayout(name, name.clone().subtract(0D, settings.bedrock().hologramLineSpacing(), 0D));
    }

    private record BedrockLayout(Location name, Location amount) { }

    private static Location offset(final Location origin, final BlockFace face, final double amount) {
        return origin.clone().add(face.getModX() * amount, face.getModY() * amount, face.getModZ() * amount);
    }

    private static float rotationYaw(final BlockFace face) {
        return switch (face) {
            case NORTH -> 180F;
            case WEST -> 90F;
            case EAST -> -90F;
            default -> 0F;
        };
    }

    private static float rotationPitch(final BlockFace face) {
        return switch (face) {
            case UP -> -90F;
            case DOWN -> 90F;
            default -> 0F;
        };
    }

    private static AxisAngle4f itemRotation(final BlockFace face) {
        return switch (face) {
            case EAST -> new AxisAngle4f(-0.5F * (float) Math.PI, 0F, 1F, 0F);
            case SOUTH -> new AxisAngle4f((float) Math.PI, 0F, 1F, 0F);
            case WEST -> new AxisAngle4f(0.5F * (float) Math.PI, 0F, 1F, 0F);
            case UP -> new AxisAngle4f(0.5F * (float) Math.PI, 1F, 0F, 0F);
            case DOWN -> new AxisAngle4f(-0.5F * (float) Math.PI, 1F, 0F, 0F);
            default -> new AxisAngle4f(0F, 0F, 0F, 1F);
        };
    }

    private static AxisAngle4f textRotation(final BlockFace face) {
        return switch (face) {
            case NORTH -> new AxisAngle4f((float) Math.PI, 0F, 1F, 0F);
            case EAST -> new AxisAngle4f(0.5F * (float) Math.PI, 0F, 1F, 0F);
            case WEST -> new AxisAngle4f(-0.5F * (float) Math.PI, 0F, 1F, 0F);
            case UP -> new AxisAngle4f(0.5F * (float) Math.PI, 1F, 0F, 0F);
            case DOWN -> new AxisAngle4f(-0.5F * (float) Math.PI, 1F, 0F, 0F);
            default -> new AxisAngle4f(0F, 0F, 0F, 1F);
        };
    }

    private record FaceLayout(
            Location origin,
            Location item,
            Location name,
            Location amount,
            BlockFace face,
            float yaw,
            float pitch
    ) {
    }

    private record RenderKey(UUID worldId, int x, int y, int z) {
        static RenderKey of(final Location location) {
            final World world = location.getWorld();
            if (world == null) {
                throw new IllegalArgumentException("drawer display location has no world");
            }
            return new RenderKey(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }
}
