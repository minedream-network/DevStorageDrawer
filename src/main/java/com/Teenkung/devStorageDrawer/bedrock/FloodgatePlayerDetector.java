package com.teenkung.devstoragedrawer.bedrock;

import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

/** Loaded only when backend Floodgate is enabled; names and UUID prefixes are never used as guesses. */
final class FloodgatePlayerDetector implements BedrockPlayerDetector {
    @Override
    public boolean isAvailable() {
        return api() != null;
    }

    @Override
    public boolean isBedrock(final Player player) {
        final FloodgateApi api = api();
        if (player == null || api == null) return false;
        try {
            return api.isFloodgatePlayer(player.getUniqueId());
        } catch (final RuntimeException | LinkageError exception) {
            return false;
        }
    }

    private static FloodgateApi api() {
        try {
            return FloodgateApi.getInstance();
        } catch (final RuntimeException | LinkageError exception) {
            return null;
        }
    }
}
