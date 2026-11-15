package ph.coop.core.accounting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import ph.coop.core.member.SpreadsheetReader;

/**
 * Checks a list of accounts read from a spreadsheet against the chart on file, with the same rules as adding one account by hand: the number is digits starting with 1 to 6, it starts
 * with the number of the heading it goes under, the heading is a heading that is in use, and a name is not used twice under one heading. A list only adds: an account whose number is
 * already in the chart is left as it is. A heading may come from the same file, and a heading with nothing above it starts a new top level, so a whole chart can be loaded into an empty
 * one. Pure logic, no database access.
 */
final class ChartImportRules {

    private ChartImportRules() {}

    /** An account already in the chart. */
    record Existing(String name, boolean summary, boolean disabled, String parent) {}

    enum State { NEW, EXISTS, PROBLEM }

    /** One row of the file after checking. {@code parent} is null for a top-level heading. {@code note} says what will happen or what is wrong. */
    record Checked(int line, String code, String name, boolean summary, String parent, boolean glOnly, State state, String note) {}

    record Result(List<Checked> rows, List<String> ignoredColumns) {
        List<Checked> toAdd() {
            // a heading before the accounts under it: a shorter number first
            return rows.stream().filter((r) -> r.state() == State.NEW).sorted(Comparator.comparingInt((Checked r) -> r.code().length()).thenComparing(Checked::code)).toList();
        }
    }

    static final List<String> COLUMNS = List.of("Account number", "Account name", "Kind", "Under", "Journal entries");

    private static final Pattern CODE = Pattern.compile("[1-6][0-9]{0,9}");
    private static final Set<String> NUMBER = Set.of("accountnumber", "number", "code", "accountcode", "accountno", "coaid");
    private static final Set<String> NAME = Set.of("accountname", "name", "description", "accounttitle", "title", "coadesc");
    private static final Set<String> KIND = Set.of("kind", "level", "coaaccttag");
    private static final Set<String> UNDER = Set.of("under", "underheading", "parent", "summaryaccount", "coaacctsum");
    private static final Set<String> JOURNAL = Set.of("journalentries", "takesjournalentries", "glonly", "coacanglonly");

    private static final class Draft {
        int line;
        String code = "";
        String name = "";
        boolean summary;
        String under = "";
        boolean glOnly = true;
        String parent;
        State state = State.NEW;
        final List<String> problems = new ArrayList<>();
        String note;
    }

