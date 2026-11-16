package ph.coop.core.accounting.journal;


import ph.coop.core.audit.AuditLog;import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lets other modules (fixed assets so far) leave a DRAFT journal for the accountants: the same checks as typing one on the Journals screen (balanced, known detail accounts
 * that are switched on, unused reference), written to the same tables, so it then goes through the usual review, approval and posting by other people.
 */
@Component
public class JournalDrafts {

    private final JdbcTemplate jdbc;

    JournalDrafts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the draft and returns its id; throws 400 for an unbalanced journal or a bad account, 409 for a reference already used. */
    public long create(int branch, String reference, LocalDate date, String description, String user, List<JournalLine> lines) {
        try {
            new Journal(reference, user, lines);
        } catch (IllegalArgumentException | JournalException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        for (JournalLine l : lines) {
            List<Map<String, Object>> a = jdbc.queryForList("SELECT COAacct_tag, COALESCE(COAisdisabled, 0) AS off FROM coa WHERE COABR_CODE = ? AND COAID = ?", branch, l.accountCode());
            if (a.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown account " + l.accountCode() + ".");
            if ("S".equals(a.get(0).get("COAacct_tag"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account " + l.accountCode() + " is a summary account and takes no postings.");
            if (((Number) a.get(0).get("off")).intValue() != 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account " + l.accountCode() + " is switched off.");
        }
        try {
            jdbc.update("INSERT INTO ecis_journal (branch_code, reference, journal_date, description, status, created_by, created_at, reverses_id) VALUES (?,?,?,?,?,?,?,?)",
                branch, reference, date, description, "DRAFT", user, LocalDateTime.now(), null);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reference " + reference + " is already used.");
        }
        Long id = jdbc.queryForObject("SELECT id FROM ecis_journal WHERE branch_code = ? AND reference = ?", Long.class, branch, reference);
        int no = 1;
        for (JournalLine l : lines) {
            jdbc.update("INSERT INTO ecis_journal_line (journal_id, line_no, account_code, debit, credit) VALUES (?,?,?,?,?)", id, no++, l.accountCode(), l.debit(), l.credit());
        }
        AuditLog.write(jdbc, user, "JOURNAL_CREATE", "journal", String.valueOf(id), null, "DRAFT", null);
        return id;
    }
}
