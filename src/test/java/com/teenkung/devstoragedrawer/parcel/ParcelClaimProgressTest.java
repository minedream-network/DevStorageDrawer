package com.teenkung.devstoragedrawer.parcel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ParcelClaimProgressTest {
    @Test
    void staleWorldParcelCannotReissueItemsSavedToPlayerData() {
        final long firstGrantRemaining = ParcelClaimProgress.afterGrant(
                ParcelClaimProgress.available(100L, null), 40L
        );

        // Playerdata saved the first grant, but the world entity still holds its old count.
        assertEquals(60L, ParcelClaimProgress.available(100L, firstGrantRemaining));
        final long secondGrantRemaining = ParcelClaimProgress.afterGrant(
                ParcelClaimProgress.available(100L, firstGrantRemaining), 60L
        );
        assertEquals(0L, ParcelClaimProgress.available(100L, secondGrantRemaining));
    }

    @Test
    void newerWorldParcelNeverGainsItemsFromAnOlderPlayerCheckpoint() {
        assertEquals(20L, ParcelClaimProgress.available(20L, 60L));
    }
}
