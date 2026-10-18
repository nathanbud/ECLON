package ph.coop.core.member;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only detail for the profile tabs (business, education, contacts and IDs, spouse, groups, closed account,
 * pledges, other) plus the change history and billing lists. Each tab is a list of sections; each section a list
 * of labelled fields read from the legacy {@code client} table. Codes are resolved to their descriptions here.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}")
class ClientDetailController {

    /**
     * kind tells the screen how to format the value: text, date, money or year. For coded fields, lookup names the
     * lookup list (see /api/lookups) and code is the stored code, so the screen can show a dropdown of the choices.
     */
    record Field(String label, String value, String kind, String lookup, String code, String key, boolean editable) {}

    record Section(String title, List<Field> fields) {}

    private static final int HISTORY_PAGE = 25;

    // ---- what to show -------------------------------------------------------------------------------------------

    /** One field of the spec: label, column, kind and an optional lookup name (or "mask" / "bool"). */
    record Spec(String label, String column, String kind, String how) {}

    private static Spec t(String label, String col) { return new Spec(label, col, "text", null); }
    private static Spec d(String label, String col) { return new Spec(label, col, "date", null); }
    private static Spec m(String label, String col) { return new Spec(label, col, "money", null); }
    private static Spec y(String label, String col) { return new Spec(label, col, "year", null); }
    private static Spec l(String label, String col, String lookup) { return new Spec(label, col, "text", lookup); }
    private static Spec b(String label, String col) { return new Spec(label, col, "text", "yesNo"); }
    /** Shows only the last four characters; used for card numbers. */
    private static Spec mask(String label, String col) { return new Spec(label, col, "text", "mask"); }

