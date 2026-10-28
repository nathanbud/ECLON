package ph.coop.core.document;



import static ph.coop.core.web.ApiErrors.bad;import ph.coop.core.audit.AuditLog;import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * Documents attached to members, loans, suppliers, assets and the rest (guide phase 16). The file goes to the document store under an opaque key; the register holds its name,
 * type, size, SHA-256, classification and retention date. A person sees and downloads only what their roles and the document's classification allow (or what was granted to them);
 * what they cannot read looks like it does not exist. Every upload, download, new version, grant and removal is written to the audit log. A file is never overwritten: a new
 * version supersedes the old one, and a document is removed (hidden, not destroyed) only after its retention date.
 */
@RestController
@RequestMapping("/api/documents")
class DocumentController {

    private static final int BRANCH = 1, LIMIT = 500;

    record Doc(long id, String docNo, String entityType, String entityId, String title, String description, String fileName, String mimeType, long sizeBytes, String sha256, String classification,
               int version, Long supersedesId, String status, LocalDate retentionDate, String createdBy, LocalDateTime createdAt, boolean canManage) {}

    record Detail(Doc document, List<Doc> versions, List<String> grants) {}

    record Reason(String reason) {}

    record GrantIn(String username) {}

    record AccessRow(LocalDateTime at, String username, String action, String detail) {}

    private static final String SELECT = """
            SELECT id, doc_no, entity_type, entity_id, title, description, file_name, mime_type, size_bytes, sha256, classification, version_no, supersedes_id, status, retention_date, created_by, created_at
            FROM ecis_document
            """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final DocumentStore store;
    private final Clock clock;

    DocumentController(JdbcTemplate jdbc, Db db, DocumentStore store, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.store = store;
        this.clock = clock;
    }

    private static Set<String> roles(Authentication auth) {
        Set<String> out = new HashSet<>();
        for (var a : auth.getAuthorities()) out.add(a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority());
        return out;
    }

    private Doc map(java.sql.ResultSet rs, int i, Set<String> roles, String user) throws java.sql.SQLException {
        String by = rs.getString(16);
        long sup = rs.getLong(13);
        Long supersedes = rs.wasNull() ? null : sup;
        return new Doc(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getString(10), rs.getString(11), rs.getInt(12),
            supersedes, rs.getString(14), rs.getDate(15).toLocalDate(), by, rs.getTimestamp(17).toLocalDateTime(), DocumentRules.canManage(roles, by.equalsIgnoreCase(user)));
    }

