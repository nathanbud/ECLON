package ph.coop.core.connection;

/**
 * One saved database connection: where the legacy database is (host, port, database name) and which branch/office this connection is for.
 * The database user and password are not stored here; they come from the server's own settings, so no secret is written to the connections file.
 *
 * @param id        stable id; {@link ConnectionStore#STARTUP_ID} for the connection the server was started with
 * @param isDefault the connection the server opens when it starts
 */
public record DbConnection(String id, String name, String host, int port, String dbName, Integer branchId, String branchName, boolean isDefault) {}
