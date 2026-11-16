package ph.coop.core.frontoffice;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.deposit.DividendRun.Credit;

/** Reads a stored yearly distribution (ecis_distribution_batch and its rows) for the distribution screens and for encoding its vouchers. */
@Component
class DistributionStore {

    /** One distribution as stored. Timestamps are shown to the minute. */
    record Batch(long id, int branch, int year, LocalDate cutoff, String method, String status, BigDecimal netSurplus, BigDecimal available, BigDecimal iscAllocation,
                 BigDecimal prAllocation, String approvalRef, BigDecimal dividendRate, BigDecimal patronageRate, BigDecimal mutualBenefit, BigDecimal hospitalization,
                 int overrides, BigDecimal totalAverageShare, BigDecimal totalPatronage, int members, BigDecimal totalIsc, BigDecimal totalPr, BigDecimal total,
                 int held, int excluded, String policy, String preparedBy, String preparedAt, String submittedBy, String submittedAt, String decidedBy,
                 String decidedAt, String decisionNote, Long supersededBy, String encodedBy, String encodedAt, Integer voucherYear, Long voucherFirst, Long voucherLast) {}

    static final String COLUMNS = """
            id, branch_code, year, cutoff, method, status, net_surplus, available, isc_allocation, pr_allocation, approval_ref, dividend_rate, patronage_rate,
            mutual_benefit, hospitalization, overrides, total_average_share, total_patronage, members, total_isc, total_pr, total, held, excluded, policy,
            prepared_by, prepared_at, submitted_by, submitted_at, decided_by, decided_at, decision_note, superseded_by, encoded_by, encoded_at, voucher_year, voucher_first, voucher_last
            """;

    private final JdbcTemplate jdbc;

    DistributionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String stamp(Timestamp t) {
        return t == null ? null : t.toLocalDateTime().toString().replace('T', ' ').substring(0, 16);
    }

    static Batch batch(ResultSet rs) throws SQLException {
        return new Batch(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
            rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getString(11), rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBigDecimal(14), rs.getBigDecimal(15), rs.getInt(16),
            rs.getBigDecimal(17), rs.getBigDecimal(18), rs.getInt(19), rs.getBigDecimal(20), rs.getBigDecimal(21), rs.getBigDecimal(22), rs.getInt(23), rs.getInt(24), rs.getString(25),
            rs.getString(26), stamp(rs.getTimestamp(27)), rs.getString(28), stamp(rs.getTimestamp(29)), rs.getString(30), stamp(rs.getTimestamp(31)), rs.getString(32),
            (Long) rs.getObject(33, Long.class), rs.getString(34), stamp(rs.getTimestamp(35)), (Integer) rs.getObject(36, Integer.class), (Long) rs.getObject(37, Long.class),
            (Long) rs.getObject(38, Long.class));
    }

    /** The distribution, if it belongs to the branch; {@code lock} holds it for the rest of the transaction. */
    Batch find(long id, int branch, boolean lock) {
        List<Batch> b = jdbc.query("SELECT " + COLUMNS + " FROM ecis_distribution_batch WHERE id = ? AND branch_code = ?" + (lock ? " FOR UPDATE" : ""), (rs, i) -> batch(rs), id, branch);
        return b.isEmpty() ? null : b.get(0);
    }

    /** The credits the vouchers carry, in order. */
    List<Credit> credits(long id) {
        return jdbc.query("SELECT client_id, slc_code, slt_code, ref_no, kind, amount FROM ecis_distribution_credit WHERE batch_id = ? ORDER BY seq",
            (rs, i) -> new Credit(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getBigDecimal(6)), id);
    }

    void markEncoded(long id, String username, LocalDateTime at, int voucherYear, long first, long last) {
        jdbc.update("UPDATE ecis_distribution_batch SET status = 'ENCODED', encoded_by = ?, encoded_at = ?, voucher_year = ?, voucher_first = ?, voucher_last = ? WHERE id = ? AND status = 'APPROVED'",
            username, Timestamp.valueOf(at), voucherYear, first, last, id);
    }
}
