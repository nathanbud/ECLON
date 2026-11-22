package ph.coop.core.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;

/**
 * Settings > System > Starter setup: the system's standard lists (account statuses, ledger postings, terms, payment modes, genders, civil statuses,
 * places and the rest; {@code starter/lists.txt}) shipped with the server, compared with the connected database and loaded into it when missing.
 * <p>
 * Only <b>adds</b>: a row is added when its key is not there; a row already there is never changed, even when it reads differently, and nothing is
 * deleted. A row that points to a record this database does not have (a branch, a user) is left out and the reason is shown. Everything is added in
 * one transaction, audited as {@code setup.starter.load} per list with the reason given. Administrators only; the database user needs INSERT on the
 * lists (PostgreSQL V034, MySQL V071).
 */
@RestController
@RequestMapping("/api/admin/starter-setup")
@PreAuthorize("hasRole('ADMIN')")
public class StarterSetupController {

    /** Up to this many reasons are listed per list for rows left out. */
    static final int WHY_SHOWN = 5;

    public record ListStatus(String table, String group, String label, int inPackage, int here, int missing, int leftOut, List<String> leftOutWhy, String problem) {}

    public record Status(List<ListStatus> lists, int missing, int leftOut, boolean canLoad, String cannotLoad) {}

    public record Load(String reason, List<String> tables) {}

    public record Result(int added, List<String> loaded, Status status) {}

    record Col(String name, int type) {}

    record Link(List<String> columns, String parent, List<String> parentColumns) {}

    record Meta(String name, List<Col> cols, List<String> key, List<Link> links) {}

    /** What one list would add: the rows (column name to value) and the reasons for rows left out. */
    record Plan(StarterLists.Entry entry, Meta meta, int inPackage, int here, List<Map<String, Object>> add, int leftOut, List<String> why, String problem) {}

    private final JdbcTemplate jdbc;
    private final Db db;
    private final List<StarterLists.Entry> catalog;
    private final Map<String, List<List<String>>> rows = new HashMap<>();

    StarterSetupController(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
        this.catalog = StarterLists.catalog(resource("starter/lists.txt"));
        for (StarterLists.Entry e : catalog) rows.put(e.table(), StarterLists.csv(resource("starter/lists/" + e.table() + ".csv")));
    }

