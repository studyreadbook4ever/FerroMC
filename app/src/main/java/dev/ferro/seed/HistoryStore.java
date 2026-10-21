package dev.ferro.seed;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Synchronous transactional persistence. Call mutations from the app's serial background executor. */
public final class HistoryStore {
    public static final int MAX_RECENT_RECORDS = 100;
    private static HistoryStore sharedInstance;
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Logger LOGGER = Logger.getLogger(HistoryStore.class.getName());
    private static final Comparator<NoteRecord> RECENCY_ORDER = (a, b) -> {
        int time = Long.compare(b.createdAt, a.createdAt);
        return time != 0 ? time : b.id.compareTo(a.id);
    };
    private static final Comparator<NoteRecord> HISTORY_ORDER = (a, b) -> {
        if (a.liked != b.liked) return a.liked ? -1 : 1;
        return RECENCY_ORDER.compare(a, b);
    };

    private final File file;
    private final File backup;
    // Published lists and their records are never mutated. Read methods copy one snapshot
    // without acquiring the writer's monitor, including while disk fsync is in progress.
    private volatile List<NoteRecord> records = Collections.emptyList();
    private final Set<Runnable> changeListeners = new CopyOnWriteArraySet<>();
    private boolean mainFileValid;
    private boolean recoveredFromBackup;

    /** Reuse one store across Activity recreation while an old generation task finishes saving. */
    public static synchronized HistoryStore shared(Context context) throws IOException {
        if (sharedInstance == null) sharedInstance = new HistoryStore(context);
        return sharedInstance;
    }

    public HistoryStore(Context context) throws IOException {
        this(new File(context.getFilesDir(), "generated-history.json"));
    }

