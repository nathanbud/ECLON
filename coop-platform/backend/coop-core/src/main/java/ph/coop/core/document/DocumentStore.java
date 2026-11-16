package ph.coop.core.document;

import java.io.IOException;

/**
 * Where the file contents are kept. Today a folder on the server ({@link LocalDocumentStore}); the guide names MinIO or any S3-compatible store, which implements the same
 * three calls. A key is written once and never replaced, so a stored file cannot be changed behind the register's back.
 */
public interface DocumentStore {

    /** Stores {@code data} under a new {@code key}; fails if the key already exists. */
    void put(String key, byte[] data) throws IOException;

    /** The stored bytes, or an IOException if there are none. */
    byte[] get(String key) throws IOException;

    boolean exists(String key);
}
