package ph.coop.core.connection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the application's DataSource (Spring Boot's own steps aside). It opens the saved default connection, or the server's own setting ({@code spring.datasource.url}) when none
 * is saved, or when the saved default cannot be reached (then it says so in the log rather than refusing to start).
 */
@Configuration
class ConnectionConfig {

    private static final Logger log = LoggerFactory.getLogger(ConnectionConfig.class);

    @Bean(destroyMethod = "close")
    SwitchableDataSource dataSource(ConnectionStore store,
                                    @Value("${spring.datasource.url}") String startupUrl,
                                    @Value("${spring.datasource.username}") String user,
                                    @Value("${spring.datasource.password}") String password,
                                    @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {
        var saved = store.defaultConnection();
        if (saved.isPresent()) {
            DbConnection c = saved.get();
            String engine = ConnectionRules.engineOf(startupUrl);
            String url = ConnectionRules.jdbcUrl(engine, c.host(), c.port(), c.dbName(), false);
            try (Connection ignored = DriverManager.getConnection(ConnectionRules.jdbcUrl(engine, c.host(), c.port(), c.dbName(), true), user, password)) {
                return new SwitchableDataSource(c.id(), url, user, password, poolSize);
            } catch (SQLException | RuntimeException e) {
                log.warn("The default connection \"{}\" could not be opened ({}); starting on the server's own setting instead.", c.name(), e.getMessage());
            }
        }
        return new SwitchableDataSource(ConnectionStore.STARTUP_ID, startupUrl, user, password, poolSize);
    }
}
