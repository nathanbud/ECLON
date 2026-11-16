package ph.coop.core.security;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.member.LookupController;

/**
 * What the signed-in person may do, in the words of Settings > Users, so their window can notice when an administrator changes it while they
 * work and say what changed (the window asks every few seconds; see {@code AccessWatcher}). The server already re-reads a person's rights on
 * every request, so a change takes effect at once; this only keeps the screen and the person informed.
 *
 * <p>{@code since} is the fingerprint the window last saw: when nothing changed the answer is only the fingerprint, so asking often costs little.
 */
@RestController
@RequestMapping("/api/me")
class MyAccessController {

    /** One thing the person may or may not do: {@code key} is stable, {@code group} and {@code label} are the words of the Users screen. */
    record Item(String key, String group, String label, String value) {}

    /** Who changed this person's access last, and when (from the audit log), for the notice. */
    record LastChange(String by, String at) {}

    record MyAccess(String fingerprint, boolean changed, List<Item> items, LastChange lastChange) {}

    private static final Map<String, String> TAB_NAMES = Map.of("general", "General", "transactions", "Transactions", "pos", "POS / Priority Queue",
        "client", "Client", "receivables", "Receivables", "deposits", "Deposits", "others", "Others");
    /**
     * Every setting of the Users screen counts, switches, limits, choices and dates alike (an assignment or a department changes what a person
     * works on), except who the person is (the Person section: names, username, SMS number; the level is listed on its own) and these, which are
     * not access or end the sign-in themselves.
     */
    private static final String PERSON = "Person";
    private static final Set<String> SKIP = Set.of("AccessIsDisabled", "AccessExpiresInDays", "AccessConnTimeOut", "AccessLastChangePassDate", "AccessPassbookInitial");

    private final JdbcTemplate jdbc;
    private final LookupController lookups;
    private final ph.coop.core.loan.LoanSteps steps;

    MyAccessController(JdbcTemplate jdbc, LookupController lookups, ph.coop.core.loan.LoanSteps steps) {
        this.jdbc = jdbc;
        this.lookups = lookups;
        this.steps = steps;
    }

    private boolean present(UserProfileController.Spec s) {
        return !ph.coop.core.loan.LoanSteps.DEDUCTION_COLUMN.equals(s.column()) || steps.available();
    }

    @GetMapping("/access")
    MyAccess access(@RequestParam(required = false) String since, Authentication auth) {
        List<Item> items = items(auth);
        String fp = fingerprint(items);
        if (fp.equals(since)) return new MyAccess(fp, false, null, null);
        return new MyAccess(fp, since != null, items, since == null ? null : lastChange(auth.getName()));
    }

