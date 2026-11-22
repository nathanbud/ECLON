package ph.coop.core.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The system errors kept for administrators (Settings > System > Error log): one JSON line per unexpected failure in a file beside the server's log
 * ({@code logs/errors.jsonl} by default), so they survive a restart and need no database. Each holds what the person was told and the technical
 * details: who, which request, the kind of failure, its cause and fix with table and column names, the exception and its trace. The file is kept on
 * the server only; it can hold names of tables and the person who met the error, never passwords (the request's body is not kept).
 */
@Component
public class ErrorJournal {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorJournal.class);
    /** The newest entries the screen is given. */
    static final int SHOWN = 500;
    private static final int TRACE_LIMIT = 8_000;

    public record Entry(String at, String reference, String user, String method, String path, int status, String code,
                        String userMessage, String problem, String fix, String exception, String detail, String trace) {}

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    public ErrorJournal(@Value("${coop.errors.file:}") String configured, @Value("${logging.file.name:}") String logFile) {
        if (!configured.isBlank()) file = Path.of(configured);
        else if (!logFile.isBlank()) file = Path.of(logFile).toAbsolutePath().getParent().resolve("errors.jsonl");
        else file = Path.of("logs", "errors.jsonl");
    }

    /** Adds one entry. Never fails the request: a journal that cannot be written is reported in the server's log. */
    public synchronized void record(String reference, String user, String method, String path, ErrorExplainer.Explained e, Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        StringWriter trace = new StringWriter();
        ex.printStackTrace(new PrintWriter(trace));
        String t = trace.toString();
        Entry entry = new Entry(LocalDateTime.now().withNano(0).toString(), reference, user, method, path, e.status(), e.code(), ErrorExplainer.forUser(e.code()),
            e.problem(), e.fix(), root.getClass().getName(), ErrorExplainer.firstLine(root.getMessage()), t.length() > TRACE_LIMIT ? t.substring(0, TRACE_LIMIT) + "\n…" : t);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, json.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException io) {
            LOG.warn("Could not write the error journal {}: {}", file, io.getMessage());
        }
    }

    /** The newest entries first, at most {@link #SHOWN}. An unreadable line is skipped. */
    public synchronized List<Entry> newest() {
        if (!Files.isRegularFile(file)) return List.of();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException io) {
            LOG.warn("Could not read the error journal {}: {}", file, io.getMessage());
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < SHOWN; i--) {
            String l = lines.get(i).trim();
            if (l.isEmpty()) continue;
            try {
                out.add(json.readValue(l, Entry.class));
            } catch (IOException bad) {
                // a line cut short (the server stopped while writing) is left out
            }
        }
        return Collections.unmodifiableList(out);
    }

    Path file() {
        return file;
    }
}
