package ph.coop.core.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.db.Db;

/**
 * Full membership lifecycle on top of the profile edits: registering a new member and closing a membership. Both are maker-checker like profile edits: a person with edit permission
 * asks, a Membership Officer who is not that person decides, and nothing touches the legacy tables until then. A member is never deleted; closing sets the status to Closed with a
 * date and reason (as the old system does), and only when every account is at zero. See {@link LifecycleRules} and documentation/membership-lifecycle-map.md.
 */
@RestController
@RequestMapping("/api/member-requests")
public class MemberLifecycleController {

    public record Request(long id, int branchCode, String kind, Integer clientId, String summary, String status, String requestedBy,
                          String requestedAt, String decidedBy, String decidedAt, String decisionNote, Map<String, String> payload) {}

    public record Register(int branchCode, int clientType, Map<String, String> fields) {}

    public record Close(int branchCode, int clientId, int reason, String dateDeceased, String note) {}

    public record Reactivate(int branchCode, int clientId, int clientType, String note) {}

    public record Decision(String note) {}

    private static final String SELECT = "SELECT id, branch_code, kind, client_id, summary, status, requested_by, requested_at, decided_by, decided_at, decision_note, payload FROM ecis_member_request";
    private static final int DEFAULT_DEPT = 2;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final LookupController lookups;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final MembershipPolicyController policy;
    private final ClientDetailController details;

