package ph.coop.core.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ph.coop.core.connection.OnConnectionSwitch;
import ph.coop.core.db.Db;

/**
 * Which areas this cooperative uses ({@link FeatureRules#ALL}), kept in {@code ecis_setting} as {@code feature.<key>} = true or false. An area not switched off is
 * on, so a database keeps everything until a super administrator switches something off (Settings > System > Features, {@link FeatureController}). Read often
 * (every request through {@link FeatureGuard}), so kept for 30 seconds, and forgotten at once after a change or a switch of database.
 */
@Component
public class Features implements OnConnectionSwitch {

    static final String PREFIX = "feature.";
    private static final long KEEP_MS = 30_000;

    private final JdbcTemplate jdbc;
    private final Db db;
    private volatile Map<String, Boolean> cache;
    private volatile long cachedAt;

    Features(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    @Override
    public void connectionSwitched() {
        cache = null;
    }

    void forget() {
        cache = null;
    }

    /** Every area, key to on. */
    public Map<String, Boolean> all() {
        Map<String, Boolean> c = cache;
        if (c != null && System.currentTimeMillis() - cachedAt < KEEP_MS) return c;
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (FeatureRules.Feature f : FeatureRules.ALL) out.put(f.key(), true);
        if (db.tableExists("ecis_setting")) {
            jdbc.query("SELECT setting_key, setting_value FROM ecis_setting WHERE setting_key LIKE ? ORDER BY setting_key", (rs) -> {
                String key = rs.getString(1).substring(PREFIX.length());
                if (out.containsKey(key)) out.put(key, !"false".equalsIgnoreCase(rs.getString(2) == null ? "" : rs.getString(2).trim()));
            }, PREFIX + "%");
        }
        cache = out;
        cachedAt = System.currentTimeMillis();
        return out;
    }

    public boolean on(String key) {
        return all().getOrDefault(key, true);
    }

    /** The keys of the areas switched off, for the screens to hide them. */
    public List<String> off() {
        List<String> out = new ArrayList<>();
        all().forEach((k, v) -> { if (!v) out.add(k); });
        return out;
    }

    /** True when accounts of this ledger class may be opened here. */
    public boolean classOn(int slc) {
        return FeatureRules.classOn(slc, all());
    }
}
