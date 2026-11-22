package ph.coop.core.organization;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.db.Db;

/**
 * The cooperative's units (the dept table) with the kind of each (ecis_department_kind; a suggested kind where none was chosen or the table is not there yet).
 * Rules in {@link UnitRules}. Read fresh each time: the list is small.
 */
@Component
public class Units {

    /** {@code kindChosen} is false while the kind is only suggested. */
    public record Unit(int id, String name, Integer parent, String kind, String kindName, boolean kindChosen, boolean disabled, int children) {}

    private final JdbcTemplate jdbc;
    private final Db db;

    Units(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    /** Whether the kinds can be stored (the database change that adds them is applied). */
    public boolean kindsStored() {
        return db.tableExists("ecis_department_kind");
    }

    public List<UnitRules.Dept> tree() {
        return jdbc.query("SELECT DeptID, DeptDesc, DeptParentID FROM dept ORDER BY DeptID",
            (rs, i) -> new UnitRules.Dept(rs.getInt(1), rs.getString(2) == null ? "" : rs.getString(2).trim(), rs.getObject(3) == null ? null : rs.getInt(3)));
    }

    public List<Unit> all() {
        Map<Integer, String> chosen = new HashMap<>();
        if (kindsStored()) jdbc.query("SELECT dept_id, kind FROM ecis_department_kind ORDER BY dept_id", (rs) -> { chosen.put(rs.getInt(1), rs.getString(2)); });
        Map<Integer, Boolean> off = new HashMap<>();
        jdbc.query("SELECT DeptID, COALESCE(DeptIsDisabled, 0) FROM dept ORDER BY DeptID", (rs) -> { off.put(rs.getInt(1), rs.getInt(2) != 0); });
        List<UnitRules.Dept> tree = tree();
        return tree.stream().map((d) -> {
            int kids = UnitRules.children(d.id(), tree).size();
            String kind = chosen.get(d.id());
            boolean isChosen = kind != null;
            if (!isChosen) kind = UnitRules.suggest(d.name(), kids > 0);
            return new Unit(d.id(), d.name(), d.parent(), kind, UnitRules.kindName(kind), isChosen, off.getOrDefault(d.id(), false), kids);
        }).toList();
    }

    public Optional<Unit> find(int id) {
        return all().stream().filter((u) -> u.id() == id).findFirst();
    }

    public Set<Integer> subtree(int id) {
        return UnitRules.subtree(id, tree());
    }
}
