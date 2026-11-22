package ph.coop.core.connection;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the connected database has every database change this build needs ({@link SchemaRules}). Checked when the server starts and after every
 * switch of database, and written to the log (a warning when something is missing), so a database that is behind is known at once instead of through
 * screens that fail later with "Something went wrong on the server". Administrators see it on Settings and on the Database connection screen
 * ({@code GET /api/admin/schema-status}).
 * <p>The build's changes are database/postgres/migrations, packaged into the application (see pom.xml), so the check works wherever the server runs.
 */
@Component
@RestController
public class SchemaCheck implements OnConnectionSwitch {

    private static final Logger log = LoggerFactory.getLogger(SchemaCheck.class);
    static final String RESOURCES = "classpath*:db/postgres-migrations/V*.sql";

    private final JdbcTemplate jdbc;
    private volatile Status last;

    public SchemaCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** state as in {@link SchemaRules.State}; missing and changed name the files; checkedAt when it was worked out. */
    public record Status(String state, String database, String message, List<String> missing, List<String> newer, List<String> changed,
                         String lastExpected, String lastRecorded, java.time.LocalDateTime checkedAt) {
        /** The database's name (the same as the record's own accessor; PortableSqlTest takes that call for MySQL's function of the same name). */
        public String dbName() {
            return database;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        report(check());
    }

    @Override
    public void connectionSwitched() {
        // called after the switch: check the new database straight away
        report(check());
    }

    @GetMapping("/api/admin/schema-status")
    @PreAuthorize("hasRole('ADMIN')")
    public Status status() {
        Status s = last;
        return s != null ? s : check();
    }

    /** Works it out now (and remembers it for the screens). Never throws: a failure to check is itself the answer. */
    public Status check() {
        Status s;
        try {
            String database = jdbc.execute((ConnectionCallback<String>) (c) -> c.getCatalog());
            boolean postgres = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) (c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
            if (!postgres) {
                var r = new SchemaRules.Result(SchemaRules.State.NOT_CHECKED, List.of(), List.of(), List.of(), null, null);
                s = status(r, database);
            } else {
                Map<String, String[]> expected = expected();
                Boolean hasLog = jdbc.queryForObject("SELECT to_regclass('legacy.ecis_pg_migration') IS NOT NULL", Boolean.class);
                Map<String, SchemaRules.Recorded> recorded = null;
                if (Boolean.TRUE.equals(hasLog)) {
                    recorded = new LinkedHashMap<>();
                    for (var row : jdbc.queryForList("SELECT version, checksum, how FROM legacy.ecis_pg_migration")) {
                        recorded.put(String.valueOf(row.get("version")).trim(),
                            new SchemaRules.Recorded(row.get("checksum") == null ? null : String.valueOf(row.get("checksum")), String.valueOf(row.get("how"))));
                    }
                }
                s = status(SchemaRules.compare(expected, recorded), database);
            }
        } catch (RuntimeException | IOException e) {
            s = new Status("NOT_CHECKED", null, "The database changes could not be checked: " + e.getMessage(), List.of(), List.of(), List.of(), null, null, java.time.LocalDateTime.now());
        }
        last = s;
        return s;
    }

    private static Status status(SchemaRules.Result r, String database) {
        return new Status(r.state().name(), database, SchemaRules.message(r, database), r.missing(), r.newer(), r.changed(), r.lastExpected(), r.lastRecorded(),
            java.time.LocalDateTime.now());
    }

    /** The changes packaged into this build: version to {file name, SHA-256 of its bytes in upper case hex, as the migration script records it}. */
    static Map<String, String[]> expected() throws IOException {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources(RESOURCES)) {
            String name = r.getFilename();
            if (name == null || !name.matches("V\\d+_.*\\.sql")) continue;
            try (InputStream in = r.getInputStream()) {
                out.put(name.substring(0, name.indexOf('_')), new String[]{name, sha256(in.readAllBytes())});
            }
        }
        return out;
    }

    /** The text of each change packaged into this build, by file name. */
    static Map<String, byte[]> files() throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources(RESOURCES)) {
            String name = r.getFilename();
            if (name == null || !name.matches("V\\d+_.*\\.sql")) continue;
            try (InputStream in = r.getInputStream()) {
                out.put(name, in.readAllBytes());
            }
        }
        return out;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Into the log: a warning when changes are missing or newer, information otherwise; plus a warning for any change edited after it was applied. */
    private static void report(Status s) {
        if ("UP_TO_DATE".equals(s.state()) || "NOT_CHECKED".equals(s.state())) log.info(s.message());
        else log.warn(s.message());
        if (!s.changed().isEmpty()) log.warn("Changed since they were applied (never edit an applied change): {}", String.join(", ", s.changed()));
    }
}
