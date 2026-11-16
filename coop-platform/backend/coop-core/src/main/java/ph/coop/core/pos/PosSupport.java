package ph.coop.core.pos;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Who may do what at the point of sale: cashiers sell and ask for returns, supervisors set prices and decide returns, administrators may do either but never decide their own work. */
@Component
class PosSupport {

    private static boolean has(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("ROLE_" + role));
    }

    boolean isSupervisor(Authentication auth) {
        return has(auth, "ADMIN") || has(auth, "POS_SUPERVISOR");
    }

    void requireSeller(Authentication auth) {
        if (!has(auth, "ADMIN") && !has(auth, "POS_CASHIER") && !has(auth, "POS_SUPERVISOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You need the POS Cashier role for this.");
    }

    void requireSupervisor(Authentication auth) {
        if (!isSupervisor(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You need the POS Supervisor role for this.");
    }
}
