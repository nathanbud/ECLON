package ph.coop.core.connection;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Applying from the application on a database that is up to date (coop): refused before any administrator sign-in, and every packaged change can be read. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class SchemaApplyTest {

    @Autowired SchemaApply apply;
    @Autowired SchemaCheck check;

    private static Authentication superadmin() {
        Authentication a = new TestingAuthenticationToken("tester", "x", "ROLE_SUPERADMIN", "ROLE_ADMIN");
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @Test
    void anUpToDateDatabaseHasNothingToApply() {
        assumeTrue("UP_TO_DATE".equals(check.check().state()), "a database with every change (coop)");
        var e = assertThrows(ResponseStatusException.class, () -> apply.apply(new SchemaApply.Apply("coop_admin", "wrong", "Test"), superadmin()));
        assertTrue(e.getReason().contains("nothing to apply"), e.getReason());
    }

    @Test
    void everyPackagedChangeIsThere() throws Exception {
        var files = SchemaCheck.files();
        assertTrue(files.containsKey("V035__offices.sql"), files.keySet().toString());
        assertTrue(SchemaCheck.expected().keySet().size() == files.size());
    }
}
