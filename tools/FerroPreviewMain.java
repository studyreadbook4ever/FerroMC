package dev.ferro.seed;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Host-only entry point into the production renderer and exporter; no Android audio calls. */
public final class FerroPreviewMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: record.json frames preview.f32le project.ferro");
        }
        NoteRecord record = NoteRecord.fromJson(new JSONObject(Files.readString(Path.of(args[0]))));
        float[] samples = TonePlayer.renderFloat(record, .7, Integer.parseInt(args[1]));
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(samples.length, Float.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples) {
            if (!Float.isFinite(sample)) throw new IllegalStateException("Nonfinite production audio");
            bytes.putFloat(sample);
        }
        Files.write(Path.of(args[2]), bytes.array());
        Files.writeString(Path.of(args[3]), FerroExporter.export(record, 120));
    }
}
