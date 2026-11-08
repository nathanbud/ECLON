package ph.coop.core.backup;

import static ph.coop.core.web.ApiErrors.conflict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;

/**
 * Settings > Backup (administrators). Lists the backups on disk and takes a new one by running {@code scripts/backup-app-data.ps1}, the same script
 * that is run by hand: every {@code ecis_} table plus the biometric key. The legacy tables are not included (they are the old system's own data and are
 * backed up as the database). Restoring is deliberately not offered here: it replaces live data and is done by hand with the dump.
 * One backup runs at a time. Each run is audited with its outcome.
 */
@RestController
public class BackupController {

    public record Status(String engine, String folder, boolean scriptFound, boolean running, List<Backup> backups) {}

    public record Backup(String name, String takenAt, long bytes, boolean complete, List<File> files) {}

    public record File(String name, long bytes) {}

    public record RunResult(boolean ok, String message, Backup backup) {}

    private static final long TIMEOUT_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final Path script;
    private final Path root;
    private final String engine;
    private final AtomicBoolean running = new AtomicBoolean(false);

    BackupController(JdbcTemplate jdbc,
                     @Value("${coop.backup.script:../../scripts/backup-app-data.ps1}") String script,
                     @Value("${coop.backup.root:../../../backups}") String root,
                     @Value("${spring.datasource.url}") String datasourceUrl) {
        this.jdbc = jdbc;
        this.script = Path.of(script).toAbsolutePath().normalize();
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.engine = datasourceUrl.startsWith("jdbc:postgresql") ? "postgres" : "mysql";
    }

    @GetMapping("/api/admin/backups")
    @PreAuthorize("hasRole('ADMIN')")
    public Status status() throws IOException {
        return new Status(engine, root.toString(), Files.isRegularFile(script), running.get(), BackupCatalog.list(root).stream().map(BackupController::view).toList());
    }

    @PostMapping("/api/admin/backups")
    @PreAuthorize("hasRole('ADMIN')")
    public RunResult run(Authentication auth) throws IOException {
        if (!Files.isRegularFile(script)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The backup script was not found on the server.");
        if (!running.compareAndSet(false, true)) throw conflict("A backup is already running.");
        String who = auth.getName();
        try {
            String before = newest();
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-Engine", engine);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            boolean finished;
            try {
                finished = p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The backup was interrupted.");
            }
            if (!finished) p.destroyForcibly();
            String text = tail(out.getNow(""));
            String after = newest();
            boolean ok = finished && p.exitValue() == 0 && after != null && !after.equals(before);
            Backup made = ok ? view(BackupCatalog.read(root.resolve(after))) : null;
            if (made != null && !made.complete()) {
                ok = false;
                text = "The backup folder was made but is missing the dump or the biometric key. " + text;
            }
            AuditLog.write(jdbc, who, ok ? "backup.run" : "backup.failed", "backup", ok ? after : "-", null, ok ? engine + ", " + made.bytes() + " bytes" : text, null);
            if (!ok) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, finished ? "The backup failed. " + text : "The backup took longer than " + TIMEOUT_MINUTES + " minutes and was stopped.");
            return new RunResult(true, text, made);
        } finally {
            running.set(false);
        }
    }

    private String newest() throws IOException {
        var all = BackupCatalog.list(root);
        return all.isEmpty() ? null : all.get(0).name();
    }

    /** The last few lines of what the script printed, which is where it says what it did or why it stopped. */
    private static String tail(String text) {
        String[] lines = text.strip().split("\\R");
        int from = Math.max(0, lines.length - 4);
        return String.join(" ", java.util.Arrays.copyOfRange(lines, from, lines.length)).strip();
    }

    private static Backup view(BackupCatalog.Entry e) {
        if (e == null) return null;
        return new Backup(e.name(), e.takenAt().toString(), e.totalBytes(), e.complete(), e.files().stream().map(f -> new File(f.name(), f.bytes())).toList());
    }
}
