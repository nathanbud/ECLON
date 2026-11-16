package ph.coop.core.connection;

/**
 * Implemented by anything that remembers facts about the database it has been talking to (which tables exist, a balance it worked out, a list it read).
 * Called after the server's database is switched, so nothing from the old database is shown against the new one.
 */
public interface OnConnectionSwitch {
    void connectionSwitched();
}
