package ph.coop.core.connection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;

/**
 * Applies the database changes the connected PostgreSQL database is missing, from the application (Settings > System > Database connection and the notice on
 * Settings, super administrators). The application's own database user cannot change permissions or tables, so the super administrator gives a database
 * administrator's name and password: they are used for one connection to the database in use now, then dropped (never stored, logged or sent back).
 * <p>Works as scripts/migrate-postgres.ps1 does: the changes packaged into this build ({@link SchemaCheck#files}) that {@link SchemaRules} finds missing are run
 * lowest number first, each in one transaction together with its row in legacy.ecis_pg_migration (how = APPLIED, the file's SHA-256), so a change that fails
 * changes nothing and stops the run. Each applied change is audited as {@code schema.apply}, a failure as {@code schema.apply.failed}. Afterwards every
 * {@link OnConnectionSwitch} is told, so caches of what the database has (tables, grants) are read again.
 */
@RestController
public class SchemaApply {

    private static final Logger log = LoggerFactory.getLogger(SchemaApply.class);
    static final String LOG_TABLE = "CREATE TABLE IF NOT EXISTS legacy.ecis_pg_migration (version varchar(20) PRIMARY KEY, file_name varchar(200) NOT NULL,"
        + " applied_at timestamp NOT NULL DEFAULT now(), checksum char(64) NOT NULL, how varchar(10) NOT NULL)";

    public record Apply(String adminUser, String adminPassword, String reason) {}

    public record Result(List<String> applied, String failedFile, String message, SchemaCheck.Status status) {}

    private final SwitchableDataSource dataSource;
    private final SchemaCheck schemaCheck;
    private final JdbcTemplate jdbc;
    private final List<OnConnectionSwitch> listeners;

    SchemaApply(SwitchableDataSource dataSource, SchemaCheck schemaCheck, JdbcTemplate jdbc, @Lazy List<OnConnectionSwitch> listeners) {
        this.dataSource = dataSource;
        this.schemaCheck = schemaCheck;
        this.jdbc = jdbc;
        this.listeners = listeners;
    }

    @PostMapping("/api/admin/schema-status/apply")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public synchronized Result apply(@RequestBody Apply a, Authentication auth) {
        SchemaCheck.Status before = schemaCheck.check();
        String dbName = before.dbName();
        List<String> problems = SchemaApplyRules.problems(before.state(), dbName, a.adminUser(), a.adminPassword(), a.reason());
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        String url = dataSource.activeUrl();
        if (url == null || !url.startsWith("jdbc:postgresql:"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This database is not PostgreSQL, so its changes are applied with scripts/migrate.ps1 on the server, not from here.");

        Map<String, byte[]> files;
        try {
            files = SchemaCheck.files();
        } catch (IOException e) {
            throw new IllegalStateException("The database changes packaged into this build could not be read", e);
        }
        String reason = a.reason().trim();
        List<String> applied = new ArrayList<>();
        String failedFile = null;
        String message;
        Properties login = new Properties();
        login.setProperty("user", a.adminUser().trim());
        login.setProperty("password", a.adminPassword());
        try (Connection c = DriverManager.getConnection(url, login)) {
            try (Statement s = c.createStatement()) {
                s.execute(LOG_TABLE);
            }
            c.setAutoCommit(false);
            for (String file : SchemaApplyRules.order(before.missing())) {
                byte[] bytes = files.get(file);
                if (bytes == null) continue; // listed by the check, so packaged; skipped rather than guessed if not
                try {
                    try (Statement s = c.createStatement()) {
                        s.execute(new String(bytes, StandardCharsets.UTF_8));
                    }
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO legacy.ecis_pg_migration (version, file_name, checksum, how) VALUES (?, ?, ?, 'APPLIED')")) {
                        p.setString(1, file.substring(0, file.indexOf('_')));
                        p.setString(2, file);
                        p.setString(3, SchemaCheck.sha256(bytes));
                        p.executeUpdate();
                    }
                    c.commit();
                    applied.add(file);
                } catch (SQLException e) {
                    c.rollback();
                    failedFile = file;
                    log.warn("Database change {} could not be applied to {} and was rolled back ({}): {}", file, dbName, e.getSQLState(), e.getMessage());
                    throw e;
                }
            }
            message = applied.isEmpty() ? "There was nothing to apply." : "Applied " + String.join(", ", applied) + " to " + dbName + ".";
        } catch (SQLException e) {
            if (failedFile == null) log.warn("Could not apply database changes to {} ({}): {}", dbName, e.getSQLState(), e.getMessage());
            message = SchemaApplyRules.failure(e.getSQLState(), failedFile == null ? "The change" : failedFile, applied.size());
        }

        for (String f : applied) AuditLog.write(jdbc, auth.getName(), "schema.apply", "database", dbName, null, f, reason);
        if (failedFile != null) AuditLog.write(jdbc, auth.getName(), "schema.apply.failed", "database", dbName, null, failedFile, reason);
        if (!applied.isEmpty()) {
            for (OnConnectionSwitch l : listeners) {
                try {
                    l.connectionSwitched();
                } catch (RuntimeException e) {
                    log.warn("{} could not reset after the database changes: {}", l.getClass().getSimpleName(), e.getMessage());
                }
            }
            AuditLog.forgetShape();
        }
        SchemaCheck.Status after = schemaCheck.check();
        if (applied.isEmpty() && failedFile == null && !message.startsWith("There was")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        return new Result(applied, failedFile, message, after);
    }
}
