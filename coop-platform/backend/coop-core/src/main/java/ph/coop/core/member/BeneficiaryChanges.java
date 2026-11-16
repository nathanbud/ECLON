package ph.coop.core.member;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.conflict;
import static ph.coop.core.web.ApiErrors.notFound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.audit.AuditLog;

/**
 * Adding, changing and removing a member's beneficiaries (the legacy {@code benef} table) through a request that a Membership Officer other than the requester approves.
 * {@link #prepare} checks a request and builds what is stored with it; {@link #apply} runs on approval, checks again against the table as it is then, and writes the change with an
 * audit row. A change or removal records the row's values as they were asked about, and approval refuses if someone changed the row since.
 */
@Component
class BeneficiaryChanges {

    /** What a person asks for. {@code benefId} names the row to change or remove; {@code note} is the reason for a removal. */
    record Change(int branchCode, int clientId, String action, Long benefId, String name, Integer relation, String dateOfBirth, String occupation, Integer type, String note) {}

    record Prepared(String summary, Map<String, String> payload) {}

    private static final List<String> FIELDS = List.of("name", "relation", "dateOfBirth", "occupation", "type");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    BeneficiaryChanges(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- asking -------------------------------------------------------------------------------------------------

    Prepared prepare(Change c) {
        String memberName = jdbc.queryForList("SELECT CONCAT_WS(', ', LName, FName) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", String.class, c.branchCode(), c.clientId())
            .stream().findFirst().orElseThrow(() -> notFound("Member not found."));
        List<String> problems = BeneficiaryRules.problems(c.action(), c.name(), c.relation(), relations(), c.dateOfBirth(), c.occupation(), c.type(), c.note(), LocalDate.now());
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
        String action = c.action();
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("action", action);
        Map<String, String> before = null;
        if (!action.equals("ADD")) {
            if (c.benefId() == null) throw bad("Choose the beneficiary.");
            Map<String, Object> row = row(c.branchCode(), c.clientId(), c.benefId(), false);
            if (row == null) throw notFound("That beneficiary is not recorded for this member.");
            before = snapshot(row);
            payload.put("benefId", c.benefId().toString());
            before.forEach((k, v) -> payload.put("before_" + k, v == null ? "" : v));
        }
        if (action.equals("REMOVE")) {
            payload.put("note", c.note().trim());
        } else {
            Map<String, String> next = proposed(c);
            next.forEach(payload::put);
            if (action.equals("EDIT") && same(before, next)) throw bad("Nothing was changed.");
            if (action.equals("ADD") && duplicate(c.branchCode(), c.clientId(), next)) throw conflict("This beneficiary is already recorded for the member.");
        }
        for (var p : pending(c.branchCode(), c.clientId())) {
            String id = payload.get("benefId");
            if (id != null && id.equals(p.get("benefId"))) throw conflict("A change to this beneficiary is already waiting for approval.");
            if (action.equals("ADD") && "ADD".equals(p.get("action")) && same(proposed(c), pick(p))) throw conflict("The same beneficiary is already waiting for approval.");
        }
        String who = BeneficiaryRules.name(action.equals("REMOVE") ? before.get("name") : c.name());
        String summary = switch (action) {
            case "ADD" -> "Add beneficiary " + who + " to " + c.clientId() + " (" + memberName + ")";
            case "EDIT" -> "Change beneficiary " + who + " of " + c.clientId() + " (" + memberName + ")";
            default -> "Remove beneficiary " + who + " of " + c.clientId() + " (" + memberName + "): " + c.note().trim();
        };
        return new Prepared(summary, payload);
    }

    // ---- approving ----------------------------------------------------------------------------------------------

    /** Writes the change and its audit row. Runs inside the approval's transaction; throws when the request no longer fits the table. */
    String apply(int branch, int client, Map<String, String> payload, long requestId, String requestedBy, String approver) {
        String action = payload.get("action");
        String reason = "Request #" + requestId + " by " + requestedBy;
        String entity = branch + "-" + client;
        if (action.equals("ADD")) {
            Map<String, String> next = pick(payload);
            problemsOf(action, next, payload);
            if (duplicate(branch, client, next)) throw conflict("This beneficiary is now already recorded for the member. Reject this request.");
            jdbc.update("INSERT INTO benef (BenefBR_CODE, ClientIDBenef, BenefName, BenefRelation, BenefDateofBirth, BenefOccu, BenefDateTimeAdded, BenefTypeID) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                branch, client, BeneficiaryRules.name(next.get("name")), Integer.valueOf(next.get("relation")), blank(next.get("dateOfBirth")) == null ? null : java.sql.Date.valueOf(next.get("dateOfBirth")),
                BeneficiaryRules.occupation(next.get("occupation")), Timestamp.valueOf(LocalDateTime.now()), Integer.valueOf(next.get("type")));
            AuditLog.write(jdbc, approver, "MEMBER_BENEFICIARY_ADDED", "client", entity, null, describe(next), reason);
            return "Beneficiary " + BeneficiaryRules.name(next.get("name")) + " added";
        }
        long id = Long.parseLong(payload.get("benefId"));
        Map<String, Object> row = row(branch, client, id, true);
        if (row == null) throw conflict("This beneficiary has already been removed. Reject this request.");
        Map<String, String> now = snapshot(row);
        Map<String, String> asked = new LinkedHashMap<>();
        for (String f : FIELDS) asked.put(f, blank(payload.get("before_" + f)));
        if (!same(asked, now)) throw conflict("This beneficiary was changed after the request was made. Reject it and ask again.");
        if (action.equals("REMOVE")) {
            jdbc.update("DELETE FROM benef WHERE BenefID = ? AND BenefBR_CODE = ? AND ClientIDBenef = ?", id, branch, client);
            AuditLog.write(jdbc, approver, "MEMBER_BENEFICIARY_REMOVED", "client", entity, describe(now), null, reason + ". " + payload.get("note"));
            return "Beneficiary " + now.get("name") + " removed";
        }
        Map<String, String> next = pick(payload);
        problemsOf(action, next, payload);
        jdbc.update("UPDATE benef SET BenefName = ?, BenefRelation = ?, BenefDateofBirth = ?, BenefOccu = ?, BenefTypeID = ? WHERE BenefID = ? AND BenefBR_CODE = ? AND ClientIDBenef = ?",
            BeneficiaryRules.name(next.get("name")), Integer.valueOf(next.get("relation")), blank(next.get("dateOfBirth")) == null ? null : java.sql.Date.valueOf(next.get("dateOfBirth")),
            BeneficiaryRules.occupation(next.get("occupation")), Integer.valueOf(next.get("type")), id, branch, client);
        AuditLog.write(jdbc, approver, "MEMBER_BENEFICIARY_CHANGED", "client", entity, describe(now), describe(next), reason);
        return "Beneficiary " + BeneficiaryRules.name(next.get("name")) + " changed";
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void problemsOf(String action, Map<String, String> next, Map<String, String> payload) {
        Integer relation = next.get("relation") == null ? null : Integer.valueOf(next.get("relation"));
        Integer type = next.get("type") == null ? null : Integer.valueOf(next.get("type"));
        List<String> problems = BeneficiaryRules.problems(action, next.get("name"), relation, relations(), next.get("dateOfBirth"), next.get("occupation"), type, payload.get("note"), LocalDate.now());
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
    }

    private Set<Integer> relations() {
        return new HashSet<>(jdbc.queryForList("SELECT RelationID FROM relation", Integer.class));
    }

    private Map<String, Object> row(int branch, int client, long id, boolean lock) {
        List<Map<String, Object>> found = jdbc.queryForList("SELECT BenefName, BenefRelation, BenefDateofBirth, BenefOccu, BenefTypeID FROM benef WHERE BenefID = ? AND BenefBR_CODE = ? AND ClientIDBenef = ?"
            + (lock ? " FOR UPDATE" : ""), id, branch, client);
        return found.isEmpty() ? null : found.get(0);
    }

    /** A row's values as text, so a request and the table can be compared. */
    private static Map<String, String> snapshot(Map<String, Object> row) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", blank(BeneficiaryRules.name((String) row.get("BenefName"))));
        m.put("relation", row.get("BenefRelation") == null ? null : String.valueOf(((Number) row.get("BenefRelation")).intValue()));
        m.put("dateOfBirth", row.get("BenefDateofBirth") == null ? null : row.get("BenefDateofBirth").toString().substring(0, 10));
        m.put("occupation", blank(BeneficiaryRules.name((String) row.get("BenefOccu"))));
        m.put("type", row.get("BenefTypeID") == null ? null : String.valueOf(((Number) row.get("BenefTypeID")).intValue()));
        return m;
    }

    private static Map<String, String> proposed(Change c) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", BeneficiaryRules.name(c.name()));
        m.put("relation", c.relation() == null ? null : c.relation().toString());
        m.put("dateOfBirth", blank(c.dateOfBirth()));
        m.put("occupation", BeneficiaryRules.occupation(c.occupation()));
        m.put("type", c.type() == null ? null : c.type().toString());
        return m;
    }

