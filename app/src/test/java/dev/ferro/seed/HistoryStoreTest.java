package dev.ferro.seed;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class HistoryStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private NoteRecord record(String id, long time, boolean liked) {
        return new NoteRecord(id, time, new int[]{43, 58, 62, 78},
                new int[]{96, 72, 101, 83}, new int[]{0, 35, 100, 61},
                new int[]{100, 0, 80, 29}, new int[]{0, 100, 20, 67},
                liked, Long.MIN_VALUE + time, "scratch-v2");
    }

    private File file() { return new File(temporary.getRoot(), "history.json"); }

    private String recentId(int number) { return String.format(Locale.ROOT, "recent-%03d", number); }

    private List<NoteRecord> recentRecords(int count) {
        List<NoteRecord> records = new ArrayList<>();
        for (int i = 0; i < count; i++) records.add(record(recentId(i), i, false));
        return records;
    }

    private byte[] writeHistory(File target, List<NoteRecord> records, int version) throws Exception {
        JSONArray items = new JSONArray();
        for (NoteRecord record : records) {
            JSONObject item = record.toJson();
            if (version == 1) {
                item.remove("tones"); item.remove("decays"); item.remove("drives");
            }
            items.put(item);
        }
        byte[] bytes = new JSONObject().put("version", version).put("records", items)
                .toString().getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), bytes);
        return bytes;
    }

    @Test public void persistsExactNotesLikesAndDeletionAcrossRestarts() throws Exception {
        HistoryStore first = new HistoryStore(file());
        first.add(record("older", 10, false));
        first.add(record("newer", 20, false));
        assertTrue(first.toggleLike("older").liked);

        HistoryStore restarted = new HistoryStore(file());
        assertEquals(2, restarted.list().size());
        assertTrue(restarted.get("older").liked);
        assertArrayEquals(new int[]{43, 58, 62, 78}, restarted.get("newer").pitches);
        assertArrayEquals(new int[]{96, 72, 101, 83}, restarted.get("newer").velocities);
        assertEquals(Long.MIN_VALUE + 20, restarted.get("newer").seed);
        assertEquals("scratch-v2", restarted.get("newer").modelVersion);
        assertArrayEquals(new int[]{0, 35, 100, 61}, restarted.get("newer").tones);
        assertArrayEquals(new int[]{100, 0, 80, 29}, restarted.get("older").decays);
        assertArrayEquals(new int[]{0, 100, 20, 67}, restarted.get("older").drives);
        assertEquals("newer", restarted.latest().id);
        assertTrue(restarted.delete("older"));
        assertFalse(restarted.delete("missing"));
        assertNull(restarted.toggleLike("missing"));
        assertEquals(1, new HistoryStore(file()).list().size());
        assertNull(new HistoryStore(file()).get("older"));
    }

    @Test public void likesStayReachableAboveNewestUnlikedSection() throws Exception {
        HistoryStore store = new HistoryStore(file());
        store.add(record("unliked-old", 10, false));
        store.add(record("liked-old", 20, true));
        store.add(record("unliked-new", 40, false));
        store.add(record("liked-new", 30, true));
        List<NoteRecord> ordered = store.sortedHistory();
        assertEquals("liked-new", ordered.get(0).id);
        assertEquals("liked-old", ordered.get(1).id);
        assertEquals("unliked-new", ordered.get(2).id);
        assertEquals("unliked-old", ordered.get(3).id);
        assertEquals(2, HistoryStore.newestUnlikedIndex(ordered));
        store.toggleLike("unliked-new");
        store.toggleLike("unliked-old");
        assertEquals(-1, HistoryStore.newestUnlikedIndex(store.sortedHistory()));
        assertEquals(-1, HistoryStore.newestUnlikedIndex(new HistoryStore(
                new File(temporary.getRoot(), "empty.json")).list()));
    }

    @Test public void arraysAndListsCannotMutateStoredResults() throws Exception {
        HistoryStore store = new HistoryStore(file());
        NoteRecord original = record("result", 10, false);
        store.add(original);
        original.pitches[0] = 1;
        original.tones[0] = 99;
        store.get("result").velocities[0] = 2;
        store.get("result").decays[0] = 2;
        List<NoteRecord> snapshot = store.list();
        snapshot.get(0).pitches[0] = 3;
        snapshot.get(0).drives[0] = 3;
        snapshot.clear();
        assertEquals(43, store.get("result").pitches[0]);
        assertEquals(96, store.get("result").velocities[0]);
        assertEquals(0, store.get("result").tones[0]);
        assertEquals(100, store.get("result").decays[0]);
        assertEquals(0, store.get("result").drives[0]);
        assertEquals(1, store.list().size());
    }

    @Test public void failedWriteDoesNotPublishUnpersistedResult() throws Exception {
        HistoryStore store = new HistoryStore(file());
        store.add(record("saved", 10, false));
        assertTrue(new File(file().getPath() + ".tmp").mkdir());
        try {
            store.add(record("failed", 20, false));
            fail("The temporary output is a directory.");
        } catch (IOException expected) {
            assertEquals(1, store.list().size());
            assertNull(store.get("failed"));
            assertEquals(1, new HistoryStore(file()).list().size());
        }
    }

    @Test public void corruptCurrentFileRecoversPreviousCompleteVersion() throws Exception {
        HistoryStore store = new HistoryStore(file());
        store.add(record("saved", 10, false));
        store.add(record("latest", 20, false));
        Files.write(file().toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        HistoryStore recovered = new HistoryStore(file());
        assertTrue(recovered.recoveredFromBackup());
        assertEquals(1, recovered.list().size());
        assertEquals("saved", recovered.latest().id);
        recovered.add(record("after-recovery", 30, false));
        HistoryStore restarted = new HistoryStore(file());
        assertEquals(2, restarted.list().size());
        assertEquals("after-recovery", restarted.latest().id);
    }

    @Test public void invalidDataIsPreservedInsteadOfReplacingHistoryWithEmptyList() throws Exception {
        byte[] invalid = "{\"version\":99,\"records\":[]}".getBytes(StandardCharsets.UTF_8);
        Files.write(file().toPath(), invalid);
        try {
            new HistoryStore(file());
            fail("Unknown persisted versions must be rejected.");
        } catch (IOException expected) {
            assertArrayEquals(invalid, Files.readAllBytes(file().toPath()));
        }
    }

    @Test public void recordRoundTripRejectsCoercionDuplicatePitchesAndFractionalValues() throws Exception {
        NoteRecord value = record("safe", 10, false);
        NoteRecord roundTrip = NoteRecord.fromJson(new JSONObject(value.toJson().toString()));
        assertArrayEquals(value.pitches, roundTrip.pitches);
        assertArrayEquals(value.tones, roundTrip.tones);
        assertArrayEquals(value.decays, roundTrip.decays);
        assertArrayEquals(value.drives, roundTrip.drives);
        assertEquals(value.seed, roundTrip.seed);

        JSONObject coerced = value.toJson().put("liked", "false");
        try { NoteRecord.fromJson(coerced); fail(); } catch (JSONException expected) { }
        JSONObject fractional = value.toJson().put("createdAt", 10.5);
        try { NoteRecord.fromJson(fractional); fail(); } catch (JSONException expected) { }
        JSONObject duplicate = value.toJson();
        duplicate.getJSONArray("pitches").put(1, 43);
        try { NoteRecord.fromJson(duplicate); fail(); } catch (JSONException expected) { }
    }

    @Test public void legacyHistoryLoadsWithoutRewriteAndMigratesOnSuccessfulMutation() throws Exception {
        JSONObject legacy = record("legacy", 10, false).toJson();
        legacy.remove("tones"); legacy.remove("decays"); legacy.remove("drives");
        byte[] original = new JSONObject().put("version", 1)
                .put("records", new JSONArray().put(legacy)).toString().getBytes(StandardCharsets.UTF_8);
        Files.write(file().toPath(), original);

        HistoryStore store = new HistoryStore(file());
        assertArrayEquals(new int[]{50, 50, 50, 50}, store.get("legacy").tones);
        assertArrayEquals(new int[]{80, 80, 80, 80}, store.get("legacy").decays);
        assertArrayEquals(new int[]{20, 20, 20, 20}, store.get("legacy").drives);
        assertArrayEquals(original, Files.readAllBytes(file().toPath()));
        assertTrue(store.toggleLike("legacy").liked);
        JSONObject migrated = new JSONObject(new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8));
        assertEquals(2, migrated.getInt("version"));
        assertEquals(4, migrated.getJSONArray("records").getJSONObject(0).getJSONArray("tones").length());
        assertArrayEquals(original, Files.readAllBytes(new File(file().getPath() + ".bak").toPath()));
        assertArrayEquals(new int[]{80, 80, 80, 80}, new HistoryStore(file()).get("legacy").decays);
        assertTrue(new HistoryStore(file()).get("legacy").liked);
    }

    @Test public void failedLegacyMigrationPreservesOriginalFileAndLikeState() throws Exception {
        JSONObject legacy = record("legacy", 10, false).toJson();
        legacy.remove("tones"); legacy.remove("decays"); legacy.remove("drives");
        byte[] original = new JSONObject().put("version", 1)
                .put("records", new JSONArray().put(legacy)).toString().getBytes(StandardCharsets.UTF_8);
        Files.write(file().toPath(), original);
        HistoryStore store = new HistoryStore(file());
        assertTrue(new File(file().getPath() + ".tmp").mkdir());
        try { store.toggleLike("legacy"); fail(); } catch (IOException expected) { }
        assertFalse(store.get("legacy").liked);
        assertArrayEquals(original, Files.readAllBytes(file().toPath()));
        assertArrayEquals(new int[]{50, 50, 50, 50}, new HistoryStore(file()).get("legacy").tones);
    }

    @Test public void soundArraysRejectMissingPartialInvalidAndLossyValues() throws Exception {
        JSONObject partial = record("safe", 10, false).toJson();
        partial.remove("drives");
        try { NoteRecord.fromJson(partial); fail(); } catch (JSONException expected) { }
        for (String field : new String[]{"tones", "decays", "drives"}) {
            for (Object invalid : new Object[]{-1, 101, 50.5, "50", JSONObject.NULL}) {
                JSONObject corrupt = record("safe", 10, false).toJson();
                corrupt.getJSONArray(field).put(0, invalid);
                try { NoteRecord.fromJson(corrupt); fail(field + " accepted " + invalid); }
                catch (JSONException expected) { }
            }
            JSONObject shortArray = record("safe", 10, false).toJson().put(field, new JSONArray().put(50));
            try { NoteRecord.fromJson(shortArray); fail(); } catch (JSONException expected) { }
        }
        NoteRecord legacy = new NoteRecord("legacy-api", 1, new int[]{60}, new int[]{100}, false, 1, "v1");
        assertArrayEquals(new int[]{50}, legacy.tones);
        assertArrayEquals(new int[]{80}, legacy.decays);
        assertArrayEquals(new int[]{20}, legacy.drives);
        legacy.tones[0] = 101;
        try { legacy.copy(); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void retainsNewestHundredAndEveryLikedRecordAcrossRestart() throws Exception {
        HistoryStore store = new HistoryStore(file());
        for (int i = 0; i < 105; i++) store.add(record("liked-" + i, i, true));
        // A permutation makes insertion order disagree with creation time.
        for (int i = 0; i < 120; i++) {
            int number = i * 37 % 120;
            store.add(record(recentId(number), number, false));
        }
        HistoryStore restarted = new HistoryStore(file());
        assertEquals(HistoryStore.MAX_RECENT_RECORDS + 105, restarted.list().size());
        assertEquals(100, restarted.list().stream().filter(value -> !value.liked).count());
        for (int i = 0; i < 120; i++) {
            if (i < 20) assertNull(restarted.get(recentId(i)));
            else assertNotNull(restarted.get(recentId(i)));
        }
        for (int i = 0; i < 105; i++) {
            NoteRecord liked = restarted.get("liked-" + i);
            assertNotNull(liked);
            assertTrue(liked.liked);
            assertArrayEquals(new int[]{43, 58, 62, 78}, liked.pitches);
            assertArrayEquals(new int[]{96, 72, 101, 83}, liked.velocities);
            assertArrayEquals(new int[]{0, 35, 100, 61}, liked.tones);
            assertArrayEquals(new int[]{100, 0, 80, 29}, liked.decays);
            assertArrayEquals(new int[]{0, 100, 20, 67}, liked.drives);
        }
    }

    @Test public void timestampTiesUseDescendingIdRatherThanInsertionOrder() throws Exception {
        HistoryStore store = new HistoryStore(file());
        for (int i = 0; i < 110; i++) {
            int number = i * 37 % 110;
            store.add(record(recentId(number), 500, false));
        }
        for (int i = 0; i < 110; i++) {
            if (i < 10) assertNull(store.get(recentId(i)));
            else assertNotNull(store.get(recentId(i)));
        }
        store.add(record("z-equal-time", 500, false));
        store.add(record("a-equal-time", 500, false));
        store.add(record("newly-added-but-old", 499, false));
        assertEquals(100, store.list().size());
        assertNull(store.get(recentId(10)));
        assertNull(store.get("a-equal-time"));
        assertNull(store.get("newly-added-but-old"));
        assertEquals("z-equal-time", store.latest().id);
        assertEquals("z-equal-time", new HistoryStore(file()).latest().id);
    }

    @Test public void unlikingAtCapacityReturnsOnlyAPersistedSurvivor() throws Exception {
        List<NoteRecord> initial = recentRecords(100);
        initial.add(record("liked-old", 0, true));
        initial.add(record("liked-new", 200, true));
        writeHistory(file(), initial, 2);
        HistoryStore store = new HistoryStore(file());
        // recent-000 wins the timestamp tie against liked-old by descending id.
        assertNull(store.toggleLike("liked-old"));
        assertNull(store.get("liked-old"));
        NoteRecord retained = store.toggleLike("liked-new");
        assertNotNull(retained);
        assertFalse(retained.liked);
        assertNull(store.get(recentId(0)));
        assertEquals(100, store.list().size());
        HistoryStore restarted = new HistoryStore(file());
        assertEquals(100, restarted.list().size());
        assertNull(restarted.get("liked-old"));
        assertFalse(restarted.get("liked-new").liked);
        assertArrayEquals(retained.tones, restarted.get("liked-new").tones);
    }

    @Test public void oversizedLegacyAndCurrentFilesTrimOnLoadAndKeepOriginalBackup() throws Exception {
        for (int version : new int[]{1, 2}) {
            File source = new File(temporary.getRoot(), "version-" + version + ".json");
            List<NoteRecord> initial = recentRecords(105);
            initial.add(record("liked-old", 0, true));
            byte[] original = writeHistory(source, initial, version);
            HistoryStore store = new HistoryStore(source);
            assertEquals(101, store.list().size());
            assertNull(store.get(recentId(4)));
            assertNotNull(store.get(recentId(5)));
            assertTrue(store.get("liked-old").liked);
            assertArrayEquals(version == 1 ? new int[]{50, 50, 50, 50} : new int[]{0, 35, 100, 61},
                    store.get("liked-old").tones);
            assertArrayEquals(version == 1 ? new int[]{80, 80, 80, 80} : new int[]{100, 0, 80, 29},
                    store.get("liked-old").decays);
            assertArrayEquals(version == 1 ? new int[]{20, 20, 20, 20} : new int[]{0, 100, 20, 67},
                    store.get("liked-old").drives);
            File backup = new File(source.getPath() + ".bak");
            assertArrayEquals(original, Files.readAllBytes(backup.toPath()));
            byte[] trimmed = Files.readAllBytes(source.toPath());
            assertEquals(2, new JSONObject(new String(trimmed, StandardCharsets.UTF_8)).getInt("version"));
            assertEquals(101, new HistoryStore(source).list().size());
            assertArrayEquals(trimmed, Files.readAllBytes(source.toPath()));
            assertArrayEquals(original, Files.readAllBytes(backup.toPath()));
        }
    }

    @Test public void oversizedRecoveredBackupIsTrimmedWithoutOverwritingGoodBackup() throws Exception {
        List<NoteRecord> initial = recentRecords(105);
        initial.add(record("liked-old", 0, true));
        File backup = new File(file().getPath() + ".bak");
        byte[] goodBackup = writeHistory(backup, initial, 2);
        Files.write(file().toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        HistoryStore recovered = new HistoryStore(file());
        assertTrue(recovered.recoveredFromBackup());
        assertEquals(101, recovered.list().size());
        assertTrue(recovered.get("liked-old").liked);
        assertNull(recovered.get(recentId(4)));
        assertArrayEquals(goodBackup, Files.readAllBytes(backup.toPath()));
        assertEquals(101, new HistoryStore(file()).list().size());
    }

    @Test public void failedCappedMutationsDoNotPruneOrUnlikePersistedRecords() throws Exception {
        List<NoteRecord> initial = recentRecords(100);
        initial.add(record("liked-old", 0, true));
        byte[] original = writeHistory(file(), initial, 2);
        HistoryStore store = new HistoryStore(file());
        assertTrue(new File(file().getPath() + ".tmp").mkdir());
        try { store.add(record("newest", 1000, false)); fail(); } catch (IOException expected) { }
        try { store.toggleLike("liked-old"); fail(); } catch (IOException expected) { }
        assertEquals(101, store.list().size());
        assertTrue(store.get("liked-old").liked);
        assertNotNull(store.get(recentId(0)));
        assertNull(store.get("newest"));
        assertArrayEquals(original, Files.readAllBytes(file().toPath()));
        assertTrue(new HistoryStore(file()).get("liked-old").liked);
    }

    @Test public void failedLoadTrimDoesNotFallBackToAnOlderBackup() throws Exception {
        byte[] original = writeHistory(file(), recentRecords(105), 1);
        List<NoteRecord> oldRecords = new ArrayList<>();
        oldRecords.add(record("stale-backup", 1, true));
        File backup = new File(file().getPath() + ".bak");
        byte[] previousBackup = writeHistory(backup, oldRecords, 2);
        assertTrue(new File(file().getPath() + ".tmp").mkdir());
        try { new HistoryStore(file()); fail(); } catch (IOException expected) { }
        assertArrayEquals(original, Files.readAllBytes(file().toPath()));
        assertArrayEquals(previousBackup, Files.readAllBytes(backup.toPath()));
    }
}
