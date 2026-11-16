package ph.coop.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ph.coop.core.workflow.WorkflowRules.StepDef;

class WorkflowRulesTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static StepDef step(int no, String name, String role, String min) {
        return new StepDef(no, name, role, d(min));
    }

    @Test
    void anAmountNeedsTheStepsWhoseLimitItReaches() {
        List<StepDef> steps = List.of(step(1, "Accountant", "ACCOUNTANT", "100000"), step(2, "Manager", "MANAGER", "1000000"), step(3, "Anyone over 50", "ADMIN", "50"));
        assertEquals(List.of(3), WorkflowRules.applicable(steps, d("50")).stream().map(StepDef::no).toList(), "the limit itself counts");
        assertEquals(List.of(3), WorkflowRules.applicable(steps, d("99999.99")).stream().map(StepDef::no).toList());
        assertEquals(List.of(1, 3), WorkflowRules.applicable(steps, d("100000")).stream().map(StepDef::no).toList());
        assertEquals(List.of(1, 2, 3), WorkflowRules.applicable(steps, d("5000000")).stream().map(StepDef::no).toList());
        assertTrue(WorkflowRules.applicable(steps, d("49.99")).isEmpty());
        assertTrue(WorkflowRules.applicable(List.of(), d("1000000000")).isEmpty(), "no workflow, no extra approval");
    }

    @Test
    void aConfigurationMustMakeSense() {
        assertTrue(WorkflowRules.definitionProblems(List.of(step(1, "Accountant", "ACCOUNTANT", "100000"), step(2, "Manager", "MANAGER", "1000000.50"))).isEmpty());
        assertTrue(WorkflowRules.definitionProblems(List.of()).isEmpty(), "no extra steps is a valid configuration");
        assertFalse(WorkflowRules.definitionProblems(List.of(step(1, " ", "ACCOUNTANT", "1"))).isEmpty(), "needs a name");
        assertFalse(WorkflowRules.definitionProblems(List.of(step(1, "Clerk", "JANITOR", "1"))).isEmpty(), "unknown role");
        assertFalse(WorkflowRules.definitionProblems(List.of(step(1, "A", "ACCOUNTANT", "-1"))).isEmpty(), "negative limit");
        assertFalse(WorkflowRules.definitionProblems(List.of(step(1, "A", "ACCOUNTANT", "1.001"))).isEmpty(), "three decimals");
        assertFalse(WorkflowRules.definitionProblems(List.of(step(1, "Same", "ACCOUNTANT", "1"), step(2, "same", "MANAGER", "2"))).isEmpty(), "names differ");
        List<StepDef> six = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) six.add(step(i, "Step " + i, "ACCOUNTANT", "1"));
        assertFalse(WorkflowRules.definitionProblems(six).isEmpty(), "at most five");
        assertFalse(WorkflowRules.definitionProblems(null).isEmpty());
    }

    @Test
    void aStepIsTakenByItsRoleOrAnAdministrator() {
        assertTrue(WorkflowRules.mayTake(Set.of("ACCOUNTANT"), "ACCOUNTANT"));
        assertFalse(WorkflowRules.mayTake(Set.of("PURCHASER"), "ACCOUNTANT"));
        assertTrue(WorkflowRules.mayTake(Set.of("ADMIN"), "MANAGER"));
        assertFalse(WorkflowRules.mayTake(Set.of(), "MANAGER"));
    }

    @Test
    void nobodyApprovesTwiceOrTheirOwnWork() {
        assertNotNull(WorkflowRules.segregationProblem("ana", List.of("ben"), "ANA"), "the maker");
        assertNotNull(WorkflowRules.segregationProblem("ana", List.of("ben", "carl"), "carl"), "an earlier step");
        assertNull(WorkflowRules.segregationProblem("ana", List.of("ben"), "dina"));
        assertNull(WorkflowRules.segregationProblem(null, List.of(), "dina"));
    }
}
