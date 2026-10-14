package com.teenkung.devstoragedrawer.persistence;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.function.Function;
import org.bukkit.inventory.ItemStack;

/** Bounded immutable-template cache. Counts, capacities and journals are never cached. */
final class DrawerTemplateDecodeCache {
    private final int maxEntries;
    private final int maxBytes;
    private final int maxItemBytes;
    private final Function<byte[], ItemStack> decoder;
    private final LinkedHashMap<BytesKey, ItemStack> entries = new LinkedHashMap<>(16, 0.75F, true);
    private long retainedBytes;

    DrawerTemplateDecodeCache() { this(128, 2 * 1024 * 1024, 65_536, ItemStack::deserializeBytes); }

    DrawerTemplateDecodeCache(final int maxEntries, final int maxBytes, final int maxItemBytes,
            final Function<byte[], ItemStack> decoder) {
        if (maxEntries <= 0 || maxBytes <= 0 || maxItemBytes <= 0) throw new IllegalArgumentException("Invalid cache bounds");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.maxItemBytes = Math.min(maxItemBytes, maxBytes);
        this.decoder = java.util.Objects.requireNonNull(decoder, "decoder");
    }

    ItemStack decode(final byte[] bytes) {
        if (bytes.length > maxItemBytes) return decoder.apply(bytes);
        final BytesKey key = new BytesKey(bytes);
        final ItemStack cached;
        synchronized (entries) { cached = entries.get(key); }
        if (cached != null) return cached.clone();

        // Parsing and cloning do not hold the shared lock across Folia regions.
        final ItemStack decoded = decoder.apply(key.bytes.clone());
        final ItemStack prototype = decoded.clone();
        synchronized (entries) {
            if (!entries.containsKey(key)) {
                entries.put(key, prototype);
                retainedBytes += key.bytes.length;
                final var oldest = entries.entrySet().iterator();
                while (entries.size() > maxEntries || retainedBytes > maxBytes) {
                    final var removed = oldest.next();
                    retainedBytes -= removed.getKey().bytes.length;
                    oldest.remove();
                }
            }
        }
        return decoded;
    }

    private static final class BytesKey {
        private final byte[] bytes;
        private final int hash;

        private BytesKey(final byte[] bytes) { this.bytes = bytes.clone(); this.hash = Arrays.hashCode(this.bytes); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(final Object other) {
            return other instanceof BytesKey key && Arrays.equals(bytes, key.bytes);
        }
    }
}
