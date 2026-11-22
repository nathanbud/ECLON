package ph.coop.core.member;

import java.util.ArrayList;
import java.util.List;
import ph.coop.core.member.MemberDefaultsController.Choice;

/** The checks on member defaults, apart from the database: each choice must exist here, at least one member type, and a reason. Leaving a default empty is allowed. */
final class MemberDefaultsRules {

    private MemberDefaultsRules() {}

    static final int MIN_REASON = 3;

    static List<String> problems(Integer department, Integer office, List<Integer> types, List<Choice> departments, List<Choice> offices, List<Choice> memberTypes, String reason) {
        List<String> out = new ArrayList<>();
        if (department != null) {
            Choice d = departments.stream().filter((x) -> x.code() == department).findFirst().orElse(null);
            if (d == null) out.add("Department " + department + " is not on this database. Choose one from the list, or add it first in Settings > Organization > Departments.");
            else if (d.disabled()) out.add(d.name() + " is switched off, so new members cannot be given it. Choose another department, or switch it on in Settings > Organization > Departments.");
        }
        if (office != null && offices.stream().noneMatch((x) -> x.code() == office))
            out.add("Office " + office + " is not on this database. Choose one from the list, or add it first in Settings > Organization > Offices.");
        if (types.isEmpty()) out.add("Choose at least one member type that can be registered, such as Regular Individual; otherwise no one can be registered.");
        for (int t : types) if (memberTypes.stream().noneMatch((x) -> x.code() == t)) out.add("Member type " + t + " is not on this database. Choose from the list.");
        if (reason == null || reason.trim().length() < MIN_REASON) out.add("Give a reason for the change (at least " + MIN_REASON + " characters); it is kept in the audit log.");
        return out;
    }
}
