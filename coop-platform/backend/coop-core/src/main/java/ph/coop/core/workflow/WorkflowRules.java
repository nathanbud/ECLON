package ph.coop.core.workflow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The workflow rules that need no database: which extra steps an amount needs, what a configuration must satisfy, and who may take a step. */
final class WorkflowRules {

    private WorkflowRules() {}

    static final int MAX_STEPS = 5;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    /** Roles an extra step may be given. Each can act through the workflow screen whatever the module's own approver role is. */
    static final Set<String> STEP_ROLES = Set.of("ACCOUNTANT", "MANAGER", "CREDIT_COMMITTEE", "PURCHASE_APPROVER", "STOCK_APPROVER", "ADMIN");

    /** One extra approval: its place in the chain, a name, the role that gives it, and the amount from which it applies. */
    record StepDef(int no, String name, String role, BigDecimal minAmount) {}

    /** The steps that apply to an item of {@code amount}, in order: those whose limit the amount reaches. */
    static List<StepDef> applicable(List<StepDef> steps, BigDecimal amount) {
        List<StepDef> out = new ArrayList<>();
        for (StepDef s : steps) if (amount.compareTo(s.minAmount()) >= 0) out.add(s);
        out.sort((a, b) -> Integer.compare(a.no(), b.no()));
        return out;
    }

    /** The problems with a configuration, empty when it can be saved. */
    static List<String> definitionProblems(List<StepDef> steps) {
        List<String> out = new ArrayList<>();
        if (steps == null) return List.of("Give the steps.");
        if (steps.size() > MAX_STEPS) out.add("A workflow can have at most " + MAX_STEPS + " extra steps.");
        Set<String> names = new HashSet<>();
        int n = 0;
        for (StepDef s : steps) {
            n++;
            String at = "Step " + n + ": ";
            if (s.name() == null || s.name().isBlank()) out.add(at + "give the step a name.");
            else if (s.name().length() > 60) out.add(at + "the name is too long (60 characters at most).");
            else if (!names.add(s.name().trim().toLowerCase(Locale.ROOT))) out.add(at + "another step has the same name.");
            if (s.role() == null || !STEP_ROLES.contains(s.role())) out.add(at + "the role must be one of " + String.join(", ", STEP_ROLES.stream().sorted().toList()) + ".");
            if (s.minAmount() == null || s.minAmount().signum() < 0) out.add(at + "the limit cannot be negative.");
            else if (s.minAmount().compareTo(MAX_AMOUNT) > 0 || s.minAmount().stripTrailingZeros().scale() > 2) out.add(at + "the limit is not a valid amount (two decimals at most).");
        }
        return out;
    }

    /** True when someone holding {@code roles} may take a step that needs {@code stepRole}: that role, or an administrator. */
    static boolean mayTake(Set<String> roles, String stepRole) {
        return roles.contains(stepRole) || roles.contains("ADMIN");
    }

    /** Why this person cannot take the step, or null: nobody takes a step on something they made or already approved at an earlier step. */
    static String segregationProblem(String requestedBy, List<String> earlierActors, String actor) {
        if (requestedBy != null && requestedBy.equalsIgnoreCase(actor)) return "You made this, so you cannot approve it.";
        for (String a : earlierActors) if (a.equalsIgnoreCase(actor)) return "You already approved an earlier step of this, so someone else must take this one.";
        return null;
    }
}