    private List<Item> items(Authentication auth) {
        List<Item> out = new ArrayList<>();
        List<String> columns = new ArrayList<>(List.of("AccessLevel"));
        UserProfileController.TABS.values().forEach((sections) -> sections.forEach((section, specs) -> specs.forEach((s) -> {
            if (!section.equals(PERSON) && !SKIP.contains(s.column()) && !s.kind().equals("note") && present(s)) columns.add(s.column());
        })));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + String.join(", ", columns) + " FROM access WHERE AccessUserName = ? LIMIT 1", auth.getName());
        if (rows.isEmpty()) {
            // the bootstrap administrator is not in the old system: only its roles
            auth.getAuthorities().stream().map((a) -> a.getAuthority()).filter((a) -> a.startsWith("ROLE_") && !a.equals("ROLE_USER")).sorted()
                .forEach((a) -> out.add(role(a.substring(5))));
            return out;
        }
        Map<String, Object> row = new org.springframework.util.LinkedCaseInsensitiveMap<>();
        row.putAll(rows.get(0));
        Object level = row.get("AccessLevel");
        out.add(new Item("level", "Your account", "Level", level == null || level.toString().isBlank() ? "User" : level.toString().trim()));
        jdbc.query("SELECT role FROM ecis_user_role WHERE username = ? ORDER BY role", (rs) -> { out.add(role(rs.getString(1))); }, auth.getName());
        UserProfileController.TABS.forEach((tab, sections) -> sections.forEach((section, specs) -> specs.forEach((s) -> {
            if (section.equals(PERSON) || SKIP.contains(s.column()) || s.kind().equals("note") || !present(s)) return; // a note is worked out from rights listed elsewhere
            out.add(new Item(s.column(), TAB_NAMES.getOrDefault(tab, tab) + " · " + section, s.label(), value(s, row.get(s.column()))));
        })));
        String cols = String.join(", ", UserProfileController.TRANSACTION_COLUMNS.stream().map((c) -> "x." + c).toList());
        jdbc.query("SELECT x.TR_CODE, COALESCE(t.TransTypeDesc, CONCAT('Type ', x.TR_CODE)), " + cols
                + " FROM access a JOIN access_trans x ON x.BR_CODE = a.AccessBR_CODE AND x.USERID = a.AccessID"
                + " LEFT JOIN transtype t ON t.TransTypeID = x.TR_CODE WHERE a.AccessUserName = ? ORDER BY x.TR_CODE", (rs) -> {
            int code = rs.getInt(1);
            String type = rs.getString(2) == null ? "Type " + code : rs.getString(2).trim();
            for (int k = 0; k < UserProfileController.TRANSACTION_COLUMNS.size(); k++) {
                out.add(new Item("TRANS:" + code + ":" + UserProfileController.TRANSACTION_COLUMNS.get(k), "Transaction types · " + type,
                    UserProfileController.TRANSACTION_LABELS.get(k), rs.getInt(3 + k) == 1 ? "Yes" : "No"));
            }
        }, auth.getName());
        return out;
    }

    private static Item role(String code) {
        String words = code.replace('_', ' ').toLowerCase();
        return new Item("role:" + code, "Roles", Character.toUpperCase(words.charAt(0)) + words.substring(1), "Yes");
    }

    /** A setting as the Users screen shows it: Yes or No, an amount, or the names behind the codes of a choice or a list. */
    private String value(UserProfileController.Spec s, Object v) {
        String kind = s.kind();
        if (kind.equals("bool")) return v != null && v.toString().trim().equals("1") ? "Yes" : "No";
        if (v == null || v.toString().isBlank() || v.toString().startsWith("0000-00-00")) return "—";
        String raw = v.toString().trim();
        if (kind.equals("date")) return raw.length() >= 10 ? raw.substring(0, 10) : raw;
        if (s.how() != null && !s.how().equals("level") && !s.how().equals("assignment")) {
            Map<Integer, String> table = lookups.all().get(s.how());
            if (table != null) {
                List<String> names = java.util.Arrays.stream(raw.split(",")).map(String::trim).filter((x) -> !x.isEmpty()).map((x) -> {
                    try {
                        String n = table.get(Integer.parseInt(x));
                        return n == null ? x : n.trim();
                    } catch (NumberFormatException e) {
                        return x;
                    }
                }).toList();
                return names.isEmpty() ? "—" : String.join(", ", names);
            }
        }
        if (kind.equals("list")) return String.join(", ", java.util.Arrays.stream(raw.split(",")).map(String::trim).filter((x) -> !x.isEmpty()).toList());
        if (kind.equals("money")) {
            try {
                return String.format("%,.2f", new BigDecimal(raw));
            } catch (NumberFormatException e) {
                return raw;
            }
        }
        return raw;
    }

    private LastChange lastChange(String username) {
        List<LastChange> r = jdbc.query("SELECT username, at FROM ecis_audit_log WHERE entity = 'user' AND entity_id = ? AND username <> ? ORDER BY at DESC LIMIT 1",
            (rs, i) -> {
                Timestamp at = rs.getTimestamp(2);
                return new LastChange(rs.getString(1), at == null ? null : at.toLocalDateTime().withNano(0).toString());
            }, username, username);
        return r.isEmpty() ? null : r.get(0);
    }

    static String fingerprint(List<Item> items) {
        StringBuilder s = new StringBuilder();
        for (Item i : items) s.append(i.key()).append('=').append(i.value()).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No SHA-256 here.");
        }
    }
}
