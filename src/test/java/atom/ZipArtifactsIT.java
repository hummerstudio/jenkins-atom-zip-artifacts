package atom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The zip atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — the zip/unzip round trip, no {@code .partial} left
 * behind, the workspace guard and the zip-slip guard — written as JUnit.
 *
 * <p>The round trip packs and unpacks in the same directory, and a zip-slip archive is binary: both prepare the
 * workspace themselves and start the packaged jar exactly as the harness does.
 */
class ZipArtifactsIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("zip then unzip the same tree: two entries, identical bytes, no .partial left")
    void roundTripsATree() throws Exception {
        Path work = Files.createDirectories(Path.of(System.getProperty("atom.scratch")).resolve("round-trip"));
        write(work, "dist/app.jar", "jar-bytes\n");
        write(work, "dist/nested/README.md", "readme\n");

        Map<String, Object> packed = runInWorkspace(work,
                "{\"operation\": \"zip\", \"archive\": \"out/app.zip\", \"includes\": \"dist/**\","
                        + " \"excludes\": \"**/*.tmp\"}");

        assertEquals("success", packed.get("status"), "zip: " + Json.write(packed));
        assertEquals("2", field(packed, "files"));
        assertEquals(work.resolve("out/app.zip").toString(), field(packed, "archive"));
        assertTrue(Files.isRegularFile(work.resolve("out/app.zip")), "no archive written");
        assertFalse(Files.exists(work.resolve("out/app.zip.partial")),
                "the .partial file must be gone after a successful pack");
        assertTrue(Json.write(packed).contains("\"type\":\"artifact\""),
                "the archive must be published as an artifact: " + Json.write(packed));

        Map<String, Object> unpacked = runInWorkspace(work,
                "{\"operation\": \"unzip\", \"archive\": \"out/app.zip\", \"target\": \"unpacked\"}");

        assertEquals("success", unpacked.get("status"), "unzip: " + Json.write(unpacked));
        assertEquals("2", field(unpacked, "entries"));
        assertArrayEquals("jar-bytes\n".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(work.resolve("unpacked/dist/app.jar")), "round trip changed the bytes");
        assertEquals("readme\n", Files.readString(work.resolve("unpacked/dist/nested/README.md")));
    }

    @Test
    @DisplayName("an archive outside the workspace is refused")
    void refusesAnArchiveOutsideTheWorkspace() throws Exception {
        AtomResult run = atom.file("a.txt", "x\n")
                .input("{\"operation\": \"zip\", \"archive\": \"../escape.zip\", \"includes\": \"a.txt\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("inside the workspace"),
                "message: " + run.file("output.json"));
        assertFalse(Files.exists(run.work().getParent().resolve("escape.zip")), "nothing was written outside");
    }

    @Test
    @DisplayName("a zip-slip entry is refused and nothing escapes the target directory")
    void refusesZipSlip() throws Exception {
        Path work = Files.createDirectories(Path.of(System.getProperty("atom.scratch")).resolve("zip-slip"));
        try (OutputStream file = Files.newOutputStream(work.resolve("evil.zip"));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("ok.txt"));
            zip.write("fine".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("should never be written".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Map<String, Object> output = runInWorkspace(work,
                "{\"operation\": \"unzip\", \"archive\": \"evil.zip\", \"target\": \"out\"}");

        assertEquals("error", output.get("status"), "a zip-slip entry must be refused: " + Json.write(output));
        assertTrue(Json.write(output).contains("outside the target directory"), "message: " + Json.write(output));
        assertFalse(Files.exists(work.resolve("../escaped.txt")), "the entry escaped into the parent directory");
    }

    @Test
    @DisplayName("a missing archive is a clear error")
    void refusesAMissingArchive() throws Exception {
        AtomResult run = atom.input("{\"operation\": \"unzip\", \"archive\": \"nope.zip\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no archive at"), "message: " + run.file("output.json"));
    }

    private static void write(Path work, String relative, String content) throws Exception {
        Path file = work.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** {@code output.json}'s {@code data.<name>.value}, the way the smoke script read it. */
    private static String field(Map<String, Object> output, String name) {
        if (output.get("data") instanceof Map<?, ?> data && data.get(name) instanceof Map<?, ?> entry) {
            Object value = entry.get("value");
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }

    /** Runs the packaged jar in {@code dir} exactly like the harness does, and returns its {@code output.json}. */
    private static Map<String, Object> runInWorkspace(Path dir, String input) throws Exception {
        Path jar = Path.of(System.getProperty("atom.module")).resolve("target/zip-artifacts.jar");
        Files.writeString(dir.resolve("input.json"), input, StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", jar.toString(), "com.jenkinschina.atom.sdk.AtomRunner")
                .directory(dir.toFile())
                .redirectErrorStream(true);
        builder.environment().put("atom_data_dir", dir.toString());
        builder.environment().put("atom_data_input", "input.json");
        builder.environment().put("atom_data_output", "output.json");
        builder.environment().put("atom_workspace", dir.toString());
        Process process = builder.start();
        String console = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertNotEquals(-1, process.waitFor(), "the atom did not finish: " + console);
        Path output = dir.resolve("output.json");
        assertTrue(Files.isRegularFile(output), "no output.json: " + console);
        return Json.parseObject(Files.readString(output, StandardCharsets.UTF_8));
    }
}
