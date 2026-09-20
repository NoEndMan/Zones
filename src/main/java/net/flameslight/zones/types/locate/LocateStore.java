package net.flameslight.zones.types.locate;

import net.flameslight.zones.logger.ModLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One dimension's recorded structure placements, on disk. /locate is the only reader, and it runs a
 * handful of times per session, so nothing here is kept in memory between reads: appends land in a
 * small buffer, the buffer is flushed to disk on the usual interval, and a search streams the files
 * back.
 *
 * Records are a fixed 10 bytes, a short index into the id table, then the packed (x,z) position that
 * written into numbered buckets (<prefix>_0.bin, _1.bin, ...). A bucket is only ever appended to;
 * once it passes BUCKET_SIZE_LIMIT the next append starts a new one, so a flush never rewrites data
 * that is already on disk. The id table lives in its own sidecar file, which keeps the buckets
 * purely append-only.
 *
 * Appends come from worldgen threads and flush/scan from the IO pool, so the pending buffer and the
 * files have separate locks: a flush swaps the buffer out under `lock` and then does its disk work
 * under `fileLock`, leaving worldgen free to keep appending meanwhile.
 */
public final class LocateStore {
    /** short id index + long packed position. */
    private static final int RECORD_BYTES = 10;
    private static final long BUCKET_SIZE_LIMIT = 1L << 20; // 1 MiB, about 100k records
    private static final String BUCKET_SUFFIX = ".bin";
    /** Deliberately NOT .bin, so the bucket glob can't pick it up. */
    private static final String ID_TABLE_SUFFIX = "_ids.dat";
    private static final int INITIAL_PENDING_RECORDS = 64;
    private static final int SCAN_BUFFER_RECORDS = 4096;
    private static final long[] EMPTY = new long[0];

    private final Path dir;
    private final String prefix;

    /** Guards the pending buffer and the id table. */
    private final Object lock = new Object();
    /** Guards the bucket files and their bookkeeping. */
    private final Object fileLock = new Object();

    private final List<String> idTable = new ArrayList<>();
    private final Map<String, Integer> idIndex = new HashMap<>();
    private boolean idTableDirty = false;

    private byte[] pending = new byte[RECORD_BYTES * INITIAL_PENDING_RECORDS];
    private int pendingLength = 0;

    private final List<Path> buckets = new ArrayList<>();
    private long activeBucketSize = 0L;

    public LocateStore(Path dir, String prefix) {
        this.dir = dir;
        this.prefix = prefix;
        loadIdTable();
        discoverBuckets();
    }

    // ---- Writing ----

    /** Called from worldgen threads once a structure has been committed to its chunk. */
    public void append(String structureId, long packedPos) {
        synchronized (lock) {
            Integer existing = idIndex.get(structureId);
            int index;
            if (existing != null) {
                index = existing;
            } else {
                if (idTable.size() > Short.MAX_VALUE) {
                    return; // more distinct structures than the format can address; nothing sane to do
                }
                index = idTable.size();
                idTable.add(structureId);
                idIndex.put(structureId, index);
                idTableDirty = true;
            }

            if (pendingLength + RECORD_BYTES > pending.length) {
                pending = Arrays.copyOf(pending, Math.max(pending.length << 1, pendingLength + RECORD_BYTES));
            }
            pending[pendingLength++] = (byte) (index >>> 8);
            pending[pendingLength++] = (byte) index;
            for (int shift = 56; shift >= 0; shift -= 8) {
                pending[pendingLength++] = (byte) (packedPos >>> shift);
            }
        }
    }

    public boolean hasPending() {
        synchronized (lock) {
            return pendingLength > 0 || idTableDirty;
        }
    }

    /** Safe to call from any thread; does its disk work outside the append lock. */
    public void flush() {
        byte[] data;
        int length;
        List<String> tableSnapshot;

        synchronized (lock) {
            length = pendingLength;
            tableSnapshot = idTableDirty ? List.copyOf(idTable) : null;
            if (length == 0 && tableSnapshot == null) {
                return;
            }
            data = pending;
            pending = new byte[Math.max(RECORD_BYTES * INITIAL_PENDING_RECORDS, length)];
            pendingLength = 0;
            idTableDirty = false;
        }

        synchronized (fileLock) {
            // The id table goes first: a record is meaningless until its id is resolvable.
            if (tableSnapshot != null) {
                writeIdTable(tableSnapshot);
            }
            if (length > 0) {
                writeRecords(data, length);
            }
        }
    }

    // ---- Reading ----

