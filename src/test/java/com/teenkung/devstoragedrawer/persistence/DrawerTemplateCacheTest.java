package com.teenkung.devstoragedrawer.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.teenkung.devstoragedrawer.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class DrawerTemplateCacheTest {
    private static final class Items {
        final Map<ItemStack, String> tags = Collections.synchronizedMap(new IdentityHashMap<>());
        final AtomicInteger encodes = new AtomicInteger();
        final AtomicInteger decodes = new AtomicInteger();
        final Material material = mock(Material.class);

        ItemStack item(String tag, int initial) {
            final ItemStack item = mock(ItemStack.class);
            final AtomicInteger amount = new AtomicInteger(initial);
            tags.put(item, tag);
            when(item.getType()).thenReturn(material);
            when(item.getAmount()).thenAnswer(call -> amount.get());
            doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
            when(item.getMaxStackSize()).thenReturn(64);
            when(item.clone()).thenAnswer(call -> item(tags.get(item), amount.get()));
            when(item.isSimilar(any())).thenAnswer(call -> Objects.equals(tags.get(item), tags.get(call.getArgument(0))));
            when(item.serializeAsBytes()).thenAnswer(call -> {
                encodes.incrementAndGet(); return (tags.get(item) + ":" + amount.get()).getBytes(StandardCharsets.UTF_8);
            });
            return item;
        }
        ItemStack decode(byte[] bytes) { decodes.incrementAndGet(); return item(Arrays.toString(bytes), 1); }
        DrawerTemplateDecodeCache cache(int entries, int bytes, int perItem) {
            return new DrawerTemplateDecodeCache(entries, bytes, perItem, this::decode);
        }
    }

    @Test void immutableJournalAndCountCopiesSerializeOneTemplateOnce() {
        final Items items = new Items();
        final DrawerState state = DrawerState.restored(2, "tier", items.item("mmo:enchanted", 32),
                1000, 64, 10000, null, DrawerDisplayLink.none());
        final DrawerStateCodec codec = new DrawerStateCodec();
        final byte[] bytes = codec.encode(state).templateBytes();
        final var journal = new DrawerProxyJournal(UUID.randomUUID(), DrawerJournalKind.GUARDED_PLAYER_TRANSFER,
                DrawerJournalPhase.PREPARED, UUID.randomUUID(), 1000, 900, 64, 64, 1);
        assertArrayEquals(bytes, codec.encode(state.withProxyJournal(journal)).templateBytes());
        assertArrayEquals(bytes, codec.encode(state.withStoredTotalAndExpectedMirrorCount(900, 64)
                .withProxyJournal(journal.withPhase(DrawerJournalPhase.APPLIED))).templateBytes());
        assertArrayEquals(bytes, codec.encode(state.withStoredTotal(900).withoutProxyJournal()).templateBytes());
        assertEquals(1, items.encodes.get());
        assertEquals(1000, state.storedTotal());
    }

    @Test void exposedTemplatesAndByteArraysCannotMutateTheSharedSnapshot() {
        final Items items = new Items();
        final ItemStack source = items.item("original-pdc", 32);
        final DrawerState state = DrawerState.restored(2, "tier", source, 1000, 64, 10000, null, DrawerDisplayLink.none());
        final byte[] expected = state.serializedTemplate();
        expected[0] = 0;
        source.setAmount(9);
        items.tags.put(source, "changed-source");
        final ItemStack exposed = state.requireTemplate();
        exposed.setAmount(64);
        items.tags.put(exposed, "changed-exposed-pdc");
        assertEquals("original-pdc:1", new String(state.withStoredTotal(900).serializedTemplate(), StandardCharsets.UTF_8));
        assertEquals(1, state.requireTemplate().getAmount());
        final DrawerState changed = state.withTemplate(items.item("new-pdc", 16));
        assertEquals("new-pdc:1", new String(changed.serializedTemplate(), StandardCharsets.UTF_8));
        assertEquals(2, items.encodes.get());
    }

    @Test void repeatedDecodesReuseOnlyMetadataAndStillReadEveryNewCountAndJournal() {
        final Items items = new Items();
        final DrawerStateCodec codec = new DrawerStateCodec(items.cache(128, 4096, 256));
        final byte[] bytes = {1, 2, 3};
        for (int i = 64; i < 1064; i++) {
            final var decoded = codec.decode(new DrawerStatePayload(2, "tier", bytes, i, 64, 10000, null, DrawerDisplayLink.none()));
            assertEquals(i, decoded.storedTotal());
            assertFalse(decoded.hasPendingProxyJournal());
        }
        final var journal = new DrawerProxyJournal(UUID.randomUUID(), DrawerJournalKind.GUARDED_PLAYER_TRANSFER,
                DrawerJournalPhase.PREPARED, UUID.randomUUID(), 1000, 900, 64, 64, 1);
        final var pending = codec.decode(new DrawerStatePayload(2, "tier", bytes, 1000, 64, 20000, journal, DrawerDisplayLink.none()));
        assertEquals(journal, pending.proxyJournal().orElseThrow());
        assertEquals(20000, pending.capacitySnapshot());
        assertThrows(DrawerValidationException.class, () -> codec.decode(
                new DrawerStatePayload(2, "tier", bytes, 20001, 64, 20000, null, DrawerDisplayLink.none())));
        assertEquals(1, items.decodes.get());
    }

    @Test void fullByteComparisonSeparatesHashCollisionsAndProtectsCachedPrototypes() {
        final Items items = new Items();
        final var cache = items.cache(4, 100, 20);
        final byte[] a = {0, 31}, b = {1, 0};
        assertEquals(Arrays.hashCode(a), Arrays.hashCode(b));
        final ItemStack first = cache.decode(a);
        first.setAmount(64);
        items.tags.put(first, "modified-returned-item");
        assertEquals("[0, 31]", items.tags.get(cache.decode(a)));
        assertEquals(1, cache.decode(a).getAmount());
        assertEquals("[1, 0]", items.tags.get(cache.decode(b)));
        a[0] = 9;
        assertEquals("[0, 31]", items.tags.get(cache.decode(new byte[] {0, 31})));
        assertEquals(2, items.decodes.get());
    }

    @Test void lruAndByteBudgetEvictEntriesAndOversizedTemplatesBypassCaching() {
        final Items items = new Items();
        final var cache = items.cache(2, 8, 4);
        cache.decode(new byte[] {1}); cache.decode(new byte[] {2}); cache.decode(new byte[] {1});
        cache.decode(new byte[] {3}); cache.decode(new byte[] {2});
        assertEquals(4, items.decodes.get());
        cache.decode(new byte[5]); cache.decode(new byte[5]);
        assertEquals(6, items.decodes.get());
        final var weighted = items.cache(10, 5, 4);
        weighted.decode(new byte[] {1, 1, 1}); weighted.decode(new byte[] {2, 2, 2});
        weighted.decode(new byte[] {1, 1, 1});
        assertEquals(9, items.decodes.get());
    }

    @Test void corruptTemplateFailuresAreNotCached() {
        final AtomicInteger attempts = new AtomicInteger();
        final var cache = new DrawerTemplateDecodeCache(2, 100, 10, bytes -> {
            attempts.incrementAndGet(); throw new IllegalArgumentException("Bad bytes");
        });
        assertThrows(IllegalArgumentException.class, () -> cache.decode(new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> cache.decode(new byte[] {1}));
        assertEquals(2, attempts.get());
    }

    @Test void concurrentImmutableSnapshotEncodingDoesNotRepeatSerialization() throws Exception {
        final Items items = new Items();
        final DrawerState state = DrawerState.restored(2, "tier", items.item("same-pdc", 1),
                1000, 64, 10000, null, DrawerDisplayLink.none());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            final List<java.util.concurrent.Future<byte[]>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) tasks.add(executor.submit(() -> state.withStoredTotal(900).serializedTemplate()));
            for (var task : tasks) assertEquals("same-pdc:1", new String(task.get(), StandardCharsets.UTF_8));
        }
        assertEquals(1, items.encodes.get());
    }

    @Test void largeEncodingsAreNotRetainedByImmutableSnapshots() {
        final Items items = new Items();
        final DrawerState state = DrawerState.restored(2, "tier", items.item("x".repeat(65_536), 1),
                1000, 64, 10000, null, DrawerDisplayLink.none());
        assertTrue(state.serializedTemplate().length > 65_536);
        assertTrue(state.withStoredTotal(900).serializedTemplate().length > 65_536);
        assertEquals(2, items.encodes.get());
    }
}
