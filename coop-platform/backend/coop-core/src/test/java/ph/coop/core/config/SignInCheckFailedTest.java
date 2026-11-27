package ph.coop.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

/**
 * A sign-in that could not be checked (the database did not answer while reading the person) is a 503, not a 401: a 401 signs the person out of the
 * window ("the server no longer accepts your sign-in"), as happened on a slow hosted database. A real refusal stays a 401.
 */
class SignInCheckFailedTest {

    private final SecurityConfig config = new SecurityConfig();
    private final LoginAttempts attempts = new LoginAttempts(Clock.systemUTC());

    @Test
    void aDatabaseFailureWhileCheckingIsA503ThatKeepsThePersonSignedIn() throws Exception {
        var res = new MockHttpServletResponse();
        var ex = new InternalAuthenticationServiceException("Unable to load user", new org.springframework.dao.DataAccessResourceFailureException("connection reset"));
        config.entryPoint(attempts).commence(new MockHttpServletRequest("GET", "/api/me/access"), res, ex);
        assertEquals(503, res.getStatus());
        assertNull(res.getHeader("WWW-Authenticate"), "no sign-in challenge");
        assertTrue(res.getContentAsString().contains("SIGN_IN_CHECK_FAILED"), res.getContentAsString());
        assertTrue(res.getContentAsString().contains("still signed in"), res.getContentAsString());
    }

    @Test
    void aWrongPasswordIsStillA401() throws Exception {
        var res = new MockHttpServletResponse();
        config.entryPoint(attempts).commence(new MockHttpServletRequest("GET", "/api/me"), res, new BadCredentialsException("Bad credentials"));
        assertEquals(401, res.getStatus());
    }
}
