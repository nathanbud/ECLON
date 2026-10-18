package ph.coop.core.security;

import ph.coop.core.db.Db;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.member.LookupController;

/**
 * Read-only view of a person's legacy account setup (the iAccs "Setup User Account" window): general settings and the
 * permission flags on {@code access}, plus the per-transaction-type permissions on {@code access_trans}.
 * Passwords and secret keys are never selected. Administrators only.
 */
@RestController
@RequestMapping("/api/admin/users/{username}")
@PreAuthorize("hasRole('ADMIN')")
class UserProfileController {

    /** kind: text, date, money, number, bool (1/0) or list (comma separated codes, resolved through `how` when given). */
    /**
     * key is the column to write, raw the stored value, and options the choices for a pick-one or pick-many field.
     * A field is editable only when editing is on for this person and the column is on the editable list.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Field(String label, String value, String kind, String key, boolean editable, String raw, List<Option> options) {}

    record Option(String value, String label) {}

    record Section(String title, List<Field> fields) {}

    record UserProfile(String username, String name, boolean enabled, boolean hasPassword, List<String> roles,
                       boolean canEdit, String editNote, Map<String, List<Section>> tabs) {}

    private record Spec(String label, String column, String kind, String how) {}

    private static Spec t(String label, String col) { return new Spec(label, col, "text", null); }
    private static Spec n(String label, String col) { return new Spec(label, col, "number", null); }
    private static Spec d(String label, String col) { return new Spec(label, col, "date", null); }
    private static Spec m(String label, String col) { return new Spec(label, col, "money", null); }
    private static Spec b(String label, String col) { return new Spec(label, col, "bool", null); }
    private static Spec l(String label, String col, String lookup) { return new Spec(label, col, "text", lookup); }
    private static Spec list(String label, String col, String lookup) { return new Spec(label, col, "list", lookup); }

    private static final Map<String, LinkedHashMap<String, List<Spec>>> TABS = tabs();

    private static Map<String, LinkedHashMap<String, List<Spec>>> tabs() {
        var out = new LinkedHashMap<String, LinkedHashMap<String, List<Spec>>>();

        var general = new LinkedHashMap<String, List<Spec>>();
        general.put("Person", List.of(
            t("Last name", "AccessLName"), t("First name", "AccessFName"), t("Middle name", "AccessMName"), t("Suffix", "AccessSName"),
            t("SMS number", "AccessSMSNumber"), t("Username", "AccessUserName"), l("Level", "AccessLevel", "level")));
        general.put("Assignment", List.of(
            list("Current assignment", "AccessCurrAssign", "assignment"), n("Teller / cashier no.", "AccessTellerNumber"),
            b("Head cashier", "AccessIsHeadCashier"), t("Passbook initial", "AccessPassbookInitial")));
        general.put("Access", List.of(
            b("Access disabled", "AccessIsDisabled"), b("Can access consolidated data", "AccessCanAccessConso"), d("Access expiry date", "AccessExpiryDate"),
            n("Max number of logins", "AccessMaxLogins"), n("Password expires in (days)", "AccessExpiresInDays"), d("Password last changed", "AccessLastChangePassDate"),
            n("Max connection time-out (seconds)", "AccessConnTimeOut"), l("Restrict licence to department", "AccessRestrictLicenseDept", "department"),
            l("Department", "AccessDepartment", "department"), b("Can choose department", "AccessCanChooseDepartment"),
            list("Offices", "AccessRestrik", "restrik"), b("Login needs a one-time code", "AccessRequireLoginOTP")));
        out.put("general", general);

        var transactions = new LinkedHashMap<String, List<Spec>>();
        transactions.put("Posting", List.of(
            b("Can reverse", "AccessCanReverse"), b("Post partial payments", "AccessCanPostPartialPayment"), b("Post with hold", "AccessCanPostWithHold"),
            b("Post over-payments", "AccessCanPostOverPayment"), b("Post uncleared", "AccessCanPostUncleared"), b("Tag approved", "AccessTagApproved"),
            b("Override non-cash payment", "AccessCanOverrideNonCashPayment"), b("Grant rebate", "AccessCanGrantRebate")));
        transactions.put("Dating and encoding", List.of(
            b("Back-date front office", "AccessCanBackDateFO"), b("Back-date back office", "AccessCanBackDateBO"), b("Future-date back office", "AccessCanFutureDateBO"),
            b("Encode PDC (front office)", "AccessCanEncodePDCFO"), b("Encode PDC", "AccessCanEncodePDC_FO"), b("Encode COCI (front office)", "AccessCanEncodeCOCI_FO"),
            b("View front office cash and COCI", "AccessCanViewFOCashCOCI")));
        transactions.put("Areas and limits", List.of(
            b("Front office", "AccessCanAccessFrontOffice"), b("Back office", "AccessCanAccessBackOffice"),
            m("Disbursement limit", "AccessDisbursementLimit"), m("Disbursement limit (other)", "AccessDisbLimit"), m("Max withdrawal", "AccessMaxWithdrawal")));
        out.put("transactions", transactions);

        var pos = new LinkedHashMap<String, List<Spec>>();
        pos.put("Point of sale", List.of(
            b("Can use POS", "AccessCanAccessPOS"), t("POS location", "AccessPOSLocation"), t("Priority queue", "AccessPriorityObjName"),
            t("Operation type", "AccessPOS_OperationType"), t("Item store tag", "AccessRestrictItemStoreTag")));
        pos.put("Permissions", List.of(
            b("View item cost", "AccessCanViewItemCost"), b("Edit price on receiving", "AccessCanEditPriceRR"), b("Edit average cost", "AccessCanEditAverageCost"),
            b("Change POS location", "AccessCanChangePOSLocation"), b("Remove punched item", "AccessCanRemovePunchedItemPOS"),
            b("Override sales return", "AccessCanOverrideSalesReturn"), b("Override discount", "AccessCanOverrideDiscount"), b("Use duplicate barcode", "AccessCanUseDuplicateBarcode")));
        out.put("pos", pos);

        var client = new LinkedHashMap<String, List<Spec>>();
        client.put("Clients", List.of(
            b("Add new client", "AccessCanAddNewClient"), b("Edit client", "AccessCanEditClient"), b("Delete client", "AccessCanDeleteClient"),
            b("Edit client name", "AccessCanEditClientName"), b("Edit client images", "AccessCanEditClientImages"), b("View closed clients", "AccessCanViewClosedClients"),
            b("Close client", "AccessCanCloseClient"), b("Approve client", "AccessCanApproveClient"), b("Edit client department", "AccessCanEditClientDept"),
            b("Transact for clients with a warning", "AccessCanTransactClientWARNING"), b("Transact for closed clients", "AccessCanTransactClosedClient"),
            b("Ask for client ID by default", "AccessIsClientIDDefaultPrompt")));
        client.put("Client details", List.of(
            b("Edit SMS number", "AccessCanEditClientSMSNumber"), b("Edit email address", "AccessCanEditClientEmailAddress"),
            b("Edit sources of income", "AccessCanEditClientSourcesOfIncome"), m("Max sources of income", "AccessMaxClientSourcesOfIncome"),
            b("Edit credit limit", "AccessCanEditClientCreditLimit"), m("Max credit limit", "AccessMaxClientCreditLimit")));
        client.put("Approval and holds", List.of(
            list("Can approve client types", "AccessCanApproveClientType", "clientType"), b("Add hold", "AccessCanAddHold"),
            b("Delete hold", "AccessCanDeleteHold"), b("Modify hold", "AccessCanModifyHold"), n("Own client no.", "AccessClientID")));
        out.put("client", client);

        var receivables = new LinkedHashMap<String, List<Spec>>();
        receivables.put("Loans", List.of(
            b("Create loan setup", "AccessCanCreateLoanSetup"), b("Edit loan setup", "AccessCanEditLoanSetup"), b("Batch edit loan setups", "AccessCanBatchEditLoanSetups"),
            b("Add loan application", "AccessCanAddLoanApplication"), b("Edit loan application", "AccessCanEditLoanApplication"),
            b("Delete loan application", "AccessCanDeleteLoanApplication"), b("Verify loan application", "AccessCanVerifyLoanApplication"),
            b("Approve loan application", "AccessCanApproveLoanApplication"), b("Release loan", "AccessCanReleaseLoan"), b("Must use loan wizard", "AccessMustUseLoanWizard")));
        receivables.put("Statements", List.of(
            b("Generate statement of account", "AccessCanGenerateSOA"), b("Batch generate statements", "AccessCanBatchGenerateSOA"), b("Export or print statements", "AccessCanExportPrintSOA")));
        out.put("receivables", receivables);

        var deposits = new LinkedHashMap<String, List<Spec>>();
        deposits.put("New accounts", List.of(
            b("Create savings deposit", "AccessCanCreateNewSD"), b("Create time deposit", "AccessCanCreateNewTD"),
            b("Create share capital", "AccessCanCreateNewSC"), b("Create IDP", "AccessCanCreateNewIDP"), b("Batch process deposits", "AccessCanBatchProcessDeposits")));
        deposits.put("Limits", List.of(
            m("Deposit balance limit", "AccessDepBalLimit"), m("Share capital balance limit", "AccessDepBalLimitSC"), m("Time deposit balance limit", "AccessDepBalLimitTD")));
        deposits.put("Checkbooks", List.of(
            b("Add checkbook", "AccessCanAddCheckBook"), b("Edit checkbook", "AccessCanEditCheckBook"), b("Delete checkbook", "AccessCanDeleteCheckBook"),
            b("Approve checkbook", "AccessCanApproveCheckBook"), b("Release checkbook", "AccessCanReleaseCheckBook"),
            b("Change check status remarks", "AccessCanModifyCheckStatusRemarks"), b("Override check status remarks", "AccessCanOverrideModifyCheckStatusRemarks")));
        out.put("deposits", deposits);

        var others = new LinkedHashMap<String, List<Spec>>();
        others.put("Reports", List.of(
            b("Export reports", "AccessCanExportReport"), b("Generate financial statements", "AccessCanGenerateFS"),
            b("Generate other front office reports", "AccessCanGenOtherFORep"), b("Reports without restriction", "AccessCanGenRepNoRestrict"), b("View activity logs", "AccessCanViewActvtLogs")));
        others.put("Messaging", List.of(
            b("Batch generate SMS", "AccessCanBatchGenerateSMS"), b("Approve SMS", "AccessCanApproveSMS"), b("Send SMS", "AccessCanSendSMS")));
        others.put("System", List.of(
            b("View subsidiary ledger", "AccessCanAccessSLView"), b("Open ATM gateway", "AccessCanOpenATMGW"), t("ATM terminal", "AccessATMTerminalID"),
            b("Run request server", "AccessCanRunRequestServer"), b("Terminate users", "AccessCanTerminateUser"), b("Do DCM", "AccessCanDoDCM"),
            b("Register attendance without fingerprint", "AccessCanRegAttendanceNoFP")));
        out.put("others", others);
        return out;
    }

    /** Identity, the link to a client record and system-kept dates are never edited here. */
    private static final Set<String> NOT_EDITABLE = Set.of("AccessUserName", "AccessClientID", "AccessLastChangePassDate");