    MemberLifecycleController(JdbcTemplate jdbc, Db db, LookupController lookups, ObjectMapper json, PlatformTransactionManager txManager, MembershipPolicyController policy, ClientDetailController details,
                              @Value("${coop.edit.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.db = db;
        this.lookups = lookups;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
        this.policy = policy;
        this.details = details;
    }

    private static boolean has(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch((a) -> a.getAuthority().equals(authority));
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    @GetMapping
    public List<Request> list(@RequestParam(defaultValue = "PENDING") String status, Authentication auth) {
        boolean officer = has(auth, "ROLE_MEMBERSHIP_OFFICER");
        String where = " WHERE status = ?" + (officer ? "" : " AND requested_by = ?");
        Object[] args = officer ? new Object[] {status} : new Object[] {status, auth.getName()};
        return jdbc.query(SELECT + where + " ORDER BY requested_at DESC LIMIT 200", (rs, i) -> map(rs), args);
    }

    /** The empty profile a registration is filled in on: the same tabs and fields as a member's details. */
    @GetMapping("/template")
    public Map<String, List<ClientDetailController.Section>> template() {
        return details.blankDetails();
    }

    @GetMapping("/count")
    public int count(Authentication auth) {
        return list("PENDING", auth).size();
    }

    // ---- steps --------------------------------------------------------------------------------------------------

    @PostMapping("/registrations")
    @PreAuthorize("hasAuthority('PERM_MEMBER_EDIT')")
    public Request register(@RequestBody Register req, Authentication auth) {
        requireAvailable();
        Map<String, String> fields = cleanFields(req.fields());
        List<String> problems = LifecycleRules.registrationProblems(req.clientType(), fields, LocalDate.now());
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
        String key = LifecycleRules.duplicateKey(fields);
        return tx.execute((s) -> {
            List<Long> same = jdbc.queryForList("SELECT ClientID FROM client WHERE ClientIDBrCode = ? AND LOWER(TRIM(LName)) = ? AND LOWER(TRIM(FName)) = ? AND DateOfBirth = ? LIMIT 1", Long.class,
                req.branchCode(), fields.get("LName").toLowerCase(), fields.get("FName").toLowerCase(), fields.get("DateOfBirth"));
            if (!same.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A member with the same name and birth date already exists: " + same.get(0) + ".");
            for (Request p : pendingOf(req.branchCode(), "REGISTER")) {
                if (key.equals(LifecycleRules.duplicateKey(p.payload()))) throw new ResponseStatusException(HttpStatus.CONFLICT, "The same person is already waiting for approval (request #" + p.id() + ").");
            }
            Map<String, String> payload = new LinkedHashMap<>(fields);
            payload.put("_clientType", Integer.toString(req.clientType()));
            String summary = fields.get("LName") + ", " + fields.get("FName") + " (" + LifecycleRules.TYPES.get(req.clientType()) + ")";
            long id = db.insertReturningId("INSERT INTO ecis_member_request (branch_code, kind, client_id, summary, payload, status, requested_by, requested_at) VALUES (?, 'REGISTER', NULL, ?, ?, 'PENDING', ?, NOW())", "id",
                req.branchCode(), summary, write(payload), auth.getName());
            audit(auth.getName(), "MEMBER_REGISTER_REQUESTED", req.branchCode() + "-new", null, summary, "Request #" + id);
            return load(id);
        });
    }

    @PostMapping("/closures")
    @PreAuthorize("hasAuthority('PERM_MEMBER_EDIT')")
    public Request close(@RequestBody Close req, Authentication auth) {
        requireAvailable();
        LocalDate deceased = parseDate(req.dateDeceased());
        return tx.execute((s) -> {
            Map<String, Object> c = member(req.branchCode(), req.clientId());
            List<String> problems = new ArrayList<>(LifecycleRules.closeProblems(((Number) c.get("AccountStatus")).intValue(), req.reason(), deceased, LocalDate.now()));
            if (problems.isEmpty()) problems.addAll(blockers(req.branchCode(), req.clientId(), req.reason()));
            if (!problems.isEmpty()) throw bad(String.join(" ", problems));
            if (!pendingFor(req.branchCode(), req.clientId()).isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This member already has a request waiting for approval.");
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("reason", Integer.toString(req.reason()));
            if (deceased != null) payload.put("dateDeceased", deceased.toString());
            if (req.note() != null && !req.note().isBlank()) payload.put("note", req.note().trim());
            String summary = "Close " + req.clientId() + " (" + c.get("Name") + "): " + LifecycleRules.REASONS.get(req.reason());
            long id = db.insertReturningId("INSERT INTO ecis_member_request (branch_code, kind, client_id, summary, payload, status, requested_by, requested_at) VALUES (?, 'CLOSE', ?, ?, ?, 'PENDING', ?, NOW())", "id",
                req.branchCode(), req.clientId(), summary, write(payload), auth.getName());
            audit(auth.getName(), "MEMBER_CLOSE_REQUESTED", req.branchCode() + "-" + req.clientId(), null, summary, "Request #" + id);
            return load(id);
        });
    }

    @PostMapping("/reactivations")
    @PreAuthorize("hasAuthority('PERM_MEMBER_EDIT')")
    public Request reactivate(@RequestBody Reactivate req, Authentication auth) {
        requireAvailable();
        return tx.execute((s) -> {
            Map<String, Object> c = jdbc.queryForList("SELECT AccountStatus, ClosedReason, ClientType, DateDeceased, CONCAT_WS(', ', LName, FName) AS Name FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", req.branchCode(), req.clientId())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found."));
            Integer reason = c.get("ClosedReason") == null ? null : ((Number) c.get("ClosedReason")).intValue();
            LocalDate deceased = c.get("DateDeceased") == null ? null : LocalDate.parse(c.get("DateDeceased").toString().substring(0, 10));
            List<String> problems = LifecycleRules.reactivateProblems(((Number) c.get("AccountStatus")).intValue(), reason, ((Number) c.get("ClientType")).intValue(), deceased, req.clientType(), req.note());
            if (!problems.isEmpty()) throw bad(String.join(" ", problems));
            if (!pendingFor(req.branchCode(), req.clientId()).isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This member already has a request waiting for approval.");
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("clientType", Integer.toString(req.clientType()));
            payload.put("note", req.note().trim());
            payload.put("priorStatus", c.get("AccountStatus").toString());
            if (reason != null) payload.put("priorReason", reason.toString());
            if (LifecycleRules.needsAdministrator(reason)) payload.put("adminOnly", "true");
            String summary = "Reactivate " + req.clientId() + " (" + c.get("Name") + ")" + (payload.containsKey("adminOnly") ? ", needs an administrator" : "");
            long id = db.insertReturningId("INSERT INTO ecis_member_request (branch_code, kind, client_id, summary, payload, status, requested_by, requested_at) VALUES (?, 'REACTIVATE', ?, ?, ?, 'PENDING', ?, NOW())", "id",
                req.branchCode(), req.clientId(), summary, write(payload), auth.getName());
            audit(auth.getName(), "MEMBER_REACTIVATE_REQUESTED", req.branchCode() + "-" + req.clientId(), null, summary, "Request #" + id);
            return load(id);
        });
    }

    @PostMapping("/{id}/approve")
    public Request approve(@PathVariable long id, @RequestBody(required = false) Decision d, Authentication auth) {
        requireAvailable();
        String note = d == null ? null : d.note();
        return tx.execute((s) -> {
            Request r = lockPending(id);
            String problem = ChangeRules.approvalProblem(r.requestedBy(), auth.getName(), has(auth, "ROLE_MEMBERSHIP_OFFICER"));
            if (problem != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, problem);
            if ("true".equals(r.payload().get("adminOnly")) && !has(auth, "ROLE_ADMIN")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This member left owing money (bad debts or offset), so an administrator must approve the return.");
            }
            String done;
            if (r.kind().equals("REGISTER")) {
                int clientId = openMember(r, auth.getName());
                jdbc.update("UPDATE ecis_member_request SET client_id = ? WHERE id = ?", clientId, id);
                done = "Member " + clientId + " opened";
            } else if (r.kind().equals("REACTIVATE")) {
                reactivateMember(r, auth.getName());
                done = "Member " + r.clientId() + " reactivated";
            } else {
                closeMember(r, auth.getName());
                done = "Member " + r.clientId() + " closed";
            }
            jdbc.update("UPDATE ecis_member_request SET status = 'APPROVED', decided_by = ?, decided_at = NOW(), decision_note = ? WHERE id = ?", auth.getName(), blank(note), id);
            audit(auth.getName(), "MEMBER_" + r.kind() + "_APPROVED", r.branchCode() + "-" + (r.clientId() == null ? "new" : r.clientId()), null, done,
                "Request #" + id + " by " + r.requestedBy() + (blank(note) == null ? "" : ". " + note.trim()));
            return load(id);
        });
    }

    @PostMapping("/{id}/reject")
    public Request reject(@PathVariable long id, @RequestBody Decision d, Authentication auth) {
        requireAvailable();
        String problem = ChangeRules.rejectionProblem(has(auth, "ROLE_MEMBERSHIP_OFFICER"), d == null ? null : d.note());
        if (problem != null) throw new ResponseStatusException(has(auth, "ROLE_MEMBERSHIP_OFFICER") ? HttpStatus.BAD_REQUEST : HttpStatus.FORBIDDEN, problem);
        return tx.execute((s) -> {
            Request r = lockPending(id);
            jdbc.update("UPDATE ecis_member_request SET status = 'REJECTED', decided_by = ?, decided_at = NOW(), decision_note = ? WHERE id = ?", auth.getName(), d.note().trim(), id);
            audit(auth.getName(), "MEMBER_" + r.kind() + "_REJECTED", r.branchCode() + "-" + (r.clientId() == null ? "new" : r.clientId()), null, null, "Request #" + id + " by " + r.requestedBy() + ". " + d.note().trim());
            return load(id);
        });
    }

    @PostMapping("/{id}/withdraw")
    public Request withdraw(@PathVariable long id, Authentication auth) {
        requireAvailable();
        return tx.execute((s) -> {
            Request r = lockPending(id);
            if (!r.requestedBy().equalsIgnoreCase(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the person who made the request can withdraw it.");
            jdbc.update("UPDATE ecis_member_request SET status = 'WITHDRAWN', decided_by = ?, decided_at = NOW() WHERE id = ?", auth.getName(), id);
            audit(auth.getName(), "MEMBER_" + r.kind() + "_WITHDRAWN", r.branchCode() + "-" + (r.clientId() == null ? "new" : r.clientId()), null, null, "Request #" + id);
            return load(id);
        });
    }

    // ---- what approval does -------------------------------------------------------------------------------------

    private int openMember(Request r, String approver) {
        Map<String, String> fields = new LinkedHashMap<>(r.payload());
        int clientType = Integer.parseInt(fields.remove("_clientType"));
        // Re-check on approval: the stored request is data, and the world may have moved on since it was made.
        List<String> problems = LifecycleRules.registrationProblems(clientType, fields, LocalDate.now());
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
        int branch = r.branchCode();
        List<Long> same = jdbc.queryForList("SELECT ClientID FROM client WHERE ClientIDBrCode = ? AND LOWER(TRIM(LName)) = ? AND LOWER(TRIM(FName)) = ? AND DateOfBirth = ? LIMIT 1", Long.class,
            branch, fields.get("LName").toLowerCase(), fields.get("FName").toLowerCase(), fields.get("DateOfBirth"));
        if (!same.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A member with the same name and birth date now exists: " + same.get(0) + ". Reject this request.");

        List<Integer> top = jdbc.queryForList("SELECT ClientID FROM client WHERE ClientIDBrCode = ? ORDER BY ClientID DESC LIMIT 1 FOR UPDATE", Integer.class, branch);
        int clientId = (top.isEmpty() ? 0 : top.get(0)) + 1;
        List<Integer> user = jdbc.queryForList("SELECT AccessID FROM access WHERE AccessUserName = ? LIMIT 1", Integer.class, r.requestedBy());
        if (user.isEmpty()) throw bad("The person who made this request is not a user of the old system, so the new member cannot be credited to them.");
        LocalDate today = LocalDate.now();

        List<String> cols = new ArrayList<>(List.of("ClientIDBrCode", "ClientID", "ClientIDChkDgt", "DateOpened", "AccountType", "ClientType", "AccountStatus", "AlertLevel", "EncdBy"));
        List<Object> args = new ArrayList<>(List.of(branch, clientId, LifecycleRules.checkDigit(clientId), today.toString(), 1, clientType, 1, 1, user.get(0)));
        if (!fields.containsKey("ClientDEPT")) {
            cols.add("ClientDEPT");
            args.add(DEFAULT_DEPT);
        }
        for (var e : fields.entrySet()) {
            if (!ClientDetailController.EDITABLE.containsKey(e.getKey()) || e.getKey().equals("ClientType") || e.getKey().equals("AccountType")) continue;
            cols.add(e.getKey());
            args.add(e.getValue());
        }
        jdbc.update("INSERT INTO client (" + String.join(", ", cols) + ") VALUES (" + "?, ".repeat(cols.size() - 1) + "?)", args.toArray());

        openAccounts(branch, clientId, clientType, today, false);
        audit(approver, "MEMBER_CREATED", branch + "-" + clientId, null, r.summary(), "Request #" + r.id() + " by " + r.requestedBy());
        return clientId;
    }


    /** Opens the standard accounts. With {@code reuse}, an account type the member already holds as active (status 15) is kept as it is and only the missing ones are opened. */
    private void openAccounts(int branch, int clientId, int clientType, LocalDate today, boolean reuse) {
        for (LifecycleRules.Acct a : LifecycleRules.accountsFor(clientType)) {
            if (reuse && holdsActive(branch, clientId, a)) continue;
            List<Integer> last = jdbc.queryForList("SELECT MREF_NO FROM slmastersum WHERE MBR_CODE = ? AND MSLC_CODE = ? AND MSLT_CODE = ? ORDER BY MREF_NO DESC LIMIT 1 FOR UPDATE", Integer.class, branch, a.slc(), a.slt());
            int ref = last.isEmpty() ? 1 : last.get(0) + 1;
            Map<String, Object> type = jdbc.queryForList("SELECT COALESCE(SLTypeMIN_BAL, 0) mn, COALESCE(SLTypeINT_RATE, 0) rt FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", branch, a.slc(), a.slt())
                .stream().findFirst().orElseThrow(() -> bad("The account type " + a.slc() + "/" + a.slt() + " is not set up."));
            jdbc.update("INSERT INTO slmastersum (MBR_CODE, MSLC_CODE, MSLT_CODE, MREF_NO, MCLIENTID) VALUES (?, ?, ?, ?, ?)", branch, a.slc(), a.slt(), ref, clientId);
            switch (a.table()) {
                case "sc" -> jdbc.update("INSERT INTO sc (SCBR_CODE, SCSLC_CODE, SCSLT_CODE, SCREF_NO, ClientIDSC, SCTR_DATE, SCSTATUS, SCMIN_BAL) VALUES (?, ?, ?, ?, ?, ?, 15, ?)",
                    branch, a.slc(), a.slt(), ref, clientId, today.toString(), type.get("mn"));
                case "sd" -> jdbc.update("INSERT INTO sd (SDBR_CODE, SDSLC_CODE, SDSLT_CODE, SDREF_NO, ClientIDSD, SDTR_DATE, SDSTATUS, SDMIN_BAL, SDINT_RATE) VALUES (?, ?, ?, ?, ?, ?, 15, ?, ?)",
                    branch, a.slc(), a.slt(), ref, clientId, today.toString(), type.get("mn"), type.get("rt"));
                default -> jdbc.update("INSERT INTO osl (OSLBR_CODE, OSLSLC_CODE, OSLSLT_CODE, OSLREF_NO, ClientIDOSL, OSLTR_DATE, OSLSTATUS) VALUES (?, ?, ?, ?, ?, ?, 15)",
                    branch, a.slc(), a.slt(), ref, clientId, today.toString());
            }
        }
    }

    private boolean holdsActive(int branch, int clientId, LifecycleRules.Acct a) {
        String sql = switch (a.table()) {
            case "sc" -> "SELECT COUNT(*) FROM sc WHERE SCBR_CODE = ? AND ClientIDSC = ? AND SCSLC_CODE = ? AND SCSLT_CODE = ? AND SCSTATUS = 15";
            case "sd" -> "SELECT COUNT(*) FROM sd WHERE SDBR_CODE = ? AND ClientIDSD = ? AND SDSLC_CODE = ? AND SDSLT_CODE = ? AND SDSTATUS = 15";
            default -> "SELECT COUNT(*) FROM osl WHERE OSLBR_CODE = ? AND ClientIDOSL = ? AND OSLSLC_CODE = ? AND OSLSLT_CODE = ? AND OSLSTATUS = 15";
        };
        Integer n = jdbc.queryForObject(sql, Integer.class, branch, clientId, a.slc(), a.slt());
        return n != null && n > 0;
    }

    private void reactivateMember(Request r, String approver) {
        int branch = r.branchCode();
        int clientId = r.clientId();
        int newType = Integer.parseInt(r.payload().get("clientType"));
        List<Map<String, Object>> found = jdbc.queryForList("SELECT AccountStatus, ClosedReason, ClientType, DateDeceased, ClosedDate FROM client WHERE ClientIDBrCode = ? AND ClientID = ? FOR UPDATE", branch, clientId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found.");
        Map<String, Object> c = found.get(0);
        Integer reason = c.get("ClosedReason") == null ? null : ((Number) c.get("ClosedReason")).intValue();
        LocalDate deceased = c.get("DateDeceased") == null ? null : LocalDate.parse(c.get("DateDeceased").toString().substring(0, 10));
        int status = ((Number) c.get("AccountStatus")).intValue();
        List<String> problems = LifecycleRules.reactivateProblems(status, reason, ((Number) c.get("ClientType")).intValue(), deceased, newType, r.payload().get("note"));
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
        String shareDue = policy.returningPaysShareCapital() ? shareCapitalShortfall(branch, clientId, newType) : null;
        if (shareDue != null && shareDue.startsWith("BLOCK:")) throw new ResponseStatusException(HttpStatus.CONFLICT, shareDue.substring(6));
        jdbc.update("UPDATE client SET AccountStatus = 1, ClientType = ?, ClosedDate = NULL, ClosedReason = NULL, DateDeceased = NULL, UpdDateTime = NOW() WHERE ClientIDBrCode = ? AND ClientID = ?", newType, branch, clientId);
        // Accounts never formally closed are kept; a type the member no longer holds as active gets a new account, so the closed one keeps its final history.
        openAccounts(branch, clientId, newType, LocalDate.now(), true);
        audit(approver, "MEMBER_REACTIVATED", branch + "-" + clientId, "status " + status + (reason == null ? "" : ", closed " + c.get("ClosedDate") + " (" + LifecycleRules.REASONS.get(reason) + ")"), "status 1",
            "Request #" + r.id() + " by " + r.requestedBy() + ". " + r.payload().get("note") + (shareDue == null ? "" : ". " + shareDue));
    }

    /**
     * The returning-member share capital rule. Returns null when nothing is due, "BLOCK:..." when the member holds an active share account that is under the type's minimum
     * (the payment can be taken at the front office first), or a note for the audit row when there is no active share account yet: the account is opened by this approval, so
     * the payment can only follow it.
     */
    private String shareCapitalShortfall(int branch, int clientId, int newType) {
        for (LifecycleRules.Acct a : LifecycleRules.accountsFor(newType)) {
            if (!a.table().equals("sc")) continue;
            List<java.math.BigDecimal> min = jdbc.queryForList("SELECT COALESCE(SLTypeMIN_BAL, 0) FROM sltype WHERE SLTypeBR_CODE = ? AND SLTypeSLC_CODE = ? AND SLTypeSLT_CODE = ?", java.math.BigDecimal.class, branch, a.slc(), a.slt());
            java.math.BigDecimal required = min.isEmpty() ? java.math.BigDecimal.ZERO : min.get(0);
            if (required.signum() <= 0) continue;
            if (!holdsActive(branch, clientId, a)) return "Share capital of " + required.toPlainString() + " is due on the new account and has not been paid yet";
            java.math.BigDecimal held = jdbc.queryForObject("SELECT COALESCE(-SUM(AMT), 0) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLC_CODE = ? AND SLT_CODE = ? AND SLE_CODE = 11", java.math.BigDecimal.class, branch, clientId, a.slc(), a.slt());
            if (held.compareTo(required) < 0) {
                return "BLOCK:The cooperative requires a returning member to pay share capital again. " + held.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " is held and "
                    + required.toPlainString() + " is required: take the payment on the member's Share Capital tab first, then approve.";
            }
        }
        return null;
    }

    private void closeMember(Request r, String approver) {
        int branch = r.branchCode();
        int clientId = r.clientId();
        int reason = Integer.parseInt(r.payload().get("reason"));
        LocalDate deceased = parseDate(r.payload().get("dateDeceased"));
        Map<String, Object> c = member(branch, clientId);
        int status = ((Number) c.get("AccountStatus")).intValue();
        List<String> problems = new ArrayList<>(LifecycleRules.closeProblems(status, reason, deceased, LocalDate.now()));
        problems.addAll(blockers(branch, clientId, reason));
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
        jdbc.queryForList("SELECT ClientID FROM client WHERE ClientIDBrCode = ? AND ClientID = ? FOR UPDATE", branch, clientId);
        jdbc.update("UPDATE client SET AccountStatus = 3, ClosedDate = NOW(), ClosedReason = ?, ClientType = ?, DateDeceased = ?, UpdDateTime = NOW() WHERE ClientIDBrCode = ? AND ClientID = ?",
            reason, LifecycleRules.closedClientType(reason), deceased == null ? null : deceased.toString(), branch, clientId);
        audit(approver, "MEMBER_CLOSED", branch + "-" + clientId, "status " + status, "status 3, " + LifecycleRules.REASONS.get(reason), "Request #" + r.id() + " by " + r.requestedBy());
    }

    /** Everything that stops a membership closing now: accounts that are not at zero, and loan applications still in progress. */
    private List<String> blockers(int branch, int clientId, int reason) {
        Map<Integer, BigDecimal> balances = new LinkedHashMap<>();
        jdbc.query("SELECT SLC_CODE, SUM(AMT) FROM sldtl WHERE SL_BRCODE = ? AND SL_CLIENTID = ? AND SLE_CODE = 11 GROUP BY SLC_CODE", (rs) -> {
            balances.put(rs.getInt(1), rs.getBigDecimal(2));
        }, branch, clientId);
        List<String> out = new ArrayList<>(LifecycleRules.balanceProblems(balances, reason));
        Integer apps = jdbc.queryForObject("SELECT COUNT(*) FROM loanapp WHERE BR_CODE = ? AND CLIENTID = ? AND STATUSID IN (1, 2, 3)", Integer.class, branch, clientId);
        if (apps != null && apps > 0) out.add("A loan application is still in progress.");
        return out;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void requireAvailable() {
        if (!enabled || !db.allTablesExist("ecis_audit_log", "ecis_member_request")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Editing is not enabled on this server.");
    }

    private Map<String, String> cleanFields(Map<String, String> raw) {
        if (raw == null) throw bad("Fill in the member's details.");
        Map<String, Integer> lengths = db.textColumnLengths("client");
        Map<String, String> out = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (var e : raw.entrySet()) {
            ClientDetailController.Spec spec = ClientDetailController.EDITABLE.get(e.getKey());
            if (spec == null || e.getKey().equals("ClientType") || e.getKey().equals("AccountType")) throw bad("The field '" + e.getKey() + "' cannot be given here.");
            Map<Integer, String> lookup = spec.how() == null ? null : lookups.all().get(spec.how());
            try {
                String v = EditRules.normalize(spec.label(), spec.kind(), lookup, lengths.get(spec.column()), e.getValue());
                if (v != null) out.put(spec.column(), v);
            } catch (IllegalArgumentException ex) {
                problems.add(ex.getMessage());
            }
        }
        if (!problems.isEmpty()) throw bad(String.join(" ", problems));
        return out;
    }

    private Map<String, Object> member(int branch, int clientId) {
        List<Map<String, Object>> found = jdbc.queryForList("SELECT AccountStatus, CONCAT_WS(', ', LName, FName) AS Name FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", branch, clientId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found.");
        return found.get(0);
    }

    private List<Request> pendingOf(int branch, String kind) {
        return jdbc.query(SELECT + " WHERE branch_code = ? AND kind = ? AND status = 'PENDING'", (rs, i) -> map(rs), branch, kind);
    }

    private List<Request> pendingFor(int branch, int clientId) {
        return jdbc.query(SELECT + " WHERE branch_code = ? AND client_id = ? AND status = 'PENDING'", (rs, i) -> map(rs), branch, clientId);
    }

    private Request lockPending(long id) {
        List<String> status = jdbc.query("SELECT status FROM ecis_member_request WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id);
        if (status.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found.");
        if (!"PENDING".equals(status.get(0))) throw new ResponseStatusException(HttpStatus.CONFLICT, "This request has already been " + status.get(0).toLowerCase() + ".");
        return load(id);
    }

    private Request load(long id) {
        List<Request> found = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs), id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found.");
        return found.get(0);
    }

    private Request map(java.sql.ResultSet rs) throws java.sql.SQLException {
        int cid = rs.getInt(4);
        Integer clientId = rs.wasNull() ? null : cid;
        return new Request(rs.getLong(1), rs.getInt(2), rs.getString(3), clientId, rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getString(10), rs.getString(11), read(rs.getString(12)));
    }

    private String write(Map<String, String> m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> read(String text) {
        try {
            return json.readValue(text, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void audit(String user, String action, String entityId, String prev, String next, String reason) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value, reason) VALUES (NOW(), ?, ?, 'client', ?, ?, ?, ?)", user, action, entityId, prev, next, reason);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw bad("The date must look like 2026-04-24.");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
