package ph.coop.core.accounting.ledger;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The request key a screen sends with a posting (header {@code Idempotency-Key}): one per action, made when its dialog opens, so pressing Post
 * twice, or a retry after a lost connection, gives back the first voucher instead of a second one (documentation/design/posting-engine.md).
 */
public final class RequestKeys {

    public static final String HEADER = "Idempotency-Key";

    private RequestKeys() {}

    /**
     * The key of the request being handled, prefixed with what is posted and by whom (so two people's keys never meet), or null when the screen sent
     * none or one that is not a plain key (letters, digits and dashes, at most 64).
     */
    public static String current(String what, String username) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)) return null;
        HttpServletRequest r = a.getRequest();
        String k = r.getHeader(HEADER);
        if (k == null || !k.matches("[A-Za-z0-9-]{8,64}")) return null;
        return what + "/" + username + "/" + k;
    }
}
