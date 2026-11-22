package ph.coop.core.member;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.db.Db;

/**
 * What a new member is given when the registration does not say, per cooperative (each database is its own cooperative): the default department, the
 * default office, and which member types may be registered here. Kept in {@code ecis_setting} and chosen by a super administrator (Settings > Membership >
 * Member defaults, {@link MemberDefaultsController}); PostgreSQL V037 stored the choices the application used to have built in, wherever that database had them.
 * Nothing about one cooperative is assumed in another: a setting not chosen is empty, and registration then says what to choose. Only the old MySQL
 * database, which has no settings table, keeps the old fixed values (department 2, office 1, Regular Individual and Associate).
 */
@Component
public class MemberDefaults {

    static final String DEPARTMENT = "membership.default_department";
    static final String OFFICE = "membership.default_office";
    static final String TYPES = "membership.registration_types";

    /** The values the application had built in, kept only for a database without the settings table (the old MySQL one). */
    static final int OLD_DEPARTMENT = 2;
    static final int OLD_OFFICE = 1;
    static final List<Integer> OLD_TYPES = List.of(1, 2);

    private final JdbcTemplate jdbc;
    private final Db db;

    MemberDefaults(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    boolean hasSettings() {
        return db.tableExists("ecis_setting");
    }

    private String setting(String key) {
        List<String> v = jdbc.queryForList("SELECT setting_value FROM ecis_setting WHERE setting_key = ?", String.class, key);
        return v.isEmpty() || v.get(0) == null || v.get(0).isBlank() ? null : v.get(0).trim();
    }

    private Integer number(String key, int old) {
        if (!hasSettings()) return old;
        try {
            String v = setting(key);
            return v == null ? null : Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The department a new member is given when none is chosen, or null when this cooperative has not chosen one. */
    public Integer department() {
        return number(DEPARTMENT, OLD_DEPARTMENT);
    }

    /** The office a new member is given when none is chosen, or null when this cooperative has not chosen one. */
    public Integer office() {
        return number(OFFICE, OLD_OFFICE);
    }

    /** The codes of the member types chosen for registration, in the order chosen; empty when none is chosen. */
    List<Integer> typeCodes() {
        if (!hasSettings()) return OLD_TYPES;
        return parseCodes(setting(TYPES));
    }

    static List<Integer> parseCodes(String v) {
        Set<Integer> out = new LinkedHashSet<>();
        if (v == null) return List.of();
        for (String p : v.split("[,;\\s]+")) {
            try {
                if (!p.isBlank()) out.add(Integer.valueOf(p.trim()));
            } catch (NumberFormatException e) {
                // not a code: left out
            }
        }
        return new ArrayList<>(out);
    }

    /** Every member type of this database ({@code clienttype}), code to name. */
    Map<Integer, String> allTypes() {
        Map<Integer, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT ClientTypeID, ClientTypeDesc FROM clienttype ORDER BY ClientTypeID",
            (rs) -> { out.put(rs.getInt(1), rs.getString(2) == null ? "Type " + rs.getInt(1) : rs.getString(2).trim()); });
        return out;
    }

    /** The member types that may be registered here, code to name: the chosen ones that this database has. */
    public Map<Integer, String> types() {
        Map<Integer, String> all = allTypes();
        Map<Integer, String> out = new LinkedHashMap<>();
        for (int c : typeCodes()) if (all.containsKey(c)) out.put(c, all.get(c));
        return out;
    }

    /** A member type's name ("Regular Individual"), or "member" when unknown. */
    public String typeName(int code) {
        return allTypes().getOrDefault(code, "member");
    }
}
