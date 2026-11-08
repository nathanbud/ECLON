package ph.coop.core.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupCatalogTest {

    @TempDir Path root;

    private void backup(String name, boolean dump, boolean key) throws IOException {
        Path d = Files.createDirectory(root.resolve(name));
        if (dump) Files.writeString(d.resolve("ecis-legacy.sql"), "-- dump");
        if (key) Files.writeString(d.resolve("biometric.key"), "k");
    }

    @Test
    void aMissingFolderHasNoBackups() throws IOException {
        assertTrue(BackupCatalog.list(root.resolve("nope")).isEmpty());
    }

    @Test
    void backupsAreListedNewestFirstAndOtherFoldersAreIgnored() throws IOException {
        backup("20261105-0900", true, true);
        backup("20261108-0709", true, true);
        Files.createDirectory(root.resolve("notes"));
        Files.writeString(root.resolve("stray.txt"), "x");
        List<BackupCatalog.Entry> all = BackupCatalog.list(root);
        assertEquals(List.of("20261108-0709", "20261105-0900"), all.stream().map(BackupCatalog.Entry::name).toList());
        assertEquals(7 + 1, all.get(0).totalBytes());
    }

    @Test
    void aBackupIsCompleteOnlyWithTheDumpAndTheKey() throws IOException {
        backup("20261101-0100", true, false);
        backup("20261102-0100", false, true);
        backup("20261103-0100", true, true);
        var byName = BackupCatalog.list(root).stream().collect(java.util.stream.Collectors.toMap(BackupCatalog.Entry::name, e -> e));
        assertFalse(byName.get("20261101-0100").complete(), "no key");
        assertFalse(byName.get("20261102-0100").complete(), "no dump");
        assertTrue(byName.get("20261103-0100").complete());
    }

    @Test
    void anEmptyDumpDoesNotCount() throws IOException {
        Path d = Files.createDirectory(root.resolve("20261104-0100"));
        Files.createFile(d.resolve("ecis-legacy.sql"));
        Files.writeString(d.resolve("biometric.key"), "k");
        assertFalse(BackupCatalog.read(d).complete());
    }

    @Test
    void aFolderNotNamedLikeABackupIsNotOne() throws IOException {
        Path d = Files.createDirectory(root.resolve("my-backup"));
        assertNull(BackupCatalog.read(d));
    }
}
