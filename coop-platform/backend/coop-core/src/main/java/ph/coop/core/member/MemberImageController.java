package ph.coop.core.member;


import ph.coop.core.audit.AuditLog;import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a member's photo and signature from the legacy {@code image} table, and adds new ones (insert-only).
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

    static final int MAX_PHOTO_BYTES = 3 * 1024 * 1024;
    static final int MAX_SIGNATURE_BYTES = 1024 * 1024;

    /**
     * Sets a member's photo. Insert-only: a new {@code img} row is added and the newest wins, so earlier photos stay. Needs both the legacy "edit client" and "edit client images" permissions.
     * Only JPEG and PNG are accepted, checked by their first bytes, up to 3 MB.
     */
    @PostMapping("/photo")
    @PreAuthorize("hasAuthority('PERM_MEMBER_IMAGES') and hasAuthority('PERM_MEMBER_EDIT')")
    ResponseEntity<Void> setPhoto(@PathVariable int branchCode, @PathVariable int clientId, @RequestParam("file") MultipartFile file, Authentication auth) throws java.io.IOException {
        return add(branchCode, clientId, file, auth, "img", "picture", MAX_PHOTO_BYTES, "member.photo.set", "Photo added");
    }

    /**
     * Sets a member's signature, the same way: a new {@code sig} row is added, the newest wins and earlier signatures stay, with the same two permissions. JPEG or PNG, up to 1 MB.
     */
    @PostMapping("/signature")
    @PreAuthorize("hasAuthority('PERM_MEMBER_IMAGES') and hasAuthority('PERM_MEMBER_EDIT')")
    ResponseEntity<Void> setSignature(@PathVariable int branchCode, @PathVariable int clientId, @RequestParam("file") MultipartFile file, Authentication auth) throws java.io.IOException {
        return add(branchCode, clientId, file, auth, "sig", "signature", MAX_SIGNATURE_BYTES, "member.signature.set", "Signature added");
    }

    private ResponseEntity<Void> add(int branchCode, int clientId, MultipartFile file, Authentication auth, String imageType, String noun, int maxBytes, String action, String reason) throws java.io.IOException {
        byte[] data = file.getBytes();
        if (data.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The " + noun + " is empty.");
        if (data.length > maxBytes) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The " + noun + " is too large (at most " + (maxBytes / (1024 * 1024)) + " MB).");
        MediaType type = detect(data);
        if (!type.equals(MediaType.IMAGE_JPEG) && !type.equals(MediaType.IMAGE_PNG)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only JPEG and PNG pictures are accepted.");
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE ClientIDBrCode = ? AND ClientID = ?", Integer.class, branchCode, clientId);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That member was not found. The member number may be mistyped, or the member is in another branch: search for the member again.");
        jdbc.update("INSERT INTO image (ImageBR_CODE, ClientIDimg, ImageType, ImageFileSize, ImageFile, ImageRemarks, ImageDateTimeAdded) VALUES (?, ?, ?, ?, ?, ?, NOW())",
            branchCode, clientId, imageType, data.length, data, "Set by " + auth.getName());
        AuditLog.write(jdbc, auth.getName(), action, "client", branchCode + "-" + clientId, null, data.length + " bytes, " + type, reason);
        return ResponseEntity.status(HttpStatus.CREATED).build();
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
            .cacheControl(CacheControl.noCache().cachePrivate())
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
