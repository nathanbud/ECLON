package ph.coop.core.member;

import ph.coop.core.db.Db;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maker-checker for profile edits to the legacy {@code client} table.
 * <ol>
 *   <li>A person who may edit clients submits changes. Nothing is written to the client yet; the request is stored
 *       as PENDING, with each field's current and proposed value.</li>
 *   <li>A Membership Officer, who is not the person who asked, approves it. Only then is the client updated, and one
 *       audit row per field records who requested and who approved. Or the officer rejects it, with a reason.</li>
 *   <li>The person who asked can withdraw it while it is pending.</li>
 * </ol>
 * Safeguards: only columns in {@link ClientDetailController#EDITABLE} can change and each value is checked by
 * {@link EditRules}; an approval is refused when the client changed after the request was made; every step commits
 * together with its audit row or not at all; and the whole feature is off unless {@code coop.edit.enabled} is true and
 * the audit table exists.
 */
@Service
class MemberChangeService {

    record ChangeItem(String column, String label, String from, String to, String fromText, String toText) {}

    record Change(long id, int branchCode, int clientId, String clientName, String requestedBy, String requestedByName,
                  String requestedAt, String status, List<ChangeItem> items, String reason,
                  String decidedBy, String decidedAt, String decisionNote) {}

    record SubmitRequest(Map<String, String> changes, String expectedUpdatedAt, String reason) {}

    private static final String SELECT = """
        SELECT ch.id, ch.branch_code, ch.client_id,
               COALESCE(NULLIF(c.AccountName, ''), CONCAT_WS(', ', c.LName, c.FName)),
               ch.requested_by, (SELECT CONCAT_WS(' ', a.AccessFName, a.AccessLName) FROM access a WHERE a.AccessUserName = ch.requested_by LIMIT 1),
               ch.requested_at, ch.status, ch.changes, ch.reason, ch.decided_by, ch.decided_at, ch.decision_note
        FROM ecis_member_change ch
        LEFT JOIN client c ON c.ClientIDBrCode = ch.branch_code AND c.ClientID = ch.client_id
        """;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final LookupController lookups;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private volatile Map<String, Integer> lengths;

    MemberChangeService(JdbcTemplate jdbc, Db db, LookupController lookups, ObjectMapper json, PlatformTransactionManager txManager,
                        @Value("${coop.edit.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.db = db;
        this.lookups = lookups;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
    }

    boolean editAvailable() {
        if (!enabled) return false;
        return db.allTablesExist("ecis_audit_log", "ecis_member_change");
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    Change pendingFor(int branchCode, int clientId) {
        List<Change> found = jdbc.query(SELECT + " WHERE ch.branch_code = ? AND ch.client_id = ? AND ch.status = 'PENDING' LIMIT 1",
            (rs, i) -> map(rs), branchCode, clientId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Officers see every request; everyone else sees their own. */
    List<Change> list(String status, String user, boolean officer) {
        String where = " WHERE ch.status = ?" + (officer ? "" : " AND ch.requested_by = ?");
        Object[] args = officer ? new Object[] {status} : new Object[] {status, user};
        return jdbc.query(SELECT + where + " ORDER BY ch.requested_at DESC LIMIT 200", (rs, i) -> map(rs), args);
    }

    int countPending(String user, boolean officer) {
        Integer n = officer
            ? jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_change WHERE status = 'PENDING'", Integer.class)
            : jdbc.queryForObject("SELECT COUNT(*) FROM ecis_member_change WHERE status = 'PENDING' AND requested_by = ?", Integer.class, user);
        return n == null ? 0 : n;
    }

    // ---- steps --------------------------------------------------------------------------------------------------

    Change submit(int branchCode, int clientId, SubmitRequest req, String user) {
        requireAvailable();
        if (req.changes() == null || req.changes().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is nothing to save.");
        Map<String, String> clean = validate(req.changes());

        return tx.execute((status) -> {
            Map<String, String> current = lockClient(branchCode, clientId, clean.keySet());
            if (!Objects.equals(EditRules.stamp(current.get("UpdDateTime")), EditRules.stamp(req.expectedUpdatedAt()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This client was changed by someone else since you opened it. Reload the profile and try again.");
            }
            List<ChangeItem> items = new ArrayList<>();
            for (var e : clean.entrySet()) {
                ClientDetailController.Spec spec = ClientDetailController.EDITABLE.get(e.getKey());
                if (EditRules.same(spec.kind(), spec.how() != null, current.get(e.getKey()), e.getValue())) continue;
                Map<Integer, String> lookup = spec.how() == null ? null : lookups.all().get(spec.how());
                items.add(new ChangeItem(spec.column(), spec.label(), current.get(e.getKey()), e.getValue(),
                    show(lookup, current.get(e.getKey())), show(lookup, e.getValue())));
            }
            if (items.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing was changed.");
            if (pendingFor(branchCode, clientId) != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This client already has a change waiting for approval. Withdraw it, or wait for a decision.");
            }
            long id = db.insertReturningId("INSERT INTO ecis_member_change (branch_code, client_id, requested_by, requested_at, base_updated_at, status, changes, reason) VALUES (?, ?, ?, NOW(), ?, 'PENDING', ?, ?)", "id",
                branchCode, clientId, user, EditRules.stamp(req.expectedUpdatedAt()), write(items), blankToNull(req.reason()));
            audit(user, "CHANGE_SUBMITTED", branchCode + "-" + clientId, null, items.size() + " field(s) for approval", "Change #" + id);
            return load(id);
        });
    }

    Change approve(long id, String approver, boolean isOfficer, String note) {
        requireAvailable();
        return tx.execute((status) -> {
            Change ch = lockPending(id);
            String problem = ChangeRules.approvalProblem(ch.requestedBy(), approver, isOfficer);
            if (problem != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, problem);

            Map<String, String> current = lockClient(ch.branchCode(), ch.clientId(), List.of());
            String base = jdbc.queryForObject("SELECT base_updated_at FROM ecis_member_change WHERE id = ?", String.class, id);
            if (!Objects.equals(EditRules.stamp(current.get("UpdDateTime")), EditRules.stamp(base))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The client was changed after this request was made. Reject it, and ask for it to be submitted again.");
            }

            // Re-check every column against the whitelist; the stored request is data, not trusted instructions.
            for (ChangeItem item : ch.items()) {
                if (!ClientDetailController.EDITABLE.containsKey(item.column())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The field '" + item.column() + "' cannot be edited.");
            }
            String sets = String.join(", ", ch.items().stream().map((i) -> i.column() + " = ?").toList());
            List<Object> args = new ArrayList<>(ch.items().stream().map(ChangeItem::to).toList());
            args.add(ch.branchCode());
            args.add(ch.clientId());
            // UpdDateTime is bumped so the legacy system and any sync job see the record as modified.
            jdbc.update("UPDATE client SET " + sets + ", UpdDateTime = NOW() WHERE ClientIDBrCode = ? AND ClientID = ?", args.toArray());

            String reason = "Change #" + id + " requested by " + ch.requestedBy() + ", approved by " + approver + (blankToNull(note) == null ? "" : ". " + note.trim());
            for (ChangeItem item : ch.items()) {
                audit(approver, "UPDATE:" + item.column(), ch.branchCode() + "-" + ch.clientId(), item.from(), item.to(), reason);
            }
            jdbc.update("UPDATE ecis_member_change SET status = 'APPROVED', decided_by = ?, decided_at = NOW(), decision_note = ? WHERE id = ?", approver, blankToNull(note), id);
            return load(id);
        });
    }

    Change reject(long id, String officer, boolean isOfficer, String note) {
        requireAvailable();
        String problem = ChangeRules.rejectionProblem(isOfficer, note);
        if (problem != null) throw new ResponseStatusException(isOfficer ? HttpStatus.BAD_REQUEST : HttpStatus.FORBIDDEN, problem);
        return tx.execute((status) -> {
            Change ch = lockPending(id);
            jdbc.update("UPDATE ecis_member_change SET status = 'REJECTED', decided_by = ?, decided_at = NOW(), decision_note = ? WHERE id = ?", officer, note.trim(), id);
            audit(officer, "CHANGE_REJECTED", ch.branchCode() + "-" + ch.clientId(), null, null, "Change #" + id + " requested by " + ch.requestedBy() + ". " + note.trim());
            return load(id);
        });
    }

    Change withdraw(long id, String user) {
        requireAvailable();
        return tx.execute((status) -> {
            Change ch = lockPending(id);
            if (!ch.requestedBy().equalsIgnoreCase(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who made the change can withdraw it.");
            jdbc.update("UPDATE ecis_member_change SET status = 'WITHDRAWN', decided_by = ?, decided_at = NOW() WHERE id = ?", user, id);
            audit(user, "CHANGE_WITHDRAWN", ch.branchCode() + "-" + ch.clientId(), null, null, "Change #" + id);
            return load(id);
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void requireAvailable() {
        if (!editAvailable()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Editing is not enabled on this server.");
    }

    /** Checks every submitted value before anything touches the database. */
    private Map<String, String> validate(Map<String, String> changes) {
        Map<String, String> clean = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (var e : changes.entrySet()) {
            ClientDetailController.Spec spec = ClientDetailController.EDITABLE.get(e.getKey());
            if (spec == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The field '" + e.getKey() + "' cannot be edited.");
            Map<Integer, String> lookup = spec.how() == null ? null : lookups.all().get(spec.how());
            try {
                clean.put(spec.column(), EditRules.normalize(spec.label(), spec.kind(), lookup, columnLengths().get(spec.column()), e.getValue()));
            } catch (IllegalArgumentException ex) {
                problems.add(ex.getMessage());
            }
        }
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));
        return clean;
    }

    /** Locks the client row and returns the requested columns plus UpdDateTime. */
    private Map<String, String> lockClient(int branchCode, int clientId, java.util.Collection<String> columns) {
        List<String> cols = new ArrayList<>(columns);
        cols.add("UpdDateTime");
        List<Map<String, String>> found = jdbc.query(
            "SELECT " + String.join(", ", cols) + " FROM client WHERE ClientIDBrCode = ? AND ClientID = ? FOR UPDATE",
            (rs, i) -> {
                Map<String, String> row = new LinkedHashMap<>();
                for (String c : cols) row.put(c, rs.getString(c));
                return row;
            }, branchCode, clientId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found.");
        return found.get(0);
    }

    private Change lockPending(long id) {
        List<String> status = jdbc.query("SELECT status FROM ecis_member_change WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id);
        if (status.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Change not found.");
        if (!"PENDING".equals(status.get(0))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This change has already been " + status.get(0).toLowerCase() + ".");
        return load(id);
    }

    private Change load(long id) {
        List<Change> found = jdbc.query(SELECT + " WHERE ch.id = ?", (rs, i) -> map(rs), id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Change not found.");
        return found.get(0);
    }

    private Change map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String name = rs.getString(6);
        return new Change(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5),
            name == null || name.isBlank() ? rs.getString(5) : name, rs.getString(7), rs.getString(8), read(rs.getString(9)),
            rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13));
    }

    private String write(List<ChangeItem> items) {
        try {
            return json.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ChangeItem> read(String text) {
        try {
            return json.readValue(text, new TypeReference<List<ChangeItem>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void audit(String user, String action, String entityId, String prev, String next, String reason) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason) VALUES (NOW(), ?, ?, 'client', ?, ?, ?, ?)",
            user, action, entityId, prev, next, reason);
    }

    private static String show(Map<Integer, String> lookup, String code) {
        if (code == null || lookup == null) return code;
        try {
            String text = lookup.get(Integer.parseInt(code.trim()));
            return text != null ? text : code;
        } catch (NumberFormatException e) {
            return code;
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** Character limits of the client table's text columns, read once. */
    private Map<String, Integer> columnLengths() {
        Map<String, Integer> l = lengths;
        if (l == null) {
            lengths = l = db.textColumnLengths("client");
        }
        return l;
    }
}
