package ph.coop.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The refusals the application words itself should say what is wrong, why where it helps, and what to do, like
 * {@code LifecycleRules.missingAccountType} ("Budikey, Nathaniel cannot be registered yet ... To finish, either: 1. ... 2. ...").
 * <p>
 * A message is <b>weak</b> when it is a complete sentence under 70 characters that gives no direction at all: no word such as choose, give, fill in,
 * check, reload, ask, try, use, set up, switch, must, only, first or then ("Member not found.", "Nothing was changed."). Rewriting them is done area by
 * area; this test keeps the work from going backwards: the number may only go down ({@link #LIMIT}, lower it when a batch is done), and the areas already
 * done ({@link #DONE}) must have none.
 */
class MessageStyleTest {

    /** Weak messages left. Only ever lower this, after rewriting some. 365 before the first batch (2026-11-21); 227 after the not-set-up messages pointed to Apply them now. */
    static final int LIMIT = 227;

    /** Areas (the folder under ph/coop/core) whose messages have all been rewritten. */
    static final Set<String> DONE = Set.of("member", "security", "businessday", "organization");

    private static final Pattern MESSAGE = Pattern.compile(
        "(?:new ResponseStatusException\\(HttpStatus\\.\\w+,\\s*|ApiErrors\\.(?:bad|forbidden|notFound|conflict)\\(\\s*|\\b(?:bad|forbidden|notFound|conflict|badRequest)\\(\\s*)\"((?:[^\"\\\\]|\\\\.)*)\"(\\s*\\+)?");
    private static final Pattern DIRECTION = Pattern.compile(
        "(?i)\\b(choose|give|fill|check|reload|ask|try|use|add|set up|switch|shorten|open|take|wait|start|apply|first|then|must|only|need|needs|enter|type|pick|select|attach|remove|contact|refresh|search|sign in|approve|reject|correct|update|change|cancel|close|post|save|send)\\b");

    record Weak(String area, String file, String text) {}

    static List<Weak> weak() throws IOException {
        List<Weak> out = new ArrayList<>();
        Path base = Path.of("src", "main", "java", "ph", "coop", "core");
        try (Stream<Path> files = Files.walk(base)) {
            for (Path f : files.filter((p) -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = MESSAGE.matcher(text);
                while (m.find()) {
                    String message = m.group(1);
                    boolean built = m.group(2) != null; // continued with +: the rest is worked out at run time
                    if (!built && message.length() < 70 && !DIRECTION.matcher(message).find()) {
                        Path rel = base.relativize(f);
                        out.add(new Weak(rel.getNameCount() > 1 ? rel.getName(0).toString() : "core", f.getFileName().toString(), message));
                    }
                }
            }
        }
        return out;
    }

    @Test
    void weakMessagesOnlyGoDown() throws IOException {
        List<Weak> w = weak();
        assertTrue(w.size() <= LIMIT, w.size() + " weak messages, more than the " + LIMIT + " allowed. Say what to do in the new ones, for example: "
            + w.stream().limit(5).map((x) -> x.file() + ": \"" + x.text() + "\"").toList());
        // when a batch brings it down, lower LIMIT to the new number so it cannot creep back up
    }

    @Test
    void theAreasAlreadyDoneHaveNone() throws IOException {
        List<Weak> left = weak().stream().filter((x) -> DONE.contains(x.area())).toList();
        assertEquals(List.of(), left, "these areas were rewritten; say what to do in these messages too");
    }
}