    private static final Map<String, Spec> SPECS = specs();

    private static Map<String, Spec> specs() {
        Map<String, Spec> out = new LinkedHashMap<>();
        TABS.values().forEach((sections) -> sections.values().forEach((list) -> list.forEach((s) -> out.putIfAbsent(s.column(), s))));
        return out;
    }

    private final JdbcTemplate jdbc;
    private final Db db;
    private final LookupController lookups;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private volatile Map<String, Integer> lengths;

    UserProfileController(JdbcTemplate jdbc, Db db, LookupController lookups, PlatformTransactionManager txManager,
                          @Value("${coop.edit.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.db = db;
        this.lookups = lookups;
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
    }

    @GetMapping("/profile")
    ResponseEntity<UserProfile> profile(@PathVariable String username, Authentication auth) {
        Set<String> columns = new LinkedHashSet<>(List.of("AccessUserName", "AccessFName", "AccessLName", "AccessIsDisabled", "AccessLevel"));
        TABS.values().forEach((sections) -> sections.values().forEach((specs) -> specs.forEach((s) -> columns.add(s.column()))));

        List<Map<String, String>> rows = jdbc.query("SELECT " + String.join(", ", columns) + " FROM access WHERE AccessUserName = ? LIMIT 1",
            (rs, i) -> {
                Map<String, String> row = new LinkedHashMap<>();
                for (String c : columns) row.put(c, rs.getString(c)); // getString also copes with 0000-00-00 dates
                return row;
            }, username);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, String> row = rows.get(0);
        String name = row.get("AccessUserName");

        String note = editNote(auth, name, row.get("AccessLevel"));
        boolean canEdit = note == null;
        Map<String, List<Section>> tabs = new LinkedHashMap<>();
        TABS.forEach((tab, sections) -> {
            List<Section> list = new ArrayList<>();
            sections.forEach((title, specs) -> list.add(new Section(title, specs.stream().map((s) -> field(s, row.get(s.column()), canEdit)).toList())));
            tabs.put(tab, list);
        });

        boolean hasPassword = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ecis_user_credential WHERE username = ?)", Boolean.class, name));
        List<String> roles = jdbc.query("SELECT role FROM ecis_user_role WHERE username = ? ORDER BY role", (rs, i) -> rs.getString(1), name);
        String display = ((row.get("AccessFName") == null ? "" : row.get("AccessFName")) + " " + (row.get("AccessLName") == null ? "" : row.get("AccessLName"))).trim();
        return ResponseEntity.ok(new UserProfile(name, display.isEmpty() ? name : display, !"1".equals(row.get("AccessIsDisabled")), hasPassword, roles, canEdit, note, tabs));
    }

