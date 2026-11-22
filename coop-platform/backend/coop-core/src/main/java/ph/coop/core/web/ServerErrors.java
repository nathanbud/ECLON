package ph.coop.core.web;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers an unexpected failure in two layers. The person using the screen is told, in everyday words, what could not be done and what they can do
 * ({@link ErrorExplainer#forUser}), with a reference. System administrators get the technical details (the cause with table and column names, the fix,
 * who met it, which request, the trace) in the error log ({@link ErrorJournal}, Settings > System > Error log, {@code GET /api/admin/errors}) and in
 * the server's log, both under the same reference.
 * <p>
 * Errors the application already words for people (a {@code ResponseStatusException} from {@link ApiErrors}, Spring's own request errors) and sign-in
 * or permission refusals are left to their usual handling: rethrowing the same exception from here passes it on unchanged.
 */
@RestControllerAdvice
@RestController
public class ServerErrors {

    private static final Logger LOG = LoggerFactory.getLogger(ServerErrors.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final ErrorJournal journal;

    public ServerErrors(ErrorJournal journal) {
        this.journal = journal;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletRequest request) throws Exception {
        if (ex instanceof ErrorResponse || ex instanceof AccessDeniedException || ex instanceof AuthenticationException) throw ex;
        ErrorExplainer.Explained e = ErrorExplainer.explain(ex);
        String reference = reference();
        Principal who = request.getUserPrincipal();
        LOG.error("[{}] {}: {} {}", reference, e.code(), e.problem(), e.fix(), ex);
        journal.record(reference, who == null ? null : who.getName(), request.getMethod(), request.getRequestURI(), e, ex);
        // only plain words go back to the screen; the technical cause stays with the administrators
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", e.status());
        body.put("reference", reference);
        body.put("message", ErrorExplainer.forUser(e.code()) + " Reference: " + reference + ".");
        return ResponseEntity.status(e.status()).body(body);
    }

    /** The error log for system administrators, newest first. */
    @GetMapping("/api/admin/errors")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ErrorJournal.Entry> errors() {
        return journal.newest();
    }

    /** E-yyMMdd-XXXX: the day and four letters, easy to read out and to find in the logs. */
    static String reference() {
        StringBuilder b = new StringBuilder("E-").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"))).append('-');
        for (int i = 0; i < 4; i++) b.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        return b.toString();
    }
}
