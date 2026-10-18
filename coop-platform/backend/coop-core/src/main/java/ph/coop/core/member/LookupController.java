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
public class LookupController {

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
        Map.entry("billType", new String[] {"billtype", "BillTypeID", "BillTypeDesc"}),
        Map.entry("billStatus", new String[] {"billstat", "BillStatID", "BillStatDesc"}));

    private final JdbcTemplate jdbc;
    private volatile Map<String, Map<Integer, String>> cache;

    LookupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Map<Integer, String>> all() {
        Map<String, Map<Integer, String>> c = cache;
        if (c == null) {
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
        }
        return c;
    }
}