    record TransactionPermissions(List<String> columns, List<String> keys, boolean canEdit, String editNote, List<Row> rows) {}

    record Row(int code, String name, List<Boolean> allowed) {}

    private static final List<String> TRANSACTION_COLUMNS = List.of(
        "CAN_FIND", "CAN_NEW", "CAN_EDIT", "CAN_EDIT_ENTRY", "CAN_DELETE", "CAN_TAG_APPROVED", "CAN_POST", "CAN_REVERSE", "CAN_CANCEL",
        "CAN_BACKDATE", "CAN_FUTUREDATE", "CAN_POST_AUTO", "CAN_POST_PARTIALPAY", "CAN_POST_OVERPAY", "CAN_POST_UNCLEAREDDEP",
        "CAN_POST_WITH_HOLD_DEP", "CAN_POST_RESULTSNEGATIVE", "CAN_SEE_OTHER_USERS_TRANS", "CAN_BACKDATE_CLOSEDBOOKS");
    private static final List<String> TRANSACTION_LABELS = List.of(
        "Find", "New", "Edit", "Edit entry", "Delete", "Tag approved", "Post", "Reverse", "Cancel",
        "Back-date", "Future-date", "Post automatically", "Post partial payment", "Post over-payment", "Post uncleared deposit",
        "Post with hold", "Post if result is negative", "See others' transactions", "Back-date closed books");

