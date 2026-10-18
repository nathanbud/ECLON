package ph.coop.core.frontoffice;

import ph.coop.core.db.Db;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.dao.DataAccessException;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.frontoffice.HandoverRules.Drawer;

/**
 * Cash in each drawer (vault, cashiers, tellers). Adding up the whole ledger takes about ten seconds, so the closing balance of the
 * last year is kept in {@code ecis_drawer_snapshot} and only this year's entries are added to it (under a second). The snapshot is
 * rebuilt in the background when the app starts, and the screens fall back to the full sum until it exists.
 */
@Component
class DrawerBalances {

    private static final Logger log = LoggerFactory.getLogger(DrawerBalances.class);
    /** Seconds the all-drawers figures are reused. */
    private static final long CACHE_SECONDS = 20;

    private final JdbcTemplate jdbc;
    private final Db db;
    private volatile long cachedAt;
    private volatile Map<Drawer, BigDecimal> cached = Map.of();
    private volatile boolean refreshing;

    private final boolean buildOnStart;

    /** {@code coop.drawer-snapshot.on-start} is on unless a test turns it off, so tests do not start a background build. */
    DrawerBalances(JdbcTemplate jdbc, Db db, @org.springframework.beans.factory.annotation.Value("${coop.drawer-snapshot.on-start:true}") boolean buildOnStart) {
        this.jdbc = jdbc;
        this.db = db;
        this.buildOnStart = buildOnStart;
    }

    /** Called after anything is posted, so the next read is fresh. */
    void changed() {
        cachedAt = 0;
    }

    /** Builds the snapshot in the background once the app is up, if last year's is missing. */
    @EventListener(ApplicationReadyEvent.class)
    void buildOnStart() {
        if (buildOnStart) startRefresh(1);
    }

    private synchronized void startRefresh(int branch) {
        if (refreshing) return;
        refreshing = true;
        Thread t = new Thread(() -> {
            try {
                if (!snapshotCurrent(branch)) refresh(branch);
            } catch (RuntimeException e) {
                log.warn("Drawer balance snapshot not built: {}", e.getMessage());
            } finally {
                refreshing = false;
            }
        }, "drawer-snapshot");
        t.setDaemon(true);
        t.start();
    }

