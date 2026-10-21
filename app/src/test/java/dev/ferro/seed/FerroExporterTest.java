package dev.ferro.seed;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class FerroExporterTest {
    private NoteRecord result() {
        return new NoteRecord("editable-example", 1700000000000L,
                new int[]{0, 43, 58, 127}, new int[]{1, 96, 72, 127},
                new int[]{0, 50, 100, 37}, new int[]{100, 0, 80, 29}, new int[]{0, 100, 20, 67},
                true, 120034, "scratch-v2");
    }

    @Test public void exportsEditableFerroV4ChordWithoutExternalAssets() throws Exception {
        JSONObject project = new JSONObject(FerroExporter.export(result(), 120));
        assertEquals("ferro-project", project.getString("format"));
        assertEquals(4, project.getInt("version"));
        assertEquals("FerroMC editable-example", project.getString("title"));
        assertEquals(1, project.getInt("songBars"));
        assertEquals(120, project.getInt("bpm"));
        assertFalse(project.getBoolean("metronome"));
        assertEquals(0.0, project.getDouble("delay"), 0.0);
        assertEquals(0.0, project.getDouble("duck"), 0.0);
        assertFalse(project.has("seed"));
        assertFalse(project.has("modelVersion"));
        JSONArray tracks = project.getJSONArray("tracks");
        assertEquals(6, tracks.length());
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.getJSONObject(i);
            assertEquals(i, track.getInt("instrument"));
            assertEquals("", track.getString("sample"));
            assertFalse(track.getBoolean("mute"));
            assertFalse(track.getBoolean("solo"));
            if (i != 2) {
                assertEquals(0, track.getJSONArray("patterns").length());
                assertEquals(0, track.getJSONArray("clips").length());
            }
        }
        JSONObject saw = tracks.getJSONObject(2);
        assertEquals(.5, saw.getDouble("tone"), 0);
        assertEquals(.8, saw.getDouble("decay"), 0);
        assertEquals(.2, saw.getDouble("drive"), 0);
        JSONObject phrase = saw.getJSONArray("patterns").getJSONObject(0);
        assertEquals("FerroMC", phrase.getString("name"));
        assertEquals(1, phrase.getInt("bars"));
        JSONArray steps = phrase.getJSONArray("steps");
        assertEquals(16, steps.length());
        JSONArray notes = steps.getJSONArray(0);
        assertEquals(4, notes.length());
        for (int i = 0; i < result().pitches.length; i++) {
            JSONObject note = notes.getJSONObject(i);
            assertEquals(result().pitches[i], note.getInt("note"));
            assertEquals(result().velocities[i] / 127.0, note.getDouble("velocity"), 1e-12);
            assertEquals(3, note.length());
            JSONObject overrides = note.getJSONObject("overrides");
            assertEquals(3, overrides.length());
            assertEquals(result().tones[i] / 100.0, overrides.getDouble("tone"), 1e-12);
            assertEquals(result().decays[i] / 100.0, overrides.getDouble("decay"), 1e-12);
            assertEquals(result().drives[i] / 100.0, overrides.getDouble("drive"), 1e-12);
            assertFalse(note.has("duration"));
            assertFalse(note.has("gate"));
        }
        for (int i = 1; i < 16; i++) assertEquals(0, steps.getJSONArray(i).length());
        JSONObject clip = saw.getJSONArray("clips").getJSONObject(0);
        assertEquals(1, clip.getInt("id"));
        assertEquals(0, clip.getInt("pattern"));
        assertEquals(0, clip.getInt("startBar"));
        assertEquals(1, clip.getInt("lengthBars"));
    }

    @Test public void supportsTempoBoundariesAndRejectsUnsupportedTempo() throws Exception {
        assertEquals(40, new JSONObject(FerroExporter.export(result(), 40)).getInt("bpm"));
        assertEquals(300, new JSONObject(FerroExporter.export(result(), 300)).getInt("bpm"));
        for (int invalid : new int[]{0, 39, 301, Integer.MAX_VALUE}) {
            try { FerroExporter.export(result(), invalid); fail(); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void likingDoesNotAlterExportedMusicalStructure() throws Exception {
        String liked = FerroExporter.export(result(), 126);
        String unliked = FerroExporter.export(result().withLiked(false), 126);
        assertEquals(liked, unliked);
    }

    @Test public void legacyConstructorExportsPreviousSoundAsExplicitNoteOverrides() throws Exception {
        NoteRecord legacy = new NoteRecord("legacy", 1, new int[]{60}, new int[]{100}, false, 1, "v1");
        JSONObject project = new JSONObject(FerroExporter.export(legacy, 120));
        JSONObject sound = project.getJSONArray("tracks").getJSONObject(2).getJSONArray("patterns")
                .getJSONObject(0).getJSONArray("steps").getJSONArray(0).getJSONObject(0).getJSONObject("overrides");
        assertEquals(.5, sound.getDouble("tone"), 0);
        assertEquals(.8, sound.getDouble("decay"), 0);
        assertEquals(.2, sound.getDouble("drive"), 0);
    }
}