    /** What this person may do with each type of transaction. */
    @GetMapping("/transaction-types")
    TransactionPermissions transactionTypes(@PathVariable String username, Authentication auth) {
        String cols = String.join(", ", TRANSACTION_COLUMNS.stream().map((c) -> "x." + c).toList());
        List<Row> rows = jdbc.query("SELECT x.TR_CODE, COALESCE(t.TransTypeDesc, CONCAT('Type ', x.TR_CODE)), " + cols
                + " FROM access a JOIN access_trans x ON x.BR_CODE = a.AccessBR_CODE AND x.USERID = a.AccessID"
                + " LEFT JOIN transtype t ON t.TransTypeID = x.TR_CODE WHERE a.AccessUserName = ? ORDER BY t.TransTypeDesc",
            (rs, i) -> {
                List<Boolean> flags = new ArrayList<>();
                for (int k = 0; k < TRANSACTION_COLUMNS.size(); k++) flags.add(rs.getInt(3 + k) == 1);
                return new Row(rs.getInt(1), rs.getString(2), flags);
            }, username);
        String note = editNote(auth, username, levelOf(username, "USER"));
        return new TransactionPermissions(TRANSACTION_LABELS, TRANSACTION_COLUMNS, note == null, note, rows);
    }

    private Field field(Spec s, String raw, boolean canEdit) {
        String v = raw == null || raw.isBlank() || raw.startsWith("0000-00-00") ? null : raw.trim();
        Map<String, String> choices = optionsFor(s);
        String shown = v;
        if (v != null && s.kind().equals("list")) {
            shown = String.join(", ", java.util.Arrays.stream(v.split(",")).map(String::trim).filter((x) -> !x.isEmpty())
                .map((x) -> choices != null && choices.containsKey(x) ? choices.get(x) : describe(s.how() == null ? null : lookups.all().get(s.how()), x)).toList());
        } else if (v != null && choices != null && choices.containsKey(v)) {
            shown = choices.get(v);
        }
        List<Option> options = choices == null ? null : choices.entrySet().stream().map((e) -> new Option(e.getKey(), e.getValue())).toList();
        return new Field(s.label(), shown, s.kind(), s.column(), canEdit && !NOT_EDITABLE.contains(s.column()), v, options);
    }

