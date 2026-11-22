package ph.coop.core.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * The one DataSource of the application, pointing at one database at a time. {@link #switchTo} opens a pool on the new database, proves it answers, and only then swaps it in and
 * retires the old pool; a switch that fails leaves the application on the database it was on. Connections already handed out finish on the old pool.
 */
public class SwitchableDataSource extends AbstractDataSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SwitchableDataSource.class);

    private final String user;
    private final String password;
    private final int poolSize;
    private volatile HikariDataSource current;
    private volatile String activeId;

    public SwitchableDataSource(String id, String url, String user, String password, int poolSize) {
        this.user = user;
        this.password = password;
        this.poolSize = poolSize;
        this.current = open(url);
        this.activeId = id;
    }

    /** Id of the connection in use now ({@link ConnectionStore#STARTUP_ID} for the server's own setting). */
    public String activeId() {
        return activeId;
    }

    /** The JDBC address of the database in use now (no user or password in it). */
    public String activeUrl() {
        return current.getJdbcUrl();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return current.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return current.getConnection();
    }

    /** Moves the application to another database. Throws (and changes nothing) if the new one cannot be reached. */
    public synchronized void switchTo(String id, String url) throws SQLException {
        HikariDataSource next;
        try {
            next = open(url);
        } catch (RuntimeException e) {
            throw new SQLException(e.getMessage(), e);
        }
        HikariDataSource old = current;
        current = next;
        activeId = id;
        log.info("Database connection switched to {}", id);
        retire(old);
    }

    private HikariDataSource open(String url) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        c.setUsername(user);
        c.setPassword(password);
        c.setMaximumPoolSize(poolSize);
        c.setPoolName("coop-" + Integer.toHexString(System.identityHashCode(url)));
        return new HikariDataSource(c); // connects now, so an unreachable database fails here
    }

    /** Lets work in progress on the old pool finish, then closes it. */
    private static void retire(HikariDataSource old) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            old.close();
        }, "coop-retire-pool");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() {
        current.close();
    }
}
