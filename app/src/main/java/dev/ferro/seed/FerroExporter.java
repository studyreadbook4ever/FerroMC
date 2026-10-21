package dev.ferro.seed;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The editable Ferro v4 project contract, verified against its actual Linux project loader. */
public final class FerroExporter {
    public static final String FERRO_REFERENCE = "ea8a1451ebca207e15cb25361a6a053f9712ac48";
    public static final double CHORD_LEVEL = 0.24;
    public static final double CHORD_TONE = 0.5;
    public static final double CHORD_DECAY = 0.8;
    public static final double CHORD_DRIVE = 0.2;
    public static final double MASTER = 0.7;
    public static final double TAIL_SECONDS = 1.0;

    private FerroExporter() {}

    public static String export(NoteRecord record, int bpm) throws JSONException {
        if (bpm < 40 || bpm > 300) throw new IllegalArgumentException("Tempo must be 40–300 BPM.");
        NoteRecord safe = record.copy();
        String[] instruments = {"Kick", "Bass", "Saw", "Lead", "Clap", "Hats"};
        double[] volumes = {0.8, 0.55, CHORD_LEVEL, 0.24, 0.42, 0.26};
        JSONArray tracks = new JSONArray();
        for (int trackIndex = 0; trackIndex < instruments.length; trackIndex++) {
            JSONArray patterns = new JSONArray();
            JSONArray clips = new JSONArray();
            if (trackIndex == 2) {
                JSONArray steps = new JSONArray();
                JSONArray chord = new JSONArray();
                for (int note = 0; note < safe.pitches.length; note++) {
                    chord.put(new JSONObject().put("note", safe.pitches[note])
                            .put("velocity", safe.velocities[note] / 127.0)
                            .put("overrides", new JSONObject().put("tone", safe.tones[note] / 100.0)
                                    .put("decay", safe.decays[note] / 100.0)
                                    .put("drive", safe.drives[note] / 100.0)));
                }
                steps.put(chord);
                for (int step = 1; step < 16; step++) steps.put(new JSONArray());
                patterns.put(new JSONObject().put("name", "FerroMC").put("bars", 1)
                        .put("steps", steps));
                clips.put(new JSONObject().put("id", 1).put("pattern", 0)
                        .put("startBar", 0).put("lengthBars", 1));
            }
            double decay = trackIndex == 2 ? CHORD_DECAY : trackIndex == 0 ? 0.35
                    : trackIndex == 4 ? 0.25 : trackIndex == 5 ? 0.14 : 0.5;
            tracks.put(new JSONObject().put("name", instruments[trackIndex])
                    .put("instrument", trackIndex).put("volume", volumes[trackIndex])
                    .put("pan", trackIndex == 5 ? 0.15 : 0.0).put("tone", CHORD_TONE)
                    .put("decay", decay).put("drive", CHORD_DRIVE)
                    .put("mute", false).put("solo", false).put("sample", "")
                    .put("patterns", patterns).put("clips", clips));
        }
        return new JSONObject().put("format", "ferro-project").put("version", 4)
                .put("title", "FerroMC " + safe.id).put("bpm", bpm).put("master", MASTER)
                .put("delay", 0.0).put("duck", 0.0).put("metronome", false)
                .put("songBars", 1).put("tailSeconds", TAIL_SECONDS)
                .put("tracks", tracks).toString(2);
    }
}
