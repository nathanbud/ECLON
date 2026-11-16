package ph.coop.core.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The sign-in screen's name, tagline and logo: anyone reads, only a SUPERADMIN changes, and a logo must be a safe picture. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class BrandingTest {

    @Autowired BrandingController branding;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    private static Authentication as(String... roles) {
        Authentication a = new TestingAuthenticationToken("boss", "x", roles);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private static MockMultipartFile file(byte[] data) {
        return new MockMultipartFile("file", "logo", "application/octet-stream", data);
    }

    @BeforeEach
    void ready() {
        assumeTrue(db.tableExists("ecis_branding"), "V044 has not been applied");
    }

    @Test
    void aSuperadminSetsTheWordsAndTheLogoAndAnyoneReadsThem() throws Exception {
        var auth = as("ROLE_ADMIN", "ROLE_SUPERADMIN");
        var after = branding.setText(new BrandingController.TextIn("  Test   Cooperative ", " Together we grow "), auth);
        assertEquals("Test Cooperative", after.coopName());
        assertEquals("Together we grow", after.tagline());
        assertFalse(after.hasLogo());

        var withLogo = branding.setLogo(file(PNG), as("ROLE_ADMIN", "ROLE_SUPERADMIN"));
        assertTrue(withLogo.hasLogo());
        SecurityContextHolder.clearContext(); // reading needs no sign-in
        var logo = branding.logo();
        assertEquals("image/png", logo.getHeaders().getContentType().toString());
        assertArrayEquals(PNG, logo.getBody());
        assertEquals("nosniff", logo.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(logo.getHeaders().getFirst("Content-Security-Policy").contains("sandbox"));
        assertEquals("Test Cooperative", branding.get().coopName());

        assertFalse(branding.removeLogo(as("ROLE_SUPERADMIN")).hasLogo());
        assertEquals(404, branding.logo().getStatusCode().value());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE entity = 'branding' AND username = 'boss'", Integer.class) >= 3);
    }

    @Test
    void emptyWordsClearTheFieldsAndLongOnesAreRefused() {
        var auth = as("ROLE_SUPERADMIN");
        var cleared = branding.setText(new BrandingController.TextIn("  ", null), auth);
        assertNull(cleared.coopName());
        assertNull(cleared.tagline());
        assertThrows(ResponseStatusException.class, () -> branding.setText(new BrandingController.TextIn("x".repeat(81), null), as("ROLE_SUPERADMIN")));
        assertThrows(ResponseStatusException.class, () -> branding.setText(new BrandingController.TextIn("Fine", "y".repeat(141)), as("ROLE_SUPERADMIN")));
    }

    @Test
    void anAdministratorWhoIsNotASuperadminCannotChangeIt() {
        var admin = as("ROLE_ADMIN");
        assertThrows(AccessDeniedException.class, () -> branding.setText(new BrandingController.TextIn("Nope", null), admin));
        assertThrows(AccessDeniedException.class, () -> branding.setLogo(file(PNG), admin));
        assertThrows(AccessDeniedException.class, () -> branding.removeLogo(admin));
    }

    @Test
    void onlyPicturesAreAcceptedAndAnSvgThatCouldRunCodeIsNot() {
        assertEquals("image/png", BrandingController.logoType(PNG));
        assertEquals("image/jpeg", BrandingController.logoType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1}));
        byte[] webp = "RIFF....WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
        assertEquals("image/webp", BrandingController.logoType(webp));
        assertEquals("image/svg+xml", BrandingController.logoType("<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"4\"/></svg>".getBytes(StandardCharsets.UTF_8)));
        for (String bad : new String[] {
            "<svg><script>alert(1)</script></svg>", "<svg onload=\"x()\"></svg>", "<svg><a href=\"javascript:x()\"/></svg>",
            "<svg><image href=\"https://evil.example/a.png\"/></svg>", "<svg><foreignObject/></svg>", "hello", "<html></html>"}) {
            assertThrows(ResponseStatusException.class, () -> BrandingController.logoType(bad.getBytes(StandardCharsets.UTF_8)), bad);
        }
        assertThrows(ResponseStatusException.class, () -> BrandingController.logoType(new byte[0]));
        assertThrows(ResponseStatusException.class, () -> BrandingController.logoType(new byte[BrandingController.MAX_LOGO_BYTES + 1]));
    }
}