    /**
     * Every recorded position for one structure, oldest bucket first. Returns an empty array for a
     * structure that has never been recorded, so callers can't tell that case apart from "recorded
     * but all buckets unreadable": both mean "we have nothing to offer", which is what the caller
     * needs. Runs on the IO pool.
     */
    public long[] scan(String structureId) {
        Integer index;
        synchronized (lock) {
            index = idIndex.get(structureId);
        }
        if (index == null) {
            return EMPTY;
        }
        short target = index.shortValue();

        List<Path> snapshot;
        synchronized (fileLock) {
            snapshot = List.copyOf(buckets);
        }
        if (snapshot.isEmpty()) {
            return EMPTY;
        }

        long[] out = new long[64];
        int found = 0;
        byte[] buffer = new byte[RECORD_BYTES * SCAN_BUFFER_RECORDS];

        for (Path bucket : snapshot) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(bucket))) {
                int carry = 0;
                int read;
                while ((read = in.read(buffer, carry, buffer.length - carry)) > 0) {
                    int total = carry + read;
                    int usable = total - (total % RECORD_BYTES);
                    for (int i = 0; i < usable; i += RECORD_BYTES) {
                        short id = (short) (((buffer[i] & 0xFF) << 8) | (buffer[i + 1] & 0xFF));
                        if (id != target) {
                            continue;
                        }
                        long packed = 0L;
                        for (int b = 2; b < RECORD_BYTES; b++) {
                            packed = (packed << 8) | (buffer[i + b] & 0xFFL);
                        }
                        if (found == out.length) {
                            out = Arrays.copyOf(out, found << 1);
                        }
                        out[found++] = packed;
                    }
                    // A partial record at the end of a read is carried into the next one.
                    carry = total - usable;
                    if (carry > 0) {
                        System.arraycopy(buffer, usable, buffer, 0, carry);
                    }
                }
            } catch (NoSuchFileException e) {
                // Removed between the snapshot and the read; nothing to recover.
            } catch (IOException e) {
                ModLogger.error("Could not read recorded placements from {}", bucket, e);
            }
        }

        return found == out.length ? out : Arrays.copyOf(out, found);
    }

    // ---- Files ----

    /** Caller holds fileLock. */
    private void writeRecords(byte[] data, int length) {
        if (buckets.isEmpty() || activeBucketSize + length > BUCKET_SIZE_LIMIT) {
            buckets.add(dir.resolve(prefix + "_" + buckets.size() + BUCKET_SUFFIX));
            activeBucketSize = 0L;
        }
        Path active = buckets.get(buckets.size() - 1);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(active,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            out.write(data, 0, length);
        } catch (IOException e) {
            ModLogger.error("Could not append recorded placements to {}", active, e);
            return;
        }
        activeBucketSize += length;
    }

    /** Caller holds fileLock. Small enough (a few hundred bytes) to rewrite whole. */
    private void writeIdTable(List<String> table) {
        Path file = idTableFile();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(file)))) {
            out.writeInt(table.size());
            for (String id : table) {
                out.writeUTF(id);
            }
        } catch (IOException e) {
            ModLogger.error("Could not write the structure id table to {}", file, e);
        }
    }

    private void loadIdTable() {
        Path file = idTableFile();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(file)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF();
                idIndex.put(id, idTable.size());
                idTable.add(id);
            }
        } catch (NoSuchFileException e) {
            // Nothing recorded in this dimension yet.
        } catch (Exception e) {
            // Without the table the existing buckets can't be decoded. Start a fresh table rather
            // than mis-resolving indices; the old records stay on disk but are simply never matched.
            ModLogger.error("Could not read the structure id table from {}; recorded placements made "
                    + "before now will not be found.", file, e);
            idTable.clear();
            idIndex.clear();
        }
    }

    private void discoverBuckets() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "_*" + BUCKET_SUFFIX)) {
            List<Path> found = new ArrayList<>();
            for (Path path : stream) {
                if (Files.isRegularFile(path) && bucketNumber(path) >= 0) {
                    found.add(path);
                }
            }
            found.sort(Comparator.comparingInt(LocateStore::bucketNumber));
            buckets.addAll(found);
        } catch (IOException e) {
            ModLogger.error("Could not list recorded placement files in {}", dir, e);
        }
        if (!buckets.isEmpty()) {
            Path active = buckets.get(buckets.size() - 1);
            try {
                activeBucketSize = Files.size(active);
            } catch (IOException e) {
                activeBucketSize = BUCKET_SIZE_LIMIT; // unknown: roll to a new bucket on the next write
            }
        }
    }

    /** -1 for anything that isn't one of our numbered buckets. */
    private static int bucketNumber(Path path) {
        String name = path.getFileName().toString();
        int start = name.lastIndexOf('_') + 1;
        int end = name.length() - BUCKET_SUFFIX.length();
        if (start <= 0 || start >= end) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Path idTableFile() {
        return dir.resolve(prefix + ID_TABLE_SUFFIX);
    }
}
