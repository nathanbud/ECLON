package ph.coop.core.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The errors controllers throw, in one place, so each reads the same: a message fit to show the user and the right status. */
public final class ApiErrors {

    private ApiErrors() {}

    /** 400: the request is wrong in a way the person can fix. */
    public static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** 403: the person may not do this. */
    public static ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    /** 404: there is no such record. */
    public static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    /** 409: the request clashes with the current state (already decided, a duplicate, changed by someone else). */
    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
