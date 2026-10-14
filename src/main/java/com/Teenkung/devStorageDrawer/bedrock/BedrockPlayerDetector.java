package com.teenkung.devstoragedrawer.bedrock;

import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;

/** Identifies players authenticated through the backend Floodgate API. */
public interface BedrockPlayerDetector {
    BedrockPlayerDetector NONE = new BedrockPlayerDetector() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean isBedrock(final Player player) {
            return false;
        }
    };

    static BedrockPlayerDetector create(final PluginManager pluginManager) {
        if (!pluginManager.isPluginEnabled("floodgate")) {
            return NONE;
        }
        return new FloodgatePlayerDetector();
    }

    boolean isAvailable();

    boolean isBedrock(Player player);
}
