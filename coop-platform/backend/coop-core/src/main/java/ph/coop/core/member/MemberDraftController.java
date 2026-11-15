package ph.coop.core.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;

/**
 * A new-member registration saved as a draft before it is sent for approval. A draft belongs to the person who typed it: nobody else can list, open, change or delete it.
 * It keeps the typed fields only, never the photo, signature or fingerprints (those need the member's consent at the time they are taken). Saving and deleting a draft are
 * audited without the values, since a draft is personal data and not yet a record. Needs the "edit client" permission, like registering.
 */
@RestController
@RequestMapping("/api/member-drafts")
@PreAuthorize("hasAuthority('PERM_MEMBER_EDIT')")
class MemberDraftController {

    static final int MAX_DRAFTS = 50;
    static final int MAX_FIELDS = 200;
    static final int MAX_VALUE = 500;
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");

    record Save(Integer branchCode, Integer clientType, Map<String, String> fields) {}

    record Summary(long id, String title, int clientType, String updatedAt) {}

    record Draft(long id, String title, int branchCode, int clientType, Map<String, String> fields, String updatedAt) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final ObjectMapper json;

    MemberDraftController(JdbcTemplate jdbc, Db db, ObjectMapper json) {
        this.jdbc = jdbc;
        this.db = db;
        this.json = json;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** The drafts' heading: the name typed so far, or a note that there is none yet. */
    static String titleOf(Map<String, String> fields) {
        String last = fields.getOrDefault("LName", "").trim();
        String first = (fields.getOrDefault("FName", "").trim() + " " + fields.getOrDefault("MName", "").trim()).trim();
        String name = last.isEmpty() ? first : first.isEmpty() ? last : last + ", " + first;
        if (name.isEmpty()) return "(no name yet)";
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    /** The fields cleaned for keeping: known-shaped column names, text values of a sensible size, blanks dropped. */
    static Map<String, String> cleaned(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) throw bad("There is nothing to save yet.");
        if (fields.size() > MAX_FIELDS) throw bad("Too many fields.");
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : fields.entrySet()) {
            if (e.getKey() == null || !COLUMN.matcher(e.getKey()).matches()) throw bad("A field name is not valid.");
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.length() > MAX_VALUE) throw bad("The text in " + e.getKey() + " is too long.");
            if (!v.isEmpty()) out.put(e.getKey(), v);
        }
        if (out.isEmpty()) throw bad("There is nothing to save yet.");
        return out;
    }

    private String write(Map<String, String> fields) {
        try {
            return json.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw bad("The draft could not be saved.");
        }
    }

    @GetMapping
    public List<Summary> mine(Authentication auth) {
        return jdbc.query("SELECT id, title, client_type, updated_at FROM ecis_member_draft WHERE owner = ? ORDER BY updated_at DESC, id DESC",
            (rs, i) -> new Summary(rs.getLong(1), rs.getString(2), rs.getInt(3), String.valueOf(rs.getTimestamp(4).toLocalDateTime())), auth.getName());
    }

    @GetMapping("/{id}")
    public Draft open(@PathVariable long id, Authentication auth) {
        var rows = jdbc.query("SELECT id, title, branch_code, client_type, fields, updated_at FROM ecis_member_draft WHERE id = ? AND owner = ?", (rs, i) -> {
            try {
                Map<String, String> f = json.readValue(rs.getString(5), new TypeReference<LinkedHashMap<String, String>>() {});
                return new Draft(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), f, String.valueOf(rs.getTimestamp(6).toLocalDateTime()));
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "This draft cannot be read.");
            }
        }, id, auth.getName());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such draft.");
        return rows.get(0);
    }

    @PostMapping
    public Summary create(@RequestBody Save in, Authentication auth) {
        Map<String, String> fields = cleaned(in.fields());
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_draft WHERE owner = ?", Long.class, auth.getName());
        if (count != null && count >= MAX_DRAFTS) throw bad("You already have " + MAX_DRAFTS + " drafts. Delete some before saving another.");
        String title = titleOf(fields);
        int type = in.clientType() == null ? 1 : in.clientType();
        long id = db.insertReturningId("INSERT INTO ecis_member_draft (owner, branch_code, client_type, title, fields, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(), NOW())", "id",
            auth.getName(), in.branchCode() == null ? 1 : in.branchCode(), type, title, write(fields));
        AuditLog.write(jdbc, auth.getName(), "member.draft.save", "member_draft", String.valueOf(id), null, null, "Saved a new-member draft");
        return summary(id, auth);
    }

    @PutMapping("/{id}")
    public Summary update(@PathVariable long id, @RequestBody Save in, Authentication auth) {
        Map<String, String> fields = cleaned(in.fields());
        int n = jdbc.update("UPDATE ecis_member_draft SET title = ?, client_type = ?, fields = ?, updated_at = NOW() WHERE id = ? AND owner = ?",
            titleOf(fields), in.clientType() == null ? 1 : in.clientType(), write(fields), id, auth.getName());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such draft.");
        AuditLog.write(jdbc, auth.getName(), "member.draft.save", "member_draft", String.valueOf(id), null, null, "Saved a new-member draft");
        return summary(id, auth);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id, Authentication auth) {
        int n = jdbc.update("DELETE FROM ecis_member_draft WHERE id = ? AND owner = ?", id, auth.getName());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such draft.");
        AuditLog.write(jdbc, auth.getName(), "member.draft.delete", "member_draft", String.valueOf(id), null, null, "Deleted a new-member draft");
    }

    private Summary summary(long id, Authentication auth) {
        return mine(auth).stream().filter((s) -> s.id() == id).findFirst().orElseThrow();
    }
}
