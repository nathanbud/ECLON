package ph.coop.core.member;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which street names to suggest for a barangay, worked out from the streets already on member records. There is no official list of streets (the Philippine Standard
 * Geographic Code stops at the barangay), so the old records are the source. They are untidy: placeholders ("NA", "."), whole addresses typed into the street box,
 * and a city that is often left blank. Pure, so it is tested without a database.
 */
final class StreetRules {

    private StreetRules() {}

    static final int MAX_SUGGESTIONS = 100;
    private static final int MAX_LENGTH = 60;
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final List<String> PLACEHOLDERS = List.of("NA", "N/A", "NONE", "NO STREET", "NO DETAILS", "NO DETAIL", "NOT APPLICABLE", "UNKNOWN", "NIL", "NULL");

    /** A place name written the way records vary it ("City of Baguio", "BAGUIO CITY", "Baguio") reduced to one form for comparing. */
    static String place(String name) {
        if (name == null) return "";
        String s = SPACES.matcher(name.trim().toUpperCase(Locale.ROOT).replace(".", "")).replaceAll(" ");
        for (String lead : List.of("CITY OF ", "MUNICIPALITY OF ", "MUNICIPIO DE ")) if (s.startsWith(lead)) s = s.substring(lead.length());
        if (s.endsWith(" CITY")) s = s.substring(0, s.length() - 5);
        return s;
    }

    /** True when the text looks like a real street name and not a placeholder or a pasted address. */
    static boolean usable(String street) {
        if (street == null) return false;
        String s = street.trim();
        if (s.length() < 3 || s.length() > MAX_LENGTH) return false;
        if (s.startsWith("*") || s.contains(",") || s.chars().noneMatch(Character::isLetter)) return false;
        return !PLACEHOLDERS.contains(SPACES.matcher(s.toUpperCase(Locale.ROOT).replace(".", "")).replaceAll(" "));
    }

    /** One street as found on a record, with the city and province written on that record. */
    record Seen(String street, String city, String province) {}

    /**
     * The streets of the chosen place, most used first, then alphabetically. A record counts when its city is the chosen one or left blank, and likewise its province,
     * because the old records often omit them; the barangay was already matched when they were read.
     */
    static List<String> suggestions(List<Seen> seen, String city, String province) {
        String wantCity = place(city);
        String wantProvince = place(province);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Seen r : seen) {
            if (!usable(r.street())) continue;
            String c = place(r.city());
            String p = place(r.province());
            if (!c.isEmpty() && !wantCity.isEmpty() && !c.equals(wantCity)) continue;
            if (!p.isEmpty() && !wantProvince.isEmpty() && !p.equals(wantProvince)) continue;
            counts.merge(SPACES.matcher(r.street().trim().toUpperCase(Locale.ROOT)).replaceAll(" "), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(counts.entrySet());
        rows.sort((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey()) : b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (var e : rows) {
            if (out.size() >= MAX_SUGGESTIONS) break;
            out.add(e.getKey());
        }
        return out;
    }
}
