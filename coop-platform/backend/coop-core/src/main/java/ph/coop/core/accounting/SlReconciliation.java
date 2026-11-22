package ph.coop.core.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reconciles the subsidiary ledger with its control accounts: each member account type's principal (sldtl event 11) posts to chart accounts named in
 * glcontrol, and those accounts' balances must equal the sum of the members' accounts. Pure, so it is tested on its own.
 * <p>
 * The comparison is made per <b>group</b>, not per account: a type and every account its principal posts to, joined with the other types that share an
 * account. A loan type posts to several accounts by the loan's status (current, past due, restructured, in litigation), and moving a loan between them is a
 * general-ledger entry that leaves the member's account alone, so only their sum can be compared with the members' total; savings types that share
 * "Savings Deposit" are compared together with it.
 * <p>
 * Amounts are the ledgers' own sign: debit positive, credit negative (sldtl stores it so; in gltrans the credit column holds negative amounts, so a
 * balance is debits plus credits). For reading, a group whose
 * members' total is a credit (savings, share capital, payables) is shown as a positive credit balance. The difference is the general ledger less the
 * members' total, in that same reading; any difference is unreconciled.
 */
public final class SlReconciliation {

    private SlReconciliation() {}

    /** One glcontrol row for principal: a type (class and code) posts to an account. */
    public record Link(int slc, int slt, String account) {}

    public record TypeTotal(int slc, int slt, String name, BigDecimal total) {}

    public record AccountTotal(String account, String name, BigDecimal balance) {}

    /** credit: shown as credit balances (each amount negated). reconciled: no difference at all. */
    public record Group(String title, boolean credit, BigDecimal members, BigDecimal ledger, BigDecimal difference, boolean reconciled,
                        List<TypeTotal> types, List<AccountTotal> accounts) {}

    public record Result(List<Group> groups, BigDecimal totalDifference, int unreconciled) {}

    private static String typeKey(int slc, int slt) {
        return "T" + slc + "/" + slt;
    }

