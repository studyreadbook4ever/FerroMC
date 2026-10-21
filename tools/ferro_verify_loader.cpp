#include "project.h"
#include "dsp.h"
#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStringList>
#include <array>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <vector>
#include <sndfile.h>

static void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    QJsonArray results;
    try {
        std::vector<int> pitches{0,43,58,127};
        std::vector<int> velocities{1,96,72,127};
        std::vector<int> tones{0,50,100,37}, decays{100,0,80,29}, drives{0,100,20,67};
        bool explicitSound = true;
        QString expectedPath;
        QString expectedId;
        QString renderDirectory;
        QStringList projects;
        for (int arg = 1; arg < argc; ++arg) {
            const QString value = QString::fromLocal8Bit(argv[arg]);
            if (value == "--expected") {
                check(expectedPath.isEmpty() && arg + 1 < argc,
                      "Use --expected once followed by a NoteRecord JSON path");
                expectedPath = QString::fromLocal8Bit(argv[++arg]);
            } else if (value == "--render-dir") {
                check(renderDirectory.isEmpty() && arg + 1 < argc,
                      "Use --render-dir once followed by an output directory");
                renderDirectory = QString::fromLocal8Bit(argv[++arg]);
            } else {
                check(!value.startsWith("--"), "Unknown verifier option");
                projects.append(value);
            }
        }
        check(!projects.empty(), "Usage: verify-loader [--expected record.json] project.ferro [...]");
        if (!expectedPath.isEmpty()) {
            QFile expectedFile(expectedPath);
            check(expectedFile.open(QIODevice::ReadOnly), "Cannot open expected NoteRecord JSON");
            QJsonParseError parseError;
            const auto document = QJsonDocument::fromJson(expectedFile.readAll(), &parseError);
            check(parseError.error == QJsonParseError::NoError && document.isObject(),
                  "Expected NoteRecord must be a JSON object");
            const auto record = document.object();
            check(record.value("pitches").isArray() && record.value("velocities").isArray(),
                  "Expected NoteRecord needs pitches and velocities arrays");
            const auto expectedPitches = record.value("pitches").toArray();
            const auto expectedVelocities = record.value("velocities").toArray();
            check(!expectedPitches.empty() && expectedPitches.size() <= 128 &&
                  expectedPitches.size() == expectedVelocities.size(), "Invalid expected note count");
            pitches.clear();
            velocities.clear();
            std::array<bool,128> used{};
            for (int i = 0; i < expectedPitches.size(); ++i) {
                const auto pitch = expectedPitches[i];
                const auto velocity = expectedVelocities[i];
                check(pitch.isDouble() && pitch.toDouble() == std::floor(pitch.toDouble()) &&
                      pitch.toDouble() >= 0 && pitch.toDouble() <= 127, "Invalid expected MIDI pitch");
                check(velocity.isDouble() && velocity.toDouble() == std::floor(velocity.toDouble()) &&
                      velocity.toDouble() >= 1 && velocity.toDouble() <= 127, "Invalid expected velocity");
                check(!used[pitch.toInt()], "Duplicate expected pitch");
                used[pitch.toInt()] = true;
                pitches.push_back(pitch.toInt());
                velocities.push_back(velocity.toInt());
            }
            expectedId = record.value("id").toString();
            const bool tone = record.contains("tones"), decay = record.contains("decays"),
                       drive = record.contains("drives");
            check(!(tone || decay || drive) || (tone && decay && drive),
                  "Expected sound arrays must be supplied together");
            explicitSound = tone && decay && drive;
            const auto readControls = [&](const char* field, int legacy) {
                std::vector<int> values(pitches.size(), legacy);
                if (!explicitSound) return values;
                check(record.value(field).isArray(), "Expected sound controls must be arrays");
                const auto array = record.value(field).toArray();
                check(size_t(array.size()) == pitches.size(), "Expected sound control count mismatch");
                for (int i = 0; i < array.size(); ++i) {
                    check(array[i].isDouble() && array[i].toDouble() == std::floor(array[i].toDouble()) &&
                          array[i].toDouble() >= 0 && array[i].toDouble() <= 100,
                          "Expected sound control must be an integer percentage from 0 to 100");
                    values[i] = array[i].toInt();
                }
                return values;
            };
            tones = readControls("tones", 50);
            decays = readControls("decays", 80);
            drives = readControls("drives", 20);
        }
        if (!renderDirectory.isEmpty()) {
            check(QDir().mkpath(renderDirectory), "Cannot create reference render directory");
        }
        for (const QString& path : projects) {
            QString error;
            fc::Project project;
            const bool loaded = fc::loadProject(path, project, error);
            check(loaded, error.toUtf8().constData());
            check(project.songBars == 1, "Expected one editable bar");
            if (!expectedId.isEmpty()) {
                check(project.title == "FerroMC " + expectedId ||
                      (!explicitSound && project.title == "Sori Seed " + expectedId),
                      "Expected record id/title mismatch");
            }
            for (int track = 0; track < 6; ++track) {
                check(project.tracks[track].samplePath.isEmpty(), "Unexpected external sample");
                if (track == 2) continue;
                check(project.tracks[track].patterns.empty(), "Unexpected notes on unused track");
                check(project.tracks[track].clips.empty(), "Unexpected clip on unused track");
            }
            auto& saw = project.tracks[2];
            check(saw.instrument == 2, "Saw instrument mismatch");
            check(std::abs(saw.tone - .5) < 1e-12 && std::abs(saw.decay - .8) < 1e-12 &&
                  std::abs(saw.drive - .2) < 1e-12, "Saw fallback sound mismatch");
            check(saw.patterns.size() == 1 && saw.clips.size() == 1, "Invalid pattern/clip count");
            check(saw.patterns[0].steps.size() == 16, "Invalid step count");
            const auto scheduled = fc::scheduledSteps(project, 2);
            check(scheduled.size() == pitches.size(), "Expected editable note count mismatch");
            QJsonArray loadedPitches;
            QJsonArray loadedVelocities;
            QJsonArray loadedTones, loadedDecays, loadedDrives;
            for (size_t i = 0; i < pitches.size(); ++i) {
                check(scheduled[i].frame == 0, "Chord onset mismatch");
                check(scheduled[i].step.note == pitches[i], "Pitch mismatch");
                check(std::abs(scheduled[i].step.velocity - velocities[i] / 127.0) < 1e-12,
                      "Velocity mismatch");
                loadedPitches.append(scheduled[i].step.note);
                loadedVelocities.append(velocities[i]);
                const auto& note = scheduled[i].step;
                if (explicitSound) {
                    check(note.overrides.tone && note.overrides.decay && note.overrides.drive,
                          "Missing explicit per-note sound override");
                }
                check(!note.overrides.level && !note.overrides.pan, "Unexpected per-note level or pan override");
                const auto sound = fc::resolvedNoteSettings(saw, note);
                check(std::abs(sound.tone - tones[i] / 100.0) < 1e-12, "Per-note tone mismatch");
                check(std::abs(sound.decay - decays[i] / 100.0) < 1e-12, "Per-note decay mismatch");
                check(std::abs(sound.drive - drives[i] / 100.0) < 1e-12, "Per-note drive mismatch");
                loadedTones.append(tones[i]); loadedDecays.append(decays[i]); loadedDrives.append(drives[i]);
            }
            auto audio = fc::renderProject(project);
            double energy = 0;
            double peak = 0;
            for (float value : audio) {
                check(std::isfinite(value), "Nonfinite rendered audio");
                energy += value * value;
                peak = std::max(peak, std::abs(double(value)));
            }
            check(energy > 0 && peak <= 1.0, "Expected audible bounded audio");
            QString renderPath;
            if (!renderDirectory.isEmpty()) {
                renderPath = QDir(renderDirectory).filePath(QFileInfo(path).completeBaseName() + ".wav");
                SF_INFO info{};
                info.samplerate = fc::SampleRate;
                info.channels = 2;
                info.format = SF_FORMAT_WAV | SF_FORMAT_FLOAT;
                SNDFILE* output = sf_open(renderPath.toLocal8Bit().constData(), SFM_WRITE, &info);
                check(output != nullptr, "Cannot open reference float WAV output");
                const auto written = sf_writef_float(output, audio.data(), audio.size() / 2);
                const int closed = sf_close(output);
                check(written == sf_count_t(audio.size() / 2) && closed == 0,
                      "Reference float WAV output was incomplete");
            }
            auto editedPitches = pitches;
            const size_t editIndex = pitches.size() > 1 ? 1 : 0;
            std::array<bool,128> used{};
            for (int pitch : pitches) used[pitch] = true;
            bool replaced = false;
            for (int offset = 1; offset < 128; ++offset) {
                const int candidate = (pitches[editIndex] + offset) % 128;
                if (!used[candidate]) {
                    editedPitches[editIndex] = candidate;
                    replaced = true;
                    break;
                }
            }
            // Every MIDI pitch occupied: swap two notes to keep the edited chord unique.
            if (!replaced) std::swap(editedPitches[0], editedPitches[1]);
            for (size_t i = 0; i < pitches.size(); ++i) {
                saw.patterns[0].steps[0].notes[i].note = editedPitches[i];
            }
            auto& editedNote = saw.patterns[0].steps[0].notes[editIndex];
            const int editedVelocity = velocities[editIndex] % 127 + 1;
            const int editedTone = (tones[editIndex] + 1) % 101;
            const int editedDecay = (decays[editIndex] + 1) % 101;
            const int editedDrive = (drives[editIndex] + 1) % 101;
            editedNote.velocity = editedVelocity / 127.0;
            editedNote.overrides.tone = editedTone / 100.0;
            editedNote.overrides.decay = editedDecay / 100.0;
            editedNote.overrides.drive = editedDrive / 100.0;
            const QString edited = path + ".edited.ferro";
            const bool saved = fc::saveProject(edited, project, error);
            check(saved, error.toUtf8().constData());
            fc::Project roundTrip;
            const bool reloaded = fc::loadProject(edited, roundTrip, error);
            check(reloaded, error.toUtf8().constData());
            const auto editedSteps = fc::scheduledSteps(roundTrip, 2);
            check(editedSteps.size() == editedPitches.size(), "Edited chord note count changed");
            for (size_t i = 0; i < editedPitches.size(); ++i) {
                check(editedSteps[i].frame == 0 && editedSteps[i].step.note == editedPitches[i],
                      "Editable pitch did not survive save and reload");
                check(std::abs(editedSteps[i].step.velocity - (i == editIndex ? editedVelocity : velocities[i]) / 127.0) < 1e-12,
                      "Edited note velocity did not survive save and reload");
                const auto sound = fc::resolvedNoteSettings(roundTrip.tracks[2], editedSteps[i].step);
                check(std::abs(sound.tone - (i == editIndex ? editedTone : tones[i]) / 100.0) < 1e-12 &&
                      std::abs(sound.decay - (i == editIndex ? editedDecay : decays[i]) / 100.0) < 1e-12 &&
                      std::abs(sound.drive - (i == editIndex ? editedDrive : drives[i]) / 100.0) < 1e-12,
                      "Edited per-note sound did not survive save and reload");
            }
            results.append(QJsonObject{{"file", path}, {"bpm", project.bpm},
                {"editable_notes", int(scheduled.size())}, {"frames", int(audio.size()/2)},
                {"pitches", loadedPitches}, {"velocities", loadedVelocities},
                {"tones", loadedTones}, {"decays", loadedDecays}, {"drives", loadedDrives},
                {"edited_note_index", int(editIndex)}, {"edited_pitch", editedPitches[editIndex]},
                {"edited_velocity", editedVelocity}, {"edited_tone", editedTone},
                {"edited_decay", editedDecay}, {"edited_drive", editedDrive},
                {"reference_float_wav", renderPath},
                {"peak", peak}, {"rms", std::sqrt(energy/audio.size())},
                {"edit_save_reload", true}, {"ok", true}});
        }
        std::cout << QJsonDocument(QJsonObject{{"reference_commit",
            "ea8a1451ebca207e15cb25361a6a053f9712ac48"}, {"results", results},
            {"expected_record", expectedPath}, {"expected_record_id", expectedId},
            {"ok", true}}).toJson().constData();
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
