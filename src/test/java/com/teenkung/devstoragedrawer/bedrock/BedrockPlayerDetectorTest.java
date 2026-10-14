package com.teenkung.devstoragedrawer.bedrock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.api.Test;

class BedrockPlayerDetectorTest {
    @Test void geyserAloneDoesNotEnableTheFloodgateRenderer() {
        final PluginManager plugins = mock(PluginManager.class);
        when(plugins.isPluginEnabled("Geyser-Spigot")).thenReturn(true);
        assertSame(BedrockPlayerDetector.NONE, BedrockPlayerDetector.create(plugins));
        assertFalse(BedrockPlayerDetector.create(plugins).isAvailable());
    }

    @Test void classifiesOnlyUuidsRecognizedByFloodgateIncludingLinkedPlayers() {
        final PluginManager plugins = mock(PluginManager.class);
        when(plugins.isPluginEnabled("floodgate")).thenReturn(true);
        final FloodgateApi api = mock(FloodgateApi.class);
        final Player linkedBedrock = mock(Player.class);
        final Player java = mock(Player.class);
        when(linkedBedrock.getUniqueId()).thenReturn(UUID.randomUUID());
        when(java.getUniqueId()).thenReturn(UUID.randomUUID());
        when(api.isFloodgatePlayer(linkedBedrock.getUniqueId())).thenReturn(true);
        try (final var bridge = mockStatic(FloodgateApi.class)) {
            bridge.when(FloodgateApi::getInstance).thenReturn(api);
            final var detector = BedrockPlayerDetector.create(plugins);
            assertTrue(detector.isAvailable());
            assertTrue(detector.isBedrock(linkedBedrock));
            assertFalse(detector.isBedrock(java));
            assertFalse(detector.isBedrock(null));
            verify(linkedBedrock, never()).getName();
            verify(java, never()).getName();
        }
    }

    @Test void anEnabledPluginWithoutAnApiCannotEnableHolograms() {
        final PluginManager plugins = mock(PluginManager.class);
        when(plugins.isPluginEnabled("floodgate")).thenReturn(true);
        try (final var bridge = mockStatic(FloodgateApi.class)) {
            final var detector = BedrockPlayerDetector.create(plugins);
            assertFalse(detector.isAvailable());
            assertFalse(detector.isBedrock(mock(Player.class)));
        }
    }

    @Test void anIncompatibleOptionalApiDoesNotBreakJavaDrawers() {
        final PluginManager plugins = mock(PluginManager.class);
        when(plugins.isPluginEnabled("floodgate")).thenReturn(true);
        try (final var bridge = mockStatic(FloodgateApi.class)) {
            bridge.when(FloodgateApi::getInstance).thenThrow(new NoClassDefFoundError("optional-api"));
            final var detector = BedrockPlayerDetector.create(plugins);
            assertFalse(detector.isAvailable());
            assertFalse(detector.isBedrock(mock(Player.class)));
        }
    }

    @Test void aFailedPlayerLookupIsNotTreatedAsFloodgate() {
        final FloodgateApi api = mock(FloodgateApi.class);
        final Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(api.isFloodgatePlayer(any())).thenThrow(new IllegalStateException("unavailable"));
        try (final var bridge = mockStatic(FloodgateApi.class)) {
            bridge.when(FloodgateApi::getInstance).thenReturn(api);
            assertFalse(new FloodgatePlayerDetector().isBedrock(player));
        }
    }
}