    /**
     * @param links      the principal postings of glcontrol for the branch
     * @param slTotals   the members' total per type, keyed "slc/slt" (debit positive); a type with no members is absent
     * @param glTotals   the balance per account (debit minus credit); an account with no entries is absent
     * @param typeNames  "slc/slt" to the type's name
     * @param classNames class code to its name
     * @param accountNames account to its name
     */
    public static Result reconcile(List<Link> links, Map<String, BigDecimal> slTotals, Map<String, BigDecimal> glTotals,
                                   Map<String, String> typeNames, Map<Integer, String> classNames, Map<String, String> accountNames) {
        // join types and accounts that are linked (union-find: each node points towards its group's root)
        Map<String, String> parent = new HashMap<>();
        for (Link l : links) {
            String a = root(parent, typeKey(l.slc(), l.slt())), b = root(parent, "A" + l.account());
            if (!a.equals(b)) parent.put(a, b);
        }

        Map<String, TreeSet<String>> typesOf = new LinkedHashMap<>();
        Map<String, TreeSet<String>> accountsOf = new LinkedHashMap<>();
        for (Link l : links) {
            String root = root(parent, typeKey(l.slc(), l.slt()));
            typesOf.computeIfAbsent(root, (k) -> new TreeSet<>(Comparator.comparingInt((String s) -> Integer.parseInt(s.split("/")[0])).thenComparingInt((s) -> Integer.parseInt(s.split("/")[1]))))
                .add(l.slc() + "/" + l.slt());
            accountsOf.computeIfAbsent(root, (k) -> new TreeSet<>()).add(l.account());
        }

        List<Group> groups = new ArrayList<>();
        for (String root : typesOf.keySet()) {
            BigDecimal members = BigDecimal.ZERO, ledger = BigDecimal.ZERO;
            List<TypeTotal> types = new ArrayList<>();
            for (String t : typesOf.get(root)) {
                BigDecimal v = slTotals.getOrDefault(t, BigDecimal.ZERO);
                members = members.add(v);
                String[] p = t.split("/");
                types.add(new TypeTotal(Integer.parseInt(p[0]), Integer.parseInt(p[1]), typeNames.getOrDefault(t, "Type " + t), v));
            }
            List<AccountTotal> accounts = new ArrayList<>();
            for (String a : accountsOf.get(root)) {
                BigDecimal v = glTotals.getOrDefault(a, BigDecimal.ZERO);
                ledger = ledger.add(v);
                accounts.add(new AccountTotal(a, accountNames.getOrDefault(a, ""), v));
            }
            // a group of no members and no entries tells nothing
            if (members.signum() == 0 && ledger.signum() == 0 && types.stream().allMatch((t) -> !slTotals.containsKey(t.slc() + "/" + t.slt()))) continue;

            boolean credit = members.signum() != 0 ? members.signum() < 0 : ledger.signum() < 0;
            int sign = credit ? -1 : 1;
            BigDecimal shownMembers = members.multiply(BigDecimal.valueOf(sign)), shownLedger = ledger.multiply(BigDecimal.valueOf(sign));
            BigDecimal diff = shownLedger.subtract(shownMembers);
            List<TypeTotal> shownTypes = types.stream().map((t) -> new TypeTotal(t.slc(), t.slt(), t.name(), t.total().multiply(BigDecimal.valueOf(sign)))).toList();
            List<AccountTotal> shownAccounts = accounts.stream().map((a) -> new AccountTotal(a.account(), a.name(), a.balance().multiply(BigDecimal.valueOf(sign)))).toList();
            groups.add(new Group(title(shownTypes, classNames), credit, shownMembers, shownLedger, diff, diff.signum() == 0, shownTypes, shownAccounts));
        }
        // unreconciled first, largest difference first; then by class and type
        groups.sort(Comparator.comparing(Group::reconciled).thenComparing((Group g) -> g.difference().abs(), Comparator.reverseOrder())
            .thenComparingInt((Group g) -> g.types().get(0).slc()).thenComparingInt((Group g) -> g.types().get(0).slt()));
        BigDecimal total = groups.stream().map((g) -> g.difference().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(List.copyOf(groups), total, (int) groups.stream().filter((g) -> !g.reconciled()).count());
    }

    /**
     * The control accounts and the group each belongs to, grouped as {@link #reconcile} groups them (a type with all its accounts, joined with types sharing
     * one), with each group's title. For the journal rule ({@link ControlAccountRules}): a journal must net to zero within a group.
     */
    public record Groups(Map<String, String> groupOf, Map<String, String> titles) {}

    public static Groups groups(List<Link> links, Map<String, String> typeNames, Map<Integer, String> classNames) {
        Map<String, String> parent = new HashMap<>();
        for (Link l : links) {
            String a = root(parent, typeKey(l.slc(), l.slt())), b = root(parent, "A" + l.account());
            if (!a.equals(b)) parent.put(a, b);
        }
        Map<String, String> groupOf = new HashMap<>();
        Map<String, List<TypeTotal>> types = new LinkedHashMap<>();
        for (Link l : links) {
            String g = root(parent, "A" + l.account());
            groupOf.put(l.account(), g);
            List<TypeTotal> t = types.computeIfAbsent(g, (k) -> new ArrayList<>());
            String key = l.slc() + "/" + l.slt();
            if (t.stream().noneMatch((x) -> (x.slc() + "/" + x.slt()).equals(key))) t.add(new TypeTotal(l.slc(), l.slt(), typeNames.getOrDefault(key, "Type " + key), BigDecimal.ZERO));
        }
        Map<String, String> titles = new HashMap<>();
        for (var e : types.entrySet()) {
            e.getValue().sort(Comparator.comparingInt(TypeTotal::slc).thenComparingInt(TypeTotal::slt));
            titles.put(e.getKey(), title(e.getValue(), classNames));
        }
        return new Groups(Map.copyOf(groupOf), Map.copyOf(titles));
    }

    /** The root of a node's group, shortening the path on the way. */
    private static String root(Map<String, String> parent, String x) {
        String r = x;
        while (parent.containsKey(r) && !parent.get(r).equals(r)) r = parent.get(r);
        String n = x;
        while (!n.equals(r)) {
            String next = parent.get(n);
            parent.put(n, r);
            n = next;
        }
        return r;
    }

    /** The group's title: its class, then its types ("Savings: SD - Regular, Kiddies"). */
    static String title(List<TypeTotal> types, Map<Integer, String> classNames) {
        Map<Integer, List<String>> byClass = new TreeMap<>();
        for (TypeTotal t : types) byClass.computeIfAbsent(t.slc(), (k) -> new ArrayList<>()).add(t.name());
        List<String> parts = new ArrayList<>();
        for (var e : byClass.entrySet()) parts.add(classNames.getOrDefault(e.getKey(), "Class " + e.getKey()) + ": " + String.join(", ", e.getValue()));
        return String.join("; ", parts);
    }
}
