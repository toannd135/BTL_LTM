package ftpserver.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Bam mat khau bang PBKDF2-HMAC-SHA256 co salt ngau nhien.
 * Dinh dang luu: pbkdf2$<iterations>$<salt base64>$<hash base64>
 * Tao hash: java -cp ftpserver.jar ftpserver.auth.PasswordHasher <mat-khau>
 */
public final class PasswordHasher {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = derive(password.toCharArray(), salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(dk);
    }

    public static boolean isValidFormat(String stored) {
        if (stored == null) {
            return false;
        }
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals(PREFIX)) {
            return false;
        }
        try {
            Integer.parseInt(p[1]);
            Base64.getDecoder().decode(p[2]);
            Base64.getDecoder().decode(p[3]);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** So sanh thoi gian khong doi. false neu stored sai dinh dang hoac password null. */
    public static boolean verify(String password, String stored) {
        if (password == null || !isValidFormat(stored)) {
            return false;
        }
        String[] p = stored.split("\\$");
        byte[] salt = Base64.getDecoder().decode(p[2]);
        byte[] expected = Base64.getDecoder().decode(p[3]);
        byte[] actual = derive(password.toCharArray(), salt, Integer.parseInt(p[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java -cp ftpserver.jar ftpserver.auth.PasswordHasher <password>");
            System.exit(1);
        }
        System.out.println(hash(args[0]));
    }
}
