package ph.coop.core.accounting;

import static ph.coop.core.web.ApiErrors.bad;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.member.SpreadsheetReader;
import ph.coop.core.member.TemplateWorkbook;

/**
 * Advanced mode of the chart of accounts: adding many accounts from a CSV or Excel list. An administrator uploads the list; it is checked row by row with the rules of
 * {@link ChartImportRules} and nothing is saved until it is sent with a reason. Sending adds the new accounts in one transaction, each written and audited exactly like an account added
 * by hand, plus one {@code chart.import} audit row for the file. A list only adds: accounts already in the chart are never changed, and nothing is deleted.
 */
@RestController
@RequestMapping("/api/accounting/chart/import")
@PreAuthorize("hasRole('ADMIN')")
public class ChartImportController {

    public record RowResult(int line, String code, String name, String state, String note) {}

    public record Preview(String fileName, int total, int ready, int existing, int errors, List<String> ignoredColumns, List<RowResult> rows) {}

    public record Done(String fileName, int added, int existing, int leftOut) {}

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ChartOfAccountsController chart;

    ChartImportController(ChartOfAccountsController chart) {
        this.chart = chart;
    }

    /** A blank list to fill in: an Accounts sheet with the column names, and a Guide sheet with what each column is and an example. */
    @GetMapping(value = "/template", produces = XLSX)
    public ResponseEntity<byte[]> template() {
        List<List<String>> guide = new ArrayList<>();
        guide.add(List.of("Column", "Required?", "What to write"));
        guide.add(List.of("Account number", "Required", "Digits only, up to 10. The first digit is the kind: 1 assets, 2 liabilities, 3 equity, 4 revenues, 5 expenses, 6 net income. It starts with the number of the heading it goes under."));
        guide.add(List.of("Account name", "Required", "Up to 50 characters. A name is used once under a heading."));
        guide.add(List.of("Kind", "", "Heading (totals the accounts under it, takes no postings) or Account. Account if left empty."));
        guide.add(List.of("Under", "", "The number of the heading it goes under. Left empty, it goes under the longest heading number its own number starts with; a heading with none above it starts a new top level."));
        guide.add(List.of("Journal entries", "", "Yes or No. No for an account that a loan, savings or share capital ledger controls. Yes if left empty; not used for a heading."));
        guide.add(List.of("Already in the chart", "", "A number that is already in the chart is left as it is. A list only adds accounts; it never changes or deletes one."));
        guide.add(List.of());
        guide.add(List.of("Example", "", ""));
        guide.add(ChartImportRules.COLUMNS);
        guide.add(List.of("1", "ASSETS", "Heading", "", ""));
        guide.add(List.of("10", "CURRENT ASSETS", "Heading", "1", ""));
        guide.add(List.of("100", "CASH AND CASH EQUIVALENTS", "Heading", "10", ""));
        guide.add(List.of("10001", "Cash on hand", "Account", "100", "Yes"));
        guide.add(List.of("10002", "Cash in bank", "Account", "100", "Yes"));
        byte[] book = TemplateWorkbook.workbook("Accounts", List.of(ChartImportRules.COLUMNS), new int[] {18, 44, 12, 14, 16}, guide, new int[] {24, 28, 100, 14, 16});
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"chart-of-accounts-template.xlsx\"").contentType(MediaType.parseMediaType(XLSX)).body(book);
    }

    /** The same blank list as plain CSV. */
    @GetMapping(value = "/template.csv", produces = "text/csv")
    public ResponseEntity<String> templateCsv() {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"chart-of-accounts-template.csv\"").contentType(MediaType.parseMediaType("text/csv"))
            .body(String.join(",", ChartImportRules.COLUMNS) + "\r\n");
    }

    /** Checks the list and reports every row, without saving anything. */
    @PostMapping("/preview")
    public Preview preview(@RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        return preview(file, check(file, auth));
    }

    /** Adds the new accounts of the list. Rows with problems are left out when {@code skipErrors} is true; otherwise any problem stops it. */
    @PostMapping
    @Transactional
    public Done upload(@RequestParam("file") MultipartFile file, @RequestParam(value = "reason", required = false) String reason,
                       @RequestParam(defaultValue = "false") boolean skipErrors, Authentication auth) throws IOException {
        String why = ChartOfAccountsController.reason(reason);
        int branch = chart.branch(auth);
        ChartImportRules.Result r = check(file, auth);
        Preview p = preview(file, r);
        if (p.errors() > 0 && !skipErrors) throw new ResponseStatusException(HttpStatus.CONFLICT, p.errors() + " row(s) have problems. Fix them, or choose to leave those rows out.");
        List<ChartImportRules.Checked> add = r.toAdd();
        if (add.isEmpty()) throw bad("There is no new account to add.");
        String from = why + " (uploaded from " + p.fileName() + ")";
        if (from.length() > 255) from = from.substring(0, 255);
        Integer userId = chart.userId(auth);
        for (ChartImportRules.Checked a : add) chart.insert(branch, a.code(), a.name(), a.summary(), a.parent(), a.glOnly(), userId, auth, from);
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("file", p.fileName()); shown.put("added", add.size()); shown.put("alreadyInChart", p.existing()); shown.put("leftOut", p.errors());
        chart.audit(auth, "chart.import", branch, "upload", null, shown, why);
        return new Done(p.fileName(), add.size(), p.existing(), p.errors());
    }

    private ChartImportRules.Result check(MultipartFile file, Authentication auth) throws IOException {
        List<SpreadsheetReader.Row> rows;
        try {
            rows = SpreadsheetReader.read(file.getOriginalFilename(), file.getBytes());
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        if (rows.isEmpty()) throw bad("The file has a header but no accounts.");
        Map<String, ChartImportRules.Existing> onFile = new LinkedHashMap<>();
        for (ChartOfAccountsController.AccountRow a : chart.list(auth)) onFile.put(a.code(), new ChartImportRules.Existing(a.name(), a.summary(), a.disabled(), a.parent()));
        ChartImportRules.Result r = ChartImportRules.check(rows, onFile);
        if (r.rows().stream().allMatch((x) -> x.code().isEmpty() && x.name().isEmpty())) {
            throw bad("No account number or name column was found. The first row must name the columns: " + String.join(", ", ChartImportRules.COLUMNS) + ".");
        }
        return r;
    }

    private static Preview preview(MultipartFile file, ChartImportRules.Result r) {
        List<RowResult> rows = r.rows().stream().map((x) -> new RowResult(x.line(), x.code(), x.name(), x.state().name(), x.note())).toList();
        int ready = (int) r.rows().stream().filter((x) -> x.state() == ChartImportRules.State.NEW).count();
        int existing = (int) r.rows().stream().filter((x) -> x.state() == ChartImportRules.State.EXISTS).count();
        return new Preview(file.getOriginalFilename(), rows.size(), ready, existing, rows.size() - ready - existing, r.ignoredColumns(), rows);
    }
}
