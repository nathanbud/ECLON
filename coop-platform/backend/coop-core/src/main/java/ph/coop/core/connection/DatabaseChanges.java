package ph.coop.core.connection;

/**
 * The one sentence for a feature the connected database is not set up for yet (a database change not applied). It points to where the change is applied
 * from the application ({@link SchemaApply}: Settings > System > Database connection, Apply them now, super administrators), not to a script on the server.
 */
public final class DatabaseChanges {

    private DatabaseChanges() {}

    /** Where a super administrator applies the missing changes. */
    public static final String WHERE = "Settings > System > Database connection";

    /** "This database is not set up for {what} yet, so nothing was done. A super administrator can ..." */
    public static String notSetUp(String what) {
        return "This database is not set up for " + what + " yet, so nothing was done. A super administrator can apply the missing database changes in "
            + WHERE + " (Apply them now), then try again.";
    }
}
