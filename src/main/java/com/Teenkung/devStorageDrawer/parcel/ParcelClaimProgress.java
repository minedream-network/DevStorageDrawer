package com.teenkung.devstoragedrawer.parcel;

/**
 * The playerdata checkpoint is authoritative when a saved inventory is newer than a world item
 * entity. A stale parcel may offer fewer items than the checkpoint, but never more.
 */
final class ParcelClaimProgress {
    private ParcelClaimProgress() {
    }

    static long available(final long parcelRemaining, final Long savedRemaining) {
        if (parcelRemaining < 1L) {
            throw new IllegalArgumentException("Parcel count must be positive");
        }
        return savedRemaining == null ? parcelRemaining : Math.min(parcelRemaining, Math.max(0L, savedRemaining));
    }

    static long afterGrant(final long available, final long granted) {
        if (available < 0L || granted < 0L || granted > available) {
            throw new IllegalArgumentException("Parcel grant exceeds its available count");
        }
        return available - granted;
    }
}
