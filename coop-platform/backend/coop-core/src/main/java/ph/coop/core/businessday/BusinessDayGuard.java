package ph.coop.core.businessday;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * While the branch has no open business day, the transaction screens are for looking only: every change there ({@link BusinessDayGuardRules}) is refused
 * with 409 and {@link BusinessDay#NOT_STARTED}, before it reaches the screen's own code, so not even a draft is saved. The places that post money or move
 * stock check it again themselves ({@link BusinessDay#postingDate}, {@link BusinessDay#requireStarted}), whatever way they are reached.
 * <p>
 * The branch is the signed-in person's own (the old system's access table; 1 for the bootstrap account). Off where business days are not in use
 * ({@link BusinessDay#inUse}: the backend tests, or a database without the business day tables).
 * Runs after sign-in is checked (a plain filter comes after Spring Security's).
 */
@Component
public class BusinessDayGuard extends OncePerRequestFilter {

    /** In the refusal, so a screen can tell this refusal from others. */
    public static final String CODE = "BUSINESS_DAY_NOT_STARTED";

    private final BusinessDay businessDay;
    private final JdbcTemplate jdbc;

    public BusinessDayGuard(BusinessDay businessDay, JdbcTemplate jdbc) {
        this.businessDay = businessDay;
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !BusinessDayGuardRules.isTransaction(req.getMethod(), req.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && businessDay.inUse() && businessDay.openDay(branchOf(auth.getName())).isEmpty()) {
            res.setStatus(HttpServletResponse.SC_CONFLICT);
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"message\":\"" + BusinessDay.NOT_STARTED.replace("\"", "\\\"") + "\",\"code\":\"" + CODE + "\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private int branchOf(String username) {
        List<Integer> b = jdbc.query("SELECT AccessBR_CODE FROM access WHERE AccessUserName = ? LIMIT 1", (rs, i) -> rs.getInt(1), username);
        return b.isEmpty() || b.get(0) <= 0 ? 1 : b.get(0);
    }
}
