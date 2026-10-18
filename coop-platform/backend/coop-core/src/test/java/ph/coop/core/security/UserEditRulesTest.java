package ph.coop.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class UserEditRulesTest {

    @Test void nobodyEditsTheirOwnAccount() {
        assertNotNull(UserEditRules.problem("admin", "SUPERADMIN", "admin", "SUPERADMIN", false));
        assertNotNull(UserEditRules.problem("Admin", "SUPERADMIN", "admin", "SUPERADMIN", false)); // not case sensitive
    }

    @Test void aSuperAdministratorMayEditAnyoneElse() {
        assertNull(UserEditRules.problem("boss", "SUPERADMIN", "clerk", "USER", false));
        assertNull(UserEditRules.problem("boss", "SUPERADMIN", "other", "ADMIN", true));
    }

    @Test void anAdministratorMayEditOrdinaryUsersButNotAdministratorsOrLevels() {
        assertNull(UserEditRules.problem("adm", "ADMIN", "clerk", "USER", false));
        assertNotNull(UserEditRules.problem("adm", "ADMIN", "other", "ADMIN", false));
        assertNotNull(UserEditRules.problem("adm", "ADMIN", "clerk", "USER", true));
    }

    @Test void switchesBecomeOneOrZero() {
        assertEquals("1", UserEditRules.normalize("Can edit", "bool", null, false, null, "true"));
        assertEquals("0", UserEditRules.normalize("Can edit", "bool", null, false, null, ""));
        assertThrows(IllegalArgumentException.class, () -> UserEditRules.normalize("Can edit", "bool", null, false, null, "maybe"));
    }

    @Test void numbersAndMoneyAreChecked() {
        assertEquals("30", UserEditRules.normalize("Days", "number", null, false, null, " 30 "));
        assertThrows(IllegalArgumentException.class, () -> UserEditRules.normalize("Days", "number", null, false, null, "-1"));
        assertEquals("50000.00", UserEditRules.normalize("Limit", "money", null, false, null, "50,000.00"));
        assertThrows(IllegalArgumentException.class, () -> UserEditRules.normalize("Limit", "money", null, false, null, "1.234"));
    }

    @Test void choicesAndListsOnlyAcceptAllowedValues() {
        Set<String> levels = UserEditRules.LEVELS;
        assertEquals("USER", UserEditRules.normalize("Level", "text", levels, false, null, "USER"));
        assertThrows(IllegalArgumentException.class, () -> UserEditRules.normalize("Level", "text", levels, false, null, "ROOT"));

        Set<String> roles = Set.of("Clerk", "Teller", "Cashier");
        assertEquals("Clerk,Teller", UserEditRules.normalize("Assignment", "text", roles, true, null, "Clerk, Teller, Clerk"));
        assertNull(UserEditRules.normalize("Assignment", "text", roles, true, null, ""));
        assertThrows(IllegalArgumentException.class, () -> UserEditRules.normalize("Assignment", "text", roles, true, null, "Clerk,Wizard"));
    }

    @Test void datesAndTextLengthAreChecked() {
        assertEquals("2027-01-31", UserEditRules.normalize("Expiry", "date", null, false, null, "2027-01-31"));
        assertThrows(IllegalArgumentException.class, () -> UserEditRules.normalize("Expiry", "date", null, false, null, "31/01/2027"));
        assertThrows(IllegalArgumentException.class, () -> UserEditRules.normalize("Name", "text", null, false, 5, "Too long name"));
    }

    @Test void unchangedValuesAreRecognised() {
        assertTrue(UserEditRules.same("bool", "1", "1"));
        assertTrue(UserEditRules.same("money", "50000.00", "50000"));
        assertTrue(UserEditRules.same("text", null, ""));
        assertTrue(UserEditRules.sameList("003,002,004", "002,003,004"));
        assertFalse(UserEditRules.same("number", "2", "3"));
        assertFalse(UserEditRules.sameList("003", "003,002"));
    }
}
