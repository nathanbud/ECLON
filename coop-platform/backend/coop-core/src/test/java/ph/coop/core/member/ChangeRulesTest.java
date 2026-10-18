package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ChangeRulesTest {

    @Test void aMembershipOfficerMayApproveSomeoneElsesChange() {
        assertNull(ChangeRules.approvalProblem("clerk", "officer", true));
    }

    @Test void nobodyApprovesTheirOwnChange() {
        assertNotNull(ChangeRules.approvalProblem("officer", "officer", true));
        assertNotNull(ChangeRules.approvalProblem("Officer", "officer", true)); // names are not case sensitive
    }

    @Test void onlyOfficersApprove() {
        assertNotNull(ChangeRules.approvalProblem("clerk", "other", false));
    }

    @Test void rejectionNeedsAnOfficerAndAReason() {
        assertNull(ChangeRules.rejectionProblem(true, "Wrong birth date"));
        assertNotNull(ChangeRules.rejectionProblem(true, "  "));
        assertNotNull(ChangeRules.rejectionProblem(true, null));
        assertNotNull(ChangeRules.rejectionProblem(false, "Wrong birth date"));
    }
}
