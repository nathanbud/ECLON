package ph.coop.core.inbox;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.coop.core.db.Db;
import ph.coop.core.workflow.WorkflowEngine;

/**
 * Everything waiting on the signed-in person, in one list: profile changes and member requests for a membership officer; journals and memos for an accountant; purchase requests and
 * orders for a purchase approver; stock adjustments for a stock approver; and any extra workflow step for their role. Each item says where to go to decide it ({@code view} and {@code tab}
 * name a screen of the desktop app). It never lists a person's own items, and it only reads: deciding happens on the item's own screen, with that screen's rules.
 */
@RestController
@RequestMapping("/api/inbox")
class InboxController {

    /** {@code kind} is a short code for the sort of work; {@code view} and {@code tab} say which screen decides it. */
    record Item(String kind, String label, String summary, String by, String at, String view, String tab) {}

    record Inbox(List<Item> items, int total, Map<String, Integer> byKind) {}

    private static final int PER_SOURCE = 100;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final WorkflowEngine workflow;
    private final Map<String, Boolean> known = new ConcurrentHashMap<>();

    InboxController(JdbcTemplate jdbc, Db db, WorkflowEngine workflow) {
        this.jdbc = jdbc;
        this.db = db;
        this.workflow = workflow;
    }

    @GetMapping
    Inbox inbox(Authentication auth) {
        String me = auth.getName();
        var roles = InboxRules.roles(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        InboxRules.Sources s = InboxRules.sourcesFor(roles);
        List<Item> out = new ArrayList<>();

        if (s.membership() && has("ecis_member_change")) {
            out.addAll(jdbc.query("""
                    SELECT ch.requested_by, ch.requested_at, COALESCE(NULLIF(c.AccountName, ''), CONCAT_WS(', ', c.LName, c.FName)), ch.client_id
                    FROM ecis_member_change ch LEFT JOIN client c ON c.ClientIDBrCode = ch.branch_code AND c.ClientID = ch.client_id
                    WHERE ch.status = 'PENDING' AND LOWER(ch.requested_by) <> LOWER(?) ORDER BY ch.requested_at LIMIT 100
                    """, (rs, i) -> new Item("CHANGE", "Profile change", "Changes to " + clean(rs.getString(3), "member " + rs.getInt(4)), rs.getString(1), iso(rs.getTimestamp(2)), "approvals", null), me));
        }
        if (s.membership() && has("ecis_member_request")) {
            out.addAll(jdbc.query("""
                    SELECT kind, summary, requested_by, requested_at FROM ecis_member_request
                    WHERE status = 'PENDING' AND batch_id IS NULL AND LOWER(requested_by) <> LOWER(?) ORDER BY requested_at LIMIT 100
                    """, (rs, i) -> new Item("MEMBER_REQUEST", requestLabel(rs.getString(1)), rs.getString(2), rs.getString(3), iso(rs.getTimestamp(4)), "approvals", null), me));
        }
        if (s.accounting() && has("ecis_journal")) {
            out.addAll(jdbc.query("""
                    SELECT status, reference, description, created_by, created_at, approved_at FROM ecis_journal
                    WHERE (status = 'FOR_REVIEW' AND LOWER(created_by) <> LOWER(?)) OR status = 'APPROVED' ORDER BY id LIMIT 100
                    """, (rs, i) -> {
                boolean review = "FOR_REVIEW".equals(rs.getString(1));
                return new Item(review ? "JOURNAL_REVIEW" : "JOURNAL_POST", review ? "Journal to review" : "Journal ready to post", rs.getString(2) + (rs.getString(3) == null ? "" : " - " + rs.getString(3)), rs.getString(4),
                    iso(rs.getTimestamp(review ? 5 : 6)), "accounting", "Journals");
            }, me));
        }
        if (s.accounting() && has("ecis_memo")) {
            out.addAll(jdbc.query("""
                    SELECT status, reference, description, created_by, approved_by, created_at, approved_at FROM ecis_memo
                    WHERE status IN ('FOR_REVIEW', 'APPROVED') ORDER BY id LIMIT 100
                    """, (rs, i) -> {
                boolean review = "FOR_REVIEW".equals(rs.getString(1));
                String maker = rs.getString(4), approver = rs.getString(5);
                if (review ? InboxRules.own(maker, me) : !InboxRules.mayPostMemo(maker, approver, me)) return null;
                return new Item(review ? "MEMO_REVIEW" : "MEMO_POST", review ? "Memo to review" : "Memo ready to post", rs.getString(2) + " - " + rs.getString(3), maker, iso(rs.getTimestamp(review ? 6 : 7)), "accounting", "Memos");
            }).stream().filter(java.util.Objects::nonNull).toList());
        }
        if (s.purchasing() && has("ecis_purchase_request")) {
            out.addAll(jdbc.query("""
                    SELECT request_no, purpose, requested_by, created_at FROM ecis_purchase_request
                    WHERE status = 'SUBMITTED' AND LOWER(requested_by) <> LOWER(?) ORDER BY id LIMIT 100
                    """, (rs, i) -> new Item("PURCHASE_REQUEST", "Purchase request", rs.getString(1) + " - " + rs.getString(2), rs.getString(3), iso(rs.getTimestamp(4)), "procurement", "Requests"), me));
        }
        if (s.purchasing() && has("ecis_purchase_order")) {
            out.addAll(jdbc.query("""
                    SELECT order_no, total, created_by, created_at FROM ecis_purchase_order
                    WHERE status = 'DRAFT' AND LOWER(created_by) <> LOWER(?) ORDER BY id LIMIT 100
                    """, (rs, i) -> new Item("PURCHASE_ORDER", "Purchase order", rs.getString(1) + " - " + rs.getBigDecimal(2).toPlainString(), rs.getString(3), iso(rs.getTimestamp(4)), "procurement", "Orders"), me));
        }
        if (s.stock() && has("ecis_stock_adjustment")) {
            out.addAll(jdbc.query("""
                    SELECT adjust_no, reason, requested_by, created_at FROM ecis_stock_adjustment
                    WHERE status = 'SUBMITTED' AND LOWER(requested_by) <> LOWER(?) ORDER BY id LIMIT 100
                    """, (rs, i) -> new Item("STOCK_ADJUSTMENT", "Stock adjustment", rs.getString(1) + " - " + rs.getString(2), rs.getString(3), iso(rs.getTimestamp(4)), "inventory", "Adjustments"), me));
        }
        if (has("ecis_workflow_instance")) {
            for (WorkflowEngine.Waiting w : workflow.waitingFor(roles, me)) {
                out.add(new Item("WORKFLOW", w.label(), w.description() + " (your step: " + w.step() + ")", w.requestedBy(), w.since() == null ? null : w.since().toString(), "approvals", null));
            }
        }

        // longest waiting first, so nothing is forgotten at the bottom
        out.sort(Comparator.comparing((Item i) -> i.at() == null ? "9999" : i.at()));
        Map<String, Integer> byKind = new java.util.TreeMap<>();
        for (Item i : out) byKind.merge(i.kind(), 1, Integer::sum);
        return new Inbox(out, out.size(), byKind);
    }

    /** Just the number, for the badge on the home page and the side menu. */
    @GetMapping("/count")
    Map<String, Integer> count(Authentication auth) {
        return Map.of("count", inbox(auth).total());
    }

    private boolean has(String table) {
        return known.computeIfAbsent(table, db::tableExists);
    }

    private static String requestLabel(String kind) {
        return switch (kind) {
            case "REGISTER" -> "New member";
            case "CLOSE" -> "Close membership";
            case "REACTIVATE" -> "Reactivate membership";
            case "BENEF" -> "Beneficiary change";
            default -> "Member request";
        };
    }

    private static String clean(String s, String otherwise) {
        return s == null || s.isBlank() ? otherwise : s;
    }

    private static String iso(Timestamp t) {
        if (t == null) return null;
        LocalDateTime d = t.toLocalDateTime();
        return d.toString();
    }
}
