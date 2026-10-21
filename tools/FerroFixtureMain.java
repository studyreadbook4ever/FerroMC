import dev.ferro.seed.FerroExporter;
import dev.ferro.seed.NoteRecord;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the production Android exporter as plain Java; does not simulate the format separately. */
public final class FerroFixtureMain {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        NoteRecord result = new NoteRecord("editable-example", 1700000000000L,
                new int[]{0, 43, 58, 127}, new int[]{1, 96, 72, 127},
                new int[]{0, 50, 100, 37}, new int[]{100, 0, 80, 29}, new int[]{0, 100, 20, 67},
                true, 120034L, "scratch-v2");
        Files.writeString(output.resolve("expected-record.json"), result.toJson().toString(2));
        for (int bpm : new int[]{40, 120, 300}) {
            Files.writeString(output.resolve("export-" + bpm + ".ferro"),
                    FerroExporter.export(result, bpm));
        }
    }
}
