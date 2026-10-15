package com.teenkung.devstoragedrawer.domain;

/** Identifies which recovery protocol owns a pending drawer journal. */
public enum DrawerJournalKind {
    PROXY_REBALANCE,
    PLAYER_WITHDRAWAL,
    EXTERNAL_SALE,
    /** Callback-based sales remain quarantined until payment and final persistence are known. */
    GUARDED_EXTERNAL_SALE,
    /** A virtual chest debit remains guarded until delivery to the player has completed. */
    GUARDED_PLAYER_TRANSFER,
    /** A quick deposit changes playerdata and drawer state in separate persistence domains. */
    GUARDED_PLAYER_DEPOSIT,
    /** Normal item drops are debited in bounded batches while the drawer remains in place. */
    WORLD_DROP;

    public boolean guardsDelivery() {
        return this == GUARDED_EXTERNAL_SALE || this == GUARDED_PLAYER_TRANSFER
                || this == GUARDED_PLAYER_DEPOSIT;
    }
}
