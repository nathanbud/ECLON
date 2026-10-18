package ph.coop.core.member;

import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a member's photo and signature from the legacy {@code image} table (read-only).
 * Photos are stored as type {@code img}; signatures as {@code sig} (JPEG) or {@code sigWacomImg} (BMP).
 * The most recently added image of the requested kind wins.
 */
@RestController
@RequestMapping("/api/members/{branchCode}/{clientId}")
class MemberImageController {

    private final JdbcTemplate jdbc;

    MemberImageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/photo")
    ResponseEntity<byte[]> photo(@PathVariable int branchCode, @PathVariable int clientId) {
        return image(branchCode, clientId, "img");
    }

    @GetMapping("/signature")
    ResponseEntity<byte[]> signature(@PathVariable int branchCode, @PathVariable int clientId) {
        return image(branchCode, clientId, "sig", "sigWacomImg");
    }

    private ResponseEntity<byte[]> image(int branchCode, int clientId, String... types) {
        String marks = String.join(",", java.util.Collections.nCopies(types.length, "?"));
        Object[] args = new Object[2 + types.length];
        args[0] = branchCode;
        args[1] = clientId;
        System.arraycopy(types, 0, args, 2, types.length);
        List<byte[]> rows = jdbc.query(
            "SELECT ImageFile FROM image WHERE ImageBR_CODE = ? AND ClientIDimg = ? AND ImageType IN (" + marks + ")"
                + " AND LENGTH(ImageFile) > 0 ORDER BY ImageID DESC LIMIT 1",
            (rs, i) -> rs.getBytes(1), args);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        byte[] data = rows.get(0);
        return ResponseEntity.ok()
            .contentType(detect(data))
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
            .body(data);
    }

    /** The legacy table does not record a content type, so it is read from the file's magic bytes. */
    private static MediaType detect(byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return MediaType.IMAGE_JPEG;
        if (d.length > 3 && (d[0] & 0xFF) == 0x89 && d[1] == 'P') return MediaType.IMAGE_PNG;
        if (d.length > 2 && d[0] == 'B' && d[1] == 'M') return MediaType.parseMediaType("image/bmp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
