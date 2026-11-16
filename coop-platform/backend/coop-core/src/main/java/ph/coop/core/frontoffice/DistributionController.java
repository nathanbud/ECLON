package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;
import ph.coop.core.deposit.DividendRun;
import ph.coop.core.deposit.DividendRun.Credit;
import ph.coop.core.deposit.DividendRun.Held;
import ph.coop.core.deposit.DividendRun.Plan;
import ph.coop.core.deposit.DividendRun.Policy;
import ph.coop.core.frontoffice.DistributionStore.Batch;
import ph.coop.core.frontoffice.FrontOfficeAccess.Perms;

/**
 * Interest on share capital and patronage refund as a stored yearly distribution (pics/divpat_system.docx sections 4 to 7 and 11; documentation/dividend-spec.md).
 * Prepare works the year out with the settings (DividendRun, ecis_distribution_policy and the statutory funds) and stores every member's figures; preparing the year
 * again replaces a distribution not yet approved (the old one is kept as SUPERSEDED). It is then submitted, and a different person approves it or rejects it with a
 * reason. Only an approved distribution is encoded into vouchers (DividendController /encode), from exactly its stored credits. The reports are read from what was
 * stored, so they never change. Settings are changed by administrators with a reason; nothing statutory is built in.
 */
@RestController
@RequestMapping("/api/front-office/dividends")
class DistributionController {

    private static final int CODE = 54;
    private static final List<String> LIVE = List.of("DRAFT", "SUBMITTED", "APPROVED", "ENCODED");

    // ---- settings ---------------------------------------------------------------------------------------------------

    record Option(int id, String name) {}

    record Fund(Long id, String name, BigDecimal percent, String account, boolean active) {}

    record PolicyOut(List<Integer> patronageEvents, List<Integer> eligibleStatuses, List<Integer> iscTypes, List<Integer> prTypes, BigDecimal minAverageShare, String updatedBy, String updatedAt) {}

    record Settings(PolicyOut policy, List<Fund> funds, List<Option> events, List<Option> statuses, List<Option> types, String words, boolean mayEdit) {}

    record PolicyIn(List<Integer> patronageEvents, List<Integer> eligibleStatuses, List<Integer> iscTypes, List<Integer> prTypes, BigDecimal minAverageShare, String reason) {}

    record FundsIn(List<Fund> funds, String reason) {}

    // ---- distributions ----------------------------------------------------------------------------------------------

    record FundRow(String name, BigDecimal percent, BigDecimal amount) {}

    record EventRow(int event, String name, BigDecimal amount, int members) {}

    record LeftOut(int clientId, String name, BigDecimal total, String reason) {}

    record Rights(boolean maySubmit, boolean mayApprove, boolean mayReject, boolean mayEncode, String note) {}

    record Detail(Batch batch, List<FundRow> funds, List<EventRow> patronage, Map<String, BigDecimal> credited, List<LeftOut> leftOut, List<String> controls, Rights rights) {}

    record Decision(String note) {}

    record Statement(long batchId, int clientId, String name, int year, BigDecimal averageShareMonth, BigDecimal dividendRate, BigDecimal dividend, BigDecimal qualifiedPatronage,
                     BigDecimal patronageRate, BigDecimal refund, BigDecimal total, BigDecimal mutualBenefit, BigDecimal hospitalization, BigDecimal rest, String status) {}

    record Section(String heading, List<String> columns, List<List<String>> rows, List<String> notes) {}

    record Report(String title, String subtitle, List<Section> sections) {}

    private final JdbcTemplate jdbc;
    private final FrontOfficeAccess access;
    private final DividendRun run;
    private final DistributionStore store;
    private final MemberLedgerPoster poster;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Db db;

    DistributionController(JdbcTemplate jdbc, FrontOfficeAccess access, DividendRun run, DistributionStore store, MemberLedgerPoster poster, PlatformTransactionManager txm, Clock clock, Db db) {
        this.jdbc = jdbc;
        this.access = access;
        this.run = run;
        this.store = store;
        this.poster = poster;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
        this.db = db;
    }

