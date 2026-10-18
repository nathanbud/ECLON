package ph.coop.core.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic sign-in until Keycloak arrives (IAM phase).
 * <ul>
 *   <li>People and their enabled/disabled state come from the legacy {@code access} table.</li>
 *   <li>Passwords are new: hashes in {@code ecis_user_credential}, because the legacy ones cannot be verified.</li>
 *   <li>Roles: {@code ROLE_ADMIN} for legacy ADMIN/SUPERADMIN levels, {@code PERM_MEMBER_EDIT} from the legacy
 *       "can edit client" flag, and any role granted in {@code ecis_user_role} (such as MEMBERSHIP_OFFICER).</li>
 *   <li>The env-configured dev user remains as a bootstrap administrator, used to set up the first passwords.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(@Value("${coop.dev-auth.username}") String devUser,
                             @Value("${coop.dev-auth.password}") String devPassword,
                             PasswordEncoder encoder, JdbcTemplate jdbc) {
        String devHash = encoder.encode(devPassword);
        return (username) -> {
            // A fresh object each time: Spring wipes the password from the user it authenticated, so a shared one
            // would stop working after the first sign-in.
            if (username.equals(devUser)) return User.withUsername(devUser).password(devHash).roles("ADMIN").build();
            List<UserDetails> found = jdbc.query("""
                    SELECT a.AccessUserName, c.password_hash, a.AccessLevel, a.AccessCanEditClient
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
                    String name = rs.getString(1); // the name as stored, whatever case was typed
                    jdbc.query("SELECT role FROM ecis_user_role WHERE username = ?",
                        (r) -> { roles.add(new SimpleGrantedAuthority("ROLE_" + r.getString(1))); }, name);
                    return User.withUsername(name).password(rs.getString(2)).authorities(roles).build();
                }, username);
            if (found.isEmpty()) throw new UsernameNotFoundException("Unknown or disabled user");
            return found.get(0);
        };
    }
}
