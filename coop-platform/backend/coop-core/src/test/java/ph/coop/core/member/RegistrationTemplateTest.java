package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The empty profile a new member is registered on has the same tabs and fields as a real member's details, with no values. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class RegistrationTemplateTest {

    @Autowired MemberLifecycleController lifecycle;
    @Autowired ClientDetailController details;

    @Test
    void theTemplateHasEveryTabAndNoValues() {
        var template = lifecycle.template();
        assertTrue(template.keySet().containsAll(java.util.List.of("business", "education", "contacts", "spouse", "groups")));
        assertTrue(template.values().stream().flatMap(java.util.List::stream).flatMap((s) -> s.fields().stream()).allMatch((f) -> f.value() == null));
        assertEquals(details.blankDetails().keySet(), template.keySet());
    }

    @Test
    void theTemplateFieldsCanBeEditedAndCarryTheirColumnKeys() {
        var work = lifecycle.template().get("business").stream().filter((s) -> s.title().equals("Work & income")).findFirst().orElseThrow();
        assertTrue(work.fields().stream().allMatch(ClientDetailController.Field::editable));
        assertTrue(work.fields().stream().anyMatch((f) -> "BusinessIncome".equals(f.key())));
    }
}
