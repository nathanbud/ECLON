package ph.coop.core.audit;

import ph.coop.core.connection.DatabaseChanges;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Whether the audit trail is intact: when it was last sealed, how much is not sealed yet, and a check of every seal. Administrators only; nothing here can change an audit row. */
@RestController
@RequestMapping("/api/admin/audit/integrity")
@PreAuthorize("hasRole('ADMIN')")
class AuditIntegrityController {

    private final AuditSeals seals;
    private final JdbcTemplate jdbc;

    AuditIntegrityController(AuditSeals seals, JdbcTemplate jdbc) {
        this.seals = seals;
        this.jdbc = jdbc;
    }

    @GetMapping
    AuditSeals.Status status() {
        return seals.status();
    }

    /** Seals what has settled since the last seal (the same work the nightly job does). */
    @PostMapping("/seal")
    AuditSeals.Status seal(Authentication auth) {
        if (!seals.installed()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, DatabaseChanges.notSetUp("the audit seals"));
        AuditSeals.Sealed s = seals.seal(auth.getName());
        // Recorded after the seal, so this row is the first of the next block.
        if (s != null) AuditLog.write(jdbc, auth.getName(), "audit.sealed", "audit_seal", String.valueOf(s.id()), null, s.rows() + " rows, ids " + s.fromId() + " to " + s.toId(), "Audit log sealed by request");
        return seals.status();
    }

    /** Re-reads every sealed block and the chain of seals and reports anything that no longer matches. */
    @PostMapping("/verify")
    AuditSeals.Verification verify() {
        return seals.verify();
    }
}

/** Switches on scheduled jobs (the nightly audit seal). */
@Configuration
@EnableScheduling
class AuditScheduling {}
