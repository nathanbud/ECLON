package ph.coop.core.report;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.report.Reports.Info;
import ph.coop.core.report.Reports.Provider;
import ph.coop.core.report.Reports.Report;
import ph.coop.core.report.Reports.Table;

/**
 * Lists and runs the reports (guide phase 20). Which reports a person sees depends on their roles; every run is written to the audit log with its parameters, because the
 * member and loan reports show personal and financial information. Reports only read.
 */
@RestController
@RequestMapping("/api/reports")
class ReportController {

    record Result(Info info, Table table, LocalDateTime ranAt, String ranBy) {}

    private final Map<String, Report> reports = new LinkedHashMap<>();
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ReportController(List<Provider> providers, JdbcTemplate jdbc, Clock clock) {
        for (Provider p : providers) for (Report r : p.reports()) reports.put(r.info().id(), r);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static boolean may(Authentication auth, Report r) {
        for (var a : auth.getAuthorities()) {
            String name = a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority();
            if (name.equals("ADMIN") || name.equals("ACCOUNTANT") || r.roles().contains(name)) return true;
        }
        return false;
    }

    /** The reports this person may run, in the guide's order. */
    @GetMapping
    List<Info> list(Authentication auth) {
        List<Info> out = new ArrayList<>();
        for (Report r : reports.values()) if (may(auth, r)) out.add(r.info());
        return out;
    }

    @GetMapping("/{id}")
    Result run(@PathVariable String id, @RequestParam Map<String, String> params, Authentication auth) {
        Report r = reports.get(id);
        if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such report.");
        if (!may(auth, r)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot run this report.");
        Table t = r.run(new Reports.Args(params));
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        jdbc.update("INSERT INTO ecis_audit_log (at, username, action, entity, entity_id, new_value) VALUES (?, ?, ?, ?, ?, ?)", Timestamp.valueOf(now), auth.getName(), "report.run", "report", id,
            params.toString() + " -> " + t.rows().size() + " rows");
        return new Result(r.info(), t, now, auth.getName());
    }
}
