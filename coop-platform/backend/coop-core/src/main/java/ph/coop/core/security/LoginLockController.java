package ph.coop.core.security;


import ph.coop.core.audit.AuditLog;import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.config.LoginAttempts;

/** Who is locked out after wrong passwords, and the administrator's way to lift a lock. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
class LoginLockController {

    record Locked(String username, long secondsLeft) {}

    private final LoginAttempts attempts;
    private final JdbcTemplate jdbc;

    LoginLockController(LoginAttempts attempts, JdbcTemplate jdbc) {
        this.attempts = attempts;
        this.jdbc = jdbc;
    }

    @GetMapping("/locked-users")
    List<Locked> locked() {
        return attempts.locked().entrySet().stream().map((e) -> new Locked(e.getKey(), e.getValue())).toList();
    }

    @PostMapping("/users/{username}/unlock")
    Locked unlock(@PathVariable String username, Authentication auth) {
        if (!attempts.unlock(username)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That sign-in is not locked, so there is nothing to unlock: the person can sign in. If they still cannot, check their password or whether their account is switched on.");
        AuditLog.write(jdbc, auth.getName(), "signin.unlocked", "user", username, null, "lock lifted by an administrator", null);
        return new Locked(username.toLowerCase(), 0);
    }
}
