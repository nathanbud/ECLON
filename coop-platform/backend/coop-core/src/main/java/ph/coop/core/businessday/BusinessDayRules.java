package ph.coop.core.businessday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rules of the business day, without the database (BusinessDayRulesTest). A branch has at most one open business date. Start Day opens a
 * date after the last closed one and never later than the real date; End Day closes it once its checks pass, or with a reason when a check that
 * must pass does not. Sundays and the holidays in the old system's holiday table are rest days: opening one is allowed, but only when confirmed.
 */
public final class BusinessDayRules {

    private BusinessDayRules() {}

    /** The fewest letters a reason must have (closing a day that failed a check, reopening a day). */
    public static final int MIN_REASON = 10;

    private static final DateTimeFormatter WORDS = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    /** A date in words, as the screens and messages say it ("Monday, 16 November 2026"). */
    public static String words(LocalDate d) {
        return d.format(WORDS);
    }

    /** Whether the date is a rest day: a Sunday or a holiday. */
    public static boolean restDay(LocalDate d, Map<LocalDate, String> holidays) {
        return d.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.containsKey(d);
    }

    /**
     * The date Start Day offers: today when it is a working day; otherwise the latest working day after the last closed one, up to today; otherwise
     * today (a rest day has to be confirmed). Days between the last closed day and the one opened simply had no business day.
     */
    public static LocalDate suggested(LocalDate lastClosed, LocalDate today, Map<LocalDate, String> holidays) {
        LocalDate earliest = lastClosed == null ? today : lastClosed.plusDays(1);
        for (LocalDate d = today; !d.isBefore(earliest); d = d.minusDays(1)) {
            if (!restDay(d, holidays)) return d;
        }
        return today;
    }

    /** Why the date cannot be opened, or null when it can. */
    public static String startProblem(LocalDate open, LocalDate lastClosed, LocalDate date, LocalDate today) {
        if (open != null) return "The business day " + words(open) + " is still open. End it before starting another.";
        if (date == null) return "Choose the date to open.";
        if (date.isAfter(today)) return "A business day cannot be later than today (" + words(today) + ").";
        if (lastClosed != null && !date.isAfter(lastClosed)) {
            return "The business day must come after the last closed day, " + words(lastClosed) + ". A closed day is not opened again; an administrator can reopen the last one.";
        }
        return null;
    }

    /** What the person should know before opening the date; each must be confirmed. */
    public static List<String> startWarnings(LocalDate lastClosed, LocalDate date, LocalDate today, Map<LocalDate, String> holidays) {
        List<String> out = new ArrayList<>();
        if (date == null) return out;
        if (holidays.containsKey(date)) out.add(words(date) + " is a holiday (" + holidays.get(date) + ").");
        else if (date.getDayOfWeek() == DayOfWeek.SUNDAY) out.add(words(date) + " is a Sunday.");
        if (date.isBefore(today)) {
            long behind = ChronoUnit.DAYS.between(date, today);
            out.add("This opens a past date, " + behind + (behind == 1 ? " day" : " days") + " before today. Everything posted until End Day is dated " + words(date) + ".");
        }
        if (lastClosed != null) {
            long gap = ChronoUnit.DAYS.between(lastClosed, date) - 1;
            long working = 0;
            for (LocalDate d = lastClosed.plusDays(1); d.isBefore(date); d = d.plusDays(1)) if (!restDay(d, holidays)) working++;
            if (working > 0) {
                out.add(working + (working == 1 ? " working day" : " working days") + " since the last closed day (" + words(lastClosed) + ") will have no business day"
                    + (gap > working ? " (" + gap + " days in all)" : "") + ".");
            }
        }
        return out;
    }

    /** One check of the day, as End Day shows it. {@code mustPass}: End Day needs a reason to go ahead while it fails; the others are for review only. */
    public record Check(String key, String label, boolean ok, String detail, boolean mustPass) {}

    /** The checks that must pass but do not. */
    public static List<Check> failing(List<Check> checks) {
        return checks.stream().filter((c) -> c.mustPass() && !c.ok()).toList();
    }

    /** Why End Day cannot go ahead with this reason, or null when it can: every check that must pass passes, or a reason is given. */
    public static String endProblem(List<Check> checks, String reason) {
        List<Check> failed = failing(checks);
        if (failed.isEmpty()) return null;
        if (reason != null && reason.trim().length() >= MIN_REASON) return null;
        return "The day is not ready to end: " + String.join(" ", failed.stream().map((c) -> c.detail() == null ? c.label() + "." : c.detail()).toList())
            + " Settle these first, or give the reason for ending the day anyway (at least " + MIN_REASON + " letters).";
    }

    /** The failed checks in a few words, kept with the closed day. */
    public static String summary(List<Check> failed) {
        String s = String.join(" | ", failed.stream().map((c) -> c.label() + ": " + (c.detail() == null ? "failed" : c.detail())).toList());
        return s.substring(0, Math.min(s.length(), 2000));
    }

    /** Why the last closed day cannot be reopened, or null when it can. */
    public static String reopenProblem(LocalDate open, LocalDate lastClosed, String reason) {
        if (open != null) return "The business day " + words(open) + " is open. Only the last closed day can be reopened, and only while no day is open.";
        if (lastClosed == null) return "No business day has been closed yet.";
        if (reason == null || reason.trim().length() < MIN_REASON) return "Give the reason for reopening the day (at least " + MIN_REASON + " letters).";
        return null;
    }
}
