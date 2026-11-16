package ph.coop.core.config;

import static ph.coop.core.web.ApiErrors.bad;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ph.coop.core.audit.AuditLog;
import ph.coop.core.db.Db;

/**
 * What the sign-in screen says about the cooperative: its name, a tagline and its logo. Anyone can read it (it is shown before signing in), only a SUPERADMIN can change it.
 * A logo is a PNG, JPEG, WebP or SVG up to 1 MB, checked by its first bytes; an SVG that could run anything (scripts, event handlers, external references) is refused, and every logo
 * is served with headers that stop a browser running it as a page.
 */
@RestController
public class BrandingController {

    public record Branding(String coopName, String tagline, boolean hasLogo, String version) {}

    public record TextIn(String coopName, String tagline) {}

    static final int MAX_LOGO_BYTES = 1024 * 1024;
    private static final Pattern UNSAFE_SVG = Pattern.compile("<script|<foreignobject|<iframe|<embed|<object|javascript:|\\son[a-z]+\\s*=|<!entity|<!doctype[^>]*\\[|(?:xlink:)?href\\s*=\\s*[\"']\\s*(?:https?:|//|data:text)", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final Db db;

    BrandingController(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    // ---- reading (public) ---------------------------------------------------------------------------------------

    @GetMapping("/api/public/branding")
    public Branding get() {
        if (!db.tableExists("ecis_branding")) return new Branding(null, null, false, "0");
        List<Branding> rows = jdbc.query("SELECT coop_name, tagline, logo IS NOT NULL, updated_at FROM ecis_branding WHERE id = 1",
            (rs, i) -> new Branding(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getTimestamp(4) == null ? "0" : Long.toString(rs.getTimestamp(4).getTime())));
        return rows.isEmpty() ? new Branding(null, null, false, "0") : rows.get(0);
    }

    @GetMapping("/api/public/branding/logo")
    public ResponseEntity<byte[]> logo() {
        if (!db.tableExists("ecis_branding")) return ResponseEntity.notFound().build();
        List<Object[]> rows = jdbc.query("SELECT logo, logo_type FROM ecis_branding WHERE id = 1 AND logo IS NOT NULL", (rs, i) -> new Object[] {rs.getBytes(1), rs.getString(2)});
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType((String) rows.get(0)[1]))
            .cacheControl(CacheControl.noCache())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
            .body((byte[]) rows.get(0)[0]);
    }

    // ---- changing (SUPERADMIN) ----------------------------------------------------------------------------------

    @PutMapping("/api/admin/branding")
    @PreAuthorize("hasRole('SUPERADMIN')")
    @Transactional
    public Branding setText(@RequestBody TextIn in, Authentication auth) {
        String name = clean(in.coopName(), 80, "The cooperative's name");
        String tag = clean(in.tagline(), 140, "The tagline");
        Branding before = get();
        jdbc.update("UPDATE ecis_branding SET coop_name = ?, tagline = ?, updated_by = ?, updated_at = ? WHERE id = 1", name, tag, auth.getName(), now());
        AuditLog.write(jdbc, auth.getName(), "branding.text.changed", "branding", "1",
            "name=" + nz(before.coopName()) + "; tagline=" + nz(before.tagline()), "name=" + nz(name) + "; tagline=" + nz(tag), "Sign-in screen wording");
        return get();
    }

    @PostMapping("/api/admin/branding/logo")
    @PreAuthorize("hasRole('SUPERADMIN')")
    @Transactional
    public Branding setLogo(@RequestParam("file") MultipartFile file, Authentication auth) throws IOException {
        byte[] data = file.getBytes();
        String type = logoType(data);
        jdbc.update("UPDATE ecis_branding SET logo = ?, logo_type = ?, updated_by = ?, updated_at = ? WHERE id = 1", data, type, auth.getName(), now());
        AuditLog.write(jdbc, auth.getName(), "branding.logo.set", "branding", "1", null, type + ", " + data.length + " bytes", "Sign-in screen logo");
        return get();
    }

    @DeleteMapping("/api/admin/branding/logo")
    @PreAuthorize("hasRole('SUPERADMIN')")
    @Transactional
    public Branding removeLogo(Authentication auth) {
        jdbc.update("UPDATE ecis_branding SET logo = NULL, logo_type = NULL, updated_by = ?, updated_at = ? WHERE id = 1", auth.getName(), now());
        AuditLog.write(jdbc, auth.getName(), "branding.logo.removed", "branding", "1", "logo", null, "Sign-in screen logo");
        return get();
    }

    // ---- checks -------------------------------------------------------------------------------------------------

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Trimmed text, null when empty; refuses text that is too long or holds control characters. */
    static String clean(String raw, int max, String what) {
        String t = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        if (t.length() > max) throw bad(what + " can be at most " + max + " characters.");
        if (t.chars().anyMatch(Character::isISOControl)) throw bad(what + " has characters that cannot be shown.");
        return t;
    }

    /** The content type of an acceptable logo, from its first bytes; refuses anything else. */
    static String logoType(byte[] d) {
        if (d == null || d.length == 0) throw bad("The logo file is empty.");
        if (d.length > MAX_LOGO_BYTES) throw bad("The logo is larger than 1 MB.");
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        String text = new String(d, StandardCharsets.UTF_8).stripLeading().replace("\uFEFF", "");
        String head = text.length() > 400 ? text.substring(0, 400).toLowerCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
        if (head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"))) {
            if (UNSAFE_SVG.matcher(text).find()) throw bad("That SVG contains scripts or outside references and cannot be used. Export a plain SVG, or use a PNG.");
            return "image/svg+xml";
        }
        throw bad("The logo must be a PNG, JPEG, WebP or SVG picture.");
    }
}