    private static String key(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Excel may hand a number over as 10001.0. */
    private static String number(String raw) {
        String t = raw.trim();
        return t.matches("[0-9]+\\.0+") ? t.substring(0, t.indexOf('.')) : t;
    }

    private static String label(String code, String name) {
        return name == null || name.isBlank() ? code : code + " " + name.trim();
    }

    static Result check(List<SpreadsheetReader.Row> rows, Map<String, Existing> chart) {
        Set<String> ignored = new TreeSet<>();
        List<Draft> drafts = new ArrayList<>();
        Map<String, Draft> byCode = new HashMap<>();

        for (SpreadsheetReader.Row row : rows) {
            Draft d = new Draft();
            d.line = row.line();
            for (Map.Entry<String, String> cell : row.values().entrySet()) {
                String k = key(cell.getKey());
                String v = cell.getValue().trim();
                if (NUMBER.contains(k)) d.code = number(v);
                else if (NAME.contains(k)) d.name = v;
                else if (UNDER.contains(k)) d.under = number(v);
                else if (KIND.contains(k)) {
                    switch (v.toLowerCase(Locale.ROOT)) {
                        case "heading", "summary", "s", "h" -> d.summary = true;
                        case "", "account", "detail", "d", "a" -> d.summary = false;
                        default -> d.problems.add("Kind must be Heading or Account.");
                    }
                } else if (JOURNAL.contains(k)) {
                    switch (v.toLowerCase(Locale.ROOT)) {
                        case "", "yes", "y", "true", "1" -> d.glOnly = true;
                        case "no", "n", "false", "0" -> d.glOnly = false;
                        default -> d.problems.add("Journal entries must be Yes or No.");
                    }
                } else ignored.add(cell.getKey());
            }
            if (d.code.isEmpty()) d.problems.add("The account number is missing.");
            else if (!CODE.matcher(d.code).matches()) d.problems.add("The account number is digits only, up to 10, and starts with 1 to 6.");
            if (d.name.isEmpty()) d.problems.add("The account name is missing.");
            else if (d.name.length() > 50) d.problems.add("The account name is longer than 50 characters.");
            if (d.summary) d.glOnly = false;

            if (!d.code.isEmpty()) {
                Draft first = byCode.putIfAbsent(d.code, d);
                if (first != null) d.problems.add("The number " + d.code + " is also on line " + first.line + ".");
                else if (d.problems.isEmpty() && chart.containsKey(d.code)) {
                    Existing e = chart.get(d.code);
                    d.state = State.EXISTS;
                    d.note = "Already in the chart" + (e.name() != null && !e.name().trim().equalsIgnoreCase(d.name) ? " as " + e.name().trim() : "") + ". Left as it is.";
                }
            }
            if (!d.problems.isEmpty()) d.state = State.PROBLEM;
            drafts.add(d);
        }

        // names already used under each heading ("" is the top level)
        Map<String, Set<String>> names = new HashMap<>();
        chart.forEach((code, e) -> names.computeIfAbsent(e.parent() == null ? "" : e.parent(), (k) -> new HashSet<>()).add(e.name() == null ? "" : e.name().trim().toLowerCase(Locale.ROOT)));
        // headings accepted from the file so far; a heading is always shorter than what goes under it, so shorter numbers are settled first
        Map<String, Draft> accepted = new LinkedHashMap<>();

        List<Draft> order = drafts.stream().filter((d) -> d.state == State.NEW).sorted(Comparator.comparingInt((Draft d) -> d.code.length()).thenComparing((d) -> d.code)).toList();
        for (Draft d : order) {
            String parent = null;
            if (!d.under.isEmpty()) {
                parent = d.under;
                Existing e = chart.get(parent);
                Draft f = byCode.get(parent);
                if (parent.equals(d.code) || !d.code.startsWith(parent)) d.problems.add("The number " + d.code + " must start with the number of its heading (" + parent + ").");
                else if (e != null) {
                    if (!e.summary()) d.problems.add(label(parent, e.name()) + " is an account, not a heading.");
                    else if (e.disabled()) d.problems.add(label(parent, e.name()) + " is switched off.");
                } else if (f == null) d.problems.add("The heading " + parent + " is not in the chart or in this file.");
                else if (f.state == State.PROBLEM) d.problems.add("Its heading " + parent + " (line " + f.line + ") has a problem.");
                else if (!f.summary) d.problems.add(label(parent, f.name) + " (line " + f.line + ") is an account, not a heading. Write Heading in its Kind column.");
            } else {
                for (int n = d.code.length() - 1; n >= 1 && parent == null; n--) {
                    String p = d.code.substring(0, n);
                    Existing e = chart.get(p);
                    if (accepted.containsKey(p) || (e != null && e.summary() && !e.disabled())) parent = p;
                }
                if (parent == null && !d.summary) d.problems.add("No heading in the chart or in this file starts the number " + d.code + ". Add its heading to the list.");
            }
            if (d.problems.isEmpty()) {
                Set<String> used = names.computeIfAbsent(parent == null ? "" : parent, (k) -> new HashSet<>());
                if (!used.add(d.name.toLowerCase(Locale.ROOT))) {
                    d.problems.add("There is already an account called " + d.name + (parent == null ? " at the top level." : " under " + parent + "."));
                }
            }
            if (!d.problems.isEmpty()) { d.state = State.PROBLEM; continue; }
            d.parent = parent;
            if (d.summary) accepted.put(d.code, d);
            String what = d.summary ? "New heading" : "New account";
            if (parent == null) d.note = "New top-level heading";
            else d.note = what + " under " + label(parent, chart.containsKey(parent) ? chart.get(parent).name() : accepted.get(parent).name);
        }

        List<Checked> out = new ArrayList<>();
        for (Draft d : drafts) {
            out.add(new Checked(d.line, d.code, d.name, d.summary, d.parent, d.glOnly, d.state, d.state == State.PROBLEM ? String.join(" ", d.problems) : d.note));
        }
        return new Result(out, List.copyOf(ignored));
    }
}
