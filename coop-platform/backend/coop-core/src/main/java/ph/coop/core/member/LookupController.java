package ph.coop.core.member;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Descriptions for the numeric codes on the member record, read from the legacy lookup tables.
 * The tables are tiny and rarely change, so they are loaded once and cached.
 */
@RestController
@RequestMapping("/api/lookups")
public class LookupController implements ph.coop.core.connection.OnConnectionSwitch {

    // lookup name -> {table, id column, description column}; fixed strings, never built from user input.
    private static final Map<String, String[]> SOURCES = Map.ofEntries(
        Map.entry("gender", new String[] {"gender", "GenderID", "GenderDesc"}),
        Map.entry("civilStatus", new String[] {"civilstat", "CivilStatID", "CivilStatDesc"}),
        Map.entry("clientType", new String[] {"clienttype", "ClientTypeID", "ClientTypeDesc"}),
        Map.entry("accountType", new String[] {"accttype", "AccTypeID", "AcctTypeDesc"}),
        Map.entry("accountStatus", new String[] {"acctstat", "AcctStatID", "AcctStatDesc"}),
        Map.entry("alertLevel", new String[] {"alertlevel", "AlertLevelID", "AlertLevelDesc"}),
        Map.entry("religion", new String[] {"religion", "ReligionID", "ReligionDesc"}),
        Map.entry("department", new String[] {"dept", "DeptID", "DeptDesc"}),
        Map.entry("mailingAddress", new String[] {"mailingaddress", "MailingAddressID", "MailingAddressDesc"}),
        Map.entry("residenceType", new String[] {"restype", "ResTypeID", "ResTypeDesc"}),
        Map.entry("dosriTag", new String[] {"dosritag", "ID", "DESC1"}),
        Map.entry("educAttained", new String[] {"educattained", "EducAttainedID", "EducAttainedDesc"}),
        Map.entry("busStatus", new String[] {"busstatus", "BusStatusID", "BusStatusDesc"}),
        Map.entry("busLevel", new String[] {"buslevel", "BusLevelID", "BusLevelDesc"}),
        Map.entry("sector", new String[] {"sector", "SectorID", "SectorDesc"}),
        Map.entry("geo", new String[] {"geo", "GeoID", "GeoDesc"}),
        Map.entry("grouping", new String[] {"grouping", "ID", "DESC1"}),
        Map.entry("closedReason", new String[] {"closedreason", "ClosedReasonID", "ClosedReasonDesc"}),
        Map.entry("restrik", new String[] {"restrik", "RestrikID", "RestrikDesc"}),
        Map.entry("branch", new String[] {"branches", "BranchesID", "BranchesDesc"}),
        Map.entry("user", new String[] {"access", "AccessID", "AccessUserName"}),
        Map.entry("relation", new String[] {"relation", "RelationID", "RelationDesc"}),
        Map.entry("billType", new String[] {"billtype", "BillTypeID", "BillTypeDesc"}),
        Map.entry("billStatus", new String[] {"billstat", "BillStatID", "BillStatDesc"}));

    /**
     * How long the lists are kept. They used to be kept until the server restarted, so a value added to a lookup table afterwards (a gender added to a new
     * database by hand) never appeared in the dropdowns; now it appears within this time.
     */
    static final long KEEP_MS = 5 * 60_000L;

    private final JdbcTemplate jdbc;
    private volatile Map<String, Map<Integer, String>> cache;
    private volatile long cachedAt;

    LookupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The lists belong to the database that was read, so a switch to another one starts them again. */
    @Override
    public void connectionSwitched() {
        cache = null;
    }

    /** Read the lists again on the next request: called after a screen changes one of them (Settings > Organization > Offices). */
    public void forget() {
        cache = null;
    }

    @GetMapping
    public Map<String, Map<Integer, String>> all() {
        Map<String, Map<Integer, String>> c = cache;
        if (c == null || System.currentTimeMillis() - cachedAt > KEEP_MS) {
            c = new LinkedHashMap<>();
            for (var e : SOURCES.entrySet()) {
                String[] src = e.getValue();
                Map<Integer, String> values = new LinkedHashMap<>();
                jdbc.query("SELECT " + src[1] + ", " + src[2] + " FROM " + src[0] + " ORDER BY 1",
                    rs -> { values.put(rs.getInt(1), rs.getString(2)); });
                c.put(e.getKey(), values);
            }
            c.put("yesNo", new LinkedHashMap<>(Map.of(0, "No", 1, "Yes")));
            cache = c;
            cachedAt = System.currentTimeMillis();
        }
        return c;
    }
}