    /** File-based constructor also supports recovery and persistence tests without an Android device. */
    public HistoryStore(File file) throws IOException {
        this.file = file.getAbsoluteFile();
        this.backup = new File(this.file.getPath() + ".bak");
        File parent = this.file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create the history directory.");
        }
        if (!this.file.exists() && !backup.exists()) {
            return;
        }
        try {
            records = Collections.unmodifiableList(read(this.file));
            mainFileValid = true;
        } catch (IOException original) {
            try {
                records = Collections.unmodifiableList(read(backup));
                recoveredFromBackup = true;
            } catch (IOException error) {
                original.addSuppressed(error);
                throw new IOException("History could not be read; its files were preserved.", original);
            }
        }
        // A failed trim is a write error, not a reason to replace valid current data with a backup.
        List<NoteRecord> retained = retainRecent(records);
        if (retained.size() != records.size()) commit(retained);
    }

    public List<NoteRecord> list() {
        List<NoteRecord> snapshot = records;
        return copies(snapshot);
    }

    public List<NoteRecord> sortedHistory() {
        List<NoteRecord> snapshot = records;
        List<NoteRecord> result = copies(snapshot);
        result.sort(HISTORY_ORDER);
        return result;
    }

    public static int newestUnlikedIndex(List<NoteRecord> sortedHistory) {
        for (int i = 0; i < sortedHistory.size(); i++) {
            if (!sortedHistory.get(i).liked) return i;
        }
        return -1;
    }

    public NoteRecord get(String id) {
        List<NoteRecord> snapshot = records;
        for (NoteRecord record : snapshot) {
            if (record.id.equals(id)) return record.copy();
        }
        return null;
    }

    public NoteRecord latest() {
        List<NoteRecord> snapshot = records;
        NoteRecord newest = null;
        for (NoteRecord record : snapshot) {
            if (newest == null || RECENCY_ORDER.compare(record, newest) < 0) newest = record;
        }
        return newest == null ? null : newest.copy();
    }

    public synchronized boolean recoveredFromBackup() {
        return recoveredFromBackup;
    }

    /**
     * Observe successful commits. Called on the committing thread after publication, while
     * its writer lock is held: listeners must only enqueue work, never block or mutate this
     * store. Registering does not replay a change; register before reading the initial snapshot.
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Already-enqueued callbacks may still run; an Activity must also guard its destroyed state. */
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    public synchronized void add(NoteRecord record) throws IOException {
        NoteRecord safe = record.copy();
        for (NoteRecord existing : records) {
            if (existing.id.equals(safe.id)) throw new IllegalArgumentException("Duplicate record id.");
        }
        List<NoteRecord> changed = copies(records);
        changed.add(safe);
        commit(changed);
    }

    /** Returns null if absent, or if unliking an old record removes it from the recent 100. */
    public synchronized NoteRecord toggleLike(String id) throws IOException {
        List<NoteRecord> changed = copies(records);
        for (int i = 0; i < changed.size(); i++) {
            NoteRecord current = changed.get(i);
            if (current.id.equals(id)) {
                NoteRecord updated = current.withLiked(!current.liked);
                changed.set(i, updated);
                commit(changed);
                return get(id);
            }
        }
        return null;
    }

    public synchronized boolean delete(String id) throws IOException {
        List<NoteRecord> changed = copies(records);
        boolean removed = changed.removeIf(record -> record.id.equals(id));
        if (removed) commit(changed);
        return removed;
    }

    private static List<NoteRecord> copies(List<NoteRecord> source) {
        List<NoteRecord> result = new ArrayList<>(source.size());
        for (NoteRecord record : source) result.add(record.copy());
        return result;
    }

    private static List<NoteRecord> retainRecent(List<NoteRecord> source) {
        List<NoteRecord> recent = new ArrayList<>();
        for (NoteRecord record : source) if (!record.liked) recent.add(record);
        if (recent.size() <= MAX_RECENT_RECORDS) return source;
        recent.sort(RECENCY_ORDER);
        Set<String> retainedIds = new HashSet<>(MAX_RECENT_RECORDS);
        for (int i = 0; i < MAX_RECENT_RECORDS; i++) retainedIds.add(recent.get(i).id);
        List<NoteRecord> retained = new ArrayList<>(source.size() - recent.size() + MAX_RECENT_RECORDS);
        for (NoteRecord record : source) {
            if (record.liked || retainedIds.contains(record.id)) retained.add(record);
        }
        return retained;
    }

    private static List<NoteRecord> read(File source) throws IOException {
        if (!source.isFile() || source.length() > MAX_BYTES) {
            throw new IOException("Missing or oversized history file: " + source.getName());
        }
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(source.toPath()),
                    StandardCharsets.UTF_8));
            Object version = root.get("version");
            if (!(version instanceof Integer) || (((Integer) version) != 1 && ((Integer) version) != 2)) {
                throw new JSONException("Unsupported history version.");
            }
            JSONArray items = root.getJSONArray("records");
            List<NoteRecord> result = new ArrayList<>(items.length());
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < items.length(); i++) {
                NoteRecord record = NoteRecord.fromJson(items.getJSONObject(i));
                if (!ids.add(record.id)) throw new JSONException("Duplicate history id.");
                result.add(record);
            }
            return result;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Invalid history data: " + source.getName(), error);
        }
    }

    private void commit(List<NoteRecord> changed) throws IOException {
        List<NoteRecord> retained = Collections.unmodifiableList(new ArrayList<>(retainRecent(changed)));
        byte[] contents;
        try {
            JSONArray items = new JSONArray();
            for (NoteRecord record : retained) items.put(record.toJson());
            contents = new JSONObject().put("version", 2).put("records", items)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IOException("Cannot encode history.", error);
        }
        if (contents.length > MAX_BYTES) throw new IOException("History storage limit reached.");
        File temporary = new File(file.getPath() + ".tmp");
        writeSynced(temporary, contents);
        if (mainFileValid) {
            File backupTemporary = new File(backup.getPath() + ".tmp");
            writeSynced(backupTemporary, Files.readAllBytes(file.toPath()));
            replace(backupTemporary, backup);
        }
        replace(temporary, file);
        // Publish in-memory state only after the durable file has been replaced successfully.
        records = retained;
        mainFileValid = true;
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException error) {
                // A notification failure cannot turn an already durable commit into a failed write
                // or prevent the remaining Activities from receiving the change.
                LOGGER.log(Level.WARNING, "History change listener failed", error);
            }
        }
    }

    private static void writeSynced(File target, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static void replace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            // The complete previous version remains in .bak if this filesystem lacks atomic moves.
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
