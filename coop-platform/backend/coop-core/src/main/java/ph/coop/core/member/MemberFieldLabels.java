package ph.coop.core.member;

import java.util.Map;
import java.util.stream.Collectors;

/** The member profile's field names in words, by column (ResAddStreet to "Street"), for screens outside the profile that show those columns, such as the audit log. */
public final class MemberFieldLabels {

    private MemberFieldLabels() {}

    private static final Map<String, String> LABELS = ClientDetailController.EDITABLE.values().stream()
        .collect(Collectors.toUnmodifiableMap(ClientDetailController.Spec::column, ClientDetailController.Spec::label, (a, b) -> a));

    /** The label of a profile column, or null when it is not one. */
    public static String of(String column) {
        return column == null ? null : LABELS.get(column);
    }
}
