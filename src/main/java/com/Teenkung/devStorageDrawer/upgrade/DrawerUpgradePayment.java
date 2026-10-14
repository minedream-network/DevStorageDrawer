package com.teenkung.devstoragedrawer.upgrade;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Withdraws once and refunds only a charge that was positively confirmed. */
final class DrawerUpgradePayment {
    enum Result { SUCCESS, NOT_READY, INSUFFICIENT, FAILED, REFUND_FAILED, UNKNOWN }

    static Result execute(final DrawerEconomy economy, final UUID playerId, final double cost,
            final BooleanSupplier commit) {
        if (!Double.isFinite(cost) || cost < 0D) throw new IllegalArgumentException("Invalid drawer upgrade cost");
        boolean charged = false;
        try {
            if (cost > 0D) {
                if (economy == null || !economy.ready()) return Result.NOT_READY;
                if (!economy.withdraw(playerId, cost)) return Result.INSUFFICIENT;
                charged = true;
            }
            if (commit.getAsBoolean()) return Result.SUCCESS;
        } catch (final RuntimeException exception) {
            if (!charged) return Result.UNKNOWN;
        }
        if (charged) {
            try {
                if (!economy.deposit(playerId, cost)) return Result.REFUND_FAILED;
            } catch (final RuntimeException exception) {
                return Result.REFUND_FAILED;
            }
        }
        return Result.FAILED;
    }

    private DrawerUpgradePayment() { }
}
