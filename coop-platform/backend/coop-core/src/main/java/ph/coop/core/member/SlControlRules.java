package ph.coop.core.member;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ph.coop.core.member.LifecycleRules.Acct;

/**
 * SL Controls, the rules without a database: which kinds of account may open by themselves with a membership, what a change must satisfy, and the accounts a member type gets
 * from the switches that are on.
 */
final class SlControlRules {

    private SlControlRules() {}

    static final int SAVINGS = 22, FUNDS = 29, SHARE_CAPITAL = 31;

    /** The classes that can open with a membership and the table each one's accounts are kept in; loans, time deposits and receivables open when they are taken out. */
    static final Map<Integer, String> TABLES = Map.of(SAVINGS, "sd", FUNDS, "osl", SHARE_CAPITAL, "sc");

    /** One account type of the switches: its class and code, and whether it is switched off in Account Types. */
    record Type(int slc, int slt, String name, boolean disabled) {}

    /** One switch: for this member type, does this account type open by itself. */
    record Switch(int clientType, int slc, int slt, boolean on) {}

    static Acct acct(int slc, int slt) {
        return new Acct(slc, slt, TABLES.get(slc));
    }

    /** The accounts that open for a member type: the switches that are on, of types still in use, in the order of class and code. */
    static List<Acct> accountsFor(int clientType, List<Switch> switches, Set<String> disabled) {
        List<Acct> out = new ArrayList<>();
        for (Switch s : switches) {
            if (s.clientType() != clientType || !s.on() || !TABLES.containsKey(s.slc()) || disabled.contains(s.slc() + "/" + s.slt())) continue;
            out.add(acct(s.slc(), s.slt()));
        }
        out.sort(Comparator.comparingInt(Acct::slc).thenComparingInt(Acct::slt));
        return out;
    }

    /**
     * What is wrong with switching {@code changes} on top of {@code current} (the switches as stored), empty when it can be saved. {@code types} are the account types of
     * the classes above, as set up.
     */
    static List<String> problems(List<Switch> changes, List<Switch> current, List<Type> types, Map<Integer, String> memberTypes, String reason) {
        List<String> out = new ArrayList<>();
        if (reason == null || reason.trim().length() < 3) out.add("Give a reason for the change.");
        if (changes == null || changes.isEmpty()) {
            out.add("Nothing was changed: the values are the same as before. Change at least one value, or close without saving.");
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (Switch c : changes) {
            String key = c.clientType() + "/" + c.slc() + "/" + c.slt();
            if (!seen.add(key)) out.add("The same switch is changed twice.");
            if (!memberTypes.containsKey(c.clientType())) {
                out.add("Member type " + c.clientType() + " is not registered in this application.");
                continue;
            }
            if (!TABLES.containsKey(c.slc())) {
                out.add("Only savings, fund and share capital accounts can open with a membership; class " + c.slc() + " accounts are opened when they are taken out.");
                continue;
            }
            Type t = types.stream().filter((x) -> x.slc() == c.slc() && x.slt() == c.slt()).findFirst().orElse(null);
            if (t == null) out.add("Account type " + c.slc() + "/" + c.slt() + " is not set up.");
            else if (c.on() && t.disabled()) out.add(t.name() + " is switched off in Account Types, so it cannot open for new members. Switch it on there first.");
        }
        // what each member type would open afterwards: every one must keep a share capital account, since membership is subscribing to the cooperative's capital
        List<Switch> after = merged(current, changes);
        Set<String> disabled = new HashSet<>();
        for (Type t : types) if (t.disabled()) disabled.add(t.slc() + "/" + t.slt());
        for (Map.Entry<Integer, String> m : memberTypes.entrySet()) {
            boolean touched = changes.stream().anyMatch((c) -> c.clientType() == m.getKey());
            if (!touched) continue;
            boolean share = accountsFor(m.getKey(), after, disabled).stream().anyMatch((a) -> a.slc() == SHARE_CAPITAL);
            if (!share) out.add(m.getValue() + " members must keep one share capital account that opens by itself: membership means subscribing to the cooperative's share capital.");
        }
        return out;
    }

    /** The switches with the changes applied (a change to a switch not stored yet adds it). */
    static List<Switch> merged(List<Switch> current, List<Switch> changes) {
        List<Switch> out = new ArrayList<>();
        for (Switch s : current) {
            Switch c = changes.stream().filter((x) -> x.clientType() == s.clientType() && x.slc() == s.slc() && x.slt() == s.slt()).findFirst().orElse(null);
            out.add(c == null ? s : c);
        }
        for (Switch c : changes) {
            if (current.stream().noneMatch((s) -> s.clientType() == c.clientType() && s.slc() == c.slc() && s.slt() == c.slt())) out.add(c);
        }
        return out;
    }
}
