package ph.coop.core.businessday;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.connection.OnConnectionSwitch;
import ph.coop.core.db.Db;

/**
 * The branch's business date, which teller and front-office postings carry (BusinessDayController starts and ends it). While a day is open,
 * {@link #postingDate} is that day, even after midnight; while none is open, postings are refused, so everything posted belongs to a day that was
 * started and is checked at End Day. Quotes shown before posting (penalties, interest, maturities) use {@link #today}, so they agree with what is
 * posted.
 * <p>
 * Off when {@code coop.business-day.required} is false (the backend tests set it, see src/test/resources/config/application.yml) or where the
 * database has no business day table yet (the MySQL migration V064 not applied): then the posting date is the calendar date, as before.
 */
@Component
public class BusinessDay implements OnConnectionSwitch {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessDay.class);

    /** The message a posting gets while no business day is open. */
    public static final String NOT_STARTED = "The business day has not been started, so nothing can be posted. Someone with the Start Day right starts it in Front Office > Business Day.";

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Clock clock;
    private final boolean required;
    private volatile Boolean available;

    BusinessDay(JdbcTemplate jdbc, Db db, Clock clock, @Value("${coop.business-day.required:true}") boolean required) {
        this.jdbc = jdbc;
        this.db = db;
        this.clock = clock;
        this.required = required;
    }

    /** Whether this database has the business day tables. Asked once and remembered. */
    public boolean available() {
        Boolean a = available;
        if (a == null) {
            a = db.tableExists("ecis_business_day") && db.tableExists("ecis_business_day_log");
            if (!a && required) LOG.warn("No business day table in this database: postings use the calendar date. Apply the business day migration (PostgreSQL V027, MySQL V064).");
            available = a;
        }
        return a;
    }

    @Override
    public void connectionSwitched() {
        available = null;
    }

    /** Whether postings follow the business day here (required and set up). */
    public boolean inUse() {
        return required && available();
    }

    /** The calendar date on the server. */
    public LocalDate calendarDate() {
        return LocalDate.now(clock);
    }

    /** The open business date of the branch, if any. */
    public Optional<LocalDate> openDay(int branch) {
        if (!available()) return Optional.empty();
        List<java.sql.Date> d = jdbc.queryForList("SELECT business_date FROM ecis_business_day WHERE branch_code = ? AND status = 'OPEN' ORDER BY business_date", java.sql.Date.class, branch);
        return d.isEmpty() ? Optional.empty() : Optional.of(d.get(0).toLocalDate());
    }

    /**
     * The date a posting in the branch carries: the open business date. Refused (409) while no day is open. The calendar date where the business day
     * is not in use.
     */
    public LocalDate postingDate(int branch) {
        if (!inUse()) return calendarDate();
        return openDay(branch).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, NOT_STARTED));
    }

    /** Refuses (409) while the branch has no open business day: for work that posts on dates of its own (interest and accrual runs, dividends). */
    public void requireStarted(int branch) {
        postingDate(branch);
    }

    /** "Today" for figures shown before posting: the open business date, else the calendar date. Never refuses. */
    public LocalDate today(int branch) {
        if (!inUse()) return calendarDate();
        return openDay(branch).orElseGet(this::calendarDate);
    }

    /** The holidays of the branch between two dates (the old system's holiday table), with their names. */
    public Map<LocalDate, String> holidays(int branch, LocalDate from, LocalDate to) {
        Map<LocalDate, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT TR_DATE, DESC1 FROM holidays WHERE BR_CODE = ? AND TR_DATE BETWEEN ? AND ? ORDER BY TR_DATE",
            (rs) -> { out.putIfAbsent(rs.getDate(1).toLocalDate(), rs.getString(2) == null ? "holiday" : rs.getString(2).trim()); },
            branch, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return out;
    }
}
