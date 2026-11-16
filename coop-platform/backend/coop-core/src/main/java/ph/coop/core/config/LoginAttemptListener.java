package ph.coop.core.config;


import ph.coop.core.audit.AuditLog;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/** Feeds sign-in results to {@link LoginAttempts} and writes an audit row when a name gets locked. */
@Component
class LoginAttemptListener {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptListener.class);

    private final LoginAttempts attempts;
    private final JdbcTemplate jdbc;

    LoginAttemptListener(LoginAttempts attempts, JdbcTemplate jdbc) {
        this.attempts = attempts;
        this.jdbc = jdbc;
    }

    @EventListener
    void wrongPassword(AuthenticationFailureBadCredentialsEvent e) {
        String name = e.getAuthentication().getName();
        if (attempts.failed(name)) {
            log.warn("Sign-in locked after {} wrong passwords: {}", LoginAttempts.MAX_FAILURES, name);
            try {
                AuditLog.write(jdbc, name.length() > 60 ? name.substring(0, 60) : name, "signin.locked", "user", name.length() > 60 ? name.substring(0, 60) : name, null, LoginAttempts.MAX_FAILURES + " wrong passwords, locked for " + LoginAttempts.LOCK.toMinutes() + " minutes", null);
            } catch (RuntimeException ex) {
                log.warn("Could not write the lock to the audit log: {}", ex.getMessage());
            }
        }
    }

    @EventListener
    void signedIn(AuthenticationSuccessEvent e) {
        attempts.succeeded(e.getAuthentication().getName());
    }
}
