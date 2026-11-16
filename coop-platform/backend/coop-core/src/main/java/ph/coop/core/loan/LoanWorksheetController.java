package ph.coop.core.loan;

import static ph.coop.core.web.ApiErrors.bad;
import static ph.coop.core.web.ApiErrors.notFound;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.loan.LoanWorksheetService.Worksheet;

/**
 * The Loan Interest and Penalty Computation Worksheet for one loan, as of a date, as a PDF or an Excel file. Read only. Every file handed out is written to the audit log and
 * to the export log, because it holds a member's financial record.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}/loans/{typeCode}/{refNo}/worksheet")
public class LoanWorksheetController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final LoanWorksheetService service;
    private final LoanWorksheetDocuments documents;
    private final JdbcTemplate jdbc;

    LoanWorksheetController(LoanWorksheetService service, LoanWorksheetDocuments documents, JdbcTemplate jdbc) {
        this.service = service;
        this.documents = documents;
        this.jdbc = jdbc;
    }

    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo,
                                      @RequestParam(required = false) String asOf, Authentication auth) {
        return file(branchCode, clientId, typeCode, refNo, asOf, auth, "pdf");
    }

    @GetMapping("/xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable int branchCode, @PathVariable int clientId, @PathVariable int typeCode, @PathVariable int refNo,
                                       @RequestParam(required = false) String asOf, Authentication auth) {
        return file(branchCode, clientId, typeCode, refNo, asOf, auth, "xlsx");
    }

    private ResponseEntity<byte[]> file(int branch, int client, int type, int ref, String asOfText, Authentication auth, String kind) {
        LocalDate asOf;
        try {
            asOf = asOfText == null || asOfText.isBlank() ? LocalDate.now() : LocalDate.parse(asOfText.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw bad("The date must look like 2026-04-24.");
        }
        if (asOf.isAfter(LocalDate.now())) throw bad("The worksheet cannot be run for a date that has not come yet.");
        Worksheet w = service.build(branch, client, type, ref, asOf).orElseThrow(() -> notFound("Loan not found."));
        if (asOf.isBefore(w.header().released())) throw bad("The date is before the loan was released (" + w.header().released() + ").");
        LocalDateTime now = LocalDateTime.now();
        boolean pdf = kind.equals("pdf");
        byte[] bytes = pdf ? documents.pdf(w, auth.getName(), now) : documents.xlsx(w, auth.getName(), now);
        String subject = "loan " + w.header().refNo() + " of member " + branch + "-" + client;
        jdbc.update("INSERT INTO ecis_data_export (at, username, export_type, subject, row_count, purpose) VALUES (?, ?, 'REPORT', ?, ?, ?)",
            Timestamp.valueOf(now), auth.getName(), subject, w.result().rows().size(), "Loan interest and penalty worksheet (" + kind + ") as of " + asOf);
        AuditLog.write(jdbc, auth.getName(), "loan.worksheet." + kind, "loan", branch + "-" + client + "-" + w.header().refNo(), null, "as of " + asOf, "Worksheet downloaded");
        String name = "loan-worksheet-" + w.header().refNo() + "-" + asOf + "." + kind;
        return ResponseEntity.ok().contentType(pdf ? MediaType.APPLICATION_PDF : XLSX).header("Content-Disposition", "attachment; filename=\"" + name + "\"").body(bytes);
    }
}
