package ph.coop.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Through the real request handling: an unexpected failure is answered in everyday words with a reference, and its technical details (table and
 * column names, the cause, the trace) go only to the administrators' error log; errors already worded for people and permission refusals keep their
 * own status and message.
 */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-pass", "coop.drawer-snapshot.on-start=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Import(ServerErrorsTest.Failing.class)
class ServerErrorsTest {

    @Autowired MockMvc mvc;
    @Autowired ErrorJournal journal;

    /** A controller that fails in each way, for this test only. */
    @TestConfiguration
    @RestController
    static class Failing {
        @GetMapping("/api/test-errors/too-long")
        String tooLong() {
            throw new DataIntegrityViolationException("PreparedStatementCallback; SQL [INSERT INTO ecis_audit_log VALUES (?)]; ERROR: value too long for type character varying(40)",
                new SQLException("ERROR: value too long for type character varying(40)", "22001"));
        }

        @GetMapping("/api/test-errors/missing-table")
        String missingTable() {
            throw new org.springframework.jdbc.BadSqlGrammarException("x", "SELECT 1 FROM ecis_sl_control", new SQLException("ERROR: relation \"ecis_sl_control\" does not exist", "42P01"));
        }

        @GetMapping("/api/test-errors/worded")
        String worded() {
            throw ApiErrors.bad("Give a reason for the change.");
        }

        @GetMapping("/api/test-errors/not-allowed")
        @PreAuthorize("hasRole('NOBODY_HAS_THIS')")
        String notAllowed() {
            return "never";
        }
    }

    private org.springframework.test.web.servlet.ResultActions call(String path) throws Exception {
        return mvc.perform(get(path).with(httpBasic("itest", "itest-pass")));
    }

    @Test void thePersonIsToldInEverydayWordsWithAReferenceAndNoTechnicalNames() throws Exception {
        call("/api/test-errors/too-long").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.reference").value(Matchers.matchesPattern("E-\\d{6}-[A-Z2-9]{4}")))
            .andExpect(jsonPath("$.message").value(Matchers.startsWith("This was not saved because one of the entries is longer than the system allows.")))
            .andExpect(jsonPath("$.message").value(Matchers.not(Matchers.containsString("character varying"))))
            .andExpect(jsonPath("$.message").value(Matchers.not(Matchers.containsString("ecis_audit_log"))))
            .andExpect(jsonPath("$.problem").doesNotExist());
        call("/api/test-errors/missing-table").andExpect(jsonPath("$.message").value(Matchers.containsString("not fully set up yet")))
            .andExpect(jsonPath("$.message").value(Matchers.not(Matchers.containsString("ecis_sl_control"))));
    }

    @Test void theAdministratorsErrorLogHasTheTechnicalDetailsUnderTheSameReference() throws Exception {
        String body = call("/api/test-errors/missing-table").andReturn().getResponse().getContentAsString();
        String reference = body.replaceAll(".*\"reference\":\"([^\"]+)\".*", "$1");
        var entry = journal.newest().stream().filter((x) -> x.reference().equals(reference)).findFirst().orElseThrow();
        assertEquals("MISSING_TABLE", entry.code());
        assertEquals("itest", entry.user());
        assertEquals("/api/test-errors/missing-table", entry.path());
        assertTrue(entry.problem().contains("ecis_sl_control"), entry.problem());
        assertTrue(entry.fix().contains("Apply them now"), entry.fix());
        assertTrue(entry.trace().contains("BadSqlGrammarException"));
        // administrators read it from the server; others may not
        mvc.perform(get("/api/admin/errors").with(httpBasic("itest", "itest-pass"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].reference").exists());
    }

    @Test void errorsAlreadyWordedForPeopleAndRefusalsAreLeftAsTheyWere() throws Exception {
        // these go through Spring's usual error handling (an error page, which MockMvc does not render, so its message is checked as the reason)
        call("/api/test-errors/worded").andExpect(status().isBadRequest()).andExpect(status().reason("Give a reason for the change."));
        call("/api/test-errors/not-allowed").andExpect(status().isForbidden());
    }
}
