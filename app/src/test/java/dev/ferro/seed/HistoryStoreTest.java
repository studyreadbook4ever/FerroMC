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
import java.util.List;

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
}
