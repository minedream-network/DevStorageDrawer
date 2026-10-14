package com.teenkung.devstoragedrawer.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DrawerUpgradePaymentTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Test
    void commitsOneConfirmedWithdrawal() {
        final FakeEconomy economy = new FakeEconomy();
        assertEquals(DrawerUpgradePayment.Result.SUCCESS,
                DrawerUpgradePayment.execute(economy, PLAYER, 125D, () -> true));
        assertEquals(125D, economy.withdrawn);
        assertEquals(0D, economy.deposited);
    }

    @Test
    void refundsWhenPersistenceRejectsCommit() {
        final FakeEconomy economy = new FakeEconomy();
        assertEquals(DrawerUpgradePayment.Result.FAILED,
                DrawerUpgradePayment.execute(economy, PLAYER, 125D, () -> false));
        assertEquals(125D, economy.withdrawn);
        assertEquals(125D, economy.deposited);
    }

    @Test
    void neverCallsCommitWhenFundsAreInsufficient() {
        final FakeEconomy economy = new FakeEconomy();
        economy.withdrawResult = false;
        final boolean[] committed = {false};
        assertEquals(DrawerUpgradePayment.Result.INSUFFICIENT,
                DrawerUpgradePayment.execute(economy, PLAYER, 125D, () -> committed[0] = true));
        assertEquals(false, committed[0]);
    }

    @Test
    void reportsUnknownProviderExceptionWithoutMintingRefund() {
        final FakeEconomy economy = new FakeEconomy();
        economy.throwWithdraw = true;
        assertEquals(DrawerUpgradePayment.Result.UNKNOWN,
                DrawerUpgradePayment.execute(economy, PLAYER, 125D, () -> true));
        assertEquals(0D, economy.deposited);
    }

    @Test
    void freeUpgradeDoesNotRequireEconomy() {
        final FakeEconomy economy = new FakeEconomy();
        economy.ready = false;
        assertEquals(DrawerUpgradePayment.Result.SUCCESS,
                DrawerUpgradePayment.execute(economy, PLAYER, 0D, () -> true));
    }

    private static final class FakeEconomy implements DrawerEconomy {
        private boolean ready = true;
        private boolean withdrawResult = true;
        private boolean throwWithdraw;
        private double withdrawn;
        private double deposited;

        @Override public boolean ready() { return ready; }
        @Override public boolean withdraw(final UUID playerId, final double amount) {
            if (throwWithdraw) throw new IllegalStateException("unknown");
            if (withdrawResult) withdrawn += amount;
            return withdrawResult;
        }
        @Override public boolean deposit(final UUID playerId, final double amount) {
            deposited += amount;
            return true;
        }
    }
}
