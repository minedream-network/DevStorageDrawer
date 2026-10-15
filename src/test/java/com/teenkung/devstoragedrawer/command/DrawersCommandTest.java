package com.teenkung.devstoragedrawer.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.config.DrawerConfigurationService;
import com.teenkung.devstoragedrawer.config.DrawerMessages;
import com.teenkung.devstoragedrawer.config.DrawerTierDefinition;
import com.teenkung.devstoragedrawer.domain.DrawerTier;
import com.teenkung.devstoragedrawer.scheduler.FoliaExecution;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class DrawersCommandTest {
    @Test void deliveryWaitsForTheTargetEntityScheduler() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            final var setup = setup(bukkit);
            final var inventory = mock(PlayerInventory.class);
            final var item = mock(ItemStack.class);
            when(setup.target.isOnline()).thenReturn(true);
            when(setup.target.getInventory()).thenReturn(inventory);
            when(setup.tier.createItem()).thenReturn(item);
            when(item.getMaxStackSize()).thenReturn(64);
            when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

            assertTrue(setup.command.onCommand(setup.sender, mock(Command.class), "drawers",
                    new String[] {"give", "Target", "tier_1", "65"}));

            verify(setup.target, never()).getInventory();
            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Consumer<ScheduledTask>> scheduled = ArgumentCaptor.forClass(Consumer.class);
            verify(setup.execution).runOnEntity(eq(setup.target), scheduled.capture(), any());
            scheduled.getValue().accept(null);
            verify(inventory, times(2)).addItem(item);
            verify(setup.execution).runGlobal(any());
        }
    }

    @Test void oversizedGrantIsRejectedBeforeScheduling() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            final var setup = setup(bukkit);

            assertTrue(setup.command.onCommand(setup.sender, mock(Command.class), "drawers",
                    new String[] {"give", "Target", "tier_1", "4097"}));

            verifyNoInteractions(setup.execution);
            verify(setup.sender).sendMessage(any(Component.class));
        }
    }

    @Test void largeGrantYieldsAfterSixteenStacksAndAcknowledgesOnlyAfterTheLastBatch() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            final var setup = setup(bukkit);
            final var inventory = mock(PlayerInventory.class);
            final var item = mock(ItemStack.class);
            when(setup.target.isOnline()).thenReturn(true);
            when(setup.target.getInventory()).thenReturn(inventory);
            when(setup.tier.createItem()).thenReturn(item);
            when(item.getMaxStackSize()).thenReturn(64);
            when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

            setup.command.onCommand(setup.sender, mock(Command.class), "drawers",
                    new String[] {"give", "Target", "tier_1", "1025"});

            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Consumer<ScheduledTask>> first = ArgumentCaptor.forClass(Consumer.class);
            verify(setup.execution).runOnEntity(eq(setup.target), first.capture(), any());
            first.getValue().accept(null);
            verify(inventory, times(16)).addItem(item);
            verify(setup.execution, never()).runGlobal(any());

            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Consumer<ScheduledTask>> second = ArgumentCaptor.forClass(Consumer.class);
            verify(setup.execution).runLaterOnEntity(eq(setup.target), eq(1L), second.capture(), any());
            second.getValue().accept(null);
            verify(inventory, times(17)).addItem(item);
            verify(setup.execution).runGlobal(any());
        }
    }

    private static Setup setup(final MockedStatic<Bukkit> bukkit) {
        final var configuration = mock(DrawerConfigurationService.class);
        final var messages = mock(DrawerMessages.class);
        final var runtime = mock(DrawerAdminFacade.class);
        final var execution = mock(FoliaExecution.class);
        final var sender = mock(CommandSender.class);
        final var target = mock(Player.class);
        final var tier = mock(DrawerTierDefinition.class);
        final var tierIdentity = mock(DrawerTier.class);
        bukkit.when(() -> Bukkit.getPlayerExact("Target")).thenReturn(target);
        when(sender.hasPermission("devstoragedrawer.admin.give")).thenReturn(true);
        when(configuration.tier("tier_1")).thenReturn(Optional.of(tier));
        when(configuration.messages()).thenReturn(messages);
        when(messages.component(any(String.class), any(Map.class))).thenReturn(Component.empty());
        when(tier.tier()).thenReturn(tierIdentity);
        when(tierIdentity.id()).thenReturn("tier_1");
        return new Setup(new DrawersCommand(configuration, runtime, execution), execution, sender, target, tier);
    }

    private record Setup(DrawersCommand command, FoliaExecution execution, CommandSender sender,
            Player target, DrawerTierDefinition tier) { }
}
