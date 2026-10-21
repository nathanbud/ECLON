package ph.coop.core.deposit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ph.coop.core.deposit.SavingsInterest.Movement;

/**
 * Read-only preview of the whole 1 Jan to 31 Mar 2026 interest run: every savings account is recomputed and the totals per savings type are compared with
 * what the old system's vouchers 138 to 151 (module 8 type 53) carry. Prints a report; asserts nothing. Runs only when DB_PASSWORD is set.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class SavingsInterestPreviewTest {

    static final LocalDate FROM = LocalDate.of(2026, 1, 1), TO = LocalDate.of(2026, 3, 31);
    static final BigDecimal MIN = new BigDecimal("500");

    @Autowired JdbcTemplate jdbc;

    record Key(int client, int type, int ref) {}

    @Test
    void previewsTheWholeQuarter() {
        long t0 = System.currentTimeMillis();
        Map<Key, BigDecimal> posted = new HashMap<>();
        jdbc.query("""
                SELECT ClientIDDT, DTSLT_CODE, DTREF_NO, -SUM(DTAMT) FROM transdt
                WHERE DTTR_BRCODE = 1 AND DTTR_YEAR = 2026 AND DTTR_MODULE = 8 AND DTTR_CODE = 53 AND DTTR_CTLNO BETWEEN 145 AND 151 AND DTAMT < 0 AND DTSLC_CODE = 22
                GROUP BY ClientIDDT, DTSLT_CODE, DTREF_NO
                """, rs -> { posted.put(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), rs.getBigDecimal(4)); });
        assumeTrue(!posted.isEmpty(), "the March 2026 vouchers are not in this database");

        Map<Integer, BigDecimal> rate = new HashMap<>();
        jdbc.query("SELECT SLTypeSLT_CODE, SLTypeINT_RATE FROM sltype WHERE SLTypeBR_CODE = 1 AND SLTypeSLC_CODE = 22", rs -> { rate.put(rs.getInt(1), rs.getBigDecimal(2)); });

        Map<Key, Integer> status = new HashMap<>();
        jdbc.query("SELECT ClientIDSD, SDSLT_CODE, SDREF_NO, SDSTATUS FROM sd WHERE SDBR_CODE = 1 AND SDSLC_CODE = 22", rs -> { status.put(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), rs.getInt(4)); });

        Map<Key, BigDecimal> opening = new HashMap<>();
        jdbc.query("""
                SELECT SL_CLIENTID, SLT_CODE, REF_NO, -SUM(AMT) FROM sldtl
                WHERE SL_BRCODE = 1 AND SLC_CODE = 22 AND SLE_CODE = 11 AND TR_DATE < ? GROUP BY SL_CLIENTID, SLT_CODE, REF_NO
                """, rs -> { opening.put(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), rs.getBigDecimal(4)); }, FROM);
        Map<Key, List<Movement>> moves = new HashMap<>();
        jdbc.query("""
                SELECT SL_CLIENTID, SLT_CODE, REF_NO, TR_DATE, -AMT FROM sldtl
                WHERE SL_BRCODE = 1 AND SLC_CODE = 22 AND SLE_CODE = 11 AND TR_DATE >= ? AND TR_DATE <= ? AND NOT (TR_CODE = 53 AND TR_DATE >= ?)
                """, rs -> { moves.computeIfAbsent(new Key(rs.getInt(1), rs.getInt(2), rs.getInt(3)), k -> new ArrayList<>()).add(new Movement(rs.getDate(4).toLocalDate(), rs.getBigDecimal(5))); }, FROM, TO, FROM);
        long t1 = System.currentTimeMillis();

        Map<Key, BigDecimal> mine = new HashMap<>();
        Map<Key, Boolean> all = new HashMap<>();
        status.keySet().forEach(k -> all.put(k, true));
        for (Key k : all.keySet()) {
            BigDecimal r = rate.get(k.type());
            BigDecimal bd = SavingsInterest.balanceDays(opening.getOrDefault(k, BigDecimal.ZERO), moves.getOrDefault(k, List.of()), FROM, TO);
            BigDecimal i = SavingsInterest.interest(bd, SavingsInterest.days(FROM, TO), r, MIN);
            if (i.signum() > 0) mine.put(k, i);
        }

        Map<Integer, BigDecimal[]> perType = new TreeMap<>(); // mine total, posted total, same count, differ count, only mine, only posted
        Map<String, Integer> onlyPostedByStatus = new TreeMap<>(), onlyMineByStatus = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        for (Key k : mine.keySet()) {
            BigDecimal[] t = perType.computeIfAbsent(k.type(), x -> zeros());
            t[0] = t[0].add(mine.get(k));
            BigDecimal p = posted.get(k);
            if (p == null) { t[4] = t[4].add(BigDecimal.ONE); onlyMineByStatus.merge("status " + status.get(k), 1, Integer::sum); if (samples.size() < 6) samples.add("only mine " + k + " " + mine.get(k)); }
            else if (p.subtract(mine.get(k)).abs().compareTo(new BigDecimal("0.01")) <= 0) t[2] = t[2].add(BigDecimal.ONE);
            else { t[3] = t[3].add(BigDecimal.ONE); if (samples.size() < 12) samples.add("differs " + k + " mine " + mine.get(k) + " posted " + p); }
        }
        for (Map.Entry<Key, BigDecimal> e : posted.entrySet()) {
            BigDecimal[] t = perType.computeIfAbsent(e.getKey().type(), x -> zeros());
            t[1] = t[1].add(e.getValue());
            if (!mine.containsKey(e.getKey())) { t[5] = t[5].add(BigDecimal.ONE); onlyPostedByStatus.merge("status " + status.get(e.getKey()), 1, Integer::sum); if (samples.size() < 18) samples.add("only posted " + e.getKey() + " " + e.getValue()); }
        }
        System.out.println("INTEREST PREVIEW accounts " + all.size() + ", mine " + mine.size() + ", posted " + posted.size() + "; load " + (t1 - t0) / 1000 + " s, compute " + (System.currentTimeMillis() - t1) / 1000 + " s");
        perType.forEach((type, t) -> System.out.println("INTEREST PREVIEW type " + type + " rate " + rate.get(type) + ": mine " + t[0] + ", posted " + t[1] + ", same " + t[2] + ", differ " + t[3] + ", only mine " + t[4] + ", only posted " + t[5]));
        System.out.println("INTEREST PREVIEW only mine by status " + onlyMineByStatus + "; only posted by status " + onlyPostedByStatus);
        int skippedZero = 0, skippedPositive = 0, paidZero = 0, paidPositive = 0;
        for (Key k : mine.keySet()) {
            BigDecimal end = opening.getOrDefault(k, BigDecimal.ZERO);
            for (Movement m : moves.getOrDefault(k, List.of())) end = end.add(m.amount());
            boolean zero = end.compareTo(BigDecimal.ONE) < 0;
            if (posted.containsKey(k)) { if (zero) paidZero++; else paidPositive++; } else { if (zero) skippedZero++; else skippedPositive++; }
        }
        System.out.println("INTEREST PREVIEW ending balance under 1 peso: skipped " + skippedZero + ", paid " + paidZero + "; ending balance 1 or more: skipped " + skippedPositive + ", paid " + paidPositive);
        Key giant = new Key(33189, 1, 45805);
        System.out.println("INTEREST PREVIEW giant opening " + opening.get(giant) + " moves " + moves.get(giant));
        mine.entrySet().stream().filter(e -> !posted.containsKey(e.getKey())).sorted((a, b) -> b.getValue().compareTo(a.getValue())).limit(8)
            .forEach(e -> System.out.println("INTEREST PREVIEW biggest only-mine " + e.getKey() + " " + e.getValue() + " status " + status.get(e.getKey())));
        mine.entrySet().stream().filter(e -> posted.containsKey(e.getKey())).sorted((a, b) -> b.getValue().subtract(posted.get(b.getKey())).abs().compareTo(a.getValue().subtract(posted.get(a.getKey())).abs())).limit(8)
            .forEach(e -> System.out.println("INTEREST PREVIEW biggest gap " + e.getKey() + " mine " + e.getValue() + " posted " + posted.get(e.getKey())));
        samples.forEach(s -> System.out.println("INTEREST PREVIEW " + s));
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] t = new BigDecimal[6];
        java.util.Arrays.fill(t, BigDecimal.ZERO);
        return t;
    }
}