    /** The choices for a field, value to label, or null when it is free text, a number, a date or a switch. */
    private Map<String, String> optionsFor(Spec s) {
        if (s.how() == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        switch (s.how()) {
            case "level" -> UserEditRules.levels().forEach((l) -> out.put(l, l));
            case "assignment" -> {
                // The old app keeps no list of its own, so the choices are the assignments already in use.
                Set<String> names = new java.util.TreeSet<>();
                jdbc.query("SELECT DISTINCT AccessCurrAssign FROM access WHERE AccessCurrAssign IS NOT NULL", (rs) -> {
                    for (String part : rs.getString(1).split(",")) if (!part.isBlank()) names.add(part.trim());
                });
                names.forEach((x) -> out.put(x, x));
            }
            default -> {
                Map<Integer, String> table = lookups.all().get(s.how());
                if (table == null) return null;
                // Lists of codes are stored zero-padded to three digits ("003,002"); single choices as a plain number.
                table.forEach((k, label) -> out.put(s.kind().equals("list") ? String.format("%03d", k) : String.valueOf(k), label));
            }
        }
        return out;
    }

    // ---- editing ------------------------------------------------------------------------------------------------

    record Change(String from, String to) {}

    record EditRequest(Map<String, Change> changes) {}

    record EditResult(int changed) {}

    private boolean editAvailable() {
        if (!enabled) return false;
        return db.tableExists("ecis_audit_log");
    }

    /** The level of a signed-in person. The bootstrap administrator is not in the legacy list and counts as a super administrator. */
    private String levelOf(String username, String fallback) {
        List<String> found = jdbc.query("SELECT AccessLevel FROM access WHERE AccessUserName = ? LIMIT 1", (rs, i) -> rs.getString(1), username);
        return found.isEmpty() ? fallback : found.get(0);
    }

    /** Why this person's account cannot be edited by the caller right now, or null when it can. */
    private String editNote(Authentication auth, String target, String targetLevel) {
        if (!editAvailable()) return "Editing is turned off on this server.";
        return UserEditRules.problem(auth.getName(), levelOf(auth.getName(), "SUPERADMIN"), target, targetLevel, false);
    }

    @PatchMapping("/profile")
    EditResult edit(@PathVariable String username, @RequestBody EditRequest req, Authentication auth) {
        if (!editAvailable()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Editing is not enabled on this server.");
        if (req.changes() == null || req.changes().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is nothing to save.");

        // 1. Check every value before touching the database.
        Map<String, String> clean = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (var e : req.changes().entrySet()) {
            Spec spec = SPECS.get(e.getKey());
            if (spec == null || NOT_EDITABLE.contains(spec.column())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The field '" + e.getKey() + "' cannot be edited.");
            Map<String, String> choices = optionsFor(spec);
            try {
                clean.put(spec.column(), UserEditRules.normalize(spec.label(), spec.kind().equals("list") ? "text" : spec.kind(),
                    choices == null ? null : choices.keySet(), spec.kind().equals("list"), columnLengths().get(spec.column()),
                    e.getValue() == null ? null : e.getValue().to()));
            } catch (IllegalArgumentException ex) {
                problems.add(ex.getMessage());
            }
        }
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", problems));

        // 2. Lock the row, check who may do this and that nothing moved, then write the update with its audit rows.
        String by = auth.getName();
        return tx.execute((status) -> {
            List<String> cols = new ArrayList<>(clean.keySet());
            if (!cols.contains("AccessLevel")) cols.add("AccessLevel");
            cols.add("AccessUserName");
            List<Map<String, String>> found = jdbc.query("SELECT " + String.join(", ", cols) + " FROM access WHERE AccessUserName = ? FOR UPDATE",
                (rs, i) -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String c : cols) row.put(c, rs.getString(c));
                    return row;
                }, username);
            if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
            Map<String, String> current = found.get(0);

            String problem = UserEditRules.problem(by, levelOf(by, "SUPERADMIN"), current.get("AccessUserName"), current.get("AccessLevel"), clean.containsKey("AccessLevel"));
            if (problem != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, problem);

            Map<String, String> changed = new LinkedHashMap<>();
            for (var e : clean.entrySet()) {
                Spec spec = SPECS.get(e.getKey());
                String opened = req.changes().get(e.getKey()).from();
                if (!sameValue(spec, current.get(e.getKey()), opened)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "This account was changed by someone else since you opened it. Reload it and try again.");
                }
                if (!sameValue(spec, current.get(e.getKey()), e.getValue())) changed.put(e.getKey(), e.getValue());
            }
            if (changed.isEmpty()) return new EditResult(0);

            String sets = String.join(", ", changed.keySet().stream().map((c) -> c + " = ?").toList());
            List<Object> args = new ArrayList<>(changed.values());
            args.add(current.get("AccessUserName"));
            jdbc.update("UPDATE access SET " + sets + " WHERE AccessUserName = ?", args.toArray());
            for (var e : changed.entrySet()) audit(by, "UPDATE:" + e.getKey(), current.get("AccessUserName"), current.get(e.getKey()), e.getValue());
            return new EditResult(changed.size());
        });
    }

    record TransChange(int code, String column, boolean allowed) {}

    record TransEditRequest(List<TransChange> changes) {}

    /** Changes what a person may do with a type of transaction. */
    @PutMapping("/transaction-types")
    EditResult editTransactionTypes(@PathVariable String username, @RequestBody TransEditRequest req, Authentication auth) {
        if (!editAvailable()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Editing is not enabled on this server.");
        if (req.changes() == null || req.changes().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is nothing to save.");
        for (TransChange c : req.changes()) {
            if (!TRANSACTION_COLUMNS.contains(c.column())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The permission '" + c.column() + "' cannot be edited.");
        }
        String by = auth.getName();
        return tx.execute((status) -> {
            List<int[]> ids = jdbc.query("SELECT AccessBR_CODE, AccessID FROM access WHERE AccessUserName = ? FOR UPDATE", (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, username);
            if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
            String problem = UserEditRules.problem(by, levelOf(by, "SUPERADMIN"), username, levelOf(username, "USER"), false);
            if (problem != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, problem);
            int branch = ids.get(0)[0];
            int userId = ids.get(0)[1];
            int changed = 0;
            for (TransChange c : req.changes()) {
                List<Integer> now = jdbc.query("SELECT " + c.column() + " FROM access_trans WHERE BR_CODE = ? AND USERID = ? AND TR_CODE = ? FOR UPDATE",
                    (rs, i) -> rs.getInt(1), branch, userId, c.code());
                if (now.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This person has no setting for transaction type " + c.code() + ".");
                int next = c.allowed() ? 1 : 0;
                if (now.get(0) == next) continue;
                jdbc.update("UPDATE access_trans SET " + c.column() + " = ? WHERE BR_CODE = ? AND USERID = ? AND TR_CODE = ?", next, branch, userId, c.code());
                audit(by, "TRANS:" + c.code() + ":" + c.column(), username, String.valueOf(now.get(0)), String.valueOf(next));
                changed++;
            }
            return new EditResult(changed);
        });
    }

    private void audit(String by, String action, String subject, String prev, String next) {
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, prev_value, new_value) VALUES (NOW(), ?, ?, 'user', ?, ?, ?)", by, action, subject, prev, next);
    }

    private static boolean sameValue(Spec spec, String a, String b) {
        return spec.kind().equals("list") ? UserEditRules.sameList(a, b) : UserEditRules.same(spec.kind(), a, b);
    }

    /** Character limits of the access table's text columns, read once. */
    private Map<String, Integer> columnLengths() {
        Map<String, Integer> l = lengths;
        if (l == null) {
            lengths = l = db.textColumnLengths("access");
        }
        return l;
    }

    private static String describe(Map<Integer, String> table, String code) {
        if (table == null) return code;
        try {
            String text = table.get(Integer.parseInt(code));
            return text != null ? text : code;
        } catch (NumberFormatException e) {
            return code;
        }
    }
}
