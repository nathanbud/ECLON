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

    @Test
    void aLoanApplicationAsksForTheStepThePersonMayDoAndNeverOfItsEncoder() {
        var all = new InboxRules.LoanRights(true, true, true);
        assertEquals("LOAN_DEDUCTION", InboxRules.loanStep(1, true, 7, 9, all));
        assertEquals("LOAN_DEDUCTION", InboxRules.loanStep(1, true, 9, 9, all), "the encoder may do the deductions of an application waiting for them");
        assertEquals("LOAN_VERIFY", InboxRules.loanStep(1, false, 7, 9, all));
        assertEquals("LOAN_APPROVE", InboxRules.loanStep(2, false, 7, 9, all));
        assertEquals(null, InboxRules.loanStep(1, false, 9, 9, all), "not one's own to verify");
        assertEquals(null, InboxRules.loanStep(2, false, 9, 9, all), "not one's own to approve");
        assertEquals(null, InboxRules.loanStep(1, true, 7, 9, new InboxRules.LoanRights(false, true, true)), "deductions need the Deduction step");
        assertEquals(null, InboxRules.loanStep(2, false, 7, 9, new InboxRules.LoanRights(true, true, false)), "approval needs the Approval step");
    }

    @Test
    void aVoucherIsApprovedBySomeoneElseAndPostedByAThirdPerson() {
        assertEquals("APPROVE", InboxRules.voucherStep(1, null, 2));
        assertEquals(null, InboxRules.voucherStep(1, null, 1), "not one's own");
        assertEquals("POST", InboxRules.voucherStep(1, 2, 3));
        assertEquals(null, InboxRules.voucherStep(1, 2, 2), "the approver does not post it");
        assertEquals(null, InboxRules.voucherStep(1, 2, 1), "the encoder does not post it");
    }

    @Test
    void supervisorRequestsGoToWhoeverDecidesThatKind() {
        assertTrue(InboxRules.decidesOverride("TD_RATE", true, false, false));
        assertFalse(InboxRules.decidesOverride("TD_RATE", false, true, false), "an accountant does not decide teller limits");
        assertTrue(InboxRules.decidesOverride("PENALTY_WAIVER", false, true, false));
        assertFalse(InboxRules.decidesOverride("PENALTY_WAIVER", true, false, false), "the head cashier does not waive penalties");
        assertTrue(InboxRules.decidesOverride("CHEQUE_RETURN", true, false, false));
        assertTrue(InboxRules.decidesOverride("CHEQUE_RETURN", false, true, false));
        assertTrue(InboxRules.decidesOverride("ANYTHING", false, false, true), "an administrator decides any");
    }
}
