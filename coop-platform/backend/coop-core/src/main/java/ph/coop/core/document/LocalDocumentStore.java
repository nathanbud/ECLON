package ph.coop.core.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keeps documents as files under one folder ({@code coop.documents.root}, default {@code ./data/documents}). Keys cannot climb out of it, and a key is written only once. */
@Component
class LocalDocumentStore implements DocumentStore {

    private final Path root;

    LocalDocumentStore(@Value("${coop.documents.root:./data/documents}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    private Path resolve(String key) throws IOException {
        if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/") || key.contains("\\") || !key.matches("[A-Za-z0-9/_.-]+")) throw new IOException("Not a valid document key.");
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IOException("Not a valid document key.");
        return p;
    }

    @Override
    public void put(String key, byte[] data) throws IOException {
        Path p = resolve(key);
        Files.createDirectories(p.getParent());
        Files.write(p, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public byte[] get(String key) throws IOException {
        return Files.readAllBytes(resolve(key));
    }

    @Override
    public boolean exists(String key) {
        try {
            return Files.isRegularFile(resolve(key));
        } catch (IOException e) {
            return false;
        }
    }
}
