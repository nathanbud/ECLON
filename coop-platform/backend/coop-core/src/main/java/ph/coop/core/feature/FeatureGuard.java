package ph.coop.core.feature;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses every request to an area this cooperative has switched off ({@link Features}), reading or changing, with 409 and the code {@link #CODE}, so a
 * screen opened from an old link or another window says why instead of failing. Addresses that belong to no area always pass.
 */
@Component
public class FeatureGuard extends OncePerRequestFilter {

    public static final String CODE = "FEATURE_OFF";

    private final Features features;

    FeatureGuard(Features features) {
        this.features = features;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/") || FeatureRules.of(req.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        FeatureRules.Feature f = FeatureRules.of(req.getRequestURI());
        if (f != null && !features.on(f.key())) {
            res.setStatus(HttpServletResponse.SC_CONFLICT);
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"message\":\"" + FeatureRules.refusal(f).replace("\"", "\\\"") + "\",\"code\":\"" + CODE + "\",\"feature\":\"" + f.key() + "\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