    private boolean snapshotCurrent(int branch) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_drawer_snapshot WHERE branch_code = ? AND EXTRACT(YEAR FROM as_of) = ?", Integer.class, branch, LocalDate.now().getYear() - 1);
            return n != null && n > 0;
        } catch (DataAccessException e) {
            return true; // the table has not been created yet; there is nothing to build into
        }
    }

    /** Cash in every drawer as of today. */
    synchronized Map<Drawer, BigDecimal> all(int branch) {
        if (System.currentTimeMillis() - cachedAt < CACHE_SECONDS * 1000) return cached;
        int year = LocalDate.now().getYear();
        Map<Drawer, BigDecimal> sum = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT event, number, balance FROM ecis_drawer_snapshot WHERE branch_code = ? AND EXTRACT(YEAR FROM as_of) = ?",
                (rs) -> { sum.put(new Drawer(rs.getInt(1), rs.getInt(2)), rs.getBigDecimal(3)); }, branch, year - 1);
        } catch (DataAccessException e) {
            // no snapshot table yet
        }
        boolean haveBase = !sum.isEmpty();
        if (!haveBase) startRefresh(branch);
        jdbc.query("""
                SELECT x.DTSLE_CODE, x.DTCTS_NUM, SUM(x.DTAMT) FROM transsum t
                JOIN transdt x ON t.TR_BRCODE = x.DTTR_BRCODE AND t.TR_YEAR = x.DTTR_YEAR AND t.TR_MODULE = x.DTTR_MODULE AND t.TR_CODE = x.DTTR_CODE AND t.TR_CTLNO = x.DTTR_CTLNO
                WHERE t.TR_BRCODE = ? AND t.TR_YEAR > ? AND t.TR_DATE <= ? AND x.DTSLC_CODE = 11 AND x.DTSLT_CODE = 1 AND x.DTSLE_CODE IN (50, 52, 53)
                GROUP BY 1, 2
                """,
            (rs) -> { sum.merge(new Drawer(rs.getInt(1), rs.getInt(2)), rs.getBigDecimal(3), BigDecimal::add); }, branch, haveBase ? year - 1 : 0, LocalDate.now().toString());
        cached = sum;
        cachedAt = System.currentTimeMillis();
        return sum;
    }

    /** Cash in one drawer as of today, always fresh. */
    BigDecimal of(int branch, Drawer d) {
        int year = LocalDate.now().getYear();
        BigDecimal base = null;
        try {
            base = jdbc.query("SELECT balance FROM ecis_drawer_snapshot WHERE branch_code = ? AND event = ? AND number = ? AND EXTRACT(YEAR FROM as_of) = ?",
                (rs, i) -> rs.getBigDecimal(1), branch, d.event(), d.number(), year - 1).stream().findFirst().orElse(null);
        } catch (DataAccessException e) {
            // no snapshot table yet
        }
        boolean haveBase = hasAnySnapshot(branch);
        BigDecimal delta = jdbc.queryForObject("""
                SELECT COALESCE(SUM(x.DTAMT), 0) FROM transsum t
                JOIN transdt x ON t.TR_BRCODE = x.DTTR_BRCODE AND t.TR_YEAR = x.DTTR_YEAR AND t.TR_MODULE = x.DTTR_MODULE AND t.TR_CODE = x.DTTR_CODE AND t.TR_CTLNO = x.DTTR_CTLNO
                WHERE t.TR_BRCODE = ? AND t.TR_YEAR > ? AND t.TR_DATE <= ? AND x.DTSLC_CODE = 11 AND x.DTSLT_CODE = 1 AND x.DTSLE_CODE = ? AND x.DTCTS_NUM = ?
                """, BigDecimal.class, branch, haveBase ? year - 1 : 0, LocalDate.now().toString(), d.event(), d.number());
        return (base == null ? BigDecimal.ZERO : base).add(delta == null ? BigDecimal.ZERO : delta);
    }

    private boolean hasAnySnapshot(int branch) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ecis_drawer_snapshot WHERE branch_code = ? AND EXTRACT(YEAR FROM as_of) = ?", Integer.class, branch, LocalDate.now().getYear() - 1);
            return n != null && n > 0;
        } catch (DataAccessException e) {
            return false;
        }
    }

    /** Rebuilds the snapshot: every drawer's balance at the end of last year. Takes about ten seconds. Returns how many drawers were stored. */
    int refresh(int branch) {
        LocalDate asOf = LocalDate.of(LocalDate.now().getYear() - 1, 12, 31);
        Map<Drawer, BigDecimal> sums = new HashMap<>();
        jdbc.query("""
                SELECT x.DTSLE_CODE, x.DTCTS_NUM, SUM(x.DTAMT) FROM transsum t
                JOIN transdt x ON t.TR_BRCODE = x.DTTR_BRCODE AND t.TR_YEAR = x.DTTR_YEAR AND t.TR_MODULE = x.DTTR_MODULE AND t.TR_CODE = x.DTTR_CODE AND t.TR_CTLNO = x.DTTR_CTLNO
                WHERE t.TR_BRCODE = ? AND t.TR_YEAR <= ? AND x.DTSLC_CODE = 11 AND x.DTSLT_CODE = 1 AND x.DTSLE_CODE IN (50, 52, 53)
                GROUP BY 1, 2
                """,
            (rs) -> { sums.put(new Drawer(rs.getInt(1), rs.getInt(2)), rs.getBigDecimal(3)); }, branch, asOf.getYear());
        for (Map.Entry<Drawer, BigDecimal> e : sums.entrySet())
            db.upsert("ecis_drawer_snapshot",
                new java.util.LinkedHashMap<>(Map.of("branch_code", branch, "event", e.getKey().event(), "number", e.getKey().number())),
                new java.util.LinkedHashMap<>(Map.of("as_of", java.sql.Date.valueOf(asOf), "balance", e.getValue())));
        changed();
        log.info("Drawer balance snapshot built for {} drawers as of {}", sums.size(), asOf);
        return sums.size();
    }
}
