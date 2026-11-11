package ph.coop.core.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Counts wrong passwords for each username. {@value #MAX_FAILURES} wrong passwords within {@link #WINDOW} lock the account for {@link #LOCK}; a right password
 * (or an administrator) clears it. Kept in memory, so a restart of the app clears every lock; each lock is written to the audit log by the caller. The key is
 * the typed name in lower case, and unknown names are counted too, so a lock does not reveal which names exist.
 */
@Component
public class LoginAttempts {

    public static final int MAX_FAILURES = 10;
    /** The sign-in screen counts the tries left only once this many are left (the fifth wrong password of ten); before that it just says the password is wrong. */
    public static final int SHOW_COUNT_FROM = 5;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    public static final Duration LOCK = Duration.ofMinutes(15);

    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntil = new ConcurrentHashMap<>();

    public LoginAttempts(Clock clock) {
        this.clock = clock;
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /** Records a wrong password. Returns true when this one locked the account. */
    public boolean failed(String username) {
        String k = key(username);
        Instant now = clock.instant();
        if (isLocked(k)) return false;
        Deque<Instant> q = failures.computeIfAbsent(k, (x) -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(WINDOW))) q.pollFirst();
            if (q.size() >= MAX_FAILURES) {
                q.clear();
                lockedUntil.put(k, now.plus(LOCK));
                return true;
            }
        }
        return false;
    }

    /** Wrong passwords left before the lock, counting those in the window; the full allowance for a name with none. */
    public int remaining(String username) {
        Deque<Instant> q = failures.get(key(username));
        if (q == null) return MAX_FAILURES;
        Instant cutoff = clock.instant().minus(WINDOW);
        synchronized (q) {
            long recent = q.stream().filter((t) -> !t.isBefore(cutoff)).count();
            return (int) Math.max(0, MAX_FAILURES - recent);
        }
    }

    /** A right password: forget the wrong ones. Does not lift a lock that is already on (that needs the lock to run out or an administrator). */
    public void succeeded(String username) {
        failures.remove(key(username));
    }

    public boolean isLocked(String username) {
        String k = key(username);
        Instant until = lockedUntil.get(k);
        if (until == null) return false;
        if (until.isAfter(clock.instant())) return true;
        lockedUntil.remove(k);
        return false;
    }

    /** Seconds left on the lock, 0 when none. */
    public long secondsLeft(String username) {
        Instant until = lockedUntil.get(key(username));
        if (until == null) return 0;
        return Math.max(0, Duration.between(clock.instant(), until).getSeconds());
    }

    /** An administrator lifts the lock. Returns true when there was one. */
    public boolean unlock(String username) {
        String k = key(username);
        failures.remove(k);
        return lockedUntil.remove(k) != null;
    }

    /** Who is locked now and for how many seconds, by name. */
    public Map<String, Long> locked() {
        Map<String, Long> out = new TreeMap<>();
        for (String k : lockedUntil.keySet()) if (isLocked(k)) out.put(k, secondsLeft(k));
        return out;
    }
}