    private static boolean admin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch((a) -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private Perms seeing(Authentication auth) {
        Perms p = access.of(auth);
        if (!p.canFind(CODE) && !admin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot look up dividends.");
        return p;
    }

    private Perms working(Authentication auth) {
        Perms p = access.of(auth);
        if (!p.canNew(CODE)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not prepare dividends (the New right on Dividends, Settings > Users > Transaction Types).");
        return p;
    }

    private static String reason(String r, int min) {
        String s = r == null ? "" : r.trim();
        if (s.length() < min) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the reason (at least " + min + " letters).");
        return s.substring(0, Math.min(s.length(), 300));
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    static List<Integer> ints(String csv) {
        List<Integer> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String x : csv.split(",")) if (!x.isBlank()) out.add(Integer.parseInt(x.trim()));
        return out;
    }

    private static Set<Integer> set(String csv) {
        return csv == null || csv.isBlank() ? null : new LinkedHashSet<>(ints(csv));
    }

    private static String csv(List<Integer> xs) {
        return xs == null || xs.isEmpty() ? null : xs.stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** The settings of the branch; the old system's rules where none are stored. */
    Policy policy(int branch) {
        List<Policy> p = jdbc.query("SELECT patronage_events, eligible_statuses, isc_types, pr_types, min_average_share FROM ecis_distribution_policy WHERE branch_code = ?",
            (rs, i) -> new Policy(ints(rs.getString(1)), set(rs.getString(2)), set(rs.getString(3)), set(rs.getString(4)), rs.getBigDecimal(5)), branch);
        return p.isEmpty() ? Policy.OLD_SYSTEM : p.get(0);
    }

    private List<Option> options(String sql) {
        return jdbc.query(sql, (rs, i) -> new Option(rs.getInt(1), rs.getString(2) == null ? "" : rs.getString(2).trim()));
    }

    /** The loan ledger events (sle_type) that apply to loans (class 12), by number. */
    private List<Option> loanEvents() {
        Map<Integer, String> m = new LinkedHashMap<>();
        // the fourth column, oddly named, holds which ledger classes the event applies to ("12xx,13xx,")
        jdbc.query("SELECT SLE_TypeCode, SLE_TypeDesc, SLE_TypeActive FROM sle_type ORDER BY SLE_TypeCode", (rs) -> {
            String mask = rs.getString(3);
            if (mask != null && mask.contains("12xx") && rs.getInt(1) != 11) m.putIfAbsent(rs.getInt(1), rs.getString(2) == null ? "" : rs.getString(2).trim());
        });
        return m.entrySet().stream().map((e) -> new Option(e.getKey(), e.getValue())).toList();
    }

    private static String named(Set<Integer> ids, List<Option> names, String everyone) {
        if (ids == null) return everyone;
        Map<Integer, String> n = names.stream().collect(Collectors.toMap(Option::id, Option::name, (a, b) -> a));
        return ids.stream().map((i) -> n.getOrDefault(i, String.valueOf(i)) + " (" + i + ")").collect(Collectors.joining(", "));
    }

    /** The settings in words, kept with each distribution. */
    private String words(Policy p) {
        List<Option> events = loanEvents();
        String ev = p.patronageEvents().isEmpty() ? "none" : named(new LinkedHashSet<>(p.patronageEvents()), events, "");
        return "Qualified patronage: " + ev + ". Taking part: " + named(p.eligibleStatuses(), options("SELECT AcctStatID, AcctStatDesc FROM acctstat ORDER BY AcctStatID"), "every member status")
            + ". Interest on share capital: " + named(p.iscTypes(), options("SELECT ClientTypeID, ClientTypeDesc FROM clienttype ORDER BY ClientTypeID"), "every member type")
            + ". Patronage refund: " + named(p.prTypes(), options("SELECT ClientTypeID, ClientTypeDesc FROM clienttype ORDER BY ClientTypeID"), "every member type")
            + ". Minimum average share-month: " + (p.minAverageShare() == null || p.minAverageShare().signum() == 0 ? "none" : money(p.minAverageShare())) + ".";
    }

    private List<Fund> funds(int branch, boolean activeOnly) {
        return jdbc.query("SELECT id, name, percent, account, active FROM ecis_distribution_fund WHERE branch_code = ?" + (activeOnly ? " AND active = 1" : "") + " ORDER BY position, id",
            (rs, i) -> new Fund(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getInt(5) == 1), branch);
    }

    @GetMapping("/settings")
    Settings settings(Authentication auth) {
        Perms p = seeing(auth);
        Policy pol = policy(p.branch());
        List<Map<String, Object>> who = jdbc.queryForList("SELECT updated_by, updated_at FROM ecis_distribution_policy WHERE branch_code = ?", p.branch());
        PolicyOut out = new PolicyOut(pol.patronageEvents(), pol.eligibleStatuses() == null ? null : List.copyOf(pol.eligibleStatuses()), pol.iscTypes() == null ? null : List.copyOf(pol.iscTypes()),
            pol.prTypes() == null ? null : List.copyOf(pol.prTypes()), pol.minAverageShare(), who.isEmpty() ? null : (String) who.get(0).get("updated_by"),
            who.isEmpty() ? null : String.valueOf(who.get(0).get("updated_at")).replace('T', ' ').substring(0, 16));
        return new Settings(out, funds(p.branch(), false), loanEvents(), options("SELECT AcctStatID, AcctStatDesc FROM acctstat ORDER BY AcctStatID"),
            options("SELECT ClientTypeID, ClientTypeDesc FROM clienttype ORDER BY ClientTypeID"), words(pol), admin(auth));
    }

    @PutMapping("/settings/policy")
    Settings putPolicy(@RequestBody PolicyIn in, Authentication auth) {
        if (!admin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator may change the dividend settings.");
        Perms p = access.of(auth);
        if (in == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send the settings.");
        String why = reason(in.reason(), 5);
        Set<Integer> known = loanEvents().stream().map(Option::id).collect(Collectors.toSet());
        if (in.patronageEvents() != null) for (int e : in.patronageEvents()) if (!known.contains(e)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loan event " + e + " is not a loan event.");
        if (in.minAverageShare() != null && in.minAverageShare().signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The minimum average share-month cannot be negative.");
        if (in.eligibleStatuses() != null && in.eligibleStatuses().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one member status, or every status.");
        String before = words(policy(p.branch()));
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        int n = jdbc.update("UPDATE ecis_distribution_policy SET patronage_events = ?, eligible_statuses = ?, isc_types = ?, pr_types = ?, min_average_share = ?, updated_by = ?, updated_at = ? WHERE branch_code = ?",
            csv(in.patronageEvents()) == null ? "" : csv(in.patronageEvents()), csv(in.eligibleStatuses()), csv(in.iscTypes()), csv(in.prTypes()),
            in.minAverageShare() == null ? BigDecimal.ZERO : in.minAverageShare(), auth.getName(), now, p.branch());
        if (n == 0) {
            jdbc.update("INSERT INTO ecis_distribution_policy (branch_code, patronage_events, eligible_statuses, isc_types, pr_types, min_average_share, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                p.branch(), csv(in.patronageEvents()) == null ? "" : csv(in.patronageEvents()), csv(in.eligibleStatuses()), csv(in.iscTypes()), csv(in.prTypes()),
                in.minAverageShare() == null ? BigDecimal.ZERO : in.minAverageShare(), auth.getName(), now);
        }
        AuditLog.write(jdbc, auth.getName(), "dividend.settings.policy", "ecis_distribution_policy", String.valueOf(p.branch()), before, words(policy(p.branch())), why);
        return settings(auth);
    }

    /** Replaces the list of statutory funds: existing ones are changed, new ones added, the ones left out switched off (never deleted). */
    @PutMapping("/settings/funds")
    Settings putFunds(@RequestBody FundsIn in, Authentication auth) {
        if (!admin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator may change the statutory funds.");
        Perms p = access.of(auth);
        if (in == null || in.funds() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send the funds.");
        String why = reason(in.reason(), 5);
        BigDecimal total = BigDecimal.ZERO;
        for (Fund f : in.funds()) {
            if (f.name() == null || f.name().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every fund needs a name.");
            if (f.percent() == null || f.percent().signum() < 0 || f.percent().compareTo(new BigDecimal("100")) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The percent of " + f.name().trim() + " must be between 0 and 100.");
            if (f.active()) total = total.add(f.percent());
        }
        if (total.compareTo(new BigDecimal("100")) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The funds add up to more than 100 percent of the net surplus.");
        String before = funds(p.branch(), true).stream().map((f) -> f.name() + " " + f.percent().stripTrailingZeros().toPlainString() + "%").collect(Collectors.joining(", "));
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        tx.executeWithoutResult((st) -> {
            Set<Long> kept = new java.util.HashSet<>();
            int pos = 1;
            for (Fund f : in.funds()) {
                String account = f.account() == null || f.account().isBlank() ? null : f.account().trim();
                if (f.id() != null && jdbc.update("UPDATE ecis_distribution_fund SET name = ?, percent = ?, account = ?, position = ?, active = ?, updated_by = ?, updated_at = ? WHERE id = ? AND branch_code = ?",
                        f.name().trim(), f.percent(), account, pos, f.active() ? 1 : 0, auth.getName(), now, f.id(), p.branch()) == 1) {
                    kept.add(f.id());
                } else {
                    kept.add(db.insertReturningId("INSERT INTO ecis_distribution_fund (branch_code, name, percent, account, position, active, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", "id",
                        p.branch(), f.name().trim(), f.percent(), account, pos, f.active() ? 1 : 0, auth.getName(), now));
                }
                pos++;
            }
            for (Fund old : funds(p.branch(), false)) {
                if (!kept.contains(old.id()) && old.active()) jdbc.update("UPDATE ecis_distribution_fund SET active = 0, updated_by = ?, updated_at = ? WHERE id = ?", auth.getName(), now, old.id());
            }
        });
        String after = funds(p.branch(), true).stream().map((f) -> f.name() + " " + f.percent().stripTrailingZeros().toPlainString() + "%").collect(Collectors.joining(", "));
        AuditLog.write(jdbc, auth.getName(), "dividend.settings.funds", "ecis_distribution_fund", String.valueOf(p.branch()), before, after, why);
        return settings(auth);
    }

    // ---- distributions ----------------------------------------------------------------------------------------------

    @GetMapping("/distributions")
    List<Batch> list(Authentication auth) {
        Perms p = seeing(auth);
        return jdbc.query("SELECT " + DistributionStore.COLUMNS + " FROM ecis_distribution_batch WHERE branch_code = ? ORDER BY year DESC, id DESC LIMIT 100",
            (rs, i) -> DistributionStore.batch(rs), p.branch());
    }

    private LocalDate cutoff(String text) {
        LocalDate d;
        try {
            d = LocalDate.parse(text);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the cut-off date, such as 2027-02-21.");
        }
        if (d.getYear() < 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That cut-off date is too early.");
        if (d.isAfter(LocalDate.now(clock))) throw new ResponseStatusException(HttpStatus.CONFLICT, "That cut-off date has not come yet.");
        return d;
    }

    /**
     * Works the year out with the current settings and stores it as a DRAFT, member by member. A distribution of the same year not yet approved is replaced
     * (kept as SUPERSEDED); an approved or encoded one, or vouchers already made for the year, stop it.
     */
    @PostMapping("/distributions")
    Detail prepare(@RequestBody DividendController.Request in, Authentication auth) {
        Perms p = working(auth);
        if (in == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the cut-off date and the rates or allocations.");
        LocalDate cutoff = cutoff(in.cutoff());
        DividendRun.Params params = in.params();
        if (params.fromAllocation() && params.statutory() == null) {
            params = new DividendRun.Params(params.dividendRate(), params.patronageRate(), params.mutualBenefit(), params.hospitalization(), params.overrides(),
                params.iscAllocation(), params.prAllocation(), params.netSurplus(),
                funds(p.branch(), true).stream().map((f) -> new DividendRun.Statutory(f.name(), f.percent())).toList(), params.approvalRef());
        }
        Policy pol = policy(p.branch());
        Plan plan;
        try {
            plan = run.plan(p.branch(), cutoff, params, pol);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (plan.credits().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No member earns interest or a refund with these figures.");
        BigDecimal isc = plan.members().stream().map(DividendRun.Member::dividend).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pr = plan.members().stream().map(DividendRun.Member::refund).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> over = DividendController.overAllocation(plan, isc, pr);
        if (!over.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.join(" ", over));
        int year = plan.year();
        String policyWords = words(pol);
        DividendRun.Params pa = plan.params();
        long id = tx.execute((st) -> {
            List<Batch> same = jdbc.query("SELECT " + DistributionStore.COLUMNS + " FROM ecis_distribution_batch WHERE branch_code = ? AND year = ? ORDER BY id FOR UPDATE",
                (rs, i) -> DistributionStore.batch(rs), p.branch(), year);
            for (Batch b : same) {
                if ("APPROVED".equals(b.status()) || "ENCODED".equals(b.status())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The " + year + " distribution is already " + b.status().toLowerCase() + ". Reject it first to prepare the year again.");
                }
            }
            Integer vouchers = jdbc.queryForObject("SELECT COUNT(*) FROM transsum WHERE TR_BRCODE = ? AND TR_YEAR = ? AND TR_MODULE = 6 AND TR_CODE = ?", Integer.class, p.branch(), cutoff.getYear(), CODE);
            if (vouchers != null && vouchers > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Dividend vouchers already exist for " + cutoff.getYear() + ".");
            // the earlier versions step aside before the new one is written (one live distribution a year)
            jdbc.update("UPDATE ecis_distribution_batch SET status = 'SUPERSEDED' WHERE branch_code = ? AND year = ? AND status IN ('DRAFT', 'SUBMITTED')", p.branch(), year);
            Map<Integer, Object[]> left = new LinkedHashMap<>();
            for (Held h : plan.held()) left.put(h.clientId(), new Object[] {h.total(), h.reason()});
            for (Held h : plan.excluded()) {
                left.merge(h.clientId(), new Object[] {h.total(), "Not eligible: " + h.reason()},
                    (a, b) -> new Object[] {((BigDecimal) a[0]).add((BigDecimal) b[0]), (a[1] + "; " + b[1])});
            }
            Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
            long newId = db.insertReturningId("""
                    INSERT INTO ecis_distribution_batch (branch_code, year, cutoff, method, status, net_surplus, available, isc_allocation, pr_allocation, approval_ref, dividend_rate,
                      patronage_rate, mutual_benefit, hospitalization, overrides, total_average_share, total_patronage, members, total_isc, total_pr, total, held, excluded, policy,
                      prepared_by, prepared_at)
                    VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "id", p.branch(), year, java.sql.Date.valueOf(cutoff), pa.fromAllocation() ? "ALLOCATION" : "RATES", pa.netSurplus(),
                pa.fromAllocation() ? DividendRun.available(pa.netSurplus(), pa.statutory()) : null, pa.iscAllocation(), pa.prAllocation(),
                pa.approvalRef() == null || pa.approvalRef().isBlank() ? null : pa.approvalRef().trim(), plan.dividendRate(), plan.patronageRate(), pa.mutualBenefit(), pa.hospitalization(),
                pa.overrides() == null ? 0 : pa.overrides().size(), plan.totalAverageShareMonth(), plan.totalQualifiedPatronage(), plan.members().size(), isc, pr, isc.add(pr),
                plan.held().size(), plan.excluded().size(), policyWords.substring(0, Math.min(policyWords.length(), 1000)), auth.getName(), now);
            jdbc.update("UPDATE ecis_distribution_batch SET superseded_by = ? WHERE branch_code = ? AND year = ? AND status = 'SUPERSEDED' AND superseded_by IS NULL", newId, p.branch(), year);
            if (pa.fromAllocation() && pa.statutory() != null) {
                List<Object[]> rows = new ArrayList<>();
                int pos = 1;
                for (DividendRun.Statutory s : pa.statutory()) {
                    rows.add(new Object[] {newId, pos++, s.name().trim(), s.percent(), pa.netSurplus().multiply(s.percent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)});
                }
                jdbc.batchUpdate("INSERT INTO ecis_distribution_batch_fund (batch_id, position, name, percent, amount) VALUES (?, ?, ?, ?, ?)", rows);
            }
            jdbc.batchUpdate("INSERT INTO ecis_distribution_member (batch_id, client_id, average_share, qualified_patronage, isc, pr, total, mutual_benefit, hospitalization, rest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                plan.members().stream().map((m) -> new Object[] {newId, m.clientId(), m.averageShareMonth(), m.qualifiedPatronage(), m.dividend(), m.refund(), m.total(), m.mutualBenefit(), m.hospitalization(), m.rest()}).toList());
            List<Object[]> credits = new ArrayList<>();
            int seq = 1;
            for (Credit c : plan.credits()) credits.add(new Object[] {newId, seq++, c.clientId(), c.slc(), c.typeCode(), c.refNo(), c.kind(), c.amount()});
            jdbc.batchUpdate("INSERT INTO ecis_distribution_credit (batch_id, seq, client_id, slc_code, slt_code, ref_no, kind, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", credits);
            jdbc.batchUpdate("INSERT INTO ecis_distribution_held (batch_id, client_id, total, reason) VALUES (?, ?, ?, ?)",
                left.entrySet().stream().map((e) -> new Object[] {newId, e.getKey(), e.getValue()[0], ((String) e.getValue()[1]).substring(0, Math.min(((String) e.getValue()[1]).length(), 200))}).toList());
            jdbc.batchUpdate("INSERT INTO ecis_distribution_patronage (batch_id, event, amount, members) VALUES (?, ?, ?, ?)",
                plan.patronageByEvent().entrySet().stream().map((e) -> new Object[] {newId, e.getKey(), e.getValue(), plan.patronageMembers().getOrDefault(e.getKey(), 0)}).toList());
            AuditLog.write(jdbc, auth.getName(), "dividend.distribution.prepare", "ecis_distribution_batch", String.valueOf(newId), null,
                year + ": " + plan.members().size() + " members, interest on share capital " + isc.toPlainString() + " at " + plan.dividendRate().stripTrailingZeros().toPlainString()
                    + "%, patronage refund " + pr.toPlainString() + " at " + plan.patronageRate().stripTrailingZeros().toPlainString() + "%" + (same.isEmpty() ? "" : "; replaces the earlier draft"), null);
            return newId;
        });
        return detail(id, auth);
    }

    @GetMapping("/distributions/{id}")
    Detail detail(@PathVariable long id, Authentication auth) {
        Perms p = seeing(auth);
        Batch b = found(id, p.branch());
        List<FundRow> funds = jdbc.query("SELECT name, percent, amount FROM ecis_distribution_batch_fund WHERE batch_id = ? ORDER BY position",
            (rs, i) -> new FundRow(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), id);
        Map<Integer, String> eventNames = loanEvents().stream().collect(Collectors.toMap(Option::id, Option::name, (a, c) -> a));
        List<EventRow> patronage = jdbc.query("SELECT event, amount, members FROM ecis_distribution_patronage WHERE batch_id = ? ORDER BY event",
            (rs, i) -> new EventRow(rs.getInt(1), eventNames.getOrDefault(rs.getInt(1), "Event " + rs.getInt(1)), rs.getBigDecimal(2), rs.getInt(3)), id);
        Map<String, BigDecimal> credited = new LinkedHashMap<>();
        jdbc.query("SELECT kind, SUM(amount) FROM ecis_distribution_credit WHERE batch_id = ? GROUP BY kind ORDER BY kind", (rs) -> { credited.put(rs.getString(1), rs.getBigDecimal(2)); }, id);
        Map<Integer, String> names = names(p.branch());
        List<LeftOut> left = jdbc.query("SELECT client_id, total, reason FROM ecis_distribution_held WHERE batch_id = ? ORDER BY total DESC, client_id LIMIT 50",
            (rs, i) -> new LeftOut(rs.getInt(1), names.get(rs.getInt(1)), rs.getBigDecimal(2), rs.getString(3)), id);
        return new Detail(b, funds, patronage, credited, left, controls(b), rights(b, auth, p));
    }

    /** The reconciliation the specification asks for: distributed against allocated, and what is credited against the total. */
    private List<String> controls(Batch b) {
        List<String> out = new ArrayList<>();
        if (b.iscAllocation() != null) out.add("Interest on share capital: allocated " + money(b.iscAllocation()) + ", distributed " + money(b.totalIsc()) + ", difference " + money(b.iscAllocation().subtract(b.totalIsc())) + ".");
        if (b.prAllocation() != null) out.add("Patronage refund: allocated " + money(b.prAllocation()) + ", distributed " + money(b.totalPr()) + ", difference " + money(b.prAllocation().subtract(b.totalPr())) + ".");
        BigDecimal credited = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ecis_distribution_credit WHERE batch_id = ?", BigDecimal.class, b.id());
        out.add(credited.compareTo(b.total()) == 0 ? "The credits to members add up to the total, " + money(b.total()) + "."
            : "The credits to members (" + money(credited) + ") do not add up to the total (" + money(b.total()) + ").");
        return out;
    }

    private Rights rights(Batch b, Authentication auth, Perms p) {
        boolean may = p.canNew(CODE);
        boolean mine = auth.getName().equalsIgnoreCase(b.preparedBy());
        String s = b.status();
        String note = switch (s) {
            case "DRAFT" -> "Prepared; submit it for review when the figures are right. Preparing the year again replaces it.";
            case "SUBMITTED" -> mine ? "Waiting for someone else to review and approve it." : "Waiting for review: approve it, or reject it with a reason.";
            case "APPROVED" -> "Approved. Encoding makes the vouchers from these figures; a second person approves them and a third posts them in Accounting > Dividends.";
            case "ENCODED" -> "Vouchers were made from this distribution.";
            case "REJECTED" -> "Rejected: " + (b.decisionNote() == null ? "" : b.decisionNote());
            default -> "Replaced by a later version of the year.";
        };
        return new Rights(may && "DRAFT".equals(s), (may || admin(auth)) && "SUBMITTED".equals(s) && !mine, (may || admin(auth)) && List.of("DRAFT", "SUBMITTED", "APPROVED").contains(s),
            may && "APPROVED".equals(s), note);
    }

    private Batch found(long id, int branch) {
        Batch b = store.find(id, branch, false);
        if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such distribution.");
        return b;
    }

    private Batch locked(long id, int branch) {
        Batch b = store.find(id, branch, true);
        if (b == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such distribution.");
        return b;
    }

    @PostMapping("/distributions/{id}/submit")
    Detail submit(@PathVariable long id, Authentication auth) {
        Perms p = working(auth);
        tx.executeWithoutResult((st) -> {
            Batch b = locked(id, p.branch());
            if (!"DRAFT".equals(b.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft can be submitted; this one is " + b.status().toLowerCase() + ".");
            jdbc.update("UPDATE ecis_distribution_batch SET status = 'SUBMITTED', submitted_by = ?, submitted_at = ? WHERE id = ?", auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock)), id);
            AuditLog.write(jdbc, auth.getName(), "dividend.distribution.submit", "ecis_distribution_batch", String.valueOf(id), "DRAFT", "SUBMITTED", null);
        });
        return detail(id, auth);
    }

    /** A different person than the one who prepared it approves it; from then on its figures are what the vouchers carry. */
    @PostMapping("/distributions/{id}/approve")
    Detail approve(@PathVariable long id, @RequestBody(required = false) Decision in, Authentication auth) {
        Perms p = access.of(auth);
        if (!p.canNew(CODE) && !admin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not approve dividends.");
        tx.executeWithoutResult((st) -> {
            Batch b = locked(id, p.branch());
            if (!"SUBMITTED".equals(b.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a distribution submitted for review can be approved; this one is " + b.status().toLowerCase() + ".");
            if (auth.getName().equalsIgnoreCase(b.preparedBy())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You prepared this distribution, so someone else must approve it.");
            String note = in == null || in.note() == null || in.note().isBlank() ? null : in.note().trim().substring(0, Math.min(in.note().trim().length(), 300));
            jdbc.update("UPDATE ecis_distribution_batch SET status = 'APPROVED', decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ?", auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock)), note, id);
            AuditLog.write(jdbc, auth.getName(), "dividend.distribution.approve", "ecis_distribution_batch", String.valueOf(id), "SUBMITTED", "APPROVED", note);
        });
        return detail(id, auth);
    }

    @PostMapping("/distributions/{id}/reject")
    Detail reject(@PathVariable long id, @RequestBody Decision in, Authentication auth) {
        Perms p = access.of(auth);
        if (!p.canNew(CODE) && !admin(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You may not reject dividends.");
        String why = reason(in == null ? null : in.note(), 5);
        tx.executeWithoutResult((st) -> {
            Batch b = locked(id, p.branch());
            if (!List.of("DRAFT", "SUBMITTED", "APPROVED").contains(b.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "This distribution is " + b.status().toLowerCase() + " and can no longer be rejected.");
            jdbc.update("UPDATE ecis_distribution_batch SET status = 'REJECTED', decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ?", auth.getName(), Timestamp.valueOf(LocalDateTime.now(clock)), why, id);
            AuditLog.write(jdbc, auth.getName(), "dividend.distribution.reject", "ecis_distribution_batch", String.valueOf(id), b.status(), "REJECTED", why);
        });
        return detail(id, auth);
    }

    // ---- member statement and register --------------------------------------------------------------------------------

    private Map<Integer, String> names(int branch) {
        Map<Integer, String> out = new HashMap<>();
        jdbc.query("SELECT ClientID, COALESCE(NULLIF(TRIM(AccountName), ''), CONCAT_WS(' ', FName, LName)) FROM client WHERE ClientIDBrCode = ?", (rs) -> { out.put(rs.getInt(1), rs.getString(2)); }, branch);
        return out;
    }

    @GetMapping("/distributions/{id}/statement")
    Statement statement(@PathVariable long id, @RequestParam int clientId, Authentication auth) {
        Perms p = seeing(auth);
        Batch b = found(id, p.branch());
        List<Statement> s = jdbc.query("""
                SELECT m.average_share, m.qualified_patronage, m.isc, m.pr, m.total, m.mutual_benefit, m.hospitalization, m.rest,
                  (SELECT COALESCE(NULLIF(TRIM(c.AccountName), ''), CONCAT_WS(' ', c.FName, c.LName)) FROM client c WHERE c.ClientIDBrCode = ? AND c.ClientID = m.client_id)
                FROM ecis_distribution_member m WHERE m.batch_id = ? AND m.client_id = ?
                """, (rs, i) -> new Statement(id, clientId, rs.getString(9), b.year(), rs.getBigDecimal(1), b.dividendRate(), rs.getBigDecimal(3), rs.getBigDecimal(2), b.patronageRate(),
            rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), b.status()), p.branch(), id, clientId);
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member " + clientId + " receives nothing in this distribution.");
        return s.get(0);
    }

    private static String csvCell(String v) {
        return v == null ? "" : v.matches(".*[\",\n].*") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /** The distribution register (spec section 7): every member, as stored. */
    @GetMapping(value = "/distributions/{id}/register.csv", produces = "text/csv")
    ResponseEntity<byte[]> register(@PathVariable long id, Authentication auth) {
        Perms p = seeing(auth);
        Batch b = found(id, p.branch());
        Map<Integer, String> names = names(p.branch());
        StringBuilder sb = new StringBuilder("Member no.,Member,Average share-month,ISC rate %,Interest on share capital,Qualified patronage,PR rate %,Patronage refund,Total distribution,To mutual benefit,To hospitalization,To savings or share capital\r\n");
        String isc = b.dividendRate().stripTrailingZeros().toPlainString(), pr = b.patronageRate().stripTrailingZeros().toPlainString();
        jdbc.query("SELECT client_id, average_share, qualified_patronage, isc, pr, total, mutual_benefit, hospitalization, rest FROM ecis_distribution_member WHERE batch_id = ? ORDER BY client_id", (rs) -> {
            sb.append(rs.getInt(1)).append(',').append(csvCell(names.get(rs.getInt(1)))).append(',').append(rs.getBigDecimal(2).toPlainString()).append(',').append(isc).append(',')
                .append(rs.getBigDecimal(4).toPlainString()).append(',').append(rs.getBigDecimal(3).toPlainString()).append(',').append(pr).append(',').append(rs.getBigDecimal(5).toPlainString()).append(',')
                .append(rs.getBigDecimal(6).toPlainString()).append(',').append(rs.getBigDecimal(7).toPlainString()).append(',').append(rs.getBigDecimal(8).toPlainString()).append(',')
                .append(rs.getBigDecimal(9).toPlainString()).append("\r\n");
        }, id);
        AuditLog.write(jdbc, auth.getName(), "dividend.register", "ecis_distribution_batch", String.valueOf(id), null, b.members() + " members", null);
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"distribution-register-" + b.year() + ".csv\"")
            .contentType(MediaType.parseMediaType("text/csv")).body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---- reports (spec section 7) --------------------------------------------------------------------------------------

    static String money(BigDecimal v) {
        if (v == null) return "";
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(v);
    }

    private static String pct(BigDecimal v) {
        return v == null ? "" : v.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private static List<String> row(String... cells) {
        return List.of(cells);
    }

    @GetMapping("/distributions/{id}/report/{kind}")
    Report report(@PathVariable long id, @PathVariable String kind, Authentication auth) {
        Perms p = seeing(auth);
        Batch b = found(id, p.branch());
        String sub = "Financial year " + b.year() + " · cut-off " + b.cutoff() + " · distribution " + b.id() + ", " + b.status().toLowerCase();
        Report r = switch (kind) {
            case "net-surplus" -> netSurplus(b, sub);
            case "funds" -> funds(b, sub);
            case "isc" -> isc(b, sub);
            case "pr" -> pr(b, sub);
            case "ledger" -> ledger(b, sub);
            case "audit" -> audit(b, sub);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such report.");
        };
        AuditLog.write(jdbc, auth.getName(), "dividend.report", "ecis_distribution_batch", String.valueOf(id), null, kind, null);
        return r;
    }

    /** Income less expenses in the general ledger for the year, leaving out the old system's running surplus rows (98) and the Closing Entry (99). */
    private BigDecimal[] books(int branch, int year) {
        BigDecimal revenue = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        List<Object[]> rows = jdbc.query("""
                SELECT c.COAacct_type, SUM(g.GLTransDR_AMT) + SUM(g.GLTransCR_AMT) FROM gltrans g
                JOIN coa c ON c.COABR_CODE = g.GLTransBR_CODE AND c.COAID = g.GLTransACCT_CODE
                WHERE g.GLTransBR_CODE = ? AND g.GLTransTR_DATE BETWEEN ? AND ? AND g.GLTransTR_CODE NOT IN (98, 99) AND c.COAacct_type IN (4, 5)
                GROUP BY c.COAacct_type
                """, (rs, i) -> new Object[] {rs.getInt(1), rs.getBigDecimal(2)}, branch, java.sql.Date.valueOf(LocalDate.of(year, 1, 1)), java.sql.Date.valueOf(LocalDate.of(year, 12, 31)));
        for (Object[] r : rows) {
            if ((int) r[0] == 4) revenue = ((BigDecimal) r[1]).negate();
            else expense = (BigDecimal) r[1];
        }
        return new BigDecimal[] {revenue, expense, revenue.subtract(expense)};
    }

    private Report netSurplus(Batch b, String sub) {
        BigDecimal[] bk = books(b.branch(), b.year());
        List<List<String>> rows = new ArrayList<>();
        rows.add(row("Revenues (general ledger)", money(bk[0])));
        rows.add(row("Expenses (general ledger)", money(bk[1])));
        rows.add(row("Net surplus per the books", money(bk[2])));
        List<String> notes = new ArrayList<>();
        if (b.netSurplus() != null) {
            rows.add(row("Net surplus approved for distribution", money(b.netSurplus())));
            rows.add(row("Difference", money(b.netSurplus().subtract(bk[2]))));
            notes.add("Approved by " + (b.approvalRef() == null ? "the resolution given" : b.approvalRef()) + ".");
            if (b.netSurplus().compareTo(bk[2]) != 0) notes.add("The approved net surplus differs from the books; the accountant's adjustments (provisions, prior-year items) explain it.");
        } else {
            notes.add("This distribution used typed rates, so no approved net surplus was entered.");
        }
        notes.add("The books leave out the old system's running surplus rows (type 98) and the Closing Entry (type 99), as the statement of operations does.");
        return new Report("Annual net surplus report", sub, List.of(new Section("Net surplus " + b.year(), row("", "Amount"), rows, notes)));
    }

    private Report funds(Batch b, String sub) {
        List<List<String>> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (b.netSurplus() == null) {
            notes.add("This distribution used typed rates; no net surplus or statutory funds were entered.");
            return new Report("Statutory fund allocation report", sub, List.of(new Section("Allocation", row("Item", "Percent", "Amount"), rows, notes)));
        }
        rows.add(row("Net surplus", "", money(b.netSurplus())));
        BigDecimal funds = BigDecimal.ZERO;
        for (FundRow f : jdbc.query("SELECT name, percent, amount FROM ecis_distribution_batch_fund WHERE batch_id = ? ORDER BY position",
                (rs, i) -> new FundRow(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), b.id())) {
            rows.add(row(f.name(), pct(f.percent()), money(f.amount())));
            funds = funds.add(f.amount());
        }
        rows.add(row("Total statutory funds", "", money(funds)));
        rows.add(row("Available for distribution", "", money(b.available())));
        rows.add(row("Interest on share capital allocation", "", money(b.iscAllocation())));
        rows.add(row("Patronage refund allocation", "", money(b.prAllocation())));
        BigDecimal allocated = (b.iscAllocation() == null ? BigDecimal.ZERO : b.iscAllocation()).add(b.prAllocation() == null ? BigDecimal.ZERO : b.prAllocation());
        rows.add(row("Not allocated", "", money(b.available().subtract(allocated))));
        notes.add("The statutory funds and their percents are the cooperative's settings at the time, not built into the system; check them against RA 9520 Article 86 and the bylaws.");
        notes.add("Allocation approved by: " + (b.approvalRef() == null ? "" : b.approvalRef()) + ".");
        return new Report("Statutory fund allocation report", sub, List.of(new Section("Allocation of the net surplus", row("Item", "Percent", "Amount"), rows, notes)));
    }

    /** How many members fall in each band of the amount they receive, and what the band adds up to. */
    private List<List<String>> bands(long id, String column) {
        String[] labels = {"Below 100.00", "100.00 to 499.99", "500.00 to 999.99", "1,000.00 to 4,999.99", "5,000.00 and over"};
        BigDecimal[] lows = {BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("5000")};
        int[] n = new int[5];
        BigDecimal[] sum = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query("SELECT " + column + " FROM ecis_distribution_member WHERE batch_id = ? AND " + column + " > 0", (rs) -> {
            BigDecimal v = rs.getBigDecimal(1);
            int i = 4;
            while (i > 0 && v.compareTo(lows[i]) < 0) i--;
            n[i]++;
            sum[i] = sum[i].add(v);
        }, id);
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(row(labels[i], String.valueOf(n[i]), money(sum[i])));
        return rows;
    }

    private int count(String sql, long id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    private Report isc(Batch b, String sub) {
        int earning = count("SELECT COUNT(*) FROM ecis_distribution_member WHERE batch_id = ? AND isc > 0", b.id());
        List<List<String>> rows = new ArrayList<>();
        rows.add(row("Members earning interest on share capital", String.valueOf(earning)));
        rows.add(row("Total average share-month", money(b.totalAverageShare())));
        rows.add(row("Rate", pct(b.dividendRate())));
        rows.add(row("Interest on share capital distributed", money(b.totalIsc())));
        if (b.iscAllocation() != null) {
            rows.add(row("Allocated", money(b.iscAllocation())));
            rows.add(row("Difference (rounding)", money(b.iscAllocation().subtract(b.totalIsc()))));
        }
        List<String> notes = List.of("Average share-month = the 12 month-end paid-up share capital balances of " + b.year() + " added up and divided by 12. Member interest = average share-month x rate, rounded to the centavo.",
            "Settings used: " + b.policy());
        return new Report("Interest on share capital summary", sub, List.of(new Section("Summary", row("", "Value"), rows, notes),
            new Section("By amount received", row("Amount", "Members", "Total"), bands(b.id(), "isc"), List.of())));
    }

    private Report pr(Batch b, String sub) {
        Map<Integer, String> eventNames = loanEvents().stream().collect(Collectors.toMap(Option::id, Option::name, (a, c) -> a));
        List<List<String>> byEvent = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Object[] r : jdbc.query("SELECT event, amount, members FROM ecis_distribution_patronage WHERE batch_id = ? ORDER BY event", (rs, i) -> new Object[] {rs.getInt(1), rs.getBigDecimal(2), rs.getInt(3)}, b.id())) {
            byEvent.add(row(eventNames.getOrDefault((int) r[0], "Event " + r[0]) + " (" + r[0] + ")", String.valueOf(r[2]), money((BigDecimal) r[1])));
            sum = sum.add((BigDecimal) r[1]);
        }
        byEvent.add(row("Total qualified patronage", "", money(sum)));
        int earning = count("SELECT COUNT(*) FROM ecis_distribution_member WHERE batch_id = ? AND pr > 0", b.id());
        List<List<String>> rows = new ArrayList<>();
        rows.add(row("Members earning a patronage refund", String.valueOf(earning)));
        rows.add(row("Total qualified patronage", money(b.totalPatronage())));
        rows.add(row("Rate", pct(b.patronageRate())));
        rows.add(row("Patronage refund distributed", money(b.totalPr())));
        if (b.prAllocation() != null) {
            rows.add(row("Allocated", money(b.prAllocation())));
            rows.add(row("Difference (rounding)", money(b.prAllocation().subtract(b.totalPr()))));
        }
        return new Report("Patronage refund summary", sub, List.of(
            new Section("Summary", row("", "Value"), rows, List.of("Member refund = the member's qualified patronage x rate, rounded to the centavo. Qualified patronage is what the member paid on loans in " + b.year() + " for the loan events chosen in the settings.")),
            new Section("Qualified patronage by kind", row("Loan event", "Members", "Amount"), byEvent, List.of("Paid amounts as the loan ledger records them; billed or accrued amounts not yet paid do not count.")),
            new Section("By amount received", row("Amount", "Members", "Total"), bands(b.id(), "pr"), List.of())));
    }

    private String accountName(int branch, String account) {
        return jdbc.queryForList("SELECT COADesc FROM coa WHERE COABR_CODE = ? AND COAID = ?", String.class, branch, account).stream().findFirst().map(String::trim).orElse("");
    }

    private Report ledger(Batch b, String sub) {
        List<Section> sections = new ArrayList<>();
        if (b.voucherFirst() == null) {
            // not encoded yet: what the vouchers will carry, by account
            Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
            Map<String, String> glCache = new HashMap<>();
            jdbc.query("SELECT slc_code, slt_code, SUM(amount) FROM ecis_distribution_credit WHERE batch_id = ? GROUP BY slc_code, slt_code ORDER BY slc_code, slt_code", (rs) -> {
                int slc = rs.getInt(1), slt = rs.getInt(2);
                String gl = glCache.computeIfAbsent(slc + "/" + slt, (k) -> poster.glAccount(b.branch(), slc, slt, 11, 15));
                byAccount.merge(gl, rs.getBigDecimal(3), BigDecimal::add);
            }, b.id());
            List<List<String>> rows = new ArrayList<>();
            rows.add(row(DividendRun.FUND_ACCOUNT, accountName(b.branch(), DividendRun.FUND_ACCOUNT), money(b.total()), ""));
            for (var e : byAccount.entrySet()) rows.add(row(e.getKey(), accountName(b.branch(), e.getKey()), "", money(e.getValue())));
            rows.add(row("", "Total", money(b.total()), money(byAccount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))));
            sections.add(new Section("Entries the vouchers will carry (not encoded yet)", row("Account", "Name", "Debit", "Credit"), rows,
                List.of("Dated the cut-off, " + b.cutoff() + ". The member ledger rows are written when each voucher is posted.")));
            return new Report("General ledger posting report", sub, sections);
        }
        List<List<String>> vouchers = new ArrayList<>();
        jdbc.query("""
                SELECT s.TR_CTLNO, s.TR_DOCNO, COUNT(*), SUM(CASE WHEN d.DTAMT > 0 THEN d.DTAMT ELSE 0 END), MIN(d.DTUPD_TAG), MAX(d.DTAPPRVBY)
                FROM transsum s JOIN transdt d ON d.DTTR_BRCODE = s.TR_BRCODE AND d.DTTR_YEAR = s.TR_YEAR AND d.DTTR_MODULE = s.TR_MODULE AND d.DTTR_CODE = s.TR_CODE AND d.DTTR_CTLNO = s.TR_CTLNO
                WHERE s.TR_BRCODE = ? AND s.TR_YEAR = ? AND s.TR_MODULE = 6 AND s.TR_CODE = ? AND s.TR_CTLNO BETWEEN ? AND ?
                GROUP BY s.TR_CTLNO, s.TR_DOCNO ORDER BY s.TR_CTLNO
                """, (rs) -> {
            String status = rs.getInt(5) == 1 ? "Posted" : rs.getObject(6) == null ? "Waiting for approval" : "Approved, not posted";
            vouchers.add(row(rs.getString(2), String.valueOf(rs.getInt(3)), money(rs.getBigDecimal(4)), status));
        }, b.branch(), b.voucherYear(), CODE, b.voucherFirst(), b.voucherLast());
        sections.add(new Section("Vouchers", row("Voucher", "Lines", "Amount", "Status"), vouchers, List.of()));
        List<List<String>> accounts = new ArrayList<>();
        BigDecimal[] tot = {BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query("""
                SELECT d.DTACCT_CODE, SUM(CASE WHEN d.DTAMT > 0 THEN d.DTAMT ELSE 0 END), SUM(CASE WHEN d.DTAMT < 0 THEN -d.DTAMT ELSE 0 END),
                  SUM(CASE WHEN d.DTUPD_TAG = 1 THEN d.DTAMT ELSE 0 END)
                FROM transdt d WHERE d.DTTR_BRCODE = ? AND d.DTTR_YEAR = ? AND d.DTTR_MODULE = 6 AND d.DTTR_CODE = ? AND d.DTTR_CTLNO BETWEEN ? AND ?
                GROUP BY d.DTACCT_CODE ORDER BY d.DTACCT_CODE
                """, (rs) -> {
            accounts.add(row(rs.getString(1), accountName(b.branch(), rs.getString(1)), money(rs.getBigDecimal(2)), money(rs.getBigDecimal(3)), money(rs.getBigDecimal(4).abs())));
            tot[0] = tot[0].add(rs.getBigDecimal(2));
            tot[1] = tot[1].add(rs.getBigDecimal(3));
        }, b.branch(), b.voucherYear(), CODE, b.voucherFirst(), b.voucherLast());
        accounts.add(row("", "Total", money(tot[0]), money(tot[1]), ""));
        sections.add(new Section("By account", row("Account", "Name", "Debit", "Credit", "Of it posted"), accounts,
            List.of(tot[0].compareTo(tot[1]) == 0 ? "Debits equal credits." : "Debits and credits differ by " + money(tot[0].subtract(tot[1])) + ".")));
        return new Report("General ledger posting report", sub, sections);
    }

    private Report audit(Batch b, String sub) {
        List<List<String>> steps = new ArrayList<>();
        steps.add(row("Prepared", b.preparedBy(), b.preparedAt(), ""));
        if (b.submittedBy() != null) steps.add(row("Submitted for review", b.submittedBy(), b.submittedAt(), ""));
        if (b.decidedBy() != null) steps.add(row("REJECTED".equals(b.status()) ? "Rejected" : "Approved", b.decidedBy(), b.decidedAt(), b.decisionNote() == null ? "" : b.decisionNote()));
        if (b.encodedBy() != null) steps.add(row("Vouchers encoded", b.encodedBy(), b.encodedAt(), "control numbers " + b.voucherFirst() + " to " + b.voucherLast()));
        if (b.voucherFirst() != null) {
            jdbc.query("SELECT action, username, at, entity_id FROM ecis_audit_log WHERE action IN ('dividend.approve', 'dividend.post') AND entity_id LIKE ? ORDER BY at",
                (rs) -> {
                    String e = rs.getString(4);
                    long ctl = Long.parseLong(e.substring(e.lastIndexOf('/') + 1));
                    if (ctl >= b.voucherFirst() && ctl <= b.voucherLast()) {
                        steps.add(row(rs.getString(1).equals("dividend.approve") ? "Voucher approved" : "Voucher posted", rs.getString(2), String.valueOf(rs.getTimestamp(3)).substring(0, 16), "voucher " + ctl));
                    }
                }, b.branch() + "/6/" + CODE + "/" + b.voucherYear() + "/%");
        }
        List<List<String>> versions = jdbc.query("SELECT id, status, prepared_by, prepared_at, total, decision_note FROM ecis_distribution_batch WHERE branch_code = ? AND year = ? ORDER BY id",
            (rs, i) -> row(String.valueOf(rs.getLong(1)), rs.getString(2).toLowerCase(), rs.getString(3), String.valueOf(rs.getTimestamp(4)).substring(0, 16), money(rs.getBigDecimal(5)),
                rs.getString(6) == null ? "" : rs.getString(6)), b.branch(), b.year());
        List<List<String>> rec = new ArrayList<>();
        BigDecimal credited = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ecis_distribution_credit WHERE batch_id = ?", BigDecimal.class, b.id());
        BigDecimal members = jdbc.queryForObject("SELECT COALESCE(SUM(total), 0) FROM ecis_distribution_member WHERE batch_id = ?", BigDecimal.class, b.id());
        rec.add(row("Interest on share capital + patronage refund", money(b.total())));
        rec.add(row("Members' totals added up", money(members)));
        rec.add(row("Credits to members' accounts", money(credited)));
        if (b.voucherFirst() != null) {
            BigDecimal debits = jdbc.queryForObject("SELECT COALESCE(SUM(DTAMT), 0) FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = 6 AND DTTR_CODE = ? AND DTTR_CTLNO BETWEEN ? AND ? AND DTACCT_CODE = ?",
                BigDecimal.class, b.branch(), b.voucherYear(), CODE, b.voucherFirst(), b.voucherLast(), DividendRun.FUND_ACCOUNT);
            BigDecimal posted = jdbc.queryForObject("SELECT COALESCE(SUM(DTAMT), 0) FROM transdt WHERE DTTR_BRCODE = ? AND DTTR_YEAR = ? AND DTTR_MODULE = 6 AND DTTR_CODE = ? AND DTTR_CTLNO BETWEEN ? AND ? AND DTACCT_CODE = ? AND DTUPD_TAG = 1",
                BigDecimal.class, b.branch(), b.voucherYear(), CODE, b.voucherFirst(), b.voucherLast(), DividendRun.FUND_ACCOUNT);
            rec.add(row("Vouchers: debit of " + DividendRun.FUND_ACCOUNT, money(debits)));
            rec.add(row("Of it posted to the ledger", money(posted)));
        }
        if (b.iscAllocation() != null) rec.add(row("Interest on share capital: allocated less distributed", money(b.iscAllocation().subtract(b.totalIsc()))));
        if (b.prAllocation() != null) rec.add(row("Patronage refund: allocated less distributed", money(b.prAllocation().subtract(b.totalPr()))));
        List<String> recNotes = new ArrayList<>();
        recNotes.add(credited.compareTo(b.total()) == 0 && members.compareTo(b.total()) == 0 ? "The member figures, the credits and the total agree." : "The member figures, the credits and the total do not agree: look into it before encoding.");
        recNotes.add(b.held() + " members had no account to credit and " + b.excluded() + " were not eligible under the settings; they are listed on the distribution.");
        return new Report("Audit and reconciliation report", sub, List.of(
            new Section("Who did what", row("Step", "By", "When", "Note"), steps, List.of()),
            new Section("Versions of " + b.year(), row("Distribution", "Status", "Prepared by", "When", "Total", "Note"), versions, List.of("A version is replaced when the year is prepared again before approval; it is kept, never changed.")),
            new Section("Reconciliation", row("", "Amount"), rec, recNotes)));
    }
}
