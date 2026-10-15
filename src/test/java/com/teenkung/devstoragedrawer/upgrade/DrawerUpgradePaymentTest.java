package com.teenkung.devstoragedrawer.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DrawerUpgradePaymentTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final UpgradeIntentStore.BlockKey BLOCK = new UpgradeIntentStore.BlockKey(
            UUID.fromString("00000000-0000-0000-0000-000000000456"), 1, 64, 2);
    @TempDir Path temp;

    @Test
    void confirmedPaymentRemainsCommittedUntilExplicitVerification() throws IOException {
        final FakeEconomy economy = new FakeEconomy();
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        final UpgradeIntentStore.Intent intent = intent(125D);
        assertEquals(DrawerUpgradePayment.Result.SUCCESS,
                DrawerUpgradePayment.execute(economy, store, intent, () -> true));
        assertEquals(125D, economy.withdrawn);
        assertEquals(0D, economy.deposited);
        final UpgradeIntentStore reloaded = new UpgradeIntentStore(temp);
        reloaded.load();
        assertEquals(UpgradeIntentStore.Status.COMMITTED, reloaded.find(intent.id()).orElseThrow().status());
        assertEquals(intent.id(), reloaded.pendingAt(BLOCK).orElseThrow().id());
        assertTrue(reloaded.hasUnresolved(BLOCK));
    }

    @Test
    void committedPaymentBlocksTheNextUpgradeUntilVerified() throws IOException {
        final FakeEconomy economy = new FakeEconomy();
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        final UpgradeIntentStore.Intent first = intent(125D);
        final UpgradeIntentStore.Intent second = intent(250D);

        assertEquals(DrawerUpgradePayment.Result.SUCCESS,
                DrawerUpgradePayment.execute(economy, store, first, () -> true));
        assertEquals(DrawerUpgradePayment.Result.LEDGER_UNAVAILABLE,
                DrawerUpgradePayment.execute(economy, store, second, () -> true));
        assertEquals(125D, economy.withdrawn);
        assertEquals(UpgradeIntentStore.Status.COMMITTED, store.find(first.id()).orElseThrow().status());

        store.transition(first.id(), UpgradeIntentStore.Status.VERIFIED);
        assertEquals(DrawerUpgradePayment.Result.SUCCESS,
                DrawerUpgradePayment.execute(economy, store, second, () -> true));

        assertEquals(375D, economy.withdrawn);
        assertTrue(store.find(first.id()).isEmpty());
        assertEquals(UpgradeIntentStore.Status.COMMITTED, store.find(second.id()).orElseThrow().status());
    }

    @Test
    void uncertainBarrelWriteKeepsChargedIntentAndDoesNotRefund() throws IOException {
        final FakeEconomy economy = new FakeEconomy();
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        final UpgradeIntentStore.Intent intent = intent(125D);
        assertEquals(DrawerUpgradePayment.Result.UNKNOWN,
                DrawerUpgradePayment.execute(economy, store, intent, () -> false));
        assertEquals(0D, economy.deposited);
        final UpgradeIntentStore reloaded = new UpgradeIntentStore(temp);
        reloaded.load();
        assertEquals(UpgradeIntentStore.Status.CHARGED, reloaded.find(intent.id()).orElseThrow().status());
        assertTrue(reloaded.pendingAt(BLOCK).isPresent());
    }

    @Test
    void providerThrowAfterDebitLeavesAttemptedIntent() throws IOException {
        final FakeEconomy economy = new FakeEconomy();
        economy.throwAfterWithdraw = true;
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        final UpgradeIntentStore.Intent intent = intent(125D);
        assertEquals(DrawerUpgradePayment.Result.UNKNOWN,
                DrawerUpgradePayment.execute(economy, store, intent, () -> true));
        assertEquals(125D, economy.withdrawn);
        assertEquals(0D, economy.deposited);
        final UpgradeIntentStore reloaded = new UpgradeIntentStore(temp);
        reloaded.load();
        assertEquals(UpgradeIntentStore.Status.ATTEMPTED, reloaded.find(intent.id()).orElseThrow().status());
        assertTrue(reloaded.pendingAt(BLOCK).isPresent());
    }

    @Test
    void commitThrowAfterBarrelWriteDoesNotRefundOrRepeat() throws IOException {
        final FakeEconomy economy = new FakeEconomy();
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        final UpgradeIntentStore.Intent intent = intent(125D);
        final boolean[] wroteBarrel = {false};
        assertEquals(DrawerUpgradePayment.Result.UNKNOWN,
                DrawerUpgradePayment.execute(economy, store, intent, () -> {
                    wroteBarrel[0] = true;
                    throw new IllegalStateException("refresh failed after write");
                }));
        assertTrue(wroteBarrel[0]);
        assertEquals(125D, economy.withdrawn);
        assertEquals(0D, economy.deposited);
        final UpgradeIntentStore reloaded = new UpgradeIntentStore(temp);
        reloaded.load();
        assertEquals(UpgradeIntentStore.Status.CHARGED, reloaded.find(intent.id()).orElseThrow().status());
    }

    @Test
    void rejectedWithdrawalNeverCommits() {
        final FakeEconomy economy = new FakeEconomy();
        economy.withdrawResult = false;
        final boolean[] committed = {false};
        final UpgradeIntentStore.Intent intent = intent(125D);
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        assertEquals(DrawerUpgradePayment.Result.INSUFFICIENT,
                DrawerUpgradePayment.execute(economy, store, intent, () -> committed[0] = true));
        assertFalse(committed[0]);
        assertTrue(store.find(intent.id()).isEmpty());
        assertFalse(Files.exists(temp.resolve(intent.id() + ".dat")));
    }

    @Test
    void terminalRecordsDoNotAccumulateOrConsumeThePendingLimit() throws IOException {
        final FakeEconomy economy = new FakeEconomy();
        economy.withdrawResult = false;
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        for (int attempt = 0; attempt < 32; attempt++) {
            assertEquals(DrawerUpgradePayment.Result.INSUFFICIENT,
                    DrawerUpgradePayment.execute(economy, store, intent(125D), () -> true));
        }
        assertTrue(store.pending().isEmpty());
        try (var files = Files.list(temp)) {
            assertEquals(0L, files.filter(path -> path.getFileName().toString().endsWith(".dat")).count());
        }
    }

    @Test
    void restartCompactsATerminalRecordLeftByACrash() throws IOException {
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        final UpgradeIntentStore.Intent intent = intent(125D);
        store.prepare(intent);
        final Path record = temp.resolve(intent.id() + ".dat");
        final byte[] encoded = Files.readAllBytes(record);
        encoded[encoded.length - 1] = (byte) UpgradeIntentStore.Status.REJECTED.ordinal();
        Files.write(record, encoded);

        final UpgradeIntentStore reloaded = new UpgradeIntentStore(temp);
        reloaded.load();

        assertTrue(reloaded.pending().isEmpty());
        assertFalse(Files.exists(record));
    }

    @Test
    void existingPendingIntentBlocksSecondCharge() throws IOException {
        final FakeEconomy economy = new FakeEconomy();
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        store.prepare(intent(125D));
        assertEquals(DrawerUpgradePayment.Result.LEDGER_UNAVAILABLE,
                DrawerUpgradePayment.execute(economy, store, intent(125D), () -> true));
        assertEquals(0D, economy.withdrawn);
    }

    @Test
    void freeUpgradeDoesNotRequireVaultOrCreateIntent() {
        final FakeEconomy economy = new FakeEconomy();
        economy.ready = false;
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        assertEquals(DrawerUpgradePayment.Result.SUCCESS,
                DrawerUpgradePayment.execute(economy, store, intent(0D), () -> true));
        assertTrue(store.pending().isEmpty());
    }

    @Test
    void corruptedLedgerEntryFailsClosedOnRestart() throws IOException {
        Files.createDirectories(temp);
        Files.write(temp.resolve(UUID.randomUUID() + ".dat"), new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> new UpgradeIntentStore(temp).load());
    }

    @Test
    void interruptedLedgerReplacementFailsClosedOnRestart() throws IOException {
        final UpgradeIntentStore store = new UpgradeIntentStore(temp);
        final UpgradeIntentStore.Intent intent = intent(125D);
        store.prepare(intent);
        Files.write(temp.resolve(intent.id() + ".tmp"), new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> new UpgradeIntentStore(temp).load());
    }

    private static UpgradeIntentStore.Intent intent(final double cost) {
        return new UpgradeIntentStore.Intent(UUID.randomUUID(), PLAYER, BLOCK, "tier_1", "tier_2",
                cost, System.currentTimeMillis(), UpgradeIntentStore.Status.ATTEMPTED);
    }

    private static final class FakeEconomy implements DrawerEconomy {
        private boolean ready = true;
        private boolean withdrawResult = true;
        private boolean throwAfterWithdraw;
        private double withdrawn;
        private double deposited;

        @Override public boolean ready() { return ready; }
        @Override public boolean withdraw(final UUID playerId, final double amount) {
            if (withdrawResult) withdrawn += amount;
            if (throwAfterWithdraw) throw new IllegalStateException("provider timeout after debit");
            return withdrawResult;
        }
        @Override public boolean deposit(final UUID playerId, final double amount) {
            deposited += amount;
            return true;
        }
    }
}
