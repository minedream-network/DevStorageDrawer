package com.teenkung.devstoragedrawer.upgrade;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One fsync-backed file per paid upgrade. An interrupted economy call remains visible and blocks
 * another purchase at the same block until an administrator reconciles it.
 */
final class UpgradeIntentStore {
    private static final int MAGIC = 0x44535550;
    private static final int VERSION = 1;
    private static final int MAX_RECORDS = 100_000;

    private final Path directory;
    private final Map<UUID, Intent> intents = new LinkedHashMap<>();

    UpgradeIntentStore(final Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    synchronized void load() throws IOException {
        intents.clear();
        if (!Files.exists(directory)) return;
        if (!Files.isDirectory(directory)) throw new IOException("Upgrade transaction path is not a directory");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (final Path file : files) {
                if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".tmp")) {
                    // A crash during a replacement may leave a second candidate for the latest
                    // state. Its outcome cannot be inferred from the older .dat file.
                    throw new IOException("Upgrade transaction ledger contains an unfinished write: "
                            + file.getFileName());
                }
                if (!file.getFileName().toString().endsWith(".dat")) continue;
                if (intents.size() >= MAX_RECORDS || !Files.isRegularFile(file)) {
                    throw new IOException("Upgrade transaction ledger is invalid or full");
                }
                final Intent intent = read(file);
                if (!file.getFileName().toString().equals(intent.id() + ".dat")) {
                    throw new IOException("Upgrade transaction ledger contains an invalid id");
                }
                if (!intent.status().pending()) {
                    // Terminal status reached disk before a crash interrupted normal compaction.
                    // It cannot authorize another economy action and no longer needs a ledger file.
                    Files.delete(file);
                    forceDirectoryBestEffort();
                    continue;
                }
                if (intents.put(intent.id(), intent) != null) {
                    throw new IOException("Upgrade transaction ledger contains an invalid or duplicate id");
                }
            }
        } catch (final IllegalArgumentException exception) {
            throw new IOException("Upgrade transaction ledger contains invalid data", exception);
        }
    }

    synchronized Optional<Intent> pendingAt(final BlockKey block) {
        return intents.values().stream().filter(intent -> intent.block().equals(block)
                && intent.status().pending()).findFirst();
    }

    synchronized boolean hasUnresolved(final BlockKey block) {
        return pendingAt(block).isPresent();
    }

    synchronized List<Intent> pending() {
        final List<Intent> pending = new ArrayList<>();
        for (final Intent intent : intents.values()) {
            if (intent.status().pending()) pending.add(intent);
        }
        return List.copyOf(pending);
    }

    synchronized Optional<Intent> find(final UUID id) {
        return Optional.ofNullable(intents.get(id));
    }

    synchronized void prepare(final Intent intent) throws IOException {
        Objects.requireNonNull(intent, "intent");
        if (intent.status() != Status.ATTEMPTED || intents.containsKey(intent.id())
                || pendingAt(intent.block()).isPresent() || pending().size() >= MAX_RECORDS) {
            throw new IOException("An upgrade transaction is already pending or the ledger is full");
        }
        try {
            persist(intent);
        } catch (final IOException exception) {
            // persist() may have completed its atomic rename before a final force failed. Keep the
            // location blocked in memory whenever the final record exists; no economy call has
            // happened yet, so failing the request remains safe.
            if (Files.isRegularFile(recordPath(intent.id()))) intents.put(intent.id(), intent);
            throw exception;
        }
        intents.put(intent.id(), intent);
    }

    synchronized Intent transition(final UUID id, final Status next) throws IOException {
        final Intent existing = intents.get(id);
        if (existing == null || !existing.status().allows(next)) {
            throw new IOException("Invalid upgrade transaction status transition for " + id);
        }
        final Intent updated = existing.withStatus(next);
        persist(updated);
        intents.put(id, updated);
        if (!updated.status().pending()) {
            compactTerminal(updated);
        }
        return updated;
    }

    private void compactTerminal(final Intent intent) {
        try {
            Files.deleteIfExists(recordPath(intent.id()));
            forceDirectoryBestEffort();
            intents.remove(intent.id(), intent);
        } catch (final IOException ignored) {
            // The terminal record remains non-pending in memory and cannot block or count toward
            // the pending limit. load() retries compaction and fails closed if storage still cannot
            // remove it, making the filesystem problem visible on the next restart.
        }
    }

    private void persist(final Intent intent) throws IOException {
        Files.createDirectories(directory);
        final Path file = recordPath(intent.id());
        final Path temporary = directory.resolve(intent.id() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeUuid(output, intent.id());
            writeUuid(output, intent.player());
            writeUuid(output, intent.block().world());
            output.writeInt(intent.block().x());
            output.writeInt(intent.block().y());
            output.writeInt(intent.block().z());
            output.writeUTF(intent.fromTier());
            output.writeUTF(intent.toTier());
            output.writeDouble(intent.cost());
            output.writeLong(intent.createdAt());
            output.writeByte(intent.status().ordinal());
            output.flush();
            channel.force(true);
        }
        // A non-atomic replacement can lose the only pre-economy record during a crash. Refuse
        // paid upgrades on filesystems that cannot provide the required atomic rename.
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        forceDirectoryBestEffort();
    }

    private Path recordPath(final UUID id) {
        return directory.resolve(id + ".dat");
    }

    private void forceDirectoryBestEffort() {
        // POSIX providers can fsync the directory entry itself. Windows does not expose directory
        // channels through FileChannel, while forcing the renamed file above is still supported.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (final IOException | UnsupportedOperationException ignored) {
            // The record file has already been forced. Unsupported directory channels are a
            // platform limitation, not a reason to rewrite the record non-atomically.
        }
    }

    private static Intent read(final Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Upgrade transaction has an invalid header: " + file.getFileName());
            }
            final UUID id = readUuid(input);
            final UUID player = readUuid(input);
            final BlockKey block = new BlockKey(readUuid(input), input.readInt(), input.readInt(), input.readInt());
            final String fromTier = input.readUTF();
            final String toTier = input.readUTF();
            final double cost = input.readDouble();
            final long createdAt = input.readLong();
            final int ordinal = input.readUnsignedByte();
            if (ordinal >= Status.values().length || input.read() != -1) {
                throw new IOException("Upgrade transaction has invalid status or trailing data: " + file.getFileName());
            }
            return new Intent(id, player, block, fromTier, toTier, cost, createdAt, Status.values()[ordinal]);
        } catch (final EOFException exception) {
            throw new IOException("Upgrade transaction is truncated: " + file.getFileName(), exception);
        }
    }

    private static UUID readUuid(final DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(final DataOutputStream output, final UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    record BlockKey(UUID world, int x, int y, int z) {
        BlockKey { Objects.requireNonNull(world, "world"); }
    }

    record Intent(UUID id, UUID player, BlockKey block, String fromTier, String toTier,
                  double cost, long createdAt, Status status) {
        Intent {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(status, "status");
            if (fromTier == null || fromTier.isBlank() || toTier == null || toTier.isBlank()
                    || !Double.isFinite(cost) || cost < 0D || createdAt <= 0L) {
                throw new IllegalArgumentException("Invalid paid upgrade transaction");
            }
        }

        Intent withStatus(final Status replacement) {
            return new Intent(id, player, block, fromTier, toTier, cost, createdAt, replacement);
        }
    }

    enum Status {
        ATTEMPTED, CHARGED, REJECTED, REFUNDED, COMMITTED, VERIFIED;

        boolean pending() { return this == ATTEMPTED || this == CHARGED || this == COMMITTED; }

        boolean allows(final Status next) {
            return switch (this) {
                case ATTEMPTED -> next == CHARGED || next == REJECTED || next == REFUNDED;
                case CHARGED -> next == REFUNDED || next == COMMITTED;
                case COMMITTED -> next == VERIFIED;
                default -> false;
            };
        }
    }
}
