package com.teenkung.devstoragedrawer.block;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** One player, one drawer/item identity, one short-lived confirmed break. */
final class DrawerBreakTicket {
    static final long DIALOG_LIFETIME_MILLIS = 60_000L;
    static final long CONFIRM_LIFETIME_MILLIS = 15_000L;

    private final UUID playerId;
    private final BlockKey block;
    private final String fingerprint;
    private final long openedAt;
    private final AtomicLong approvedUntil = new AtomicLong();
    private volatile boolean dialogShown;

    DrawerBreakTicket(final UUID playerId, final BlockKey block, final String fingerprint, final long openedAt) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.block = Objects.requireNonNull(block, "block");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.openedAt = openedAt;
    }

    boolean approve(final long now) {
        if (now < openedAt || now - openedAt > DIALOG_LIFETIME_MILLIS) {
            return false;
        }
        return approvedUntil.compareAndSet(0L, now + CONFIRM_LIFETIME_MILLIS);
    }

    BlockKey block() { return block; }
    boolean hasApproval() { return approvedUntil.get() > 0L; }
    boolean wasConsumed() { return approvedUntil.get() == -1L; }
    void markDialogShown() { dialogShown = true; }

    boolean isDialogQueued(final UUID player, final BlockKey target, final long now) {
        return !dialogShown && playerId.equals(player) && block.equals(target) && approvedUntil.get() == 0L
                && now >= openedAt && now - openedAt <= DIALOG_LIFETIME_MILLIS;
    }

    boolean consume(final UUID player, final BlockKey target, final String currentFingerprint, final long now) {
        if (!playerId.equals(player) || !block.equals(target) || !fingerprint.equals(currentFingerprint)) {
            return false;
        }
        final long until = approvedUntil.get();
        return until > 0L && now <= until && approvedUntil.compareAndSet(until, -1L);
    }

    record BlockKey(UUID worldId, int x, int y, int z) {
        BlockKey {
            Objects.requireNonNull(worldId, "worldId");
        }
    }
}