    static String resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The starter setup file " + path + " is missing from this build.", e);
        }
    }

    List<StarterLists.Entry> catalog() {
        return catalog;
    }

    @GetMapping
    public Status status() {
        return status(plan(catalog, rows));
    }

    @PostMapping("/load")
    @Transactional
    public Result load(@RequestBody Load in, Authentication auth) {
        return load(in, auth, rows);
    }

    /** The load itself, with the package given (the tests pass their own rows). */
    Result load(Load in, Authentication auth, Map<String, List<List<String>>> pack) {
        String why = in == null || in.reason() == null ? "" : in.reason().trim();
        if (why.length() < 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason for loading the lists (at least 3 characters), so the audit log says why they were added. Nothing was loaded.");
        List<StarterLists.Entry> chosen = catalog;
        if (in.tables() != null && !in.tables().isEmpty()) {
            Set<String> wanted = new HashSet<>();
            for (String t : in.tables()) wanted.add(t.toLowerCase(Locale.ROOT));
            chosen = catalog.stream().filter((e) -> wanted.contains(e.table().toLowerCase(Locale.ROOT))).toList();
            if (chosen.size() != wanted.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One of the lists asked for is not a standard list, so nothing was loaded. Reload Starter setup and choose from the lists shown.");
        }
        List<Plan> plans = plan(chosen, pack);
        String cannot = cannotLoad(plans);
        if (cannot != null) throw new ResponseStatusException(HttpStatus.CONFLICT, cannot);
        int added = 0;
        List<String> loaded = new ArrayList<>();
        for (Plan p : plans) {
            if (p.problem() != null || p.add().isEmpty()) continue;
            List<Col> cols = p.meta().cols().stream().filter((c) -> p.add().get(0).containsKey(c.name())).toList();
            String sql = "INSERT INTO " + p.meta().name() + " (" + String.join(", ", cols.stream().map(Col::name).toList()) + ") VALUES ("
                + String.join(", ", cols.stream().map((c) -> "?").toList()) + ")";
            int[] types = cols.stream().mapToInt(Col::type).toArray();
            for (Map<String, Object> r : p.add()) jdbc.update(sql, cols.stream().map((c) -> r.get(c.name())).toArray(), types);
            added += p.add().size();
            loaded.add(p.entry().table());
            AuditLog.write(jdbc, auth.getName(), "setup.starter.load", "starter-list", p.entry().table(), p.here() + " rows",
                (p.here() + p.add().size()) + " rows (" + p.add().size() + " added: " + p.entry().label() + ")", why);
        }
        return new Result(added, loaded, status(plan(catalog, pack)));
    }

    private Status status(List<Plan> plans) {
        List<ListStatus> out = new ArrayList<>();
        int missing = 0;
        int left = 0;
        for (Plan p : plans) {
            out.add(new ListStatus(p.entry().table(), p.entry().group(), p.entry().label(), p.inPackage(), p.here(), p.add().size(), p.leftOut(), p.why(), p.problem()));
            missing += p.add().size();
            left += p.leftOut();
        }
        String cannot = cannotLoad(plans);
        return new Status(out, missing, left, cannot == null, cannot);
    }

    /** Why nothing can be added, or null. Only lists with something to add are checked for the grant. */
    private String cannotLoad(List<Plan> plans) {
        List<String> needed = plans.stream().filter((p) -> p.problem() == null && !p.add().isEmpty()).map((p) -> p.meta().name()).toList();
        if (needed.isEmpty()) return null;
        if (!db.canInsertInto(needed.toArray(String[]::new)))
            return ph.coop.core.connection.DatabaseChanges.notSetUp("adding to the standard lists");
        return null;
    }

    // ---- comparing the package with the database ----------------------------------------------------------------

    private List<Plan> plan(List<StarterLists.Entry> entries, Map<String, List<List<String>>> pack) {
        Map<String, Meta> metas = metas(entries);
        Map<String, Set<String>> parentKeys = new HashMap<>(); // parent table + columns -> keys there
        Map<String, List<Map<String, Object>>> pending = new HashMap<>(); // rows this run adds, by table (lowercase)
        List<Plan> out = new ArrayList<>();
        for (StarterLists.Entry e : entries) {
            List<List<String>> file = pack.getOrDefault(e.table(), List.of());
            int inPackage = Math.max(0, file.size() - 1);
            Meta m = metas.get(e.table().toLowerCase(Locale.ROOT));
            if (m == null) {
                out.add(new Plan(e, null, inPackage, 0, List.of(), 0, List.of(), "This list's table is not on this database, so it cannot be loaded here."));
                continue;
            }
            int here = jdbc.queryForObject("SELECT COUNT(*) FROM " + m.name(), Integer.class);
            if (file.isEmpty()) {
                out.add(new Plan(e, m, 0, here, List.of(), 0, List.of(), null));
                continue;
            }
            Map<String, Col> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Col c : m.cols()) byName.put(c.name(), c);
            List<String> header = file.get(0);
            List<Col> cols = new ArrayList<>();
            String problem = null;
            for (String h : header) {
                Col c = byName.get(h == null ? "" : h.trim());
                if (c == null) { problem = "This database's " + e.label().toLowerCase(Locale.ROOT) + " list is laid out differently (it has no column " + h + "), so it was not loaded. Tell your administrator."; break; }
                cols.add(c);
            }
            Set<String> inHeader = new HashSet<>();
            for (Col c : cols) inHeader.add(c.name().toLowerCase(Locale.ROOT));
            if (problem == null && (m.key().isEmpty() || !m.key().stream().allMatch((k) -> inHeader.contains(k.toLowerCase(Locale.ROOT)))))
                problem = "This database's " + e.label().toLowerCase(Locale.ROOT) + " list has no key the package can match, so it was not loaded. Tell your administrator.";
            if (problem != null) {
                out.add(new Plan(e, m, inPackage, here, List.of(), 0, List.of(), problem));
                continue;
            }
            Set<String> existing = keysOf(m.name(), m.key());
            List<Map<String, Object>> add = new ArrayList<>();
            List<String> why = new ArrayList<>();
            int left = 0;
            for (int i = 1; i < file.size(); i++) {
                List<String> f = file.get(i);
                Map<String, Object> row = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (int k = 0; k < cols.size(); k++) row.put(cols.get(k).name(), StarterLists.convert(k < f.size() ? f.get(k) : null, cols.get(k).type()));
                String key = StarterLists.key(m.key().stream().map(row::get).toList());
                if (!existing.add(key)) continue; // already there (or twice in the file)
                String missingLink = missingLink(m, row, parentKeys, pending);
                if (missingLink != null) {
                    left++;
                    if (why.size() < WHY_SHOWN) why.add(missingLink);
                    continue;
                }
                add.add(row);
            }
            pending.computeIfAbsent(m.name().toLowerCase(Locale.ROOT), (x) -> new ArrayList<>()).addAll(add);
            out.add(new Plan(e, m, inPackage, here, add, left, why, null));
        }
        return out;
    }

    /** Null when every record the row points to is there (or added in this run); otherwise why the row is left out. */
    private String missingLink(Meta m, Map<String, Object> row, Map<String, Set<String>> parentKeys, Map<String, List<Map<String, Object>>> pending) {
        for (Link l : m.links()) {
            List<Object> values = new ArrayList<>();
            boolean anyNull = false;
            for (String c : l.columns()) {
                Object v = row.get(c);
                if (v == null) anyNull = true;
                values.add(v);
            }
            if (anyNull) continue; // a link left empty points nowhere
            String key = StarterLists.key(values);
            String cacheKey = l.parent().toLowerCase(Locale.ROOT) + "|" + String.join(",", l.parentColumns()).toLowerCase(Locale.ROOT);
            Set<String> there = parentKeys.computeIfAbsent(cacheKey, (x) -> keysOf(l.parent(), l.parentColumns()));
            if (there.contains(key)) continue;
            boolean adding = pending.getOrDefault(l.parent().toLowerCase(Locale.ROOT), List.of()).stream()
                .anyMatch((p) -> StarterLists.key(l.parentColumns().stream().map(p::get).toList()).equals(key));
            if (adding) continue;
            return "A row with " + String.join(", ", l.columns()) + " = " + values.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                + " points to a record of " + l.parent() + " this database does not have, so it was left out.";
        }
        return null;
    }

    private Set<String> keysOf(String table, List<String> columns) {
        Set<String> out = new HashSet<>();
        // cast: the editor's compiler finds the plain lambda ambiguous (see MyAccessController)
        jdbc.query("SELECT " + String.join(", ", columns) + " FROM " + table, (org.springframework.jdbc.core.RowCallbackHandler) (ResultSet rs) -> {
            List<Object> v = new ArrayList<>();
            for (int i = 1; i <= columns.size(); i++) v.add(rs.getObject(i));
            out.add(StarterLists.key(v));
        });
        return out;
    }

    /** The columns, key and links of each list's table, read from the database itself; a table that is not there is left out. */
    private Map<String, Meta> metas(List<StarterLists.Entry> entries) {
        Map<String, Meta> out = new LinkedHashMap<>();
        db.tableLayouts(entries.stream().map(StarterLists.Entry::table).toList()).forEach((t, l) -> out.put(t, new Meta(l.name(),
            l.columns().stream().map((c) -> new Col(c.name(), c.sqlType())).toList(), l.key(),
            l.links().stream().map((k) -> new Link(k.columns(), k.parent(), k.parentColumns())).toList())));
        return out;
    }
}
