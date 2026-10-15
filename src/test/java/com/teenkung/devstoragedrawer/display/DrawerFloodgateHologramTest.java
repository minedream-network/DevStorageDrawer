package com.teenkung.devstoragedrawer.display;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.bedrock.BedrockPlayerDetector;
import com.teenkung.devstoragedrawer.config.DrawerSettings;
import com.teenkung.devstoragedrawer.domain.DrawerDisplayLink;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DrawerFloodgateHologramTest {
    @SuppressWarnings("unchecked")
    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final Barrel barrel = mock(Barrel.class);
        final Directional facing = mock(Directional.class);
        final DrawerStateRepository repository = mock(DrawerStateRepository.class);
        final BedrockPlayerDetector detector = mock(BedrockPlayerDetector.class);
        final Map<UUID, Entity> entities = new LinkedHashMap<>();
        final Map<UUID, Location> positions = new HashMap<>();
        final Map<UUID, Component> names = new HashMap<>();
        final AtomicReference<DrawerState> state = new AtomicReference<>();
        final Player floodgate = mock(Player.class);
        final Player java = mock(Player.class);
        final ItemStack item = mock(ItemStack.class);
        final Location origin = new Location(world, 10, 64, -20);
        final DrawerVisualRenderer renderer;

        Fixture(final DrawerSettings.Bedrock bedrock, final boolean apiAvailable) {
            final FoliaExecution execution = mock(FoliaExecution.class);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            when(barrel.getWorld()).thenReturn(world);
            when(barrel.getBlock()).thenReturn(block);
            when(barrel.getBlockData()).thenReturn(facing);
            when(facing.getFacing()).thenReturn(BlockFace.NORTH);
            when(barrel.getLocation()).thenAnswer(call -> origin.clone());
            when(block.getLocation()).thenAnswer(call -> origin.clone());
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(10);
            when(block.getY()).thenReturn(64);
            when(block.getZ()).thenReturn(-20);
            when(block.getState()).thenReturn(barrel);
            final Inventory inventory = mock(Inventory.class);
            when(inventory.getContents()).thenReturn(new ItemStack[27]);
            when(barrel.getInventory()).thenReturn(inventory);
            when(item.getType()).thenReturn(mock(Material.class));
            when(item.getAmount()).thenReturn(1);
            when(item.getMaxStackSize()).thenReturn(64);
            when(item.clone()).thenReturn(item);
            final ItemMeta meta = mock(ItemMeta.class);
            when(meta.hasDisplayName()).thenReturn(true);
            when(item.getItemMeta()).thenReturn(meta);
            when(item.displayName()).thenReturn(Component.text("Stored sword"));
            state.set(DrawerState.restored(2, "tier", item, 1200, 64, 4096, null, DrawerDisplayLink.none()));
            when(repository.isDrawer(barrel)).thenReturn(true);
            when(repository.read(barrel)).thenAnswer(call -> new DrawerStateReadResult.Valid(state.get()));
            when(repository.save(eq(barrel), any())).thenAnswer(call -> { state.set(call.getArgument(1)); return true; });
            when(detector.isAvailable()).thenReturn(apiAvailable);
            when(detector.isBedrock(floodgate)).thenReturn(apiAvailable);
            doAnswer(call -> { ((Consumer<?>) call.getArgument(2)).accept(null); return null; })
                    .when(execution).runLaterAt(any(), anyLong(), any());
            doAnswer(call -> { ((Consumer<?>) call.getArgument(1)).accept(null); return null; })
                    .when(execution).runOnEntity(any(), any(), any());
            when(world.getEntity(any())).thenAnswer(call -> entities.get(call.getArgument(0)));
            when(world.spawn(any(Location.class), any(Class.class), any(Consumer.class))).thenAnswer(call -> {
                final Class<? extends Entity> type = call.getArgument(1);
                final Entity entity = mock(type);
                final UUID id = UUID.randomUUID();
                when(entity.getUniqueId()).thenReturn(id);
                when(entity.isValid()).thenReturn(true);
                when(entity.getLocation()).thenAnswer(ignored -> positions.get(id).clone());
                positions.put(id, call.<Location>getArgument(0).clone());
                when(entity.teleportAsync(any())).thenAnswer(ignored -> {
                    positions.put(id, ignored.<Location>getArgument(0).clone());
                    return CompletableFuture.completedFuture(true);
                });
                final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
                final Map<NamespacedKey, Object> data = new HashMap<>();
                doAnswer(ignored -> { data.put(ignored.getArgument(0), ignored.getArgument(2)); return null; })
                        .when(pdc).set(any(), any(), any());
                when(pdc.get(any(), any())).thenAnswer(ignored -> data.get(ignored.getArgument(0)));
                when(entity.getPersistentDataContainer()).thenReturn(pdc);
                if (entity instanceof ArmorStand stand) when(stand.getEquipment()).thenReturn(mock(EntityEquipment.class));
                doAnswer(ignored -> { names.put(id, ignored.getArgument(0)); return null; }).when(entity).customName(any());
                entities.put(id, entity);
                call.<Consumer<Entity>>getArgument(2).accept(entity);
                return entity;
            });
            renderer = new DrawerVisualRenderer(mock(JavaPlugin.class), execution, repository, detector, settings(bedrock));
        }

        void render() { renderer.refresh(barrel, state.get(), 64); }
        ArmorStand name() { return (ArmorStand) entities.get(state.get().displayLink().bedrockItemDisplayId()); }
        ArmorStand amount() { return (ArmorStand) entities.get(state.get().displayLink().bedrockTextDisplayId()); }
        String text(final Entity entity) { return PlainTextComponentSerializer.plainText().serialize(names.get(entity.getUniqueId())); }
        boolean hidden(final Player player, final Entity entity) {
            final var event = new PlayerTrackEntityEvent(player, entity);
            renderer.onPlayerTrackDisplay(event);
            return event.isCancelled();
        }
        @Override public void close() { bukkit.close(); }
    }

    private static DrawerSettings settings(final DrawerSettings.Bedrock bedrock) {
        final DrawerSettings settings = mock(DrawerSettings.class);
        when(settings.bedrock()).thenReturn(bedrock);
        when(settings.visuals()).thenReturn(new DrawerSettings.Visuals(true, true, 1, 0.54,
                true, 0.36F, 0.08F, 0.02, ItemDisplay.ItemDisplayTransform.NONE,
                true, 0.4F, 92, 0.30, -0.42));
        return settings;
    }

    private static DrawerSettings.Bedrock defaults() {
        return new DrawerSettings.Bedrock(true, false, false, true, true, true);
    }

    @Test void createsTwoProtectedTextRowsWithNoHelmetByDefault() {
        try (final var f = new Fixture(defaults(), true)) {
            f.render();
            assertEquals(5, f.entities.size());
            assertEquals("Stored sword", f.text(f.name()));
            assertEquals("1,200 / 4,096", f.text(f.amount()));
            for (final var row : List.of(f.name(), f.amount())) {
                verify(row, atLeastOnce()).setInvisible(true);
                verify(row, atLeastOnce()).setMarker(true);
                verify(row, atLeastOnce()).setCustomNameVisible(true);
                verify(row, atLeastOnce()).setGravity(false);
                verify(row, atLeastOnce()).setInvulnerable(true);
                verify(row, atLeastOnce()).setCanPickupItems(false);
            }
            verify(f.name().getEquipment(), never()).setHelmet(notNull());
        }
    }

    @Test void keepsHologramsAboveTheDrawerForEveryBarrelOrientation() {
        for (final var face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            try (final var f = new Fixture(defaults(), true)) {
                when(f.facing.getFacing()).thenReturn(face);
                f.render();
                final Location top = f.positions.get(f.name().getUniqueId());
                final Location lower = f.positions.get(f.amount().getUniqueId());
                assertEquals(10.5, top.getX());
                assertEquals(-19.5, top.getZ());
                assertEquals(65.35, top.getY(), 0.00001);
                assertEquals(0.25, top.getY() - lower.getY(), 0.00001);
            }
        }
    }

    @Test void floodgateAndJavaPlayersSeeOnlyTheirOwnRenderer() {
        try (final var f = new Fixture(defaults(), true)) {
            f.render();
            final var link = f.state.get().displayLink();
            for (final var id : List.of(link.javaItemDisplayId(), link.javaNameTextDisplayId(), link.javaAmountTextDisplayId())) {
                assertFalse(f.hidden(f.java, f.entities.get(id)));
                assertTrue(f.hidden(f.floodgate, f.entities.get(id)));
            }
            for (final var row : List.of(f.name(), f.amount())) {
                assertTrue(f.hidden(f.java, row));
                assertFalse(f.hidden(f.floodgate, row));
            }
            f.renderer.reloadSettings(settings(new DrawerSettings.Bedrock(false, false, false, true, true, true)));
            assertTrue(f.hidden(f.floodgate, f.name()));
        }
    }

    @Test void missingFloodgateCreatesOnlyJavaDisplays() {
        try (final var f = new Fixture(defaults(), false)) {
            f.render();
            assertEquals(3, f.entities.size());
            assertNull(f.state.get().displayLink().bedrockItemDisplayId());
            assertNull(f.state.get().displayLink().bedrockTextDisplayId());
            assertTrue(f.entities.values().stream().noneMatch(ArmorStand.class::isInstance));
        }
    }

    @Test void stockChangesReuseExistingHologramsAndShowAuthoritativeTotals() {
        try (final var f = new Fixture(defaults(), true)) {
            f.render();
            final var previous = f.state.get().displayLink();
            f.state.set(f.state.get().withStoredTotalAndExpectedMirrorCount(1300, 64));
            f.render();
            assertEquals(previous, f.state.get().displayLink());
            assertEquals(5, f.entities.size());
            assertEquals("1,300 / 4,096", f.text(f.amount()));
        }
    }

    @Test void reloadRepositionsRowsAndRemovesPreviouslyEnabledHelmet() {
        try (final var f = new Fixture(new DrawerSettings.Bedrock(true, false, true, true, true, true), true)) {
            f.render();
            final var previous = f.state.get().displayLink();
            verify(f.name().getEquipment(), atLeastOnce()).setHelmet(f.item);
            clearInvocations(f.name().getEquipment());
            f.renderer.reloadSettings(settings(new DrawerSettings.Bedrock(true, false, false, true, true, true, 2, 0.4)));
            f.render();
            assertEquals(previous, f.state.get().displayLink());
            assertEquals(66, f.positions.get(f.name().getUniqueId()).getY());
            assertEquals(65.6, f.positions.get(f.amount().getUniqueId()).getY(), 0.00001);
            verify(f.name().getEquipment()).setHelmet(null);
            verify(f.name().getEquipment(), never()).setHelmet(notNull());
        }
    }

    @Test void disablingBedrockRemovesItsRowsAndClearsOnlyTheirPersistedIds() {
        try (final var f = new Fixture(defaults(), true)) {
            f.render();
            final var name = f.name();
            final var amount = f.amount();
            final var previous = f.state.get().displayLink();
            f.renderer.reloadSettings(settings(new DrawerSettings.Bedrock(false, false, false, true, true, true)));
            f.render();
            verify(name).remove();
            verify(amount).remove();
            assertEquals(previous.javaItemDisplayId(), f.state.get().displayLink().javaItemDisplayId());
            assertNull(f.state.get().displayLink().bedrockItemDisplayId());
            assertNull(f.state.get().displayLink().bedrockTextDisplayId());
            assertEquals(1200, f.state.get().storedTotal());
        }
    }
}
