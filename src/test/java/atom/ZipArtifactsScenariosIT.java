package atom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scenarios the smoke script never reached: which files a glob selects, what the outputs say, and every way the atom
 * refuses a job.
 *
 * <p>Each case starts the packaged jar through {@link Atom}, so the assertions read {@code output.json} and the files
 * the atom actually wrote — a zip is opened again and compared entry by entry rather than assumed.
 */
class ZipArtifactsScenariosIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("no inputs at all: every file goes into artifacts.zip in the workspace root")
    void packsEverythingByDefault() throws Exception {
        AtomResult run = atom.file("a.txt", "a\n").file("sub/b.txt", "b\n").run();

        run.assertOk();
        Path archive = run.work().resolve("artifacts.zip");
        assertEquals(archive.toString(), run.field("archive"), "default archive name: " + run.field("archive"));
        assertTrue(Files.isRegularFile(archive), "the default archive was not written");

        Map<String, byte[]> packed = entries(archive);
        assertTrue(packed.containsKey("a.txt"), "a.txt missing from " + packed.keySet());
        assertTrue(packed.containsKey("sub/b.txt"), "sub/b.txt missing from " + packed.keySet());
        // every regular file under the workspace is fair game under the default **/* — input.json included
        assertEquals(String.valueOf(packed.size()), run.field("files"), "files must count the entries written");
        assertEquals(String.valueOf(Files.size(archive)), run.field("bytes"), "bytes must be the archive size");
    }

    @Test
    @DisplayName("includes select a subtree, excludes drop what it matches")
    void honoursIncludesAndExcludes() throws Exception {
        AtomResult run = atom.file("src/a.txt", "a\n")
                .file("src/nested/b.java", "class B {}\n")
                .file("src/skip.tmp", "tmp\n")
                .file("other/c.txt", "c\n")
                .input("includes", "src/**")
                .input("excludes", "**/*.tmp")
                .input("archive", "out/src.zip")
                .run();

        run.assertOk();
        Map<String, byte[]> packed = entries(run.work().resolve("out/src.zip"));
        assertEquals(List.of("src/a.txt", "src/nested/b.java"), new ArrayList<>(packed.keySet()),
                "entries: " + packed.keySet());
        assertEquals("a\n", new String(packed.get("src/a.txt"), StandardCharsets.UTF_8));
        assertEquals("class B {}\n", new String(packed.get("src/nested/b.java"), StandardCharsets.UTF_8));
        assertEquals("2", run.field("files"));
    }

    @Test
    @DisplayName("a pattern without a slash also matches a bare file name, so *.txt finds nested files")
    void matchesBareFileNames() throws Exception {
        AtomResult run = atom.file("root.txt", "root\n")
                .file("sub/nested.txt", "nested\n")
                .file("sub/nested.md", "md\n")
                .input("includes", "*.txt")
                .input("archive", "only-text.zip")
                .run();

        run.assertOk();
        Map<String, byte[]> packed = entries(run.work().resolve("only-text.zip"));
        assertEquals(List.of("root.txt", "sub/nested.txt"), new ArrayList<>(packed.keySet()),
                "entries: " + packed.keySet());
        assertEquals("2", run.field("files"));
    }

    @Test
    @DisplayName("a comma separated pattern list is trimmed and empty parts are ignored")
    void splitsCommaSeparatedPatterns() throws Exception {
        AtomResult run = atom.file("notes.md", "md\n")
                .file("log.txt", "txt\n")
                .file("ignored.bin", "bin\n")
                .input("includes", " *.md , , *.txt ")
                .input("archive", "multi.zip")
                .run();

        run.assertOk();
        Map<String, byte[]> packed = entries(run.work().resolve("multi.zip"));
        assertEquals(List.of("log.txt", "notes.md"), new ArrayList<>(packed.keySet()),
                "entries: " + packed.keySet());
    }

    @Test
    @DisplayName("an archive in a directory that does not exist yet is created with its parents")
    void createsTheArchiveDirectoryChain() throws Exception {
        AtomResult run = atom.file("a.txt", "a\n")
                .input("includes", "a.txt")
                .input("archive", "build/deep/nested/app.zip")
                .run();

        run.assertOk();
        Path archive = run.work().resolve("build/deep/nested/app.zip");
        assertTrue(Files.isRegularFile(archive), "the parent chain was not created");
        assertFalse(Files.exists(archive.resolveSibling("app.zip.partial")), "a .partial file was left behind");
        assertEquals(List.of("a.txt"), new ArrayList<>(entries(archive).keySet()));
    }

    @Test
    @DisplayName("packing twice replaces the archive: no stale entry from the first run survives")
    void rewritesAnExistingArchive() throws Exception {
        AtomResult first = atom.file("a.txt", "a\n")
                .input("includes", "*.txt")
                .input("archive", "twice.zip")
                .run();
        first.assertOk();

        Path work = first.work();
        Files.writeString(work.resolve("b.txt"), "b\n", StandardCharsets.UTF_8);
        Map<String, Object> second = runIn(work,
                "{\"operation\": \"zip\", \"archive\": \"twice.zip\", \"includes\": \"*.txt\"}");

        assertEquals("success", second.get("status"), "second pack: " + Json.write(second));
        Map<String, byte[]> packed = entries(work.resolve("twice.zip"));
        assertEquals(List.of("a.txt", "b.txt"), new ArrayList<>(packed.keySet()),
                "the rewritten archive must hold both files, once each: " + packed.keySet());
        assertEquals("2", field(second, "files"));
    }

    @Test
    @DisplayName("an archive that matches the include glob is never packed into itself, even on a second run")
    void neverPacksTheArchiveIntoItself() throws Exception {
        Path work = aZipToRepack();

        Map<String, Object> first = runIn(work,
                "{\"operation\": \"zip\", \"archive\": \"all.zip\", \"includes\": \"**/*.zip\"}");
        assertEquals("success", first.get("status"), "first pack: " + Json.write(first));
        assertEquals(List.of("old.zip"), new ArrayList<>(entries(work.resolve("all.zip")).keySet()));

        // the archive now exists in the workspace and matches **/*.zip — it must still exclude itself
        Map<String, Object> second = runIn(work,
                "{\"operation\": \"zip\", \"archive\": \"all.zip\", \"includes\": \"**/*.zip\"}");

        assertEquals("success", second.get("status"), "second pack: " + Json.write(second));
        Map<String, byte[]> packed = entries(work.resolve("all.zip"));
        assertEquals(List.of("old.zip"), new ArrayList<>(packed.keySet()),
                "only the pre-existing zip belongs in the archive: " + packed.keySet());
        assertEquals("1", field(second, "files"));
    }

    @Test
    @DisplayName("nothing matches the glob: refused, and no archive is written")
    void refusesWhenNothingMatches() throws Exception {
        AtomResult run = atom.file("a.txt", "a\n")
                .input("includes", "nothing/here/**")
                .input("archive", "empty.zip")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no file matched"),
                "message: " + run.file("output.json"));
        assertTrue(run.file("output.json").contains("40003"), "error code: " + run.file("output.json"));
        assertFalse(run.exists("empty.zip"), "an archive was written for an empty match");
        assertFalse(run.exists("empty.zip.partial"), "a .partial file was left behind");
    }

    @Test
    @DisplayName("an archive path that climbs out of the workspace is refused with 40001")
    void refusesAnArchiveOutsideTheWorkspace() throws Exception {
        AtomResult run = atom.file("a.txt", "a\n")
                .input("includes", "a.txt")
                .input("archive", "../escape.zip")
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("inside the workspace"), "message: " + output);
        assertTrue(output.contains("40001"), "error code: " + output);
        assertFalse(Files.exists(run.work().getParent().resolve("escape.zip")), "the archive escaped the workspace");
    }

    @Test
    @DisplayName("an operation other than zip/unzip is refused with 40002")
    void refusesAnUnknownOperation() throws Exception {
        AtomResult run = atom.file("a.txt", "a\n").input("operation", "tar").run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("unknown operation 'tar'"), "message: " + output);
        assertTrue(output.contains("40002"), "error code: " + output);
    }

    @Test
    @DisplayName("a small archive is reported in bytes")
    void reportsHumanSizeInBytes() throws Exception {
        AtomResult run = atom.file("x.txt", "hi\n").input("includes", "x.txt").run();

        run.assertOk();
        String human = run.field("human");
        assertTrue(Pattern.matches("\\d+ B", human), "human: '" + human + "'");
        assertTrue(Long.parseLong(human.split(" ")[0]) < 1024, "a tiny zip is under 1 KiB: " + human);
    }

    @Test
    @DisplayName("a few compressed kilobytes are reported in KiB")
    void reportsHumanSizeInKib() throws Exception {
        AtomResult run = atom.file("noise.bin", randomText(9_000))
                .input("includes", "noise.bin")
                .input("archive", "kib.zip")
                .run();

        run.assertOk();
        String human = run.field("human");
        assertTrue(human.endsWith(" KiB"), "human: '" + human + "'");
        assertTrue(Pattern.matches("\\d+\\.\\d KiB", human), "one decimal place: '" + human + "'");
        assertEquals(String.valueOf(Files.size(run.work().resolve("kib.zip"))), run.field("bytes"));
    }

    @Test
    @DisplayName("a megabyte of incompressible text is reported in MiB")
    void reportsHumanSizeInMib() throws Exception {
        AtomResult run = atom.file("noise.bin", randomText(2_600_000))
                .input("includes", "noise.bin")
                .input("archive", "mib.zip")
                .run();

        run.assertOk();
        String human = run.field("human");
        assertTrue(human.endsWith(" MiB"), "human: '" + human + "'");
        assertTrue(Pattern.matches("\\d+\\.\\d MiB", human), "one decimal place: '" + human + "'");
    }

    @Test
    @DisplayName("unzip restores directory entries, nested paths and overwrites an older file")
    void unzipsDirectoryEntriesAndNestedFiles() throws Exception {
        byte[] payload = "deep\n".getBytes(StandardCharsets.UTF_8);
        Path work = aZipWithDirectoryEntries(payload);

        Files.createDirectories(work.resolve("restored/dir"));
        Files.writeString(work.resolve("restored/dir/file.txt"), "stale\n", StandardCharsets.UTF_8);

        Map<String, Object> output = runIn(work,
                "{\"operation\": \"unzip\", \"archive\": \"tree.zip\", \"target\": \"restored\"}");

        assertEquals("success", output.get("status"), "unzip: " + Json.write(output));
        assertEquals("2", field(output, "entries"), "directory entries are not counted as files");
        assertEquals(String.valueOf(2 * payload.length), field(output, "bytes"),
                "bytes must be the payload, not the archive");
        assertEquals(work.resolve("restored").toString(), field(output, "target"));
        assertEquals(payload.length * 2 + " B", field(output, "human"),
                "the payload size, human readable: " + field(output, "human"));
        assertArrayEquals(payload, Files.readAllBytes(work.resolve("restored/dir/file.txt")),
                "an existing file must be replaced, not appended to");
        assertArrayEquals(payload, Files.readAllBytes(work.resolve("restored/a/b/c.txt")),
                "a nested entry must get its parent directories");
    }

    @Test
    @DisplayName("unzip of a missing archive is refused with 40004")
    void refusesAMissingArchive() throws Exception {
        AtomResult run = atom.input("operation", "unzip").input("archive", "nope.zip").run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("no archive at"), "message: " + output);
        assertTrue(output.contains("40004"), "error code: " + output);
    }

    @Test
    @DisplayName("an unzip target that climbs out of the workspace is refused with 40005")
    void refusesATargetOutsideTheWorkspace() throws Exception {
        Path work = aZipToRepack();
        Map<String, Object> output = runIn(work,
                "{\"operation\": \"unzip\", \"archive\": \"old.zip\", \"target\": \"../escaped\"}");

        assertEquals("error", output.get("status"), "unzip: " + Json.write(output));
        assertTrue(Json.write(output).contains("inside the workspace"), "message: " + Json.write(output));
        assertTrue(Json.write(output).contains("40005"), "error code: " + Json.write(output));
        assertFalse(Files.exists(work.getParent().resolve("escaped")), "the target escaped the workspace");
    }

    @Test
    @DisplayName("a zip-slip entry is refused with 40006 and nothing outside the target is written")
    void refusesAZipSlipEntry() throws Exception {
        Path work = Files.createDirectories(Path.of(System.getProperty("atom.scratch")).resolve("slip-" + System.nanoTime()));
        try (OutputStream file = Files.newOutputStream(work.resolve("evil.zip"));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("ok.txt"));
            zip.write("fine".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("never".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        Map<String, Object> output = runIn(work,
                "{\"operation\": \"unzip\", \"archive\": \"evil.zip\", \"target\": \"out\"}");

        assertEquals("error", output.get("status"), "zip-slip must fail: " + Json.write(output));
        assertTrue(Json.write(output).contains("outside the target directory"), "message: " + Json.write(output));
        assertTrue(Json.write(output).contains("40006"), "error code: " + Json.write(output));
        assertFalse(Files.exists(work.getParent().resolve("escaped.txt")), "the entry escaped the workspace");
    }

    // ---------------------------------------------------------------- fixtures

    /** A workspace holding a small, valid zip named {@code old.zip}. */
    private static Path aZipToRepack() throws Exception {
        Path work = Files.createDirectories(
                Path.of(System.getProperty("atom.scratch")).resolve("repack-" + System.nanoTime()));
        writeZip(work.resolve("old.zip"), "inside.txt", "old\n".getBytes(StandardCharsets.UTF_8));
        return work;
    }

    /** A zip with two directory entries and two files, at two nesting depths. */
    private static Path aZipWithDirectoryEntries(byte[] payload) throws Exception {
        Path work = Files.createDirectories(
                Path.of(System.getProperty("atom.scratch")).resolve("dirs-" + System.nanoTime()));
        try (OutputStream file = Files.newOutputStream(work.resolve("tree.zip"));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("dir/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("dir/file.txt"));
            zip.write(payload);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("a/b/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("a/b/c.txt"));
            zip.write(payload);
            zip.closeEntry();
        }
        return work;
    }

    private static void writeZip(Path archive, String name, byte[] content) throws Exception {
        try (OutputStream file = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
    }

    /** Deterministic pseudo-random letters: text the deflate stream cannot squeeze, so the size is predictable. */
    private static String randomText(int length) {
        Random random = new Random(7);
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append((char) ('a' + random.nextInt(26)));
        }
        return text.toString();
    }

    /** Every file entry of an archive, in zip order, with its decompressed bytes. */
    private static Map<String, byte[]> entries(Path archive) throws Exception {
        Map<String, byte[]> packed = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    packed.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return packed;
    }

    private static String field(Map<String, Object> output, String name) {
        if (output.get("data") instanceof Map<?, ?> data && data.get(name) instanceof Map<?, ?> entry) {
            Object value = entry.get("value");
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }

    /**
     * Runs the packaged jar in {@code dir} the way the harness does — including the JaCoCo agent, so the lines this case
     * exercises land in {@code target/jacoco.exec} like every {@link Atom} run does.
     */
    private static Map<String, Object> runIn(Path dir, String input) throws Exception {
        Path jar = Path.of(System.getProperty("atom.module")).resolve("target/zip-artifacts.jar");
        Files.writeString(dir.resolve("input.json"), input, StandardCharsets.UTF_8);

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        String destfile = System.getProperty("jacoco.destfile", "");
        String agent = destfile.isEmpty() ? null : jacocoAgent();
        if (agent != null) {
            command.add("-javaagent:" + agent + "=destfile=" + destfile + ",append=true");
        }
        command.add("-cp");
        command.add(jar.toString());
        command.add("com.jenkinschina.atom.sdk.AtomRunner");

        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
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

    private static String jacocoAgent() {
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            String name = Path.of(entry).getFileName().toString();
            if (name.startsWith("org.jacoco.agent") && name.endsWith(".jar")) {
                return entry;
            }
        }
        return null;
    }
}
