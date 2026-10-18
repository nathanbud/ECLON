package ph.coop.core.db;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the app portable to PostgreSQL: no MySQL-only SQL outside {@link Db}, the one class that knows how the two differ.
 * If this fails, use a Db helper (or portable SQL) instead of adding the MySQL form.
 */
class PortableSqlTest {

    private static final List<Pattern> BANNED = List.of(
        Pattern.compile("LAST_INSERT_ID", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ON\\s+DUPLICATE\\s+KEY", Pattern.CASE_INSENSITIVE),
        Pattern.compile("INSERT\\s+IGNORE", Pattern.CASE_INSENSITIVE),
        Pattern.compile("REPLACE\\s+INTO", Pattern.CASE_INSENSITIVE),
        Pattern.compile("GROUP_CONCAT", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bDATABASE\\(\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("information_schema", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?<![.\\w])(YEAR|MONTH|DAY)\\(", Pattern.CASE_INSENSITIVE), // SQL only, not a Java call such as r.year()
        Pattern.compile("\\b(IFNULL|DATE_FORMAT|STR_TO_DATE|DATE_ADD|DATE_SUB|DATEDIFF|TIMESTAMPDIFF|UNIX_TIMESTAMP|FIND_IN_SET|CURDATE)\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bSHOW\\s+(TABLES|COLUMNS|CREATE)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("STRAIGHT_JOIN", Pattern.CASE_INSENSITIVE));

    @Test
    void sqlOutsideTheDbHelperIsPortable() throws IOException {
        Path root = Path.of("src", "main", "java");
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter((p) -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("Db.java")) continue;
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.trim().startsWith("//") || line.trim().startsWith("*") || line.trim().startsWith("/*")) continue;
                    for (Pattern p : BANNED) {
                        if (p.matcher(line).find()) problems.add(f + ":" + (i + 1) + " uses " + p.pattern());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "MySQL-only SQL found:\n" + String.join("\n", problems));
    }
}
