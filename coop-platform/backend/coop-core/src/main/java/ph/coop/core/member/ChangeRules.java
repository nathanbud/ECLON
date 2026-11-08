package ph.coop.core.member;

/** Who may decide a member change. Pure logic so it can be tested without a database. */
final class ChangeRules {

    private ChangeRules() {}

    /** Why this person may not approve the change, or null when they may. */
    static String approvalProblem(String requestedBy, String approver, boolean isOfficer) {
        if (!isOfficer) return "Only a Membership Officer can approve or reject changes.";
        if (requestedBy.equalsIgnoreCase(approver)) return "You cannot approve your own change. Another Membership Officer must approve it.";
        return null;
    }

    /**
     * A Membership Officer's own edit goes straight through, with no second person to approve it, when the server is set that way ({@code coop.edit.officer-direct}, on by default).
     * It is still recorded as a change, with who made it and a line per field in the audit log. Anyone else's edit still waits for an officer.
     */
    static boolean appliesDirectly(boolean isOfficer, boolean officerDirect) {
        return isOfficer && officerDirect;
    }

    /** Rejecting needs the same role, and a reason so the person who asked knows why. */
    static String rejectionProblem(boolean isOfficer, String note) {
        if (!isOfficer) return "Only a Membership Officer can approve or reject changes.";
        if (note == null || note.trim().length() < 3) return "Give a reason for rejecting the change.";
        return null;
    }
}
