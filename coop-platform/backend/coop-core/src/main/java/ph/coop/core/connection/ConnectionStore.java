package ph.coop.core.connection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ph.coop.core.web.ApiErrors;

/**
 * The saved connections, kept in a small JSON file next to the server ({@code coop.connections.file}, git-ignored). They are not kept in the database itself:
 * the database is what they choose between, and sign-in needs it. The file holds no password. It is written whole, to a temporary file first, so a crash cannot leave half of it.
 */
@Component
public class ConnectionStore {

    /** The connection the server was started with (its own settings), shown when nothing is saved and never editable. */
    public static final String STARTUP_ID = "startup";
    public static final String STARTUP_NAME = "Server startup setting";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Saved(List<DbConnection> connections) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final SecureRandom random = new SecureRandom();

    public ConnectionStore(@Value("${coop.connections.file:./data/connections.json}") String file) {
        this.file = Path.of(file);
    }

    /** All saved connections, oldest first. A missing or unreadable file means none. */
    public synchronized List<DbConnection> all() {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            Saved s = JSON.readValue(Files.readAllBytes(file), Saved.class);
            return s.connections() == null ? List.of() : List.copyOf(s.connections());
        } catch (IOException e) {
            return List.of();
        }
    }

    public synchronized Optional<DbConnection> find(String id) {
        return all().stream().filter((c) -> c.id().equals(id)).findFirst();
    }

    public synchronized Optional<DbConnection> defaultConnection() {
        return all().stream().filter(DbConnection::isDefault).findFirst();
    }

    /** Adds a connection with a new id. The first one saved becomes the default. */
    public synchronized DbConnection add(DbConnection in) {
        List<DbConnection> list = new ArrayList<>(all());
        boolean makeDefault = in.isDefault() || list.isEmpty();
        DbConnection saved = with(newId(list), in, makeDefault);
        if (makeDefault) list.replaceAll(ConnectionStore::notDefault);
        list.add(saved);
        write(list);
        return saved;
    }

    /** Replaces a connection's details. Making it the default un-defaults the others; un-checking the only default is ignored (one is always the default). */
    public synchronized DbConnection update(String id, DbConnection in) {
        List<DbConnection> list = new ArrayList<>(all());
        int at = indexOf(list, id);
        boolean makeDefault = in.isDefault() || list.get(at).isDefault();
        DbConnection saved = with(id, in, makeDefault);
        if (makeDefault) list.replaceAll(ConnectionStore::notDefault);
        list.set(at, saved);
        write(list);
        return saved;
    }

    /** Removes a connection; if it was the default, the first remaining one becomes the default. */
    public synchronized void remove(String id) {
        List<DbConnection> list = new ArrayList<>(all());
        DbConnection gone = list.remove(indexOf(list, id));
        if (gone.isDefault() && !list.isEmpty()) list.set(0, with(list.get(0).id(), list.get(0), true));
        write(list);
    }

    private static DbConnection with(String id, DbConnection c, boolean isDefault) {
        return new DbConnection(id, c.name(), c.host(), c.port(), c.dbName(), c.branchId(), c.branchName(), isDefault);
    }

    private static DbConnection notDefault(DbConnection c) {
        return with(c.id(), c, false);
    }

    private static int indexOf(List<DbConnection> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) return i;
        throw ApiErrors.notFound("There is no such connection.");
    }

    private String newId(List<DbConnection> existing) {
        byte[] b = new byte[4];
        while (true) {
            random.nextBytes(b);
            String id = "c" + HexFormat.of().formatHex(b);
            if (existing.stream().noneMatch((c) -> c.id().equals(id))) return id;
        }
    }

    private void write(List<DbConnection> list) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Saved(list)));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("The connections file could not be written: " + e.getMessage(), e);
        }
    }
}
