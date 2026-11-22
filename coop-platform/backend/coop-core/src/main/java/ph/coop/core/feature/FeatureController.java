package ph.coop.core.feature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.connection.DatabaseChanges;
import ph.coop.core.db.Db;

/**
 * Features (Settings > System > Features): which areas this cooperative uses. Everyone signed in reads them (the screens hide what is off); only a super
 * administrator switches them, with a reason, each change audited as {@code feature.switched}. Switching an area off hides it and refuses its addresses; it
 * deletes nothing, and switching it back on brings everything back as it was.
 */
@RestController
public class FeatureController {

    public record Row(String key, String label, String note, boolean on) {}

    public record Save(Map<String, Boolean> changes, String reason) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final Features features;

    FeatureController(JdbcTemplate jdbc, Db db, Features features) {
        this.jdbc = jdbc;
        this.db = db;
        this.features = features;
    }

    @GetMapping("/api/features")
    public List<Row> list() {
        Map<String, Boolean> on = features.all();
        return FeatureRules.ALL.stream().map((f) -> new Row(f.key(), f.label(), f.note(), on.getOrDefault(f.key(), true))).toList();
    }

    @PutMapping("/api/admin/features")
    @PreAuthorize("hasRole('SUPERADMIN')")
    @Transactional
    public List<Row> save(@RequestBody Save s, Authentication auth) {
        if (!db.allTablesExist("ecis_setting", "ecis_audit_log")) throw new ResponseStatusException(HttpStatus.CONFLICT, DatabaseChanges.notSetUp("switching features"));
        List<String> p = FeatureRules.problems(s.changes(), s.reason());
        if (!p.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", p));
        Map<String, Boolean> before = features.all();
        for (var e : s.changes().entrySet()) {
            boolean was = before.getOrDefault(e.getKey(), true);
            boolean now = Boolean.TRUE.equals(e.getValue());
            if (was == now) continue;
            String key = Features.PREFIX + e.getKey();
            db.upsert("ecis_setting", Map.of("setting_key", key), new LinkedHashMap<>(Map.of("setting_value", Boolean.toString(now), "updated_by", auth.getName())));
            jdbc.update("UPDATE ecis_setting SET updated_at = NOW() WHERE setting_key = ?", key);
            AuditLog.write(jdbc, auth.getName(), "feature.switched", "feature", e.getKey(), was ? "on" : "off", now ? "on" : "off", s.reason().trim());
        }
        features.forget();
        return list();
    }
}
