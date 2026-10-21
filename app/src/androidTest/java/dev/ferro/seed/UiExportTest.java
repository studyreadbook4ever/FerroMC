package dev.ferro.seed;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** Real SAF export test. Missing DocumentsUI is a failure, never an automatic skip. */
@RunWith(AndroidJUnit4.class)
public final class UiExportTest {
    private static final long TIMEOUT = 15_000;
    private UiDevice device;

    @Test public void savesActualEditableFerroDocumentAndRemovesOnlyItsOwnFile() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        device = UiDevice.getInstance(instrumentation);
        HistoryStore store = HistoryStore.shared(context);
        String token = UUID.randomUUID().toString();
        String id = "ui-export-" + token;
        String filename = "FerroMC-ui-export-" + token + ".ferro";
        File download = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), filename);
        File evidence = new File(context.getExternalFilesDir(null), "ui-export-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        JSONObject report = new JSONObject().put("status", "running")
                .put("application_id", context.getPackageName()).put("test_record_id", id)
                .put("test_filename", filename).put("fixture_generated_by_npu", false)
                .put("scope", "Actual app UI, Android document picker, written Ferro JSON; no NPU claim.");
        Activity activity = null;
        long newest = System.currentTimeMillis();
        for (NoteRecord previous : store.list()) newest = Math.max(newest, previous.createdAt);
        assertTrue(newest < Long.MAX_VALUE - 1);
        NoteRecord fixture = new NoteRecord(id, newest + 1, new int[]{0, 43, 58, 127},
                new int[]{1, 96, 72, 127}, new int[]{0, 50, 100, 37},
                new int[]{100, 0, 80, 29}, new int[]{0, 100, 20, 67}, true, 120034L, "ui-export-fixture");
        Throwable primaryFailure = null;
        try {
            String resolved = device.executeShellCommand("cmd package resolve-activity --brief"
                    + " -a android.intent.action.CREATE_DOCUMENT -c android.intent.category.OPENABLE"
                    + " -t application/octet-stream");
            report.put("document_picker_resolution", resolved.trim());
            assertTrue("This image lacks a document picker; actual SAF export requires DocumentsUI.",
                    resolved.contains("/") && !resolved.contains("No activity found"));
            store.add(fixture);
            activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(By.text(".ferro 내보내기")).click();
            // PackageManager.resolveActivity can be filtered even when startActivity is permitted.
            await(By.text(Pattern.compile("FerroMC-\\d{8}-\\d{6}\\.ferro")));
            String pickerPackage = device.getCurrentPackageName();
            assertNotEquals("A real system document picker must be open.",
                    context.getPackageName(), pickerPackage);
            report.put("document_picker_package", pickerPackage);
            selectDownloads(pickerPackage);

            // AOSP/Google DocumentsUI uses the framework title ID, not an app file_name ID.
            UiObject2 name = await(By.res("android", "title")
                    .clazz("android.widget.EditText").pkg(pickerPackage));
            name.setText(filename);
            assertEquals(filename, name.getText());
            assertTrue(device.takeScreenshot(new File(evidence, "01-save-dialog.png")));
            UiObject2 save = device.findObject(By.res("android", "button1"));
            if (save == null) save = device.findObject(By.res(pickerPackage, "button1"));
            if (save == null) save = await(By.text(Pattern.compile("(?i)save|저장")));
            assertTrue("Save must be enabled for the unique filename.", save.isEnabled());
            save.click();
            await(By.desc("음 생성"));

            String expected = FerroExporter.export(fixture, 120);
            String contents = waitForExactDocument(download, expected);
            JSONObject project = new JSONObject(contents);
            assertEquals("ferro-project", project.getString("format"));
            assertEquals(4, project.getInt("version"));
            assertEquals(6, project.getJSONArray("tracks").length());
            JSONArray steps = project.getJSONArray("tracks").getJSONObject(2)
                    .getJSONArray("patterns").getJSONObject(0).getJSONArray("steps");
            assertEquals(16, steps.length());
            assertEquals(4, steps.getJSONArray(0).length());
            for (int index = 0; index < fixture.pitches.length; index++) {
                JSONObject note = steps.getJSONArray(0).getJSONObject(index);
                assertEquals(fixture.pitches[index], note.getInt("note"));
                assertEquals(fixture.velocities[index] / 127.0, note.getDouble("velocity"), 1e-12);
                JSONObject overrides = note.getJSONObject("overrides");
                assertEquals(fixture.tones[index] / 100.0, overrides.getDouble("tone"), 1e-12);
                assertEquals(fixture.decays[index] / 100.0, overrides.getDouble("decay"), 1e-12);
                assertEquals(fixture.drives[index] / 100.0, overrides.getDouble("drive"), 1e-12);
                assertFalse(note.has("duration"));
                assertFalse(note.has("gate"));
            }
            write(new File(evidence, "exported-from-ui.ferro"), contents);
            write(new File(evidence, "expected-record.json"), fixture.toJson().toString(2));
            assertTrue(device.takeScreenshot(new File(evidence, "02-export-completed.png")));
            byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
            report.put("status", "passed").put("bytes", bytes.length)
                    .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .put("exact_production_export_match", true).put("editable_notes", 4)
                    .put("pitches", new JSONArray(fixture.pitches));
        } catch (Exception | AssertionError failure) {
            primaryFailure = failure;
            report.put("status", "failed").put("error", failure.toString());
            throw failure;
        } finally {
            Exception cleanupFailure = null;
            try {
                // This name is a per-run UUID, never a wildcard or a pre-existing user filename.
                String parent = shellToken(download.getParent());
                if (!parent.equals(device.executeShellCommand("ls -d " + parent).trim())) {
                    throw new java.io.IOException("The Downloads directory is unavailable for cleanup verification.");
                }
                device.executeShellCommand("rm -f " + shellToken(download.getAbsolutePath()));
                String remaining = device.executeShellCommand("find " + parent
                        + " -maxdepth 1 -name " + shellToken(filename) + " -print").trim();
                report.put("downloads_test_file_removed", remaining.isEmpty());
                if (!remaining.isEmpty()) throw new java.io.IOException("Test export cleanup failed: " + remaining);
            } catch (Exception error) { cleanupFailure = error; }
            if (activity != null) {
                Activity launched = activity;
                instrumentation.runOnMainSync(launched::finish);
                instrumentation.waitForIdleSync();
            }
            try {
                store.delete(id);
                report.put("history_test_record_removed", store.get(id) == null);
            } catch (Exception error) { cleanupFailure = error; }
            if (cleanupFailure != null) {
                report.put("status", "failed").put("cleanup_error", cleanupFailure.toString());
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
            }
            write(new File(evidence, "result.json"), report.toString(2));
            Log.i("SoriUiExportTest", "Evidence: " + evidence.getAbsolutePath());
            if (cleanupFailure != null && primaryFailure == null) throw cleanupFailure;
        }
    }

    private void selectDownloads(String pickerPackage) {
        Pattern downloads = Pattern.compile("Downloads|다운로드");
        UiObject2 roots = device.findObject(By.res(pickerPackage, "roots_list"));
        if (roots == null) {
            UiObject2 navigation = device.findObject(By.desc(Pattern.compile(
                    "Show roots|루트 표시|Open navigation drawer|탐색 창 열기")));
            if (navigation == null) {
                UiObject2 toolbar = device.findObject(By.res(pickerPackage, "toolbar"));
                if (toolbar != null) navigation = toolbar.findObject(By.clazz("android.widget.ImageButton"));
            }
            assertNotNull("The document picker must expose its location drawer.", navigation);
            navigation.click();
            roots = await(By.res(pickerPackage, "roots_list"));
        }
        UiObject2 target = roots.findObject(By.text(downloads));
        assertNotNull("The Downloads root must be available in DocumentsUI.", target);
        target.click();
        device.waitForIdle();
    }

    private String waitForExactDocument(File file, String expected) throws Exception {
        String last = "";
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        while (SystemClock.uptimeMillis() < deadline) {
            // UiAutomation launches the command directly; it does not interpret && or shell quotes.
            last = device.executeShellCommand("cat " + shellToken(file.getAbsolutePath()));
            if (expected.equals(last)) return last;
            SystemClock.sleep(80);
        }
        assertEquals("The actual SAF file must match the selected record's production export.", expected, last);
        return last;
    }

    private UiObject2 await(BySelector selector) {
        UiObject2 object = device.wait(Until.findObject(selector), TIMEOUT);
        assertNotNull("Missing required UI element: " + selector, object);
        return object;
    }

    private static String shellToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_./-]+") || value.startsWith("-")) {
            throw new IllegalArgumentException("Unsafe test shell argument: " + value);
        }
        return value;
    }

    private static void write(File target, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte element : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", element & 255));
        return value.toString();
    }
}
