package ftpserver.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    @Test
    void correctPassword_verifies() {
        String h = PasswordHasher.hash("s3cret");
        assertTrue(PasswordHasher.isValidFormat(h));
        assertTrue(PasswordHasher.verify("s3cret", h));
    }

    @Test
    void wrongOrNullPassword_fails() {
        String h = PasswordHasher.hash("s3cret");
        assertFalse(PasswordHasher.verify("other", h));
        assertFalse(PasswordHasher.verify(null, h));
    }

    @Test
    void sameInput_givesDifferentHashesBecauseOfSalt() {
        assertNotEquals(PasswordHasher.hash("x"), PasswordHasher.hash("x"));
    }

    @Test
    void malformedStoredValue_neverVerifies() {
        assertFalse(PasswordHasher.verify("x", "plaintext"));
        assertFalse(PasswordHasher.verify("x", null));
    }
}