    private static Map<String, String> pick(Map<String, String> payload) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String f : FIELDS) m.put(f, blank(payload.get(f)));
        return m;
    }

    private static boolean same(Map<String, String> a, Map<String, String> b) {
        for (String f : FIELDS) if (!Objects.equals(blank(a.get(f)), blank(b.get(f)))) return false;
        return true;
    }

    private boolean duplicate(int branch, int client, Map<String, String> next) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM benef WHERE BenefBR_CODE = ? AND ClientIDBenef = ? AND LOWER(TRIM(BenefName)) = ? AND BenefRelation = ?", Integer.class, branch, client,
            BeneficiaryRules.name(next.get("name")).toLowerCase(), Integer.valueOf(next.get("relation")));
        return n != null && n > 0;
    }

    private List<Map<String, String>> pending(int branch, int client) {
        return jdbc.queryForList("SELECT payload FROM ecis_member_request WHERE branch_code = ? AND client_id = ? AND kind = 'BENEF' AND status = 'PENDING'", String.class, branch, client).stream().map((p) -> {
            try {
                return json.readValue(p, new TypeReference<Map<String, String>>() {});
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private String describe(Map<String, String> m) {
        String rel = m.get("relation") == null ? "no relationship" : jdbc.queryForList("SELECT RelationDesc FROM relation WHERE RelationID = ?", String.class, Integer.valueOf(m.get("relation"))).stream().findFirst().orElse(m.get("relation"));
        String type = m.get("type") == null ? "" : ", " + BeneficiaryRules.TYPES.getOrDefault(Integer.valueOf(m.get("type")), "type " + m.get("type"));
        return m.get("name") + " (" + rel + ")" + (m.get("dateOfBirth") == null ? "" : ", born " + m.get("dateOfBirth")) + (m.get("occupation") == null ? "" : ", " + m.get("occupation")) + type;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
