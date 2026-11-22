package ph.coop.core.accounting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which chart accounts to suggest for a ledger link of an account type (a savings, share capital, fund or other product), best first, each with the reason in words. Only a
 * suggestion: the administrator still chooses, and the link is checked as before when saved. In order:
 * <ol>
 *   <li>the accounts the same posting of the class's other types goes to (most types first), since products of one class nearly always share their control account;</li>
 *   <li>the account this type's same posting goes to for another account status (a dormant balance next to the active one);</li>
 *   <li>accounts of the kind the posting needs (a deposit balance is a liability, share capital is equity, a receivable an asset) whose names share words with the type's name,
 *       the class's name and the posting's name; the type's own name counts double.</li>
 * </ol>
 * Pure logic, no database access.
 */
final class LinkSuggestions {

    private LinkSuggestions() {}

    /** A chart account that takes postings and is in use; {@code kind} is 1 assets to 6 net income. */
    record Account(String code, String name, int kind) {}

    /** A link of another type: the account it posts to, and the type's name. */
    record Peer(String account, String typeName) {}

    record Suggestion(String account, String accountName, String why) {}

    /** How many suggestions are returned. */
    static final int MAX = 5;

    private static final Set<String> STOP = Set.of("the", "and", "for", "of", "on", "in", "to", "a", "an", "balance", "account", "accounts", "type", "types", "regular", "new",
        "member", "members", "sl", "gl");

    /**
     * The kind of chart account a posting of a class needs, or 0 when it is not certain (then any kind is suggested by name). A balance is an asset for cash, loans and
     * receivables, a liability for deposits, funds and payables, equity for share capital.
     */
    static int expectedKind(int classId, int event) {
        if (event == 11) {
            if (classId == 11 || classId == 12 || classId == 13 || classId == 14) return 1;
            if (classId == 31) return 3;
            if (classId >= 20 && classId < 30) return 2;
            return 0;
        }
        return switch (event) {
            case 21 -> 1;               // accrued interest receivable
            case 22, 71, 73, 75 -> 2;   // unearned interest, accrued interest payable, withholding tax
            case 23, 31, 41 -> 4;       // interest paid by the member, service fee, penalty: income
            default -> 0;
        };
    }

    static List<Suggestion> suggest(int classId, int event, String typeName, String className, String postingName, List<Peer> peers, List<String> ownOtherStatus,
                                    List<Account> chart) {
        Map<String, Account> byCode = new LinkedHashMap<>();
        for (Account a : chart) byCode.put(a.code(), a);
        Map<String, Suggestion> out = new LinkedHashMap<>();

        // 1. the same posting on the class's other types, the account most of them use first
        Map<String, List<String>> users = new LinkedHashMap<>();
        for (Peer p : peers) if (byCode.containsKey(p.account())) users.computeIfAbsent(p.account(), (k) -> new ArrayList<>()).add(p.typeName() == null ? "another type" : p.typeName().trim());
        users.entrySet().stream().sorted((x, y) -> y.getValue().size() - x.getValue().size()).forEach((e) ->
            out.putIfAbsent(e.getKey(), new Suggestion(e.getKey(), byCode.get(e.getKey()).name(), "Used for this posting by " + names(e.getValue()))));

        // 2. this type's same posting for another account status
        for (String a : ownOtherStatus) {
            if (byCode.containsKey(a)) out.putIfAbsent(a, new Suggestion(a, byCode.get(a).name(), "This type already posts here for its other account statuses"));
        }

        // 3. by name, among accounts of the kind the posting needs
        int kind = expectedKind(classId, event);
        Set<String> own = words(typeName);
        Set<String> context = words(className);
        context.addAll(words(postingName));
        context.removeAll(own);
        record Scored(Account a, int score, List<String> matched) {}
        List<Scored> scored = new ArrayList<>();
        for (Account a : chart) {
            if (kind != 0 && a.kind() != kind) continue;
            Set<String> name = words(a.name());
            int score = 0;
            List<String> matched = new ArrayList<>();
            for (String w : own) if (matches(w, name)) { score += 2; matched.add(w); }
            for (String w : context) if (matches(w, name)) { score += 1; matched.add(w); }
            if (score > 0) scored.add(new Scored(a, score, matched));
        }
        scored.sort((x, y) -> x.score() != y.score() ? y.score() - x.score() : x.a().code().compareTo(y.a().code()));
        for (Scored s : scored) {
            if (out.size() >= MAX) break;
            out.putIfAbsent(s.a().code(), new Suggestion(s.a().code(), s.a().name(), "Its name matches “" + String.join("”, “", s.matched()) + "”" + kindWords(kind)));
        }
        return out.values().stream().limit(MAX).toList();
    }

    private static String kindWords(int kind) {
        return switch (kind) {
            case 1 -> ", and it is an asset account";
            case 2 -> ", and it is a liability account";
            case 3 -> ", and it is an equity account";
            case 4 -> ", and it is a revenue account";
            default -> "";
        };
    }

    /** Up to three names, then how many more. */
    private static String names(List<String> n) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(n));
        if (distinct.size() <= 3) return join(distinct);
        return String.join(", ", distinct.subList(0, 3)) + " and " + (distinct.size() - 3) + " more";
    }

    private static String join(List<String> n) {
        if (n.size() <= 1) return n.isEmpty() ? "another type" : n.get(0);
        return String.join(", ", n.subList(0, n.size() - 1)) + " and " + n.get(n.size() - 1);
    }

    /** The meaningful words of a name, lower case: letters only, three or more of them, without the everyday words every account shares. */
    static Set<String> words(String text) {
        Set<String> w = new LinkedHashSet<>();
        if (text == null) return w;
        for (String part : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (part.length() >= 3 && !STOP.contains(part)) w.add(part);
        }
        return w;
    }

    /** A word matches when one is the start of the other, so fund matches funds and deposit matches deposits. */
    private static boolean matches(String w, Set<String> in) {
        for (String x : in) if (x.startsWith(w) || w.startsWith(x)) return true;
        return false;
    }
}
