package ph.coop.core.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The shape every report takes (guide phase 20): a report declares its parameters and returns a table of typed columns, so one screen can list, run, print and export any of
 * them. Reports are read only. A group of reports is a {@link Provider} bean; {@link ReportController} collects them all.
 */
public final class Reports {

    private Reports() {}

    /** A parameter a report asks for: type is "date", "number" or "text"; a blank default means the user must give it or it is optional. */
    public record Param(String name, String label, String type, String defaultValue) {}

    /** A column: type is "text", "money", "number", "percent" or "date" (the screen formats by it). */
    public record Column(String key, String label, String type) {}

    /** The result: rows aligned to the columns (strings, numbers, ISO dates), optional totals row and a note under the table. */
    public record Table(List<Column> columns, List<List<Object>> rows, List<Object> totals, String note) {}

    public record Info(String id, String group, String title, String description, List<Param> params) {}

    public interface Report {
        Info info();

        /** Roles besides ADMIN and ACCOUNTANT that may run it (names without ROLE_). */
        Set<String> roles();

        Table run(Args args);
    }

    public interface Provider {
        List<Report> reports();
    }

    /** The parameters of one run, with the checks reports need. */
    public static final class Args {
        private final Map<String, String> values;

        public Args(Map<String, String> values) {
            this.values = values;
        }

        public String text(String name) {
            String v = values.get(name);
            return v == null || v.isBlank() ? null : v.trim();
        }

        public LocalDate date(String name, LocalDate fallback) {
            String v = text(name);
            if (v == null) return fallback;
            try {
                return LocalDate.parse(v);
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give " + name + " as a date, such as 2026-04-30.");
            }
        }

        public Long number(String name) {
            String v = text(name);
            if (v == null) return null;
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give " + name + " as a number.");
            }
        }

        public int integer(String name, int fallback, int min, int max) {
            Long v = number(name);
            return v == null ? fallback : (int) Math.max(min, Math.min(max, v));
        }

        /** A period: both dates given, the end not before the start, and not longer than {@code maxDays}. */
        public LocalDate[] period(LocalDate defaultFrom, LocalDate defaultTo, int maxDays) {
            LocalDate from = date("from", defaultFrom), to = date("to", defaultTo);
            if (to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
            if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > maxDays) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a period of at most " + maxDays + " days.");
            return new LocalDate[] {from, to};
        }
    }

    /** Builds a report from its description and a function. */
    public static Report of(String id, String group, String title, String description, List<Param> params, Set<String> roles, Function<Args, Table> body) {
        Info info = new Info(id, group, title, description, params);
        return new Report() {
            @Override public Info info() { return info; }
            @Override public Set<String> roles() { return roles; }
            @Override public Table run(Args args) { return body.apply(args); }
        };
    }

    public static Column text(String key, String label) {
        return new Column(key, label, "text");
    }

    public static Column money(String key, String label) {
        return new Column(key, label, "money");
    }

    public static Column number(String key, String label) {
        return new Column(key, label, "number");
    }

    public static Column percent(String key, String label) {
        return new Column(key, label, "percent");
    }

    public static Column date(String key, String label) {
        return new Column(key, label, "date");
    }

    public static Param dateParam(String name, String label, LocalDate def) {
        return new Param(name, label, "date", def == null ? "" : def.toString());
    }

    public static Param numberParam(String name, String label, String def) {
        return new Param(name, label, "number", def == null ? "" : def);
    }

    public static Param textParam(String name, String label, String def) {
        return new Param(name, label, "text", def == null ? "" : def);
    }

    /** A cell for a date (as ISO text) or null. */
    public static Object isoDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate().toString();
    }

    /** Percent of {@code part} in {@code whole}, to two decimals, 0 when the whole is zero. */
    public static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return part.multiply(new BigDecimal("100")).divide(whole, 2, java.math.RoundingMode.HALF_UP);
    }

    /** A mutable list for building a row. */
    public static List<Object> row(Object... cells) {
        return new ArrayList<>(java.util.Arrays.asList(cells));
    }
}
