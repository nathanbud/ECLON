package ph.coop.core.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InboxRulesTest {

    @Test
    void rolesLoseTheirPrefixAndOtherAuthoritiesStay() {
        assertEquals(Set.of("ACCOUNTANT", "PERM_MEMBER_EDIT"), InboxRules.roles(List.of("ROLE_ACCOUNTANT", "PERM_MEMBER_EDIT")));
    }

    @Test
    void eachRoleSeesItsOwnKindOfWork() {
        var officer = InboxRules.sourcesFor(Set.of("MEMBERSHIP_OFFICER"));
        assertTrue(officer.membership());
        assertFalse(officer.accounting() || officer.purchasing() || officer.stock());

        var accountant = InboxRules.sourcesFor(Set.of("ACCOUNTANT"));
        assertTrue(accountant.accounting());
        assertFalse(accountant.membership() || accountant.purchasing() || accountant.stock());

        assertTrue(InboxRules.sourcesFor(Set.of("PURCHASE_APPROVER")).purchasing());
        assertTrue(InboxRules.sourcesFor(Set.of("STOCK_APPROVER")).stock());
    }

    @Test
    void anAdministratorSeesEverythingExceptMembershipApprovalsWhichNeedTheOfficerRole() {
        var admin = InboxRules.sourcesFor(Set.of("ADMIN"));
        assertTrue(admin.accounting() && admin.purchasing() && admin.stock());
        assertFalse(admin.membership(), "member requests are decided by a Membership Officer");
    }

    @Test
    void aPersonWithNoApprovingRoleSeesNothing() {
        assertFalse(InboxRules.sourcesFor(Set.of()).any());
        assertFalse(InboxRules.sourcesFor(Set.of("POS_CASHIER", "PURCHASER", "STOREKEEPER")).any(), "making things is not approving them");
    }

    @Test
    void nobodyIsShownTheirOwnWorkToDecide() {
        assertTrue(InboxRules.own("Ana", "ana"));
        assertFalse(InboxRules.own("ana", "ben"));
        assertFalse(InboxRules.own(null, "ben"));
    }

    @Test
    void aMemoNeedsAThirdPersonToPost() {
        assertTrue(InboxRules.mayPostMemo("ana", "ben", "carla"));
        assertFalse(InboxRules.mayPostMemo("ana", "ben", "ana"));
        assertFalse(InboxRules.mayPostMemo("ana", "ben", "BEN"));
    }
}