    private static Map<String, LinkedHashMap<String, List<Spec>>> tabs() {
        var tabs = new LinkedHashMap<String, LinkedHashMap<String, List<Spec>>>();

        var business = new LinkedHashMap<String, List<Spec>>();
        business.put("Employment", List.of(
            t("Employer / business", "BusName"), t("Nature of business", "BusNature"), t("Job title", "BusJobTitle"),
            l("Employment status", "BusStatus", "busStatus"), l("Level", "BusLevel", "busLevel"),
            d("Date hired", "BusDateHired"), y("In business since", "BusSince"), t("Employees", "BusNumEmployees"),
            t("Employee code", "EmpCode"), t("Employee no.", "EMPNO"), t("Employee ID", "BusEmpID")));
        business.put("Income", List.of(
            m("Business income", "BusinessIncome"), m("Meal allowance", "MealAllowance"),
            t("Other source of income", "BusOtherSourceOfIncome"), t("Profession", "BusProfession"), t("Occupation", "BusOccupation")));
        business.put("Business address", List.of(
            t("Street", "BusAddStreet"), t("Barangay", "BusAddBarangay"), t("City / Municipality", "BusAddCity"),
            t("Province", "BusAddProvince"), t("Zip code", "BusAddZipCode"), t("Business phone", "EmpBusTelNum")));
        business.put("DepEd", List.of(
            t("Region code", "DepEdRegionCode"), t("Division code", "DepEdDivisionCode"), t("Station code", "DepEdStationCode")));
        tabs.put("business", business);

        var education = new LinkedHashMap<String, List<Spec>>();
        education.put("Education", List.of(
            l("Highest education", "EducAttained", "educAttained"), t("School last attended", "SchoolLastAttended")));
        tabs.put("education", education);

        var contacts = new LinkedHashMap<String, List<Spec>>();
        contacts.put("Phone and online", List.of(
            t("Mobile", "CellNum"), t("SMS number", "SMSNumber"), t("Residence phone", "ResTelNum"),
            t("Provincial phone", "ProvTelNum"), t("Business phone", "EmpBusTelNum"), t("Other contact", "OtherContactNum"),
            t("Spouse mobile", "SpCellNum"), t("Email", "EmailAddress"), t("Social media", "SocialMediaAcct")));
        contacts.put("Government IDs", List.of(
            t("TIN", "TINNum"), t("SSS / GSIS", "SSSGSISNum"), t("GSIS", "GSISNum"), t("PhilHealth", "PhilHealthNum"),
            t("Pag-IBIG", "PagibigNum"), t("Driver's licence", "DriverLicenseNum"), t("Voter's ID", "VotersID"),
            t("NSO", "NSONum"), t("Other ID", "OtherIDNum")));
        contacts.put("Community tax certificate", List.of(
            t("CTC number", "CTCNumber"), d("Date issued", "CTCDateIssued"), t("Place issued", "CTCPlaceIssued")));
        contacts.put("Bank and training", List.of(
            t("ATM bank", "ATMBank"), mask("ATM number", "ATMNum"), t("PMES batch", "PMESBatchNum"), d("PMES date", "PMESDate")));
        tabs.put("contacts", contacts);

        var spouse = new LinkedHashMap<String, List<Spec>>();
        spouse.put("Spouse", List.of(
            t("Last name", "SpLName"), t("First name", "SpFName"), t("Middle name", "SpMName"), t("Suffix", "SpSName"),
            l("Gender", "SpGender", "gender"), d("Date of birth", "SpDateofBirth"), t("Mobile", "SpCellNum"),
            t("Education", "SpEducAttained"), t("Degree / course", "SpDegreeCourse")));
        spouse.put("Employment", List.of(
            t("Occupation", "SpOccupation"), t("Employer / business", "SpBusName"), t("Job title", "SpBusJobTitle"),
            l("Employment status", "SpBusStatus", "busStatus"), l("Level", "SpBusLevel", "busLevel"), t("Nature of business", "SpBusNature"),
            y("In business since", "SpBusSince"), d("Date hired", "SpBusDateHired"), t("Employees", "SpBusNumEmployees"),
            t("Other source of income", "SpOtherSourceOfIncome"), m("Monthly income", "SpMonthlyIncome")));
        spouse.put("Business address", List.of(
            t("Street", "SpBusAddStreet"), t("Barangay", "SpBusAddBarangay"), t("City / Municipality", "SpBusAddCity"),
            t("Province", "SpBusAddProvince"), t("Zip code", "SpBusAddZipCode")));
        tabs.put("spouse", spouse);

        var groups = new LinkedHashMap<String, List<Spec>>();
        groups.put("Groups", List.of(
            l("Group", "Grouping", "grouping"), l("Area", "Geographical", "geo"), l("Sector", "Sectoral", "sector"),
            t("Occupation code", "Occupational"), l("Department", "ClientDEPT", "department"), l("Office", "RestrikID", "restrik"),
            t("Collector no.", "Collector"), t("Recruiter no.", "RecruiterID")));
        tabs.put("groups", groups);

        var closed = new LinkedHashMap<String, List<Spec>>();
        closed.put("Closed account", List.of(
            d("Date closed", "ClosedDate"), l("Reason", "ClosedReason", "closedReason"), d("Date transferred", "DateTransferred"),
            l("Transferred to", "ClosedDestBranch", "branch"), l("Origin branch", "OriginBranch", "branch"),
            t("Board resolution", "ClosedBODReso"), d("Resolution approved", "ClosedBODResoDateApproved")));
        closed.put("Deceased", List.of(
            d("Date of death", "DateDeceased"), t("Claimant", "DeathClaimantName"), t("Relation", "DeathClaimantRelation"),
            d("Claimant birth date", "DeathClaimantBirthDate"), t("Claimant address", "DeathClaimantAddress"),
            t("Claimant mobile", "DeathClaimantSMSNumber"), t("Claimant email", "DeathClaimantEmailAddress")));
        tabs.put("closed", closed);

        var pledges = new LinkedHashMap<String, List<Spec>>();
        pledges.put("Pledges", List.of(m("Share capital pledge", "SCPledge"), m("Savings deposit pledge", "SDPledge")));
        tabs.put("pledges", pledges);

        var others = new LinkedHashMap<String, List<Spec>>();
        others.put("Limits", List.of(
            m("Sales credit limit", "SalesCreditLimit"), m("Additional credit limit", "SalesCreditLimitAdditional"),
            d("Additional limit expires", "SalesCreditLimitAdditionalExpiry"), m("Deduction limit", "DeductionLimit"),
            t("Delivery fee factor", "DeliveryFeeFactor")));
        others.put("Settings", List.of(
            b("Excluded from SSP", "SSPExcluded"), b("Show total points", "IsShowTotalPoints"), t("Loan availment type", "LoanAvailType"),
            t("Rating", "Rating"), t("Joint type", "JointType"), t("Primary client no.", "CLIENTID_PRIMARY")));
        others.put("Previous records", List.of(
            t("Old client ID", "OldClientID"), t("Old PSBN", "OldPSBN"), t("Old client name", "OldClientName"),
            t("Encoded by", "EncdBy"), d("Encoded on", "EncdDateTime"), t("Last updated by", "UpdUser"), d("Last updated", "UpdDateTime")));
        others.put("Remarks", List.of(t("Remarks", "Remarks")));
        tabs.put("others", others);
        return tabs;
    }

