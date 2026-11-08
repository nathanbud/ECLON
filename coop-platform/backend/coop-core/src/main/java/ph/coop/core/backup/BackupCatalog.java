package ph.coop.core.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the folder that {@code scripts/backup-app-data.ps1} fills: one folder per backup, named {@code yyyyMMdd-HHmm}, holding the dump
 * ({@code ecis-*.sql}) and the biometric key. Read only; a folder whose name is not a date is not a backup and is ignored.
 */
final class BackupCatalog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    record FileInfo(String name, long bytes) {}

    /** One backup. {@code complete} is true when it holds a dump and the biometric key (the two must be kept together). */
    record Entry(String name, LocalDateTime takenAt, List<FileInfo> files, long totalBytes, boolean hasDump, boolean hasKey) {
        boolean complete() { return hasDump && hasKey; }
    }

    private BackupCatalog() {}

    /** Newest first. An empty list when the folder does not exist yet. */
    static List<Entry> list(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                Entry e = read(d);
                if (e != null) out.add(e);
            }
        }
        out.sort(Comparator.comparing(Entry::takenAt).reversed());
        return out;
    }

    /** The backup in {@code dir}, or null when the folder is not named like one. */
    static Entry read(Path dir) throws IOException {
        String name = dir.getFileName().toString();
        LocalDateTime at;
        try {
            at = LocalDateTime.parse(name, STAMP);
        } catch (DateTimeParseException e) {
            return null;
        }
        List<FileInfo> files = new ArrayList<>();
        long total = 0;
        boolean dump = false, key = false;
        try (Stream<Path> fs = Files.list(dir)) {
            for (Path f : (Iterable<Path>) fs.filter(Files::isRegularFile).sorted()::iterator) {
                long size = Files.size(f);
                String fn = f.getFileName().toString();
                files.add(new FileInfo(fn, size));
                total += size;
                if (fn.startsWith("ecis-") && fn.endsWith(".sql") && size > 0) dump = true;
                if (fn.equals("biometric.key") && size > 0) key = true;
            }
        }
        return new Entry(name, at, files, total, dump, key);
    }
}
