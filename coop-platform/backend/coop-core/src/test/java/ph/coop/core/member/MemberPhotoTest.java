package ph.coop.core.member;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Setting a member's photo: needs the images permission, accepts only JPEG and PNG, adds a row and keeps the earlier ones. Rolled back. */
@SpringBootTest(properties = {"DEV_API_USER=itest", "DEV_API_PASSWORD=itest-unused", "coop.drawer-snapshot.on-start=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@Transactional
class MemberPhotoTest {

    @Autowired MemberImageController images;
    @Autowired JdbcTemplate jdbc;
    @Autowired ph.coop.core.db.Db db;

    private static Authentication me(String... auths) {
        Authentication a = new TestingAuthenticationToken("admin", "x", auths);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5};

    @BeforeEach
    void ready() {
        assumeTrue(db.canInsertInto("image"), "V039 has not been applied");
    }

    private int someMember() {
        return jdbc.queryForObject("SELECT ClientID FROM client WHERE ClientIDBrCode = 1 ORDER BY ClientID DESC LIMIT 1", Integer.class);
    }

    @Test
    void aPhotoIsAddedAndTheNewestIsServed() throws Exception {
        int id = someMember();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE ImageBR_CODE = 1 AND ClientIDimg = ? AND ImageType = 'img'", Integer.class, id);
        Authentication a = me("PERM_MEMBER_IMAGES", "PERM_MEMBER_EDIT");
        assertEquals(HttpStatus.CREATED, images.setPhoto(1, id, new MockMultipartFile("file", "p.jpg", "image/jpeg", JPEG), a).getStatusCode());
        assertEquals(before + 1, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE ImageBR_CODE = 1 AND ClientIDimg = ? AND ImageType = 'img'", Integer.class, id));
        assertArrayEquals(JPEG, images.photo(1, id).getBody());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'member.photo.set' AND entity_id = ?", Integer.class, "1-" + id));
    }

    @Test
    void otherFilesAndOversizePicturesAreRefused() {
        int id = someMember();
        Authentication a = me("PERM_MEMBER_IMAGES", "PERM_MEMBER_EDIT");
        assertThrows(ResponseStatusException.class, () -> images.setPhoto(1, id, new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes()), a));
        assertThrows(ResponseStatusException.class, () -> images.setPhoto(1, id, new MockMultipartFile("file", "e.jpg", "image/jpeg", new byte[0]), a));
        byte[] big = new byte[MemberImageController.MAX_PHOTO_BYTES + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        big[3] = (byte) 0xE0;
        assertThrows(ResponseStatusException.class, () -> images.setPhoto(1, id, new MockMultipartFile("file", "b.jpg", "image/jpeg", big), a));
    }

    @Test
    void withoutThePermissionNothingIsAdded() {
        int id = someMember();
        Authentication editOnly = me("PERM_MEMBER_EDIT");
        assertThrows(AccessDeniedException.class, () -> images.setPhoto(1, id, new MockMultipartFile("file", "p.jpg", "image/jpeg", JPEG), editOnly));
        Authentication imagesOnly = me("PERM_MEMBER_IMAGES");
        assertThrows(AccessDeniedException.class, () -> images.setPhoto(1, id, new MockMultipartFile("file", "p.jpg", "image/jpeg", JPEG), imagesOnly));
    }

    @Test
    void aSignatureIsAddedAsSigAndTheNewestIsServed() throws Exception {
        int id = someMember();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE ImageBR_CODE = 1 AND ClientIDimg = ? AND ImageType = 'sig'", Integer.class, id);
        int photosBefore = jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE ImageBR_CODE = 1 AND ClientIDimg = ? AND ImageType = 'img'", Integer.class, id);
        Authentication a = me("PERM_MEMBER_IMAGES", "PERM_MEMBER_EDIT");
        assertEquals(HttpStatus.CREATED, images.setSignature(1, id, new MockMultipartFile("file", "s.jpg", "image/jpeg", JPEG), a).getStatusCode());
        assertEquals(before + 1, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE ImageBR_CODE = 1 AND ClientIDimg = ? AND ImageType = 'sig'", Integer.class, id));
        assertEquals(photosBefore, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE ImageBR_CODE = 1 AND ClientIDimg = ? AND ImageType = 'img'", Integer.class, id), "a signature is not a photo");
        assertArrayEquals(JPEG, images.signature(1, id).getBody());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ecis_audit_log WHERE action = 'member.signature.set' AND entity_id = ?", Integer.class, "1-" + id));
    }

    @Test
    void aSignatureHasTheSameRulesAsAPhoto() {
        int id = someMember();
        Authentication a = me("PERM_MEMBER_IMAGES", "PERM_MEMBER_EDIT");
        assertThrows(ResponseStatusException.class, () -> images.setSignature(1, id, new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes()), a));
        byte[] big = new byte[MemberImageController.MAX_SIGNATURE_BYTES + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        big[3] = (byte) 0xE0;
        assertThrows(ResponseStatusException.class, () -> images.setSignature(1, id, new MockMultipartFile("file", "b.jpg", "image/jpeg", big), a));
        Authentication editOnly = me("PERM_MEMBER_EDIT");
        assertThrows(AccessDeniedException.class, () -> images.setSignature(1, id, new MockMultipartFile("file", "s.jpg", "image/jpeg", JPEG), editOnly));
    }
}
