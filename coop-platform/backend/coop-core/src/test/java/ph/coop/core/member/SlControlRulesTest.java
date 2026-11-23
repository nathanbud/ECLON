package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ph.coop.core.member.SlControlRules.Switch;
import ph.coop.core.member.SlControlRules.Type;

/** SL Controls without a database: what opens for a member type, and what a change must satisfy. */
class SlControlRulesTest {

    static final Map<Integer, String> MEMBERS = Map.of(1, "Regular Individual", 2, "Associate");
    static final List<Type> TYPES = List.of(
        new Type(22, 1, "SD - Regular", false), new Type(22, 2, "SD- Associate", false), new Type(22, 9, "Old savings", true),
        new Type(29, 5, "MBFP", false), new Type(29, 6, "HABP", false), new Type(31, 1, "Paid up Share Cap", false), new Type(31, 3, "Paid Up Cap Pref", false));
    static final List<Switch> START = List.of(
        new Switch(1, 22, 1, true), new Switch(1, 29, 5, true), new Switch(1, 29, 6, true), new Switch(1, 31, 1, true), new Switch(2, 22, 2, true), new Switch(2, 31, 3, true));

    @Test
    void nothingOpensThatTheCooperativeDidNotSwitchOn() {
        assertEquals(List.of(), SlControlRules.accountsFor(1, List.of(), Set.of()), "no switches stored: no account opens by itself");
        assertEquals(List.of(), SlControlRules.accountsFor(3, START, Set.of()), "a member type with no switch on gets nothing");
    }

    @Test
    void onlySwitchesThatAreOnOfTypesInUseOpenInOrder() {
        List<Switch> s = List.of(new Switch(1, 31, 1, true), new Switch(1, 29, 6, false), new Switch(1, 22, 1, true), new Switch(1, 22, 9, true), new Switch(2, 31, 3, true));
        var accts = SlControlRules.accountsFor(1, s, Set.of("22/9"));
        assertEquals(List.of("22/1/sd", "31/1/sc"), accts.stream().map((a) -> a.slc() + "/" + a.slt() + "/" + a.table()).toList());
    }

    @Test
    void aSoundChangeHasNoProblems() {
        List<Switch> change = List.of(new Switch(1, 29, 6, false), new Switch(2, 29, 5, true));
        assertEquals(List.of(), SlControlRules.problems(change, START, TYPES, MEMBERS, "HABP is now optional"));
        List<Switch> after = SlControlRules.merged(START, change);
        assertEquals(7, after.size(), "a switch not stored before is added");
        assertTrue(after.contains(new Switch(1, 29, 6, false)));
    }

    @Test
    void everyMemberTypeKeepsAShareCapitalAccount() {
        var p = SlControlRules.problems(List.of(new Switch(2, 31, 3, false)), START, TYPES, MEMBERS, "testing");
        assertEquals(1, p.size());
        assertTrue(p.get(0).startsWith("Associate members must keep one share capital account"), p.get(0));
        // swapping it for another share capital type in the same save is fine
        assertEquals(List.of(), SlControlRules.problems(List.of(new Switch(2, 31, 3, false), new Switch(2, 31, 1, true)), START, TYPES, MEMBERS, "testing"));
    }

    @Test
    void badChangesAreRefusedWithTheReason() {
        assertTrue(SlControlRules.problems(List.of(new Switch(1, 29, 6, false)), START, TYPES, MEMBERS, " ").contains("Give a reason for the change."));
        assertTrue(SlControlRules.problems(List.of(), START, TYPES, MEMBERS, "why").contains("Nothing was changed: the values are the same as before. Change at least one value, or close without saving."));
        assertTrue(SlControlRules.problems(List.of(new Switch(1, 12, 1, true)), START, TYPES, MEMBERS, "why").get(0).startsWith("Only savings, fund and share capital accounts"));
        assertTrue(SlControlRules.problems(List.of(new Switch(1, 22, 9, true)), START, TYPES, MEMBERS, "why").get(0).contains("switched off in Account Types"));
        assertTrue(SlControlRules.problems(List.of(new Switch(1, 22, 77, true)), START, TYPES, MEMBERS, "why").get(0).contains("is not set up"));
        assertTrue(SlControlRules.problems(List.of(new Switch(5, 22, 1, true)), START, TYPES, MEMBERS, "why").get(0).contains("not registered in this application"));
        assertTrue(SlControlRules.problems(List.of(new Switch(1, 29, 6, false), new Switch(1, 29, 6, true)), START, TYPES, MEMBERS, "why").contains("The same switch is changed twice."));
    }

    @Test
    void aSwitchLeftOnForATypeNotSetUpCanBeSwitchedOffButNotOn() {
        // a second cooperative's database kept switches from the first one's list for types it does not have (29/9 here)
        List<Switch> current = new java.util.ArrayList<>(START);
        current.add(new Switch(1, 29, 9, true));
        assertEquals(List.of(), SlControlRules.problems(List.of(new Switch(1, 29, 9, false)), current, TYPES, MEMBERS, "Not offered here"));
        assertTrue(SlControlRules.problems(List.of(new Switch(2, 29, 9, true)), current, TYPES, MEMBERS, "why").get(0).contains("Set it up in Settings > Products first"));
    }
}