    private static final Map<String, LinkedHashMap<String, List<Spec>>> TABS = tabs();

    // ---- what can be edited -------------------------------------------------------------------------------------

    /** These tabs hold account state and limits, which are changed through their own processes, not by editing a profile. */
    private static final Set<String> READ_ONLY_TABS = Set.of("closed", "pledges", "others");

    /** Coded or masked columns whose meaning is not fully known, so they stay read-only. */
    private static final Set<String> READ_ONLY_COLUMNS = Set.of("Occupational", "Collector", "RecruiterID", "ATMNum", "EMPNO", "EmpCode");

    private static List<Spec> personal() {
        return List.of(
            t("Account name", "AccountName"), t("First name", "FName"), t("Middle name", "MName"), t("Last name", "LName"), t("Suffix", "SName"),
            d("Date of birth", "DateOfBirth"), l("Gender", "Gender", "gender"), l("Civil status", "CivilStatus", "civilStatus"),
            l("Religion", "ReligionID", "religion"), t("Mobile", "CellNum"), t("Email", "EmailAddress"),
            t("Street", "ResAddStreet"), t("Barangay", "ResAddBarangay"), t("City / Municipality", "ResAddCity"),
            t("Province", "ResAddProvince"), t("Zip code", "ResAddZipCode"), y("Living here since", "ResSince"),
            l("Residence type", "ResType", "residenceType"), l("Mailing address", "MailingAddress", "mailingAddress"),
            t("Provincial street", "ProvAddStreet"), t("Provincial barangay", "ProvAddBarangay"),
            t("Provincial city", "ProvAddCity"), t("Provincial province", "ProvAddProvince"),
            t("Profession", "Profession"), t("Height", "Height"), t("Weight", "Weight"), t("Blood type", "BloodType"),
            m("Monthly income", "BusinessIncome"), l("Health care", "IsIncludeHealthCare", "yesNo"),
            l("Client type", "ClientType", "clientType"), l("Account type", "AccountType", "accountType"),
            l("Department", "ClientDEPT", "department"), l("DOSRI tag", "DOSRITag", "dosriTag"));
    }

    /** Every column a profile edit may change, by column name. Anything not listed here is refused. */
    static final Map<String, Spec> EDITABLE = editable();

    private static Map<String, Spec> editable() {
        Map<String, Spec> out = new LinkedHashMap<>();
        personal().forEach((s) -> out.put(s.column(), s));
        TABS.forEach((tab, sections) -> {
            if (READ_ONLY_TABS.contains(tab)) return;
            sections.values().forEach((specs) -> specs.forEach((s) -> {
                if (!READ_ONLY_COLUMNS.contains(s.column())) out.putIfAbsent(s.column(), s);
            }));
        });
        return out;
    }

    /** The "Encoded by / Last updated by" columns hold user numbers. */
    private static final Set<String> USER_COLUMNS = Set.of("EncdBy", "UpdUser");

    private final JdbcTemplate jdbc;
    private final LookupController lookups;

    ClientDetailController(JdbcTemplate jdbc, LookupController lookups) {
        this.jdbc = jdbc;
        this.lookups = lookups;
    }

    // ---- endpoints ----------------------------------------------------------------------------------------------

