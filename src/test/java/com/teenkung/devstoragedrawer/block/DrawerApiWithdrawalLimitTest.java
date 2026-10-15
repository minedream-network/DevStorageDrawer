package com.teenkung.devstoragedrawer.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.api.DrawerLocation;
import com.teenkung.devstoragedrawer.api.DrawerOperationContext;
import com.teenkung.devstoragedrawer.api.DrawerTransferStatus;
import com.teenkung.devstoragedrawer.domain.DrawerDisplayLink;
import com.teenkung.devstoragedrawer.domain.DrawerState;
import com.teenkung.devstoragedrawer.hopper.DrawerHopperBridge;
import com.teenkung.devstoragedrawer.persistence.DrawerStateReadResult;
import com.teenkung.devstoragedrawer.persistence.DrawerStateRepository;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DrawerApiWithdrawalLimitTest {

    @Test
    @SuppressWarnings("unchecked")
    void oversizedResultIsRejectedBeforeStockIsDebited() {
        final UUID worldId = UUID.randomUUID();
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final Barrel barrel = mock(Barrel.class);
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Logger logger = mock(Logger.class);
        final FoliaExecution execution = mock(FoliaExecution.class);
        final DrawerRuntimeContext context = mock(DrawerRuntimeContext.class);
        final DrawerStateRepository repository = mock(DrawerStateRepository.class);
        final DrawerBlockAccess blocks = mock(DrawerBlockAccess.class);
        final DrawerSellWandBridge withdrawals = mock(DrawerSellWandBridge.class);
        final Location location = new Location(world, 1, 2, 3);
        final ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(mock(Material.class));
        when(item.getAmount()).thenReturn(1);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.clone()).thenReturn(item);
        final DrawerState state = DrawerState.restored(2, "tier", item,
                20_000, 0, 20_000, null, DrawerDisplayLink.none());

        when(plugin.isEnabled()).thenReturn(true);
        when(context.plugin()).thenReturn(plugin);
        when(context.logger()).thenReturn(logger);
        when(context.execution()).thenReturn(execution);
        when(context.repository()).thenReturn(repository);
        when(context.blocks()).thenReturn(blocks);
        when(world.getUID()).thenReturn(worldId);
        when(world.getBlockAt(1, 2, 3)).thenReturn(block);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(barrel.getLocation()).thenReturn(location);
        when(blocks.barrel(block)).thenReturn(Optional.of(barrel));
        when(blocks.inspect(eq(barrel), eq(state))).thenReturn(new DrawerBlockAccess.PhysicalStock(0, true));
        when(repository.isDrawer(barrel)).thenReturn(true);
        when(repository.read(barrel)).thenReturn(new DrawerStateReadResult.Valid(state));
        doAnswer(call -> { ((Consumer<ScheduledTask>) call.getArgument(0)).accept(null); return null; })
                .when(execution).runGlobal(any());
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(execution).executeAt(any(), any());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            final DrawerApiService api = new DrawerApiService(context, mock(DrawerHopperBridge.class), withdrawals);
            final var result = api.withdraw(new DrawerLocation(worldId, 1, 2, 3), Long.MAX_VALUE,
                    DrawerOperationContext.of(mock(Plugin.class), "test")).join();

            assertEquals(DrawerTransferStatus.INVALID_REQUEST, result.status());
            assertEquals(0L, result.acceptedAmount());
            verifyNoInteractions(withdrawals);
        }
    }
}
