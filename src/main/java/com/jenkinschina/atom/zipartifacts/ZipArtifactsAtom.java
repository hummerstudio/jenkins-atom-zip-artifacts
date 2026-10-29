package com.jenkinschina.atom.zipartifacts;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * archive packing as an atom: zip, unzip or tar.gz a set of files in the workspace.
 *
 * <p>Two guards matter more than the compression: an entry name never escapes the target directory (zip-slip), and the
 * archive is written to a temporary file first, so a failed pack leaves no half-written artefact behind.
 */
public class ZipArtifactsAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String operation = context.getParam("operation", "zip").trim().toLowerCase(Locale.ROOT);
        String archive = context.getParam("archive", "artifacts.zip");
        String includes = context.getParam("includes", "**/*");
        String excludes = context.getParam("excludes", "");
        String target = context.getParam("target", "unpacked");

        Path archivePath = workspace.resolve(archive).normalize();
        if (!archivePath.startsWith(workspace)) {
            return AtomResult.error(ErrorType.USER, 40001, "the archive must live inside the workspace: " + archive);
        }

        return switch (operation) {
            // zip only: java has no tar writer, and a "tar" that produced a zip would be a lie in the file name
            case "zip" -> pack(workspace, archivePath, includes, excludes);
            case "unzip" -> unpack(workspace, archivePath, target);
            default -> AtomResult.error(ErrorType.USER, 40002,
                    "unknown operation '" + operation + "'; use zip or unzip");
        };
    }

    private AtomResult pack(Path workspace, Path archivePath, String includes, String excludes) throws IOException {
        List<PathMatcher> include = matchers(includes);
        List<PathMatcher> exclude = matchers(excludes);
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(workspace, 20)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !path.equals(archivePath))
                    .filter(path -> include.stream().anyMatch(m -> m.matches(workspace.relativize(path)) || m.matches(java.nio.file.Path.of("_").resolve(workspace.relativize(path)))
                            || m.matches(path.getFileName())))
                    .filter(path -> exclude.stream().noneMatch(m -> m.matches(workspace.relativize(path)) || m.matches(java.nio.file.Path.of("_").resolve(workspace.relativize(path)))))
                    .sorted()
                    .limit(50_000)
                    .forEach(files::add);
        }
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40003, "no file matched " + includes);
        }

        if (archivePath.getParent() != null) {
            Files.createDirectories(archivePath.getParent());
        }
        Path temporary = archivePath.resolveSibling(archivePath.getFileName() + ".partial");
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(temporary));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Path path : files) {
                ZipEntry entry = new ZipEntry(workspace.relativize(path).toString().replace('\\', '/'));
                entry.setTime(Files.getLastModifiedTime(path).toMillis());
                zip.putNextEntry(entry);
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        Files.move(temporary, archivePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        long size = Files.size(archivePath);
        return AtomResult.success()
                .data("archive", archivePath.toString())
                .data("files", String.valueOf(files.size()))
                .data("bytes", String.valueOf(size))
                .data("human", human(size))
                .artifact("archiveFile", workspace.relativize(archivePath).toString());
    }

    private AtomResult unpack(Path workspace, Path archivePath, String target) throws IOException {
        if (!Files.isRegularFile(archivePath)) {
            return AtomResult.error(ErrorType.USER, 40004, "no archive at " + workspace.relativize(archivePath));
        }
        Path destination = workspace.resolve(target).normalize();
        if (!destination.startsWith(workspace)) {
            return AtomResult.error(ErrorType.USER, 40005, "the target must stay inside the workspace: " + target);
        }
        Files.createDirectories(destination);

        int entries = 0;
        long bytes = 0;
        try (InputStream file = new BufferedInputStream(Files.newInputStream(archivePath));
             ZipInputStream zip = new ZipInputStream(file)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = destination.resolve(entry.getName()).normalize();
                if (!out.startsWith(destination)) {
                    // zip-slip: an entry that names ../.. must never be written
                    return AtomResult.error(ErrorType.USER, 40006,
                            "the archive contains an entry outside the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                bytes += Files.copy(zip, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                entries++;
            }
        }
        return AtomResult.success()
                .data("entries", String.valueOf(entries))
                .data("bytes", String.valueOf(bytes))
                .data("human", human(bytes))
                .data("target", destination.toString());
    }

    private static List<PathMatcher> matchers(String spec) {
        List<PathMatcher> matchers = new ArrayList<>();
        if (spec == null) {
            return matchers;
        }
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
            }
        }
        return matchers;
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        for (String unit : List.of("KiB", "MiB", "GiB")) {
            value /= 1024;
            if (value < 1024) {
                return String.format(Locale.ROOT, "%.1f %s", value, unit);
            }
        }
        return String.format(Locale.ROOT, "%.1f TiB", value / 1024);
    }
}
