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
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
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
 *   <li>People and their enabled/disabled state come from the legacy {@code access} table. A switched-off person with the right password is refused with
 *       {@code X-Auth-Reason: ACCOUNT_DISABLED}, so the app can say so; with a wrong password they get the usual answer.</li>
 *   <li>Passwords are new: hashes in {@code ecis_user_credential}, because the legacy ones cannot be verified.</li>
 *   <li>Roles: {@code ROLE_ADMIN} for legacy ADMIN/SUPERADMIN levels, {@code PERM_MEMBER_EDIT} from the legacy
 *       "can edit client" flag, and any role granted in {@code ecis_user_role} (such as MEMBERSHIP_OFFICER).</li>
 *   <li>A password an administrator issued is temporary: it stops working after its expiry (the sign-in answer carries {@code X-Auth-Reason: TEMPORARY_PASSWORD_EXPIRED}),
 *       and until the person replaces it they can reach only their own sign-in details and the password change.</li>
 *   <li>Ten wrong passwords for a name within 15 minutes lock it for 15 minutes ({@link LoginAttempts}); an administrator can lift the lock.</li>
 *   <li>The env-configured dev user remains as a bootstrap administrator, used to set up the first passwords.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginAttempts attempts) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                // what the sign-in screen shows before anyone has signed in: the cooperative's name, tagline and logo
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/public/**").permitAll()
                .anyRequest().authenticated())
            .httpBasic(b -> b.authenticationEntryPoint(entryPoint(attempts)))
            .addFilterAfter(new TemporaryPasswordGate(), BasicAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Lets the desktop app, which is served from its own address (tauri://localhost, http://tauri.localhost), call a server at another address that a person chose on the sign-in screen.
     * Only the listed origins ({@code coop.cors.allowed-origins}, comma separated) may; sign-in is a header, not a cookie, so no credentials ride along by themselves.
     */
    @Bean
    org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource(
            @Value("${coop.cors.allowed-origins:tauri://localhost,http://tauri.localhost,https://tauri.localhost,http://localhost:5173}") List<String> origins) {
        var c = new org.springframework.web.cors.CorsConfiguration();
        c.setAllowedOrigins(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", ph.coop.core.accounting.ledger.RequestKeys.HEADER));
        c.setExposedHeaders(List.of("X-Auth-Reason", "X-Auth-Attempts-Left", "Retry-After", "Content-Disposition"));
        c.setMaxAge(3600L);
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }

    /** The usual 401, plus a reason when the password was right but the temporary one has run out, so the sign-in screen can say so. */
    BasicAuthenticationEntryPoint entryPoint(LoginAttempts attempts) {
        BasicAuthenticationEntryPoint e = new BasicAuthenticationEntryPoint() {
            @Override
            public void commence(HttpServletRequest request, HttpServletResponse response, org.springframework.security.core.AuthenticationException ex) throws IOException {
                String who = ex.getAuthenticationRequest() == null ? null : ex.getAuthenticationRequest().getName();
                // The person could not be checked at all (the database did not answer, a connection dropped): not a refusal, so not a 401, which would
                // sign the person out of the window. A 503 says the server is busy; the next request tries again.
                if (ex instanceof org.springframework.security.authentication.AuthenticationServiceException) {
                    Throwable root = ex;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    LOG.warn("Could not check the sign-in of {} on {} {}: {}: {}", who, request.getMethod(), request.getRequestURI(), root.getClass().getSimpleName(), root.getMessage());
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"The server could not check your sign-in just now because its database did not answer in time."
                        + " You are still signed in; nothing was saved. Try again in a moment.\",\"code\":\"SIGN_IN_CHECK_FAILED\"}");
                    return;
                }
                // A refusal of someone the server knew was signed in or signing in: written to the log (name and kind only, never the password),
                // so "You were signed out" can be traced.
                if (who != null && !who.isBlank()) LOG.info("Sign-in refused for {} on {} {}: {}", who, request.getMethod(), request.getRequestURI(), ex.getClass().getSimpleName());
                if (ex instanceof CredentialsExpiredException) response.setHeader("X-Auth-Reason", "TEMPORARY_PASSWORD_EXPIRED");
                // only ever raised after the password was checked (see authProvider), so it tells nothing to someone who merely typed a name
                if (ex instanceof DisabledException) response.setHeader("X-Auth-Reason", "ACCOUNT_DISABLED");
                if (ex instanceof LockedException) {
                    response.setHeader("X-Auth-Reason", "ACCOUNT_LOCKED");
                    String typed = ex.getAuthenticationRequest() == null ? null : ex.getAuthenticationRequest().getName();
                    response.setHeader("Retry-After", String.valueOf(Math.max(1, attempts.secondsLeft(typed))));
                }
                if (ex instanceof org.springframework.security.authentication.BadCredentialsException) {
                    String typed = ex.getAuthenticationRequest() == null ? null : ex.getAuthenticationRequest().getName();
                    int left = attempts.isLocked(typed) ? 0 : attempts.remaining(typed);
                    if (left <= LoginAttempts.SHOW_COUNT_FROM) response.setHeader("X-Auth-Attempts-Left", String.valueOf(left));
                    if (attempts.isLocked(typed)) {
                        response.setHeader("X-Auth-Reason", "ACCOUNT_LOCKED");
                        response.setHeader("Retry-After", String.valueOf(Math.max(1, attempts.secondsLeft(typed))));
                    }
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

    /**
     * The usual password check, with one change in order: Spring checks "switched off" before the password, which would tell anyone who types a name
     * that the account exists and is switched off. Here the lock is checked first (as before), then the password, and only then whether the account is
     * switched off or its temporary password has run out, so those reasons reach only the person who knows the password.
     */
    @Bean
    DaoAuthenticationProvider authProvider(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider(users);
        p.setPasswordEncoder(encoder);
        p.setPreAuthenticationChecks((u) -> {
            if (!u.isAccountNonLocked()) throw new LockedException("This sign-in is locked.");
        });
        p.setPostAuthenticationChecks((u) -> {
            if (!u.isEnabled()) throw new DisabledException("This account is switched off.");
            if (!u.isCredentialsNonExpired()) throw new CredentialsExpiredException("The temporary password has run out.");
        });
        return p;
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
            if (username.equals(devUser)) return User.withUsername(devUser).password(devHash).roles("ADMIN", "SUPERADMIN").accountLocked(attempts.isLocked(devUser)).build();
            List<UserDetails> found = jdbc.query("""
                    SELECT a.AccessUserName, c.password_hash, a.AccessLevel, a.AccessCanEditClient, c.must_change, c.expires_at, a.AccessCanEditClientImages,
                           a.AccessIsDisabled
                    FROM access a JOIN ecis_user_credential c ON c.username = a.AccessUserName
                    WHERE a.AccessUserName = ?
                    LIMIT 1
                    """,
                (rs, i) -> {
                    List<GrantedAuthority> roles = new ArrayList<>();
                    roles.add(new SimpleGrantedAuthority("ROLE_USER"));
                    String level = rs.getString(3);
                    if ("ADMIN".equalsIgnoreCase(level) || "SUPERADMIN".equalsIgnoreCase(level)) roles.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    if ("SUPERADMIN".equalsIgnoreCase(level)) roles.add(new SimpleGrantedAuthority("ROLE_SUPERADMIN"));
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
                    // switched off by an administrator: refused, with the reason given only once the password is right (see authProvider)
                    boolean disabled = rs.getInt(8) == 1;
                    return User.withUsername(name).password(rs.getString(2)).authorities(roles).credentialsExpired(expired).accountLocked(attempts.isLocked(name)).disabled(disabled).build();
                }, username);
            if (found.isEmpty()) throw new UsernameNotFoundException("Unknown user");
            return found.get(0);
        };
    }
}