    @GetMapping("/details")
    ResponseEntity<Map<String, List<Section>>> details(@PathVariable int branchCode, @PathVariable int clientId) {
        Set<String> columns = new LinkedHashSet<>();
        TABS.values().forEach((sections) -> sections.values().forEach((specs) -> specs.forEach((s) -> columns.add(s.column()))));

        String sql = "SELECT " + String.join(", ", columns) + " FROM client WHERE ClientIDBrCode = ? AND ClientID = ?";
        List<Map<String, String>> rows = jdbc.query(sql, (rs, i) -> {
            Map<String, String> row = new LinkedHashMap<>();
            for (String c : columns) row.put(c, rs.getString(c)); // getString also copes with 0000-00-00 dates
            return row;
        }, branchCode, clientId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, String> row = rows.get(0);

        Map<String, List<Section>> out = new LinkedHashMap<>();
        TABS.forEach((tab, sections) -> {
            List<Section> list = new ArrayList<>();
            sections.forEach((title, specs) -> list.add(new Section(title, specs.stream().map((s) -> field(s, row.get(s.column()))).toList())));
            out.put(tab, list);
        });
        return ResponseEntity.ok(out);
    }

    record HistoryEntry(String at, String user, String activity, String info, String from, String to) {}

    @GetMapping("/history")
    List<HistoryEntry> history(@PathVariable int branchCode, @PathVariable int clientId,
                               @RequestParam(defaultValue = "0") int page) {
        return jdbc.query("""
                SELECT a.DateTimeAdded, u.AccessUserName, l.M_DESC, a.ActvtInfo, a.FromData, a.ToData
                FROM actvtlog a
                LEFT JOIN actvtlist l ON l.ID = a.ActvtListID
                LEFT JOIN access u ON u.AccessID = a.UserID AND u.AccessBR_CODE = a.BR_CODE
                WHERE a.BR_CODE = ? AND a.ClientID = ?
                ORDER BY a.ID DESC LIMIT ? OFFSET ?
                """,
            (rs, i) -> new HistoryEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
            branchCode, clientId, HISTORY_PAGE, Math.max(page, 0) * HISTORY_PAGE);
    }

    record BillingEntry(String reference, String date, String amount, String type, String status, String policy) {}

    @GetMapping("/billing")
    List<BillingEntry> billing(@PathVariable int branchCode, @PathVariable int clientId) {
        Map<Integer, String> types = lookups.all().get("billType");
        Map<Integer, String> statuses = lookups.all().get("billStatus");
        return jdbc.query("""
                SELECT REFERENCENO, BILL_DATE, BILL_AMT, BILL_TYPE, BILL_STATUS, POLICYNO
                FROM billing WHERE BR_CODE = ? AND CLIENTID = ? ORDER BY BILL_DATE DESC LIMIT 100
                """,
            (rs, i) -> new BillingEntry(rs.getString(1), rs.getString(2), rs.getString(3),
                describe(types, rs.getString(4)), describe(statuses, rs.getString(5)), rs.getString(6)),
            branchCode, clientId);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private Field field(Spec s, String raw) {
        String v = raw == null || raw.isBlank() || raw.startsWith("0000-00-00") ? null : raw.trim();
        String lookup = null;
        String code = null;
        if (v != null && s.how() != null) {
            switch (s.how()) {
                case "mask" -> v = v.length() <= 4 ? v : "•".repeat(v.length() - 4) + v.substring(v.length() - 4);
                default -> {
                    lookup = s.how();
                    code = v;
                    v = describe(lookups.all().get(s.how()), v);
                }
            }
        } else if (v != null && USER_COLUMNS.contains(s.column())) {
            v = describe(lookups.all().get("user"), v);
        }
        return new Field(s.label(), v, s.kind(), lookup, code, s.column(), EDITABLE.containsKey(s.column()));
    }

    /** The description for a numeric code, or the code itself when no description is on file. */
    private static String describe(Map<Integer, String> table, String code) {
        if (code == null) return null;
        try {
            String text = table == null ? null : table.get(Integer.parseInt(code.trim()));
            return text != null ? text : code;
        } catch (NumberFormatException e) {
            return code;
        }
    }
}
