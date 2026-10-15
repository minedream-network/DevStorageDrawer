package com.teenkung.devstoragedrawer.upgrade;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Coordinates a durable upgrade intent, one Vault withdrawal, and the barrel write. */
final class DrawerUpgradePayment {
    enum Result { SUCCESS, NOT_READY, INSUFFICIENT, FAILED, UNKNOWN, LEDGER_UNAVAILABLE }

    static Result execute(final DrawerEconomy economy, final UpgradeIntentStore store,
            final UpgradeIntentStore.Intent intent, final BooleanSupplier commit) {
        Objects.requireNonNull(economy, "economy");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(commit, "commit");
        if (intent.status() != UpgradeIntentStore.Status.ATTEMPTED) {
            throw new IllegalArgumentException("Upgrade intent must be new");
        }
        if (intent.cost() == 0D) {
            try {
                return commit.getAsBoolean() ? Result.SUCCESS : Result.FAILED;
            } catch (final RuntimeException exception) {
                return Result.UNKNOWN;
            }
        }
        try {
            if (!economy.ready()) return Result.NOT_READY;
        } catch (final RuntimeException exception) {
            return Result.NOT_READY;
        }
        try {
            // This must reach disk before invoking a provider that can move money.
            store.prepare(intent);
        } catch (final IOException exception) {
            return Result.LEDGER_UNAVAILABLE;
        }
        final boolean charged;
        try {
            charged = economy.withdraw(intent.player(), intent.cost());
        } catch (final RuntimeException exception) {
            // A provider may throw after debiting. Keep ATTEMPTED for manual reconciliation.
            return Result.UNKNOWN;
        }
        try {
            store.transition(intent.id(), charged
                    ? UpgradeIntentStore.Status.CHARGED : UpgradeIntentStore.Status.REJECTED);
        } catch (final IOException exception) {
            // The last durable status is deliberately left pending.
            return Result.UNKNOWN;
        }
        if (!charged) return Result.INSUFFICIENT;
        try {
            if (!commit.getAsBoolean()) return Result.UNKNOWN;
        } catch (final RuntimeException exception) {
            // A barrel update can commit before throwing; do not refund or repeat it.
            return Result.UNKNOWN;
        }
        try {
            store.transition(intent.id(), UpgradeIntentStore.Status.COMMITTED);
            // Barrel.update() confirms that the tile state accepted the write, but it does not
            // prove that the containing chunk has reached stable storage. Leave the operation in
            // COMMITTED until an operator has verified the saved barrel and resolves the intent.
            // Advancing to VERIFIED here would reopen the drawer for another paid upgrade during
            // the exact crash window this ledger is meant to cover.
            return Result.SUCCESS;
        } catch (final IOException exception) {
            return Result.UNKNOWN;
        }
    }

    private DrawerUpgradePayment() { }
}
