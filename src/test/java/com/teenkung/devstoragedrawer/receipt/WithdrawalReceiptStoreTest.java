package com.teenkung.devstoragedrawer.receipt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WithdrawalReceiptStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsStatusAndPermanentCompletionAtomically() throws IOException {
        final Path file = temporaryDirectory.resolve("receipts.dat");
        final WithdrawalReceipt receipt = receipt();
        final WithdrawalReceiptStore writer = new WithdrawalReceiptStore(file);
        writer.prepare(receipt);
        writer.markDeliverable(receipt.operationId());

        final WithdrawalReceiptStore reader = new WithdrawalReceiptStore(file);
        reader.load();
        final WithdrawalReceipt loaded = reader.find(receipt.operationId()).orElseThrow();
        assertEquals(WithdrawalReceiptStatus.DELIVERABLE, loaded.status());
        assertEquals(receipt.ownerId(), loaded.ownerId());
        assertArrayEquals(receipt.templateBytes(), loaded.templateBytes());

        reader.completeAll(List.of(receipt.operationId()));
        final WithdrawalReceiptStore restarted = new WithdrawalReceiptStore(file);
        restarted.load();
        final WithdrawalReceipt completed = restarted.find(receipt.operationId()).orElseThrow();
        assertEquals(WithdrawalReceiptStatus.COMPLETED, completed.status());
        assertEquals(receipt.ownerId(), completed.ownerId());
        assertEquals(0, completed.templateBytes().length, "Tombstones retain no item payload");
        assertTrue(restarted.forOwner(receipt.ownerId()).isEmpty());
        restarted.markDeliverable(receipt.operationId());
        restarted.completeAll(List.of(receipt.operationId()));
        assertEquals(WithdrawalReceiptStatus.COMPLETED, restarted.find(receipt.operationId()).orElseThrow().status());
        assertThrows(IOException.class, () -> restarted.prepare(receipt));
        assertThrows(IOException.class, () -> restarted.remove(receipt.operationId()));
        assertThrows(IllegalArgumentException.class, () -> completed.withStatus(WithdrawalReceiptStatus.DELIVERABLE));
    }

    @Test
    void removalIsRestrictedToUndeliveredPreparedReceipts() throws IOException {
        final WithdrawalReceiptStore store = new WithdrawalReceiptStore(temporaryDirectory.resolve("receipts.dat"));
        final WithdrawalReceipt receipt = receipt();
        store.prepare(receipt);
        store.remove(receipt.operationId());
        assertTrue(store.snapshot().isEmpty());
        store.prepare(receipt);
        store.markDeliverable(receipt.operationId());
        assertThrows(IOException.class, () -> store.remove(receipt.operationId()));
        assertEquals(WithdrawalReceiptStatus.DELIVERABLE, store.find(receipt.operationId()).orElseThrow().status());
    }

    @Test
    void anInvalidBatchCannotPartiallyCompleteDeliveryEvidence() throws IOException {
        final WithdrawalReceiptStore store = new WithdrawalReceiptStore(temporaryDirectory.resolve("receipts.dat"));
        final WithdrawalReceipt deliverable = receipt();
        final WithdrawalReceipt prepared = receipt();
        store.prepare(deliverable);
        store.markDeliverable(deliverable.operationId());
        store.prepare(prepared);
        assertThrows(IOException.class, () -> store.completeAll(List.of(deliverable.operationId(), prepared.operationId())));
        assertThrows(IOException.class, () -> store.completeAll(List.of(deliverable.operationId(), UUID.randomUUID())));
        assertEquals(WithdrawalReceiptStatus.DELIVERABLE, store.find(deliverable.operationId()).orElseThrow().status());
    }

    @Test
    void failedCompletionPersistenceRetainsTheDeliverableReceipt() throws IOException {
        final Path file = temporaryDirectory.resolve("receipts.dat");
        final WithdrawalReceiptStore store = new WithdrawalReceiptStore(file);
        final WithdrawalReceipt receipt = receipt();
        store.prepare(receipt);
        store.markDeliverable(receipt.operationId());
        Files.createDirectory(file.resolveSibling("receipts.dat.tmp"));
        assertThrows(IOException.class, () -> store.completeAll(List.of(receipt.operationId())));
        assertEquals(WithdrawalReceiptStatus.DELIVERABLE, store.find(receipt.operationId()).orElseThrow().status());
        final WithdrawalReceiptStore restarted = new WithdrawalReceiptStore(file);
        restarted.load();
        assertEquals(WithdrawalReceiptStatus.DELIVERABLE, restarted.find(receipt.operationId()).orElseThrow().status());
    }

    @Test
    void loadsVersionOneReceiptsAndUpgradesThemWithoutLosingOwnership() throws IOException {
        final Path file = temporaryDirectory.resolve("legacy.dat");
        final WithdrawalReceipt receipt = receipt();
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeInt(0x44534452);
            output.writeInt(1);
            output.writeInt(1);
            for (final UUID id : List.of(receipt.operationId(), receipt.ownerId(), receipt.worldId())) {
                output.writeLong(id.getMostSignificantBits());
                output.writeLong(id.getLeastSignificantBits());
            }
            output.writeInt(receipt.blockX()); output.writeInt(receipt.blockY()); output.writeInt(receipt.blockZ());
            output.writeInt(receipt.templateBytes().length); output.write(receipt.templateBytes());
            output.writeLong(receipt.count()); output.writeByte(receipt.status().ordinal());
            output.writeLong(receipt.createdAtEpochMillis());
        }
        final WithdrawalReceiptStore store = new WithdrawalReceiptStore(file);
        store.load();
        assertEquals(receipt, store.find(receipt.operationId()).orElseThrow());
        store.markDeliverable(receipt.operationId());
        store.completeAll(List.of(receipt.operationId()));
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            assertEquals(0x44534452, input.readInt());
            assertEquals(2, input.readInt());
        }
        final WithdrawalReceiptStore restarted = new WithdrawalReceiptStore(file);
        restarted.load();
        assertEquals(WithdrawalReceiptStatus.COMPLETED, restarted.find(receipt.operationId()).orElseThrow().status());
        assertEquals(receipt.ownerId(), restarted.find(receipt.operationId()).orElseThrow().ownerId());
    }

    @Test
    void rejectsTruncatedLedgerWithoutDiscardingIt() throws IOException {
        final Path file = temporaryDirectory.resolve("receipts.dat");
        final WithdrawalReceiptStore store = new WithdrawalReceiptStore(file);
        store.prepare(receipt());
        final byte[] complete = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(complete, complete.length - 3));

        final WithdrawalReceiptStore corrupt = new WithdrawalReceiptStore(file);
        assertThrows(IOException.class, corrupt::load);
        assertEquals(complete.length - 3, Files.size(file));
    }

    private static WithdrawalReceipt receipt() {
        return new WithdrawalReceipt(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                2,
                3,
                new byte[] {4, 5, 6},
                64L,
                WithdrawalReceiptStatus.PREPARED,
                123L
        );
    }
}