    private boolean granted(long id, String user) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_document_grant WHERE document_id = ? AND username = ?", Integer.class, id, user);
        return n != null && n > 0;
    }

    private boolean mayRead(Doc d, Authentication auth) {
        return DocumentRules.canRead(d.classification(), d.entityType(), roles(auth), d.createdBy().equalsIgnoreCase(auth.getName()), granted(d.id(), auth.getName()));
    }

    /** The document if it exists and the caller may read it; otherwise "no such document", so a document one may not read does not reveal that it exists. */
    private Doc readable(long id, Authentication auth) {
        Set<String> r = roles(auth);
        List<Doc> found = jdbc.query(SELECT + " WHERE branch_code = ? AND id = ?", (rs, i) -> map(rs, i, r, auth.getName()), BRANCH, id);
        if (found.isEmpty() || !mayRead(found.get(0), auth)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such document.");
        return found.get(0);
    }

    private void audit(Authentication auth, String action, String docNo, String detail) {
        AuditLog.write(jdbc, Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)), auth.getName(), action, "ecis_document", docNo, null, detail, null);
    }

    private static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }


    // ---- reading --------------------------------------------------------------------------------------------------

    /** The documents the caller may read: the current versions of one record, or a search across all of them. */
    @GetMapping
    List<Doc> list(@RequestParam(required = false) String entityType, @RequestParam(required = false) String entityId, @RequestParam(required = false) String q,
                   @RequestParam(defaultValue = "false") boolean history, Authentication auth) {
        Set<String> r = roles(auth);
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        List<Doc> all = jdbc.query(SELECT + """
                 WHERE branch_code = ? AND (? = 1 OR status = 'ACTIVE') AND (? IS NULL OR entity_type = ?) AND (? IS NULL OR entity_id = ?)
                   AND (? IS NULL OR LOWER(title) LIKE ? OR LOWER(file_name) LIKE ? OR LOWER(doc_no) LIKE ? OR LOWER(entity_id) LIKE ?) ORDER BY id DESC LIMIT %d
                """.formatted(LIMIT), (rs, i) -> map(rs, i, r, auth.getName()), BRANCH, history ? 1 : 0, entityType, entityType, entityId, entityId, like, like, like, like, like);
        Set<Long> mine = new HashSet<>(jdbc.queryForList("SELECT document_id FROM ecis_document_grant WHERE username = ?", Long.class, auth.getName()));
        List<Doc> out = new ArrayList<>();
        for (Doc d : all) if (DocumentRules.canRead(d.classification(), d.entityType(), r, d.createdBy().equalsIgnoreCase(auth.getName()), mine.contains(d.id()))) out.add(d);
        return out;
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable long id, Authentication auth) {
        Doc d = readable(id, auth);
        Set<String> r = roles(auth);
        // the chain of versions this one belongs to
        Set<Long> ids = new HashSet<>();
        Long up = d.id();
        while (up != null && ids.add(up)) {
            List<Long> before = jdbc.query("SELECT supersedes_id FROM ecis_document WHERE id = ?", (rs, i) -> { long v = rs.getLong(1); return rs.wasNull() ? null : v; }, up);
            up = before.isEmpty() ? null : before.get(0);
        }
        List<Long> frontier = new ArrayList<>(ids);
        while (!frontier.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (Long f : frontier) for (Long c : jdbc.queryForList("SELECT id FROM ecis_document WHERE supersedes_id = ?", Long.class, f)) if (ids.add(c)) next.add(c);
            frontier = next;
        }
        List<Doc> versions = new ArrayList<>();
        for (Long v : ids) versions.addAll(jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs, i, r, auth.getName()), v));
        versions.sort((a, b) -> Integer.compare(b.version(), a.version()));
        List<String> grants = d.canManage() ? jdbc.queryForList("SELECT username FROM ecis_document_grant WHERE document_id = ? ORDER BY username", String.class, id) : List.of();
        return new Detail(d, versions, grants);
    }

    /** The file itself. Checked against its classification, written to the audit log, never cached, and compared with the hash taken when it was stored. */
    @GetMapping("/{id}/content")
    ResponseEntity<byte[]> content(@PathVariable long id, Authentication auth) {
        Doc d = readable(id, auth);
        byte[] data;
        try {
            data = store.get(storeKey(id));
        } catch (IOException e) {
            audit(auth, "document.download.failed", d.docNo(), "the stored file could not be read");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The stored file could not be read.");
        }
        if (!sha256(data).equals(d.sha256())) {
            audit(auth, "document.integrity.failed", d.docNo(), "the stored file no longer matches its hash");
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "This file no longer matches the fingerprint taken when it was stored, so it is not released. Tell an administrator.");
        }
        audit(auth, "document.download", d.docNo(), d.fileName() + " v" + d.version());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.parseMediaType(d.mimeType()))
            .contentLength(data.length)
            .body(data);
    }

    private String storeKey(long id) {
        return jdbc.queryForObject("SELECT object_key FROM ecis_document WHERE id = ?", String.class, id);
    }

    // ---- writing --------------------------------------------------------------------------------------------------

    private record Checked(String name, String mime, byte[] data, String hash) {}

    private Checked check(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("Choose a file.");
        if (file.getSize() > DocumentRules.MAX_BYTES) throw bad("The file is larger than " + DocumentRules.MAX_BYTES / (1024 * 1024) + " MB.");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw bad("The file could not be read.");
        }
        String name = DocumentRules.cleanName(file.getOriginalFilename());
        List<String> problems = DocumentRules.fileProblems(name, data.length, java.util.Arrays.copyOf(data, Math.min(data.length, 8192)));
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
        return new Checked(name, DocumentRules.MIME.get(DocumentRules.extension(name)), data, sha256(data));
    }

    private String nextNumber(int year) {
        List<String> last = jdbc.queryForList("SELECT doc_no FROM ecis_document WHERE branch_code = ? AND doc_no LIKE ? ORDER BY id DESC LIMIT 1 FOR UPDATE", String.class, BRANCH, "DOC-" + year + "-%");
        int seq = 0;
        if (!last.isEmpty()) {
            try {
                seq = Integer.parseInt(last.get(0).substring(("DOC-" + year + "-").length()));
            } catch (RuntimeException e) {
                seq = 0;
            }
        }
        return String.format("DOC-%d-%06d", year, seq + 1);
    }

    private long insert(Checked f, String entityType, String entityId, String title, String description, String classification, int version, Long supersedes, LocalDate retention, String user) {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        String no = nextNumber(now.getYear());
        String key = String.format("%d/%02d/%s", now.getYear(), now.getMonthValue(), UUID.randomUUID());
        long id = db.insertReturningId("""
                INSERT INTO ecis_document (branch_code, doc_no, entity_type, entity_id, title, description, object_key, file_name, mime_type, size_bytes, sha256, classification, version_no, supersedes_id, status, retention_date, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, "id", BRANCH, no, entityType, entityId, title, description, key, f.name(), f.mime(), (long) f.data().length, f.hash(), classification, version, supersedes, retention, user, Timestamp.valueOf(now));
        try {
            store.put(key, f.data());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The file could not be stored.");
        }
        return id;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    Doc upload(@RequestParam("file") MultipartFile file, @RequestParam String entityType, @RequestParam String entityId, @RequestParam String title,
               @RequestParam(required = false) String description, @RequestParam(required = false) String classification, @RequestParam(required = false) LocalDate retentionDate, Authentication auth) {
        Set<String> r = roles(auth);
        String type = entityType == null ? "" : entityType.trim().toUpperCase();
        if (!DocumentRules.knownEntity(type)) throw bad("The record kind is one of " + String.join(", ", DocumentRules.OWNERS.keySet().stream().sorted().toList()) + ".");
        if (!DocumentRules.canUpload(type, r)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not attach documents to this kind of record.");
        String eid = entityId == null ? "" : entityId.trim();
        if (eid.isEmpty() || eid.length() > 40) throw bad("Give the record number (40 characters at most).");
        if (type.equals("MEMBER") && (!eid.matches("[0-9]{1,9}") || jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, BRANCH, Integer.parseInt(eid)) == 0)) throw bad("No such member.");
        if (title == null || title.isBlank() || title.length() > 120) throw bad("Give the document a title (120 characters at most).");
        if (description != null && description.length() > 255) throw bad("The description is too long (255 characters at most).");
        String cls = classification == null || classification.isBlank() ? DocumentRules.defaultClassification(type) : classification.trim().toUpperCase();
        if (!DocumentRules.CLASSES.contains(cls)) throw bad("The classification is one of " + String.join(", ", DocumentRules.CLASSES) + ".");
        LocalDate today = LocalDate.now(clock);
        LocalDate min = DocumentRules.minimumRetention(type, today);
        if (retentionDate != null && retentionDate.isBefore(min)) throw bad("A " + type.toLowerCase() + " document must be kept until at least " + min + ".");
        Checked f = check(file);
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_document WHERE branch_code = ? AND entity_type = ? AND entity_id = ? AND sha256 = ? AND status = 'ACTIVE'", Integer.class, BRANCH, type, eid, f.hash());
        if (dup != null && dup > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This exact file is already attached to this record.");
        long id = insert(f, type, eid, title.trim(), description == null || description.isBlank() ? null : description.trim(), cls, 1, null, retentionDate == null ? min : retentionDate, auth.getName());
        Doc d = readable(id, auth);
        audit(auth, "document.upload", d.docNo(), type + " " + eid + ", " + d.fileName() + ", " + d.sizeBytes() + " bytes, " + cls + ", sha256 " + d.sha256());
        return d;
    }

    /** Replaces a document with a corrected or renewed file: a new version, the old one kept and marked superseded. */
    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    Doc newVersion(@PathVariable long id, @RequestParam("file") MultipartFile file, Authentication auth) {
        Doc old = readable(id, auth);
        if (!DocumentRules.canUpload(old.entityType(), roles(auth)) && !old.createdBy().equalsIgnoreCase(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not change documents of this kind of record.");
        List<String> st = jdbc.queryForList("SELECT status FROM ecis_document WHERE id = ? FOR UPDATE", String.class, id);
        if (!st.get(0).equals("ACTIVE")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only the current version can be replaced.");
        Checked f = check(file);
        if (f.hash().equals(old.sha256())) throw new ResponseStatusException(HttpStatus.CONFLICT, "This is the same file as the current version.");
        // the new file keeps the document's own name and extension type, so a version cannot quietly change what kind of document it is
        if (!DocumentRules.extension(f.name()).equals(DocumentRules.extension(old.fileName()))) throw bad("A new version must be the same kind of file (." + DocumentRules.extension(old.fileName()) + ").");
        long nid = insert(f, old.entityType(), old.entityId(), old.title(), old.description(), old.classification(), old.version() + 1, old.id(), old.retentionDate(), auth.getName());
        jdbc.update("UPDATE ecis_document SET status = 'SUPERSEDED' WHERE id = ? AND status = 'ACTIVE'", id);
        jdbc.update("INSERT INTO ecis_document_grant (document_id, username, granted_by, granted_at) SELECT ?, username, granted_by, granted_at FROM ecis_document_grant WHERE document_id = ?", nid, id);
        Doc d = readable(nid, auth);
        audit(auth, "document.version", d.docNo(), "replaces " + old.docNo() + ", v" + d.version() + ", sha256 " + d.sha256());
        return d;
    }

    /** Hides a document that has passed its retention date. The file stays in the store: destroying records is a retention workflow of its own, not an everyday button. */
    @PostMapping("/{id}/remove")
    @Transactional
    Doc remove(@PathVariable long id, @RequestBody Reason in, Authentication auth) {
        Doc d = readable(id, auth);
        if (!d.canManage()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who uploaded it, a document clerk or an administrator can remove a document.");
        if (in == null || in.reason() == null || in.reason().isBlank() || in.reason().length() > 255) throw bad("Give the reason (255 characters at most).");
        String problem = DocumentRules.removeProblem(d.status(), d.retentionDate(), LocalDate.now(clock));
        if (problem != null) throw new ResponseStatusException(HttpStatus.CONFLICT, problem);
        jdbc.update("UPDATE ecis_document SET status = 'REMOVED', removed_by = ?, removed_at = ?, remove_reason = ? WHERE id = ? AND status = 'ACTIVE'", auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)), in.reason().trim(), id);
        audit(auth, "document.remove", d.docNo(), in.reason().trim());
        return readable(id, auth);
    }

    // ---- who else may read ----------------------------------------------------------------------------------------

    @PostMapping("/{id}/grants")
    @Transactional
    List<String> grant(@PathVariable long id, @RequestBody GrantIn in, Authentication auth) {
        Doc d = readable(id, auth);
        if (!d.canManage()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who uploaded it, a document clerk or an administrator can share a document.");
        String user = in.username() == null ? "" : in.username().trim();
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM access WHERE AccessBR_CODE = ? AND AccessUserName = ? AND COALESCE(AccessIsDisabled, 0) = 0", Integer.class, BRANCH, user);
        if (user.isEmpty() || exists == null || exists == 0) throw bad("No enabled user called " + user + ".");
        if (granted(id, user)) throw new ResponseStatusException(HttpStatus.CONFLICT, user + " already has access.");
        jdbc.update("INSERT INTO ecis_document_grant (document_id, username, granted_by, granted_at) VALUES (?, ?, ?, ?)", id, user, auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock).withNano(0)));
        audit(auth, "document.grant", d.docNo(), "to " + user);
        return jdbc.queryForList("SELECT username FROM ecis_document_grant WHERE document_id = ? ORDER BY username", String.class, id);
    }

    @DeleteMapping("/{id}/grants/{username}")
    @Transactional
    List<String> revoke(@PathVariable long id, @PathVariable String username, Authentication auth) {
        Doc d = readable(id, auth);
        if (!d.canManage()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who uploaded it, a document clerk or an administrator can change who may read a document.");
        if (jdbc.update("DELETE FROM ecis_document_grant WHERE document_id = ? AND username = ?", id, username) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, username + " has no special access.");
        audit(auth, "document.revoke", d.docNo(), "from " + username);
        return jdbc.queryForList("SELECT username FROM ecis_document_grant WHERE document_id = ? ORDER BY username", String.class, id);
    }

    /** Who uploaded, downloaded, shared or removed this document, newest first (for those who manage it). */
    @GetMapping("/{id}/access")
    List<AccessRow> access(@PathVariable long id, Authentication auth) {
        Doc d = readable(id, auth);
        if (!d.canManage()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who uploaded it, a document clerk or an administrator can see who has used a document.");
        return jdbc.query("SELECT at, username, action, new_value FROM ecis_audit_log WHERE entity = 'ecis_document' AND entity_id = ? ORDER BY id DESC LIMIT 200",
            (rs, i) -> new AccessRow(rs.getTimestamp(1).toLocalDateTime(), rs.getString(2), rs.getString(3), rs.getString(4)), d.docNo());
    }

    /** The kinds of record, the classifications and the minimum keep periods, for the upload form. */
    @GetMapping("/setup")
    Map<String, Object> setup() {
        return Map.of("entityTypes", DocumentRules.OWNERS.keySet().stream().sorted().toList(), "classifications", DocumentRules.CLASSES, "minRetentionYears", DocumentRules.MIN_RETENTION_YEARS,
            "extensions", DocumentRules.MIME.keySet().stream().sorted().toList(), "maxMegabytes", DocumentRules.MAX_BYTES / (1024 * 1024));
    }
}
