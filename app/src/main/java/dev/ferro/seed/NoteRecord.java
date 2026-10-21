package dev.ferro.seed;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;

/** A generated result, including the exact notes needed to replay it without inference. */
public final class NoteRecord {
    public static final int LEGACY_TONE = 50;
    public static final int LEGACY_DECAY = 80;
    public static final int LEGACY_DRIVE = 20;
    public final String id;
    public final long createdAt;
    public final int[] pitches;
    public final int[] velocities;
    public final int[] tones;
    public final int[] decays;
    public final int[] drives;
    public final boolean liked;
    public final long seed;
    public final String modelVersion;

    public NoteRecord(String id, long createdAt, int[] pitches, int[] velocities,
                      boolean liked, long seed, String modelVersion) {
        this(id, createdAt, pitches, velocities, legacyValues(pitches, LEGACY_TONE),
                legacyValues(pitches, LEGACY_DECAY), legacyValues(pitches, LEGACY_DRIVE),
                liked, seed, modelVersion);
    }

    public NoteRecord(String id, long createdAt, int[] pitches, int[] velocities,
                      int[] tones, int[] decays, int[] drives,
                      boolean liked, long seed, String modelVersion) {
        if (id == null || id.isEmpty() || id.length() > 128) {
            throw new IllegalArgumentException("A record needs an id of 1–128 characters.");
        }
        if (createdAt < 0) throw new IllegalArgumentException("Invalid creation time.");
        if (modelVersion == null || modelVersion.isEmpty() || modelVersion.length() > 128) {
            throw new IllegalArgumentException("A record needs a model version.");
        }
        if (pitches == null || velocities == null || pitches.length == 0
                || pitches.length > 128 || pitches.length != velocities.length) {
            throw new IllegalArgumentException("A result needs 1–128 pitches and matching velocities.");
        }
        this.pitches = pitches.clone();
        this.velocities = velocities.clone();
        this.tones = controls(tones, pitches.length, "Tones");
        this.decays = controls(decays, pitches.length, "Decays");
        this.drives = controls(drives, pitches.length, "Drives");
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < this.pitches.length; i++) {
            if (this.pitches[i] < 0 || this.pitches[i] > 127 || !used.add(this.pitches[i])) {
                throw new IllegalArgumentException("Pitches must be unique MIDI values from 0 to 127.");
            }
            if (this.velocities[i] < 1 || this.velocities[i] > 127) {
                throw new IllegalArgumentException("Velocities must be from 1 to 127.");
            }
        }
        this.id = id;
        this.createdAt = createdAt;
        this.liked = liked;
        this.seed = seed;
        this.modelVersion = modelVersion;
    }

    public NoteRecord copy() {
        return new NoteRecord(id, createdAt, pitches, velocities, tones, decays, drives,
                liked, seed, modelVersion);
    }

    public NoteRecord withLiked(boolean value) {
        return new NoteRecord(id, createdAt, pitches, velocities, tones, decays, drives,
                value, seed, modelVersion);
    }

    public JSONObject toJson() throws JSONException {
        // Revalidate because arrays are exposed for convenient playback, but never shared by the store.
        NoteRecord checked = copy();
        JSONArray notes = new JSONArray();
        JSONArray levels = new JSONArray();
        JSONArray toneValues = new JSONArray();
        JSONArray decayValues = new JSONArray();
        JSONArray driveValues = new JSONArray();
        for (int i = 0; i < checked.pitches.length; i++) {
            notes.put(checked.pitches[i]);
            levels.put(checked.velocities[i]);
            toneValues.put(checked.tones[i]);
            decayValues.put(checked.decays[i]);
            driveValues.put(checked.drives[i]);
        }
        return new JSONObject().put("id", id).put("createdAt", createdAt)
                .put("pitches", notes).put("velocities", levels).put("liked", liked)
                .put("tones", toneValues).put("decays", decayValues).put("drives", driveValues)
                .put("seed", seed).put("modelVersion", modelVersion);
    }

    public static NoteRecord fromJson(JSONObject value) throws JSONException {
        Object idValue = value.get("id");
        Object modelValue = value.get("modelVersion");
        Object likeValue = value.get("liked");
        if (!(idValue instanceof String) || !(modelValue instanceof String)
                || !(likeValue instanceof Boolean)) {
            throw new JSONException("Invalid record text or liked value.");
        }
        JSONArray notes = value.getJSONArray("pitches");
        JSONArray levels = value.getJSONArray("velocities");
        if (notes.length() == 0 || notes.length() > 128 || notes.length() != levels.length()) {
            throw new JSONException("Invalid record note arrays.");
        }
        int[] pitches = new int[notes.length()];
        int[] velocities = new int[levels.length()];
        for (int i = 0; i < pitches.length; i++) {
            long pitch = integer(notes.get(i), "pitch");
            long velocity = integer(levels.get(i), "velocity");
            if (pitch < 0 || pitch > 127 || velocity < 1 || velocity > 127) {
                throw new JSONException("Record note values are outside MIDI limits.");
            }
            pitches[i] = (int) pitch;
            velocities[i] = (int) velocity;
        }
        try {
            boolean tone = value.has("tones"), decay = value.has("decays"), drive = value.has("drives");
            if ((tone || decay || drive) && !(tone && decay && drive)) {
                throw new JSONException("All three per-note sound arrays are required together.");
            }
            int[] tones = tone ? readControls(value, "tones", pitches.length)
                    : legacyValues(pitches, LEGACY_TONE);
            int[] decays = decay ? readControls(value, "decays", pitches.length)
                    : legacyValues(pitches, LEGACY_DECAY);
            int[] drives = drive ? readControls(value, "drives", pitches.length)
                    : legacyValues(pitches, LEGACY_DRIVE);
            return new NoteRecord((String) idValue, integer(value.get("createdAt"), "createdAt"),
                    pitches, velocities, tones, decays, drives, (Boolean) likeValue,
                    integer(value.get("seed"), "seed"), (String) modelValue);
        } catch (IllegalArgumentException error) {
            throw new JSONException(error.getMessage());
        }
    }

    private static int[] legacyValues(int[] pitches, int value) {
        if (pitches == null) return null;
        int[] result = new int[pitches.length];
        Arrays.fill(result, value);
        return result;
    }

    private static int[] controls(int[] values, int count, String field) {
        if (values == null || values.length != count) {
            throw new IllegalArgumentException(field + " must match the note count.");
        }
        int[] result = values.clone();
        for (int value : result) {
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException(field + " must be integer percentages from 0 to 100.");
            }
        }
        return result;
    }

    private static int[] readControls(JSONObject record, String field, int count) throws JSONException {
        JSONArray values = record.getJSONArray(field);
        if (values.length() != count) throw new JSONException(field + " must match the note count.");
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            long value = integer(values.get(index), field);
            if (value < 0 || value > 100) throw new JSONException(field + " must be from 0 to 100.");
            result[index] = (int) value;
        }
        return result;
    }

    private static long integer(Object value, String field) throws JSONException {
        // JSON written by this app stores integers. Reject coercions and lossy floating-point values.
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long)) {
            throw new JSONException(field + " must be an integer.");
        }
        return ((Number) value).longValue();
    }
}
