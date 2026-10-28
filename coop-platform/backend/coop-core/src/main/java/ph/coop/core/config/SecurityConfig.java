package ph.coop.core.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP Basic sign-in until Keycloak arrives (IAM phase).
 * <ul>
 *   <li>People and their enabled/disabled state come from the legacy {@code access} table.</li>
 *   <li>Passwords are new: hashes in {@code ecis_user_credential}, because the legacy ones cannot be verified.</li>
 *   <li>Roles: {@code ROLE_ADMIN} for legacy ADMIN/SUPERADMIN levels, {@code PERM_MEMBER_EDIT} from the legacy
 *       "can edit client" flag, and any role granted in {@code ecis_user_role} (such as MEMBERSHIP_OFFICER).</li>
 *   <li>A password an administrator issued is temporary: it stops working after its expiry (the sign-in answer carries {@code X-Auth-Reason: TEMPORARY_PASSWORD_EXPIRED}),
 *       and until the person replaces it they can reach only their own sign-in details and the password change.</li>
 *   <li>Five wrong passwords for a name within 15 minutes lock it for 15 minutes ({@link LoginAttempts}); an administrator can lift the lock.</li>
 *   <li>The env-configured dev user remains as a bootstrap administrator, used to set up the first passwords.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginAttempts attempts) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .httpBasic(b -> b.authenticationEntryPoint(entryPoint(attempts)))
            .addFilterAfter(new TemporaryPasswordGate(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /** The usual 401, plus a reason when the password was right but the temporary one has run out, so the sign-in screen can say so. */
    private BasicAuthenticationEntryPoint entryPoint(LoginAttempts attempts) {
        BasicAuthenticationEntryPoint e = new BasicAuthenticationEntryPoint() {
            @Override
            public void commence(HttpServletRequest request, HttpServletResponse response, org.springframework.security.core.AuthenticationException ex) throws IOException {
                if (ex instanceof CredentialsExpiredException) response.setHeader("X-Auth-Reason", "TEMPORARY_PASSWORD_EXPIRED");
                if (ex instanceof LockedException) {
                    response.setHeader("X-Auth-Reason", "ACCOUNT_LOCKED");
                    String typed = ex.getAuthenticationRequest() == null ? null : ex.getAuthenticationRequest().getName();
                    response.setHeader("Retry-After", String.valueOf(Math.max(1, attempts.secondsLeft(typed))));
                }
                super.commence(request, response, ex);
            }
        };
        e.setRealmName("Coop Platform");
        return e;
    }

    /** Someone on a temporary password may only look at who they are and replace it. Everything else is refused until they have. */
    static final class TemporaryPasswordGate extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            boolean restricted = auth != null && auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals("PWD_CHANGE_REQUIRED"));
            String path = req.getRequestURI();
            if (restricted && !path.equals("/api/me") && !path.equals("/api/me/password") && !path.equals("/actuator/health")) {
                res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                res.setContentType("application/json");
                res.getWriter().write("{\"message\":\"Replace your temporary password first.\",\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
                return;
            }
            chain.doFilter(req, res);
        }
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(@Value("${coop.dev-auth.username}") String devUser,
                             @Value("${coop.dev-auth.password}") String devPassword,
                             PasswordEncoder encoder, JdbcTemplate jdbc, Clock clock, LoginAttempts attempts) {
        String devHash = encoder.encode(devPassword);
        return (username) -> {
            // A fresh object each time: Spring wipes the password from the user it authenticated, so a shared one
            // would stop working after the first sign-in.
            if (username.equals(devUser)) return User.withUsername(devUser).password(devHash).roles("ADMIN").accountLocked(attempts.isLocked(devUser)).build();
            List<UserDetails> found = jdbc.query("""
                    SELECT a.AccessUserName, c.password_hash, a.AccessLevel, a.AccessCanEditClient, c.must_change, c.expires_at, a.AccessCanEditClientImages
                    FROM access a JOIN ecis_user_credential c ON c.username = a.AccessUserName
                    WHERE a.AccessUserName = ? AND (a.AccessIsDisabled = 0 OR a.AccessIsDisabled IS NULL)
                    LIMIT 1
                    """,
                (rs, i) -> {
                    List<GrantedAuthority> roles = new ArrayList<>();
                    roles.add(new SimpleGrantedAuthority("ROLE_USER"));
                    String level = rs.getString(3);
                    if ("ADMIN".equalsIgnoreCase(level) || "SUPERADMIN".equalsIgnoreCase(level)) roles.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    if (rs.getInt(4) == 1) roles.add(new SimpleGrantedAuthority("PERM_MEMBER_EDIT"));
                    if (rs.getInt(7) == 1) roles.add(new SimpleGrantedAuthority("PERM_MEMBER_IMAGES"));
                    String name = rs.getString(1); // the name as stored, whatever case was typed
                    jdbc.query("SELECT role FROM ecis_user_role WHERE username = ?",
                        (r) -> { roles.add(new SimpleGrantedAuthority("ROLE_" + r.getString(1))); }, name);
                    // a temporary password: usable only to replace it, and not at all once it has run out
                    boolean temporary = rs.getInt(5) == 1;
                    Timestamp expires = rs.getTimestamp(6);
                    boolean expired = temporary && expires != null && expires.toLocalDateTime().isBefore(LocalDateTime.now(clock));
                    if (temporary && !expired) roles.add(new SimpleGrantedAuthority("PWD_CHANGE_REQUIRED"));
                    return User.withUsername(name).password(rs.getString(2)).authorities(roles).credentialsExpired(expired).accountLocked(attempts.isLocked(name)).build();
                }, username);
            if (found.isEmpty()) throw new UsernameNotFoundException("Unknown or disabled user");
            return found.get(0);
        };
    }
}
