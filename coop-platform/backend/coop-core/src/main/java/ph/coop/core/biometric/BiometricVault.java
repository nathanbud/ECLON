package ph.coop.core.biometric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts fingerprint templates before they reach the database (AES-256-GCM, a fresh random IV each time, the member and finger bound in as authenticated data, so a template
 * copied onto another member or finger fails to decrypt). The key is never stored in the database: it comes from {@code coop.biometric.key} (base64, 32 bytes; use the environment
 * or a key service in production) or, for development only, a key file created once under the data folder.
 */
@Component
public class BiometricVault {

    private static final int IV_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public BiometricVault(@Value("${coop.biometric.key:}") String configured, @Value("${coop.biometric.key-file:./data/biometric.key}") String keyFile) {
        this.key = new SecretKeySpec(load(configured, Path.of(keyFile)), "AES");
    }

    private byte[] load(String configured, Path file) {
        try {
            if (configured != null && !configured.isBlank()) {
                byte[] k = Base64.getDecoder().decode(configured.trim());
                if (k.length != 32) throw new IllegalStateException("coop.biometric.key must be 32 bytes, base64 encoded.");
                return k;
            }
            if (Files.exists(file)) return Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).trim());
            byte[] k = new byte[32];
            random.nextBytes(k);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(k), StandardCharsets.US_ASCII);
            return k;
        } catch (IOException e) {
            throw new IllegalStateException("The fingerprint key could not be read or created.", e);
        }
    }

    private static byte[] aad(int branch, int client, int finger) {
        return (branch + "|" + client + "|" + finger).getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] seal(byte[] template, int branch, int client, int finger) {
        return sealWith(template, aad(branch, client, finger));
    }

    public byte[] open(byte[] sealed, int branch, int client, int finger) {
        return openWith(sealed, aad(branch, client, finger));
    }

    /** Seals a template that does not belong to a member yet (one held for a registration), bound to a scope such as the request and finger. */
    public byte[] sealScoped(byte[] template, String scope) {
        return sealWith(template, scope.getBytes(StandardCharsets.US_ASCII));
    }

    public byte[] openScoped(byte[] sealed, String scope) {
        return openWith(sealed, scope.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] sealWith(byte[] template, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(aad);
            byte[] body = c.doFinal(template);
            byte[] out = Arrays.copyOf(iv, IV_BYTES + body.length);
            System.arraycopy(body, 0, out, IV_BYTES, body.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The fingerprint could not be protected.", e);
        }
    }

    private byte[] openWith(byte[] sealed, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, IV_BYTES));
            c.updateAAD(aad);
            return c.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("A stored fingerprint could not be read back.", e);
        }
    }
}
