package com.teenkung.devstoragedrawer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class DrawerStateUpgradeTest {
    @Test
    void tierUpgradePreservesEmptyStateWhileChangingCapacity() {
        final DrawerState original = DrawerState.empty("tier_1", 2_048L);

        final DrawerState upgraded = original.withTierAndCapacitySnapshot("tier_2", 4_096L);

        assertEquals("tier_2", upgraded.tierId());
        assertEquals(4_096L, upgraded.capacitySnapshot());
        assertEquals(0L, upgraded.storedTotal());
        assertEquals(0L, upgraded.expectedMirrorCount());
        assertEquals(0L, upgraded.totalForPhysical(0L));
    }
}
