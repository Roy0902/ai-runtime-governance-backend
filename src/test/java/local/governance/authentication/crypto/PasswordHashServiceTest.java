package local.governance.authentication.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class PasswordHashServiceTest {

    private final PasswordHashService passwords = new PasswordHashService();

    @Test
    void hashesWithTheCurrentPbkdf2Profile() {
        String encoded = passwords.hash("correct horse battery staple");

        String[] parts = encoded.split("\\$");
        assertEquals(5, parts.length);
        assertEquals("pbkdf2-sha256", parts[1]);
        assertEquals("i=210000", parts[2]);
        assertEquals(16, Base64.getDecoder().decode(parts[3]).length);
        assertEquals(32, Base64.getDecoder().decode(parts[4]).length);
    }

    @Test
    void usesAFreshSaltForEveryHash() {
        assertNotEquals(passwords.hash("same password"), passwords.hash("same password"));
    }

    @Test
    void verifiesTheCurrentPassword() {
        String encoded = passwords.hash("correct horse battery staple");

        assertEquals(PasswordHashService.Verification.CURRENT,
                passwords.verify("correct horse battery staple", encoded));
        assertEquals(PasswordHashService.Verification.FAILED,
                passwords.verify("correct horse battery stapl", encoded));
    }

    @Test
    void identifiesThePredecessorProfileForRehash() {
        String encoded = passwords.hashPredecessorForTest("correct horse battery staple");

        assertTrue(encoded.startsWith("$pbkdf2-sha256$i=100000$"));
        assertEquals(PasswordHashService.Verification.PREDECESSOR,
                passwords.verify("correct horse battery staple", encoded));
    }

    @Test
    void rejectsMalformedAndUnreviewedProfiles() {
        String encoded = passwords.hash("correct horse battery staple");
        String body = encoded.substring(encoded.indexOf("$i="));

        assertEquals(PasswordHashService.Verification.FAILED,
                passwords.verify("correct horse battery staple", null));
        assertEquals(PasswordHashService.Verification.FAILED,
                passwords.verify("correct horse battery staple", "not-a-verifier"));
        assertEquals(PasswordHashService.Verification.FAILED,
                passwords.verify("correct horse battery staple", "$pbkdf2-sha256$i=210000$broken"));
        assertEquals(PasswordHashService.Verification.FAILED,
                passwords.verify("correct horse battery staple", "$pbkdf2-sha256" + body.replace("210000", "1")));
        assertEquals(PasswordHashService.Verification.FAILED,
                passwords.verify("correct horse battery staple", "$argon2id$v=19$m=65536,t=3,p=1$c2FsdA$aGFzaA"));
    }
}
