package ph.coop.core.accounting.surplus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.accounting.close.ClosedBooks;
import ph.coop.core.db.Db;

/**
 * What the books and the stored appropriations say, for the appropriation screen and for the dividend distribution that pays members what was appropriated:
 * the net surplus a year's closing put in the undivided net surplus account, the cooperative's registration date, and a year's posted appropriation.
 */
@Component
public class Appropriations {

    /** The setting (ecis_setting) holding the date the cooperative was registered, as yyyy-mm-dd. */
    public static final String REGISTERED_ON = "organization.registered_on";

    /** A year's posted appropriation, as the distribution takes it: the net surplus, each fund's name and percent, and the amounts allocated to members. */
    public record Posted(long id, String reference, int year, BigDecimal netSurplus, List<Fund> funds, BigDecimal isc, BigDecimal pr, String resolution) {}

    public record Fund(String name, BigDecimal percent, BigDecimal amount) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final ClosedBooks books;
    private final String surplusAccount;

    Appropriations(JdbcTemplate jdbc, Db db, ClosedBooks books, @Value("${coop.year-end.surplus-account:30198}") String surplusAccount) {
        this.jdbc = jdbc;
        this.db = db;
        this.books = books;
        this.surplusAccount = surplusAccount;
    }

    public String surplusAccount() {
        return surplusAccount;
    }

    public boolean stored() {
        return db.tableExists("ecis_appropriation");
    }

    /** The net surplus of a closed year: what its Closing Entry (type 99) credited to the undivided net surplus account (a loss is negative); null while the year is open. */
    public BigDecimal closedSurplus(int branch, int year) {
        if (!books.isClosed(branch, year)) return null;
        BigDecimal net = jdbc.queryForObject("""
                SELECT COALESCE(SUM(GLTransDR_AMT), 0) + COALESCE(SUM(GLTransCR_AMT), 0) FROM gltrans
                WHERE GLTransBR_CODE = ? AND GLTransACCT_CODE = ? AND GLTransTR_CODE = ? AND GLTransTR_DATE BETWEEN ? AND ?
                """, BigDecimal.class, branch, surplusAccount, ClosedBooks.CLOSING_ENTRY, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        return (net == null ? BigDecimal.ZERO : net).negate().setScale(2, RoundingMode.HALF_UP);
    }

    public LocalDate registeredOn() {
        if (!db.tableExists("ecis_setting")) return null;
        List<String> v = jdbc.queryForList("SELECT setting_value FROM ecis_setting WHERE setting_key = ?", String.class, REGISTERED_ON);
        if (v.isEmpty() || v.get(0) == null || v.get(0).isBlank()) return null;
        try {
            return LocalDate.parse(v.get(0).trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The year's posted appropriation, or null when none is posted (or the database keeps none). */
    public Posted posted(int branch, int year) {
        if (!stored()) return null;
        List<Object[]> a = jdbc.query("SELECT id, reference, net_surplus, resolution FROM ecis_appropriation WHERE branch_code = ? AND year = ? AND status = 'POSTED' ORDER BY id DESC",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)}, branch, year);
        if (a.isEmpty()) return null;
        long id = (Long) a.get(0)[0];
        List<Fund> funds = new ArrayList<>();
        BigDecimal[] members = {BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query("SELECT kind, name, percent, amount FROM ecis_appropriation_line WHERE appropriation_id = ? ORDER BY line_no", (rs) -> {
            String kind = rs.getString(1);
            if (AppropriationRules.ISC.equals(kind)) members[0] = members[0].add(rs.getBigDecimal(4));
            else if (AppropriationRules.PR.equals(kind)) members[1] = members[1].add(rs.getBigDecimal(4));
            else funds.add(new Fund(rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)));
        }, id);
        return new Posted(id, (String) a.get(0)[1], year, (BigDecimal) a.get(0)[2], funds, members[0], members[1], (String) a.get(0)[3]);
    }

    /**
     * Why a distribution of a year may not be prepared with these figures, given the year's posted appropriation; null when it may (or none is posted). The interest
     * and refund credited may not be more than appropriated (beyond the half centavo a member that rounding adds); a distribution worked out from an allocation
     * must use the appropriation's net surplus.
     */
    public String distributionProblem(int branch, int year, boolean fromAllocation, BigDecimal netSurplus, BigDecimal isc, BigDecimal pr, int members) {
        Posted a = posted(branch, year);
        if (a == null) return null;
        BigDecimal slack = new BigDecimal("0.005").multiply(BigDecimal.valueOf(members)).setScale(2, RoundingMode.CEILING);
        List<String> out = new ArrayList<>();
        if (fromAllocation && netSurplus != null && netSurplus.compareTo(a.netSurplus()) != 0) {
            out.add("The net surplus entered (" + netSurplus.toPlainString() + ") is not the one appropriated in " + a.reference() + " (" + a.netSurplus().toPlainString()
                + "). Use Fill in from the posted appropriation.");
        }
        if (isc.compareTo(a.isc().add(slack)) > 0) out.add("The interest on share capital to be credited (" + isc.toPlainString() + ") is more than " + a.reference() + " appropriated (" + a.isc().toPlainString() + ").");
        if (pr.compareTo(a.pr().add(slack)) > 0) out.add("The patronage refund to be credited (" + pr.toPlainString() + ") is more than " + a.reference() + " appropriated (" + a.pr().toPlainString() + ").");
        if (out.isEmpty()) return null;
        return String.join(" ", out) + " Nothing was saved: lower the rates, or correct the appropriation.";
    }
}
